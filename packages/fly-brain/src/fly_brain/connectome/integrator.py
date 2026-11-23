# pyright: reportUnknownMemberType=false, reportUnknownVariableType=false
# pyright: reportUnknownArgumentType=false, reportUnknownLambdaType=false
"""Fit the compass as an angular integrator on a synthetic path-integration task (ADR 0028).

The fit runs on the compass alone (EPG, PEN_a, PEN_b, PEG, Delta7), with anatomical weights
taken from the compiled brain. It learns the compass cell-type-pair gains, one release
threshold per PEN type, the yaw drive scale and one bias per compass type. Each trial seeds a
bump at a random heading with a brief EPG kick, then applies turns, drawn from recorded clip yaw
or synthetic segments. The bump phase should follow ``seed + bump_gain * summed yaw``. A bump gain
below one keeps the required rotation within what the circuit can produce per frame.
"""

from collections.abc import Callable, Sequence
from dataclasses import dataclass, field

import jax
import jax.numpy as jnp
import numpy as np
from numpy.typing import NDArray

from fly_brain.connectome.subcircuit import SubCircuit, run_subcircuit

PEN_TYPES = ("PEN_a(PEN1)", "PEN_b(PEN2)")


@dataclass(frozen=True)
class IntegratorStage:
    steps: int
    learning_rate: float
    bump_gain: float
    real_yaw_fraction: float = 0.8

    def __post_init__(self) -> None:
        if self.steps < 1 or not self.learning_rate > 0:
            raise ValueError("integrator stages need positive steps and learning rate")
        if not 0 < self.bump_gain <= 2:
            raise ValueError("bump_gain must lie in (0, 2]")
        if not 0 <= self.real_yaw_fraction <= 1:
            raise ValueError("real_yaw_fraction must lie in [0, 1]")


@dataclass(frozen=True)
class IntegratorFitConfig:
    stages: tuple[IntegratorStage, ...]
    transitions_per_frame: int = 4
    batch_size: int = 32
    frames: int = 120
    kick_frames: int = 10
    kick: float = 0.3
    minimum_strength: float = 0.4
    synthetic_yaw_mean: float = 3.7
    maximum_yaw: float = 12.0
    initial_threshold: float = 0.3
    initial_scale: float = 0.04
    release_sharpness: float = 20.0
    seed: int = 0

    def __post_init__(self) -> None:
        if not self.stages:
            raise ValueError("an integrator fit needs at least one stage")
        if self.transitions_per_frame < 1 or self.batch_size < 1:
            raise ValueError("transitions and batch size must be positive")
        if self.frames <= self.kick_frames + 1 or self.kick_frames < 1:
            raise ValueError("frames must exceed the kick")
        if not self.initial_scale > 0 or not self.release_sharpness > 0:
            raise ValueError("initial scale and release sharpness must be positive")


@dataclass(frozen=True)
class CompassCircuit:
    """The compass alone: per-neuron types, angles and sides, and its internal edges."""

    cell_types: NDArray[np.str_]
    angles: NDArray[np.float64]  # heading per EPG neuron; NaN elsewhere
    sides: NDArray[np.str_]  # protocerebral-bridge hemisphere, "L" or "R"
    source: NDArray[np.int32]
    target: NDArray[np.int32]
    weight: NDArray[np.float32]
    group: NDArray[np.int32]  # index into ``group_pairs``
    group_pairs: tuple[str, ...]  # "SOURCE>TARGET" per group
    compass_types: tuple[str, ...]
    leak: float
    maximum_gain: float

    def __post_init__(self) -> None:
        n = self.cell_types.size
        if not (self.angles.size == self.sides.size == n):
            raise ValueError("per-neuron compass arrays must have the same length")
        if not np.isfinite(self.angles[self.cell_types == "EPG"]).all():
            raise ValueError("every EPG neuron needs a heading angle")
        if not np.isin(self.sides[np.isin(self.cell_types, PEN_TYPES)], ("L", "R")).all():
            raise ValueError("every PEN neuron needs a left or right hemisphere")
        if not 0 < self.leak <= 1 or not self.maximum_gain > 0:
            raise ValueError("compass leak and maximum gain are out of range")


