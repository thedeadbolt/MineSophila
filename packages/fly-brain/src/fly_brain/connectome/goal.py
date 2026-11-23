# pyright: reportUnknownMemberType=false, reportUnknownVariableType=false
# pyright: reportUnknownArgumentType=false
"""Fan-shaped-body goal memory fits (ADR 0030): hold, then (later stages) compare and write.

Stage 1 fits FC2, FB5A and hDelta to hold a goal: a brief kick seeds an FC2 bump at a random
heading, and the bump should stay there, with strength at least ``minimum_strength``, once the
kick ends. It learns the internal type-pair gains and one bias per cell type.
"""

from dataclasses import dataclass, field
from typing import Any

import jax
import jax.numpy as jnp
import numpy as np
from numpy.typing import NDArray

from fly_brain.connectome.dynamics import LeakyRateDynamics
from fly_brain.connectome.subcircuit import (
    Params,
    SubCircuit,
    adam_step,
    clipped_moments,
    run_subcircuit,
)

_VALIDATION_SEED = 12345
_VALIDATION_SIZE = 32
_FADED = 0.2


@dataclass(frozen=True)
class GoalHoldConfig:
    steps: int
    learning_rate: float
    frames: int = 120
    kick_frames: int = 10
    kick: float = 0.3
    minimum_strength: float = 0.4
    transitions_per_frame: int = 4
    batch_size: int = 32
    release_sharpness: float = 20.0
    seed: int = 0

    def __post_init__(self) -> None:
        if self.steps < 1 or not self.learning_rate > 0:
            raise ValueError("a goal fit needs positive steps and learning rate")
        if self.kick_frames < 1 or self.frames <= self.kick_frames + 1:
            raise ValueError("frames must exceed the kick")
        if self.transitions_per_frame < 1 or self.batch_size < 1:
            raise ValueError("transitions and batch size must be positive")


@dataclass(frozen=True)
class GoalFit:
    gains: dict[str, float]  # effective gain per "SOURCE>TARGET"
    type_bias: dict[str, float]
    hold_error_deg: float
    history: tuple[dict[str, float], ...] = field(default=())


def _types(circuit: SubCircuit) -> tuple[str, ...]:
    return tuple(dict.fromkeys(str(name) for name in circuit.cell_types))


def _decode(
    states: jax.Array, angles: NDArray[np.floating]
) -> tuple[jax.Array, jax.Array, jax.Array]:
    """Population vector (..., 2), strength and amplitude of angled neurons' release per frame.

    Amplitude is the vector's length in release units; strength is that length over the summed
    release. Phase is ``arctan2`` of the vector; losses use the bounded agreement instead.
    """
    angled = np.isfinite(angles)
    theta = np.where(angled, angles, 0.0)
    mask = jnp.asarray(angled, dtype=jnp.float32)
    release = jnp.clip(0.5 * jnp.tanh(2.0 * states), 0.0, None) * mask
    floor = jnp.min(jnp.where(mask > 0, release, jnp.inf), axis=-1, keepdims=True)
    release = (release - floor) * mask
    c = release @ jnp.asarray(np.cos(theta) * angled, dtype=jnp.float32)
    s = release @ jnp.asarray(np.sin(theta) * angled, dtype=jnp.float32)
    norm = jnp.sqrt(c**2 + s**2 + 1e-8)
    return jnp.stack([c, s], -1), norm / (release.sum(-1) + 1e-3), norm


def _hold_batch(
    circuit: SubCircuit,
    angles: NDArray[np.floating],
    config: GoalHoldConfig,
    rng: np.random.Generator,
    size: int,
) -> tuple[jax.Array, jax.Array]:
    """External drive (B, T, N) with a kick at a random heading on angled neurons, and seeds."""
    seed = rng.uniform(-np.pi, np.pi, size)
    angled = np.isfinite(angles)
    pattern = config.kick * np.maximum(
        np.cos(np.where(angled, angles, 0.0)[None] - seed[:, None]), 0
    )
    external = np.zeros((size, config.frames, circuit.cell_types.size), np.float32)
    external[:, : config.kick_frames] = (pattern * angled)[:, None]
    return jnp.asarray(external), jnp.asarray(seed, dtype=jnp.float32)


