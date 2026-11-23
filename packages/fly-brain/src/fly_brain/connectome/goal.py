# pyright: reportUnknownMemberType=false, reportUnknownVariableType=false
# pyright: reportUnknownArgumentType=false
"""Fan-shaped-body goal memory fits (ADR 0030): hold, then (later stages) compare and write.

Stage 1 fits FC2, FB5A and hDelta to hold a goal: a brief kick seeds an FC2 bump at a random
heading, and the bump should stay there, with strength at least ``minimum_strength``, once the
kick ends. It learns the internal type-pair gains and one bias per cell type.
"""

from dataclasses import dataclass, field

import jax
import jax.numpy as jnp
import numpy as np
from numpy.typing import NDArray

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