@dataclass(frozen=True)
class IntegratorFit:
    gains: dict[str, float]  # effective gain per "SOURCE>TARGET"
    type_bias: dict[str, float]
    release_thresholds: dict[str, float]
    self_motion_scale: float
    history: tuple[dict[str, float], ...] = field(default=())


Params = dict[str, jax.Array]


def _arrays(circuit: CompassCircuit) -> dict[str, jax.Array]:
    types = circuit.cell_types
    is_epg = types == "EPG"
    angle = np.where(is_epg, np.nan_to_num(circuit.angles), 0.0)
    pen = np.isin(types, PEN_TYPES)
    return {
        "src": jnp.asarray(circuit.source),
        "dst": jnp.asarray(circuit.target),
        "w": jnp.asarray(circuit.weight),
        "grp": jnp.asarray(circuit.group),
        "pen_type": jnp.asarray(np.where(types == PEN_TYPES[1], 1, 0)),
        "side": jnp.asarray(np.where(circuit.sides == "L", 1.0, -1.0) * pen, dtype=jnp.float32),
        "type_index": jnp.asarray(
            [circuit.compass_types.index(str(name)) for name in types], dtype=jnp.int32
        ),
        "is_epg": jnp.asarray(is_epg, dtype=jnp.float32),
        "cos": jnp.asarray(np.cos(angle) * is_epg, dtype=jnp.float32),
        "sin": jnp.asarray(np.sin(angle) * is_epg, dtype=jnp.float32),
    }


def as_subcircuit(circuit: CompassCircuit) -> SubCircuit:
    """The compass as a generic sub-circuit, with PEN release gated."""
    return SubCircuit(
        cell_types=circuit.cell_types,
        neuron_index=np.arange(circuit.cell_types.size, dtype=np.int64),
        source=circuit.source,
        target=circuit.target,
        group=circuit.group,
        weight=circuit.weight,
        group_pairs=circuit.group_pairs,
        global_groups=np.arange(len(circuit.group_pairs), dtype=np.int64),
        gate=np.isin(circuit.cell_types, PEN_TYPES),
        leak=circuit.leak,
        maximum_gain=circuit.maximum_gain,
    )


def simulate(
    circuit: CompassCircuit,
    params: Params,
    yaw: jax.Array,
    kick: jax.Array,
    *,
    transitions: int,
    sharpness: float,
) -> jax.Array:
    """Run the compass on yaw (B, T) and EPG kicks (B, T, N); return EPG (cos, sin, total)."""
    a = _arrays(circuit)
    scale = jnp.exp(params["log_scale"])
    external = kick + yaw[:, :, None] * scale * a["side"][None, None, :]
    states = run_subcircuit(
        as_subcircuit(circuit),
        params["raw"],
        params["bias"][a["type_index"]],
        params["theta"][a["pen_type"]],
        external,
        transitions=transitions,
        sharpness=sharpness,
    )
    epg = jnp.clip(0.5 * jnp.tanh(2.0 * states), 0.0, None) * a["is_epg"]
    floor = jnp.min(jnp.where(a["is_epg"] > 0, epg, jnp.inf), axis=2, keepdims=True)
    epg = (epg - floor) * a["is_epg"]
    return jnp.stack([epg @ a["cos"], epg @ a["sin"], epg.sum(2)], -1)


def _batch(
    circuit: CompassCircuit,
    config: IntegratorFitConfig,
    stage: IntegratorStage,
    rng: np.random.Generator,
    yaw_sequences: Sequence[NDArray[np.floating]],
    size: int,
) -> tuple[jax.Array, jax.Array, jax.Array]:
    is_epg = circuit.cell_types == "EPG"
    angle = np.nan_to_num(circuit.angles)
    frames, kick_frames = config.frames, config.kick_frames
    seed = rng.uniform(-np.pi, np.pi, size)
    kick = np.zeros((size, frames, circuit.cell_types.size), np.float32)
    pattern = config.kick * np.maximum(np.cos(angle[None] - seed[:, None]), 0) * is_epg
    kick[:, :kick_frames] = pattern[:, None]
    yaw = np.zeros((size, frames), np.float32)
    usable = [y for y in yaw_sequences if y.size >= 20]
    for row in range(size):
        if usable and rng.random() < stage.real_yaw_fraction:
            clip = usable[rng.integers(len(usable))]
            start = rng.integers(0, max(1, clip.size - (frames - kick_frames)))
            piece = clip[start : start + frames - kick_frames]
            yaw[row, kick_frames : kick_frames + piece.size] = piece
            continue
        t = kick_frames + int(rng.integers(0, 10))
        while t < frames:
            length = int(rng.integers(5, 30))
            rate = 0.0
            if rng.random() >= 0.3:
                rate = rng.choice([-1, 1]) * rng.gamma(1.5, config.synthetic_yaw_mean / 1.5)
            yaw[row, t : t + length] = np.clip(rate, -config.maximum_yaw, config.maximum_yaw)
            t += length
    target = seed[:, None] + np.radians(stage.bump_gain * np.cumsum(yaw, 1))
    return jnp.asarray(yaw), jnp.asarray(kick), jnp.asarray(target, dtype=jnp.float32)