def _params(circuit: SubCircuit, gains: NDArray[np.floating]) -> Params:
    g = np.clip(np.asarray(gains, dtype=np.float64), 0.05, circuit.maximum_gain * 0.95)
    return {
        "raw": jnp.asarray(np.log(g / (circuit.maximum_gain - g)), dtype=jnp.float32),
        "bias": jnp.zeros(len(_types(circuit)), dtype=jnp.float32),
    }


def _from_fit(circuit: SubCircuit, fit: GoalFit) -> Params:
    gains = np.array([fit.gains[pair] for pair in circuit.group_pairs], dtype=np.float64)
    return {
        "raw": jnp.asarray(np.log(gains / (circuit.maximum_gain - gains)), dtype=jnp.float32),
        "bias": jnp.asarray([fit.type_bias[name] for name in _types(circuit)], jnp.float32),
    }


def _run(
    circuit: SubCircuit, params: Params, external: jax.Array, config: GoalHoldConfig
) -> jax.Array:
    index = jnp.asarray([_types(circuit).index(str(name)) for name in circuit.cell_types])
    return run_subcircuit(
        circuit,
        params["raw"],
        params["bias"][index],
        jnp.zeros(circuit.cell_types.size),
        external,
        transitions=config.transitions_per_frame,
        sharpness=config.release_sharpness,
    )


def _hold_loss(
    circuit: SubCircuit,
    angles: NDArray[np.floating],
    config: GoalHoldConfig,
    params: Params,
    external: jax.Array,
    seed: jax.Array,
) -> tuple[jax.Array, jax.Array]:
    vector, strength, amplitude = _decode(_run(circuit, params, external, config), angles)
    mask = (jnp.arange(config.frames) >= config.kick_frames).astype(jnp.float32)[None]
    count = mask.sum() * external.shape[0]
    reference = jax.lax.stop_gradient(amplitude[:, config.kick_frames - 1 : config.kick_frames])
    agreement = (
        vector[..., 0] * jnp.cos(seed)[:, None] + vector[..., 1] * jnp.sin(seed)[:, None]
    ) / jnp.sqrt(vector[..., 0] ** 2 + vector[..., 1] ** 2 + 1e-4)
    error = ((1 - agreement) * mask).sum() / count
    weak = (jax.nn.relu(config.minimum_strength - strength) * mask).sum() / count
    fading = (jax.nn.relu(0.5 * reference - amplitude) / (reference + 1e-6) * mask).sum() / count
    return error + weak + fading, error


def goal_hold_error(
    circuit: SubCircuit,
    angles: NDArray[np.floating],
    gains: NDArray[np.floating] | None,
    fit: GoalFit | None,
    config: GoalHoldConfig,
    batch: tuple[jax.Array, jax.Array] | None,
) -> float:
    """Mean absolute goal-phase error (degrees) at the last frame; a fixed batch if None.

    A bump that faded below ``_FADED`` of its amplitude at the end of the kick counts as 90
    degrees (chance), since its phase is no longer a memory.
    """
    if batch is None:
        batch = _hold_batch(
            circuit, angles, config, np.random.default_rng(_VALIDATION_SEED), _VALIDATION_SIZE
        )
    params = _from_fit(circuit, fit) if fit is not None else _params(circuit, np.asarray(gains))
    external, seed = batch
    vector, _, amplitude = _decode(_run(circuit, params, external, config), angles)
    phase = np.arctan2(np.asarray(vector)[:, -1, 1], np.asarray(vector)[:, -1, 0])
    error = np.degrees(np.abs(np.angle(np.exp(1j * (phase - np.asarray(seed))))))
    amplitude = np.asarray(amplitude)
    faded = amplitude[:, -1] < _FADED * amplitude[:, config.kick_frames - 1]
    return float(np.where(faded, 90.0, error).mean())


def fit_goal_hold(
    circuit: SubCircuit,
    angles: NDArray[np.floating],
    initial_gains: NDArray[np.floating],
    config: GoalHoldConfig,
    *,
    progress_interval: int = 100,
) -> GoalFit:
    """Fit internal gains and per-type biases so a seeded goal bump holds (Adam, clipped)."""
    params = _params(circuit, initial_gains)
    rng = np.random.default_rng(config.seed)

    def loss(p: Params, external: jax.Array, seed: jax.Array):
        return _hold_loss(circuit, angles, config, p, external, seed)

    grad = jax.jit(jax.value_and_grad(loss, has_aux=True))
    first = jax.tree.map(jnp.zeros_like, params)
    second = jax.tree.map(jnp.zeros_like, params)
    history: list[dict[str, float]] = []
    for step in range(1, config.steps + 1):
        (value, error), gradients = grad(
            params, *_hold_batch(circuit, angles, config, rng, config.batch_size)
        )
        moments = clipped_moments(gradients, first, second)
        if moments is None:
            continue
        first, second = moments
        params = adam_step(params, first, second, config.learning_rate, step)
        if step % progress_interval == 0 or step == config.steps:
            history.append({"step": float(step), "loss": float(value), "phase_loss": float(error)})
    gains = circuit.maximum_gain * np.asarray(jax.nn.sigmoid(params["raw"]), dtype=np.float64)
    partial = GoalFit(
        gains={pair: float(g) for pair, g in zip(circuit.group_pairs, gains, strict=True)},
        type_bias={
            name: float(b)
            for name, b in zip(_types(circuit), np.asarray(params["bias"]), strict=True)
        },
        hold_error_deg=0.0,
        history=tuple(history),
    )
    error = goal_hold_error(circuit, angles, None, partial, config, None)
    return GoalFit(partial.gains, partial.type_bias, error, partial.history)


def goal_heading_decoding(
    states: NDArray[np.floating], offset: NDArray[np.floating], *, ridge: float = 1e-3
) -> dict[str, float]:
    """How well (cos, sin) of goal - heading is linearly decodable from states (S, N).

    A ridge decoder with a bias, fitted on a fixed 75% of trials and scored on the rest; returns
    the mean absolute angular error in degrees and the trial count.
    """
    x = np.asarray(states, dtype=np.float64)
    target = np.stack([np.cos(offset), np.sin(offset)], axis=1)
    order = np.random.default_rng(0).permutation(x.shape[0])
    split = int(0.75 * order.size)
    train, test = order[:split], order[split:]
    # Standardise on the training trials so the ridge penalty does not depend on signal scale.
    mean, scale = x[train].mean(axis=0), x[train].std(axis=0)
    x = (x - mean) / np.where(scale > 1e-12, scale, 1.0)
    design = np.hstack([x, np.ones((x.shape[0], 1))])
    gram = design[train].T @ design[train] + ridge * np.eye(design.shape[1])
    weights = np.linalg.solve(gram, design[train].T @ target[train])
    predicted = design[test] @ weights
    error = np.angle(
        np.exp(1j * (np.arctan2(predicted[:, 1], predicted[:, 0]) - np.asarray(offset)[test]))
    )
    return {"error_deg": float(np.degrees(np.abs(error)).mean()), "trials": float(x.shape[0])}


def probe_goal_heading(
    circuit: SubCircuit,
    angles: NDArray[np.floating],
    raw_gains: NDArray[np.floating],
    bias: NDArray[np.floating],
    threshold: NDArray[np.floating],
    *,
    heading: NDArray[np.bool_],
    goal: NDArray[np.bool_],
    readout: NDArray[np.bool_],
    transitions: int,
    trials: int = 64,
    frames: int = 30,
    kick_frames: int = 10,
    kick: float = 0.3,
    sharpness: float = 20.0,
    seed: int = 0,
) -> dict[str, float]:
    """Stage 2: seed heading and goal bumps at random angles; decode goal - heading from readout.

    Kicks drive the angled ``heading`` and ``goal`` neurons toward random angles for
    ``kick_frames``. The readout's *release* at the last frame is decoded with
    ``goal_heading_decoding``: goal minus heading appears only in products of the two bumps,
    which thresholded release (spiking PFL3) provides and a linear state does not.
    """
    rng = np.random.default_rng(seed)
    h = rng.uniform(-np.pi, np.pi, trials)
    g = rng.uniform(-np.pi, np.pi, trials)
    theta = np.where(np.isfinite(angles), angles, 0.0)
    external = np.zeros((trials, frames, circuit.cell_types.size), np.float32)
    for mask, target in ((heading, h), (goal, g)):
        use = mask & np.isfinite(angles)
        pattern = kick * np.maximum(np.cos(theta[None] - target[:, None]), 0.0) * use
        external[:, :kick_frames] += pattern[:, None]
    states = run_subcircuit(
        circuit,
        jnp.asarray(raw_gains, dtype=jnp.float32),
        jnp.asarray(bias, dtype=jnp.float32),
        jnp.asarray(threshold, dtype=jnp.float32),
        jnp.asarray(external),
        transitions=transitions,
        sharpness=sharpness,
    )
    final = states[:, -1]
    dynamics = LeakyRateDynamics(activation="graded", release_sharpness=sharpness)
    gated = dynamics.thresholded_release(final, jnp.asarray(threshold, dtype=jnp.float32)[None])
    release = jnp.where(jnp.asarray(circuit.gate)[None], gated, dynamics.release(final))
    return goal_heading_decoding(np.asarray(release)[:, readout], np.angle(np.exp(1j * (g - h))))