def _loss(
    circuit: CompassCircuit,
    config: IntegratorFitConfig,
    params: Params,
    yaw: jax.Array,
    kick: jax.Array,
    target: jax.Array,
) -> tuple[jax.Array, tuple[jax.Array, jax.Array]]:
    out = simulate(
        circuit,
        params,
        yaw,
        kick,
        transitions=config.transitions_per_frame,
        sharpness=config.release_sharpness,
    )
    c, s, total = out[..., 0], out[..., 1], out[..., 2]
    norm = jnp.sqrt(c**2 + s**2 + 1e-8)
    agreement = (c * jnp.cos(target) + s * jnp.sin(target)) / norm
    strength = norm / (total + 1e-3)
    mask = (jnp.arange(config.frames) >= config.kick_frames).astype(jnp.float32)[None]
    count = mask.sum() * yaw.shape[0]
    phase = ((1 - agreement) * mask).sum() / count
    weak = (jax.nn.relu(config.minimum_strength - strength) * mask).sum() / count
    return phase + weak, (phase, (strength * mask).sum() / count)


def initial_parameters(
    circuit: CompassCircuit, gains: NDArray[np.floating], config: IntegratorFitConfig
) -> Params:
    """Start from effective gains per group (e.g. the ring-attractor search)."""
    g = np.clip(np.asarray(gains, dtype=np.float64), 0.05, circuit.maximum_gain * 0.95)
    return {
        "raw": jnp.asarray(np.log(g / (circuit.maximum_gain - g)), dtype=jnp.float32),
        "theta": jnp.full(len(PEN_TYPES), config.initial_threshold, dtype=jnp.float32),
        "log_scale": jnp.asarray(np.log(config.initial_scale), dtype=jnp.float32),
        "bias": jnp.zeros(len(circuit.compass_types), dtype=jnp.float32),
    }


def fit_compass_integrator(
    circuit: CompassCircuit,
    initial_gains: NDArray[np.floating],
    yaw_sequences: Sequence[NDArray[np.floating]],
    config: IntegratorFitConfig,
    *,
    progress: Callable[[dict[str, float]], None] | None = None,
    progress_interval: int = 100,
) -> IntegratorFit:
    """Fit by Adam with unit-norm gradient clipping, stage by stage; returns effective values."""
    params = initial_parameters(circuit, initial_gains, config)
    rng = np.random.default_rng(config.seed)
    history: list[dict[str, float]] = []
    step_count = 0
    for stage_index, stage in enumerate(config.stages):

        def loss(p: Params, y: jax.Array, k: jax.Array, t: jax.Array):
            return _loss(circuit, config, p, y, k, t)

        grad = jax.jit(jax.value_and_grad(loss, has_aux=True))
        first = jax.tree.map(jnp.zeros_like, params)
        second = jax.tree.map(jnp.zeros_like, params)
        for step in range(1, stage.steps + 1):
            batch = _batch(circuit, config, stage, rng, yaw_sequences, config.batch_size)
            (value, (phase, strength)), g = grad(params, *batch)
            norm = float(np.sqrt(sum(float(jnp.sum(x * x)) for x in jax.tree.leaves(g))))
            if not np.isfinite(norm):
                continue
            g = jax.tree.map(lambda x, n=norm: x * min(1.0, 1.0 / max(n, 1e-12)), g)
            first = jax.tree.map(lambda m, x: 0.9 * m + 0.1 * x, first, g)
            second = jax.tree.map(lambda v, x: 0.999 * v + 0.001 * x * x, second, g)
            params = _adam_step(params, first, second, stage.learning_rate, step)
            step_count += 1
            if step % progress_interval == 0 or step == stage.steps:
                record = {
                    "stage": float(stage_index),
                    "step": float(step_count),
                    "loss": float(value),
                    "phase_loss": float(phase),
                    "strength": float(strength),
                }
                history.append(record)
                if progress is not None:
                    progress(record)
    gains = circuit.maximum_gain * np.asarray(jax.nn.sigmoid(params["raw"]), dtype=np.float64)
    return IntegratorFit(
        gains={pair: float(value) for pair, value in zip(circuit.group_pairs, gains, strict=True)},
        type_bias={
            name: float(value)
            for name, value in zip(circuit.compass_types, np.asarray(params["bias"]), strict=True)
        },
        release_thresholds={
            name: float(value)
            for name, value in zip(PEN_TYPES, np.asarray(params["theta"]), strict=True)
        },
        self_motion_scale=float(np.exp(np.asarray(params["log_scale"]))),
        history=tuple(history),
    )