def goal_targets(
    heading: NDArray[np.floating],
    bearing: NDArray[np.floating],
    visible: NDArray[np.bool_],
    *,
    bump_gain: float,
) -> tuple[NDArray[np.float64], NDArray[np.bool_]]:
    """Target goal phase (radians, bump units) and mask, per frame (B, T).

    While honey is visible the goal is ``heading + bump_gain * bearing`` (heading in bump units,
    bearing egocentric, positive right); through occlusion it keeps the last sighting. Frames
    before the first sighting are masked out.
    """
    heading = np.asarray(heading, dtype=np.float64)
    target = np.zeros_like(heading)
    mask = np.zeros(heading.shape, dtype=np.bool_)
    for row in range(heading.shape[0]):
        current: float | None = None
        for t in range(heading.shape[1]):
            if visible[row, t]:
                current = float(heading[row, t] + bump_gain * bearing[row, t])
            if current is not None:
                target[row, t] = current
                mask[row, t] = True
    return target, mask


def masked_phase_loss(
    vector: jax.Array | NDArray[Any],
    target: jax.Array | NDArray[Any],
    mask: jax.Array | NDArray[Any],
) -> jax.Array:
    """Mean ``1 - agreement`` between population vectors (..., 2) and target phases, masked.

    Agreement uses a floored norm, so a faded vector scores chance with a bounded gradient.
    Returns 0 when nothing is masked in.
    """
    vector = jnp.asarray(vector)
    target = jnp.asarray(target)
    weight = jnp.asarray(mask, dtype=jnp.float32)
    agreement = (vector[..., 0] * jnp.cos(target) + vector[..., 1] * jnp.sin(target)) / jnp.sqrt(
        vector[..., 0] ** 2 + vector[..., 1] ** 2 + 1e-4
    )
    return ((1 - agreement) * weight).sum() / jnp.maximum(weight.sum(), 1.0)


@dataclass(frozen=True)
class GoalWriteConfig:
    steps: int
    learning_rate: float
    bump_gain: float
    frames: int = 120
    kick_frames: int = 10
    kick: float = 0.3
    transitions_per_frame: int = 4
    batch_size: int = 16
    release_sharpness: float = 20.0
    initial_vector_threshold: float = 0.0
    initial_egocentric_scale: float = 0.1
    seed: int = 0

    def __post_init__(self) -> None:
        if self.steps < 1 or not self.learning_rate > 0:
            raise ValueError("a goal write fit needs positive steps and learning rate")
        if not 0 < self.bump_gain <= 2:
            raise ValueError("bump_gain must lie in (0, 2]")
        if self.kick_frames < 1 or self.frames <= self.kick_frames + 1:
            raise ValueError("frames must exceed the kick")


@dataclass(frozen=True)
class GoalWriteFit:
    gains: dict[str, float]
    type_bias: dict[str, float]
    vector_threshold: float
    egocentric_scale: float
    write_error_deg: float
    history: tuple[dict[str, float], ...] = field(default=())


def _write_batch(
    clips: dict[str, NDArray[Any]],
    rows: NDArray[np.int64],
    config: GoalWriteConfig,
    rng: np.random.Generator,
) -> dict[str, NDArray[Any]]:
    """Kick frames (still, no honey) then the first clip frames, padded and masked."""
    frames, kick = config.frames, config.kick_frames
    length = frames - kick
    out: dict[str, NDArray[Any]] = {}
    for key, fill in (("yaw", 0.0), ("bearing", 0.0), ("visible", False), ("mask", False)):
        source = clips[key][rows, :length]
        padded = np.full((rows.size, frames), fill, dtype=source.dtype)
        padded[:, kick : kick + source.shape[1]] = source
        out[key] = padded
    out["mask"][:, :kick] = True
    out["seed"] = rng.uniform(-np.pi, np.pi, rows.size)
    return out


def fit_goal_write(
    circuit: SubCircuit,
    angles: NDArray[np.floating],
    *,
    offsets: NDArray[np.floating],
    vector_targets: NDArray[np.bool_],
    heading: NDArray[np.bool_],
    goal: NDArray[np.bool_],
    yaw_drive: NDArray[np.floating],
    initial_gains: NDArray[np.floating],
    frozen: NDArray[np.bool_],
    base_bias: NDArray[np.floating],
    base_threshold: NDArray[np.floating],
    clips: dict[str, NDArray[Any]],
    config: GoalWriteConfig,
    progress_interval: int = 100,
) -> GoalWriteFit:
    """Stage 3: fit the PFN -> hDelta -> FC2 write path on recorded turns and honey bearings.

    ``yaw_drive`` is the per-neuron drive per degree of yaw (the fitted compass's signed PEN
    scale); ``offsets`` are the PFN heading shifts for ``vector_targets``. Groups in ``frozen``
    (the fitted compass) keep their gains. While visible, the egocentric vector
    ``scale * (cos(k b), sin(k b))`` drives the PFN; the goal (FC2) phase should follow
    ``goal_targets`` of the decoded heading. Learns the other gains, a bias per type, one
    threshold for the vector neurons and the egocentric scale.
    """
    types = _types(circuit)
    type_index = jnp.asarray([types.index(str(name)) for name in circuit.cell_types])
    vector_mask = jnp.asarray(vector_targets, dtype=jnp.float32)
    cos_o = jnp.asarray(np.where(vector_targets, np.cos(offsets), 0.0), dtype=jnp.float32)
    sin_o = jnp.asarray(np.where(vector_targets, np.sin(offsets), 0.0), dtype=jnp.float32)
    heading_angles = np.where(heading, angles, np.nan)
    goal_angles = np.where(goal, angles, np.nan)
    theta = np.where(np.isfinite(heading_angles), heading_angles, 0.0)
    params: Params = {
        **_params(circuit, initial_gains),
        "vector_threshold": jnp.asarray(config.initial_vector_threshold, dtype=jnp.float32),
        "log_scale": jnp.asarray(np.log(config.initial_egocentric_scale), dtype=jnp.float32),
    }
    frozen_mask = jnp.asarray(frozen)

    def simulate(p: Params, batch: dict[str, jax.Array]) -> tuple[jax.Array, jax.Array]:
        raw = jnp.where(frozen_mask, jax.lax.stop_gradient(p["raw"]), p["raw"])
        bias = jnp.asarray(base_bias, dtype=jnp.float32) + p["bias"][type_index]
        threshold = jnp.where(
            vector_mask > 0, p["vector_threshold"], jnp.asarray(base_threshold, jnp.float32)
        )
        egocentric = jnp.exp(p["log_scale"]) * batch["visible"]
        x = egocentric * jnp.cos(config.bump_gain * batch["bearing"])
        y = egocentric * jnp.sin(config.bump_gain * batch["bearing"])
        external = (
            batch["yaw"][..., None] * jnp.asarray(yaw_drive, dtype=jnp.float32)
            + x[..., None] * cos_o
            + y[..., None] * sin_o
            + batch["kick"]
        )
        states = run_subcircuit(
            circuit,
            raw,
            bias,
            threshold,
            external,
            transitions=config.transitions_per_frame,
            sharpness=config.release_sharpness,
        )
        heading_vector, _, _ = _decode(states, heading_angles)
        goal_vector, _, _ = _decode(states, goal_angles)
        return heading_vector, goal_vector

    def prepare(raw_batch: dict[str, NDArray[Any]]) -> dict[str, jax.Array]:
        kick = np.zeros((raw_batch["yaw"].shape[0], config.frames, circuit.cell_types.size))
        pattern = config.kick * np.maximum(np.cos(theta[None] - raw_batch["seed"][:, None]), 0)
        kick[:, : config.kick_frames] = (pattern * np.isfinite(heading_angles))[:, None]
        return {
            "yaw": jnp.asarray(raw_batch["yaw"], dtype=jnp.float32),
            "bearing": jnp.asarray(raw_batch["bearing"], dtype=jnp.float32),
            "visible": jnp.asarray(raw_batch["visible"], dtype=jnp.float32),
            "mask": jnp.asarray(raw_batch["mask"]),
            "kick": jnp.asarray(kick, dtype=jnp.float32),
        }

    def targets(heading_vector: jax.Array, raw_batch: dict[str, NDArray[Any]]):
        phase = np.arctan2(np.asarray(heading_vector)[..., 1], np.asarray(heading_vector)[..., 0])
        target, mask = goal_targets(
            phase,
            np.asarray(raw_batch["bearing"], dtype=np.float64),
            np.asarray(raw_batch["visible"], dtype=np.bool_),
            bump_gain=config.bump_gain,
        )
        return jnp.asarray(target, jnp.float32), jnp.asarray(mask & raw_batch["mask"])

    def loss(p: Params, batch: dict[str, jax.Array], target: jax.Array, mask: jax.Array):
        _, goal_vector = simulate(p, batch)
        return masked_phase_loss(goal_vector, target, mask)

    run = jax.jit(simulate)
    grad = jax.jit(jax.value_and_grad(loss))
    rng = np.random.default_rng(config.seed)
    first = jax.tree.map(jnp.zeros_like, params)
    second = jax.tree.map(jnp.zeros_like, params)
    history: list[dict[str, float]] = []
    count = clips["yaw"].shape[0]
    for step in range(1, config.steps + 1):
        rows = rng.integers(0, count, config.batch_size)
        raw_batch = _write_batch(clips, rows, config, rng)
        batch = prepare(raw_batch)
        heading_vector, _ = run(params, batch)
        target, mask = targets(heading_vector, raw_batch)
        value, gradients = grad(params, batch, target, mask)
        moments = clipped_moments(gradients, first, second)
        if moments is None:
            continue
        first, second = moments
        params = adam_step(params, first, second, config.learning_rate, step)
        if step % progress_interval == 0 or step == config.steps:
            history.append({"step": float(step), "loss": float(value)})
    # Write error: occluded frames after a sighting, over every clip, in world degrees.
    errors: list[NDArray[np.float64]] = []
    for start in range(0, count, config.batch_size):
        rows = np.arange(start, min(start + config.batch_size, count))
        raw_batch = _write_batch(clips, rows, config, np.random.default_rng(start))
        heading_vector, goal_vector = run(params, prepare(raw_batch))
        h = np.arctan2(np.asarray(heading_vector)[..., 1], np.asarray(heading_vector)[..., 0])
        g = np.arctan2(np.asarray(goal_vector)[..., 1], np.asarray(goal_vector)[..., 0])
        _, seen = goal_targets(
            h, raw_batch["bearing"], raw_batch["visible"], bump_gain=config.bump_gain
        )
        occluded = seen & raw_batch["mask"] & ~np.asarray(raw_batch["visible"], dtype=np.bool_)
        wrong = np.angle(np.exp(1j * (g - h - config.bump_gain * raw_batch["bearing"])))
        errors.append(np.degrees(np.abs(wrong[occluded])) / config.bump_gain)
    pooled = np.concatenate(errors) if errors else np.array([])
    gains = circuit.maximum_gain * np.asarray(jax.nn.sigmoid(params["raw"]), dtype=np.float64)
    return GoalWriteFit(
        gains={pair: float(g) for pair, g in zip(circuit.group_pairs, gains, strict=True)},
        type_bias={
            name: float(b) for name, b in zip(types, np.asarray(params["bias"]), strict=True)
        },
        vector_threshold=float(params["vector_threshold"]),
        egocentric_scale=float(np.exp(np.asarray(params["log_scale"]))),
        write_error_deg=float(pooled.mean()) if pooled.size else float("nan"),
        history=tuple(history),
    )