def _adam_step(params: Params, first: Params, second: Params, rate: float, step: int) -> Params:
    first_scale, second_scale = 1 - 0.9**step, 1 - 0.999**step
    return jax.tree.map(
        lambda p, m, v: p - rate * (m / first_scale) / (jnp.sqrt(v / second_scale) + 1e-8),
        params,
        first,
        second,
    )


def constant_turn_response(
    circuit: CompassCircuit,
    fit: IntegratorFit,
    config: IntegratorFitConfig,
    rates: Sequence[float] = (-8.0, -4.0, -2.0, -1.0, 0.0, 1.0, 2.0, 4.0, 8.0),
    settle: int = 40,
) -> dict[str, object]:
    """Seed 8 headings, hold still, then turn at each constant rate; report hold and velocity."""
    params = parameters_from_fit(circuit, fit)
    seeds = np.radians(np.arange(8) * 45.0)
    frames = max(config.frames, settle + 40)
    size = len(rates) * seeds.size
    is_epg = circuit.cell_types == "EPG"
    angle = np.nan_to_num(circuit.angles)
    kick = np.zeros((size, frames, circuit.cell_types.size), np.float32)
    yaw = np.zeros((size, frames), np.float32)
    for i, rate in enumerate(rates):
        for j, seed in enumerate(seeds):
            row = i * seeds.size + j
            kick[row, : config.kick_frames] = (
                config.kick * np.maximum(np.cos(angle - seed), 0) * is_epg
            )
            yaw[row, settle:] = rate
    out = np.asarray(
        simulate(
            circuit,
            params,
            jnp.asarray(yaw),
            jnp.asarray(kick),
            transitions=config.transitions_per_frame,
            sharpness=config.release_sharpness,
        )
    )
    phase = np.degrees(np.arctan2(out[..., 1], out[..., 0]))
    step = (np.diff(phase, axis=1) + 180.0) % 360.0 - 180.0
    still = list(rates).index(0.0) if 0.0 in rates else 0
    held = np.radians(phase[still * seeds.size : (still + 1) * seeds.size, settle - 1])
    return {
        "hold_error_deg": float(np.degrees(np.abs(np.angle(np.exp(1j * (held - seeds))))).mean()),
        "velocity_deg_per_frame": {
            str(rate): float(step[i * seeds.size : (i + 1) * seeds.size, settle + 10 :].mean())
            for i, rate in enumerate(rates)
        },
    }


def parameters_from_fit(circuit: CompassCircuit, fit: IntegratorFit) -> Params:
    gains = np.array([fit.gains[pair] for pair in circuit.group_pairs], dtype=np.float64)
    return {
        "raw": jnp.asarray(np.log(gains / (circuit.maximum_gain - gains)), dtype=jnp.float32),
        "theta": jnp.asarray([fit.release_thresholds[name] for name in PEN_TYPES], jnp.float32),
        "log_scale": jnp.asarray(np.log(fit.self_motion_scale), dtype=jnp.float32),
        "bias": jnp.asarray([fit.type_bias[name] for name in circuit.compass_types], jnp.float32),
    }
