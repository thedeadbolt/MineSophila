"""Connectome localisation trained on clips, with state carried across frames (Slice 9g).

Each frame holds its port values for ``transitions_per_frame`` brain transitions; the state
then carries into the next frame, so recurrent circuits such as the central-complex compass
can hold information through occlusion. Training uses truncated backpropagation through time
and the same balanced localisation loss and evaluator as the recurrent (GRU) control.
"""

# pyright: reportUnknownArgumentType=false, reportUnknownLambdaType=false
# pyright: reportUnknownMemberType=false, reportUnknownVariableType=false

from collections.abc import Callable
from dataclasses import dataclass, replace
from functools import partial

import jax
import jax.numpy as jnp
import numpy as np
from numpy.typing import NDArray

from fly_brain.connectome import BrainParameters, BrainState, CompiledBrain
from fly_brain.contracts._arrays import readonly
from fly_brain.models.connectome import _adamw_update, readout_scale  # pyright: ignore[reportPrivateUsage]
from fly_brain.models.temporal import (
    TemporalBatch,
    TemporalPrediction,
    temporal_localisation_loss,
)


@dataclass(frozen=True)
class ConnectomeClipTrainingConfig:
    steps: int = 2_000
    batch_size: int = 4
    learning_rate: float = 1e-3
    weight_decay: float = 1e-5
    truncation_steps: int = 16
    seed: int = 0

    def __post_init__(self) -> None:
        if self.steps < 1 or self.batch_size < 1:
            raise ValueError("clip training steps and batch size must be positive")
        if self.truncation_steps < 1:
            raise ValueError("truncation_steps must be positive")
        if not np.isfinite(self.learning_rate) or self.learning_rate <= 0:
            raise ValueError("learning_rate must be positive")
        if not np.isfinite(self.weight_decay) or self.weight_decay < 0:
            raise ValueError("weight_decay must be non-negative")


@dataclass(frozen=True)
class ConnectomeClipTrainingResult:
    parameters: BrainParameters
    losses: tuple[float, ...]


@dataclass(frozen=True)
class ConnectomeClipModel:
    """Run a compiled brain over clips and read localisation from its readout neurons."""

    brain: CompiledBrain
    transitions_per_frame: int = 2
    readout_mean: NDArray[np.float32] | None = None
    readout_scale: NDArray[np.float32] | None = None

    def __post_init__(self) -> None:
        if self.brain.output_width != 4:
            raise ValueError("localisation output head must have width four")
        if self.transitions_per_frame < 1:
            raise ValueError("transitions_per_frame must be positive")
        if (self.readout_mean is None) != (self.readout_scale is None):
            raise ValueError("readout mean and scale must be set together")
        if self.readout_mean is not None and self.readout_scale is not None:
            shape = (self.brain.readout_indexes.size,)
            mean = np.asarray(self.readout_mean, dtype=np.float32)
            scale = np.asarray(self.readout_scale, dtype=np.float32)
            if mean.shape != shape or scale.shape != shape or not np.all(scale > 0):
                raise ValueError("readout statistics must match the readout neurons")
            object.__setattr__(self, "readout_mean", readonly(mean))
            object.__setattr__(self, "readout_scale", readonly(scale))

    def initialize(self, seed: int = 0) -> BrainParameters:
        return self.brain.initialize_parameters(seed)

    def run(
        self,
        parameters: BrainParameters,
        port_values: NDArray[np.float32],
        time_mask: NDArray[np.bool_],
        *,
        truncation_steps: int | None = None,
    ) -> TemporalPrediction:
        values, mask = self._check(port_values, time_mask)
        apply = jax.jit(
            partial(
                _apply_truncated, model=self, truncation_steps=truncation_steps or values.shape[1]
            )
        )
        raw = np.asarray(jax.device_get(apply(parameters, jnp.asarray(values), jnp.asarray(mask))))
        return _prediction(raw, mask)

    def with_readout_normalization(
        self,
        parameters: BrainParameters,
        port_values: NDArray[np.float32],
        time_mask: NDArray[np.bool_],
    ) -> "ConnectomeClipModel":
        """Measure readout activity over clips under ``parameters`` and z-score it from now on."""
        values, mask = self._check(port_values, time_mask)
        plain = replace(self, readout_mean=None, readout_scale=None)
        collect = jax.jit(partial(_readout_activity, model=plain))
        activity = np.asarray(
            jax.device_get(collect(parameters, jnp.asarray(values), jnp.asarray(mask)))
        )[mask].astype(np.float64)
        return replace(
            self,
            readout_mean=activity.mean(axis=0).astype(np.float32),
            readout_scale=readout_scale(activity.std(axis=0)),
        )

    def fold_readout_normalization(self, parameters: BrainParameters) -> BrainParameters:
        return _effective_parameters(parameters, self)

    def _check(
        self, port_values: NDArray[np.float32], time_mask: NDArray[np.bool_]
    ) -> tuple[NDArray[np.float32], NDArray[np.bool_]]:
        values = np.asarray(port_values, dtype=np.float32)
        mask = np.asarray(time_mask, dtype=np.bool_)
        if values.ndim != 3 or values.shape[2] != self.brain.input_width:
            raise ValueError("clip port values must have shape (B, T, brain.input_width)")
        if mask.shape != values.shape[:2]:
            raise ValueError("clip time mask must match (B, T)")
        return values, mask


def train_connectome_clips(
    model: ConnectomeClipModel,
    batch: TemporalBatch,
    config: ConnectomeClipTrainingConfig,
    initial: BrainParameters | None = None,
    *,
    progress: Callable[[int, float], None] | None = None,
    progress_interval: int = 50,
) -> ConnectomeClipTrainingResult:
    """Train with truncated backpropagation through time on randomly drawn clips.

    ``progress`` receives the step and the mean loss of the last ``progress_interval`` steps.
    """
    if batch.input_width != model.brain.input_width:
        raise ValueError("clip batch width does not match the compiled brain")
    parameters = initial if initial is not None else model.initialize(config.seed)
    first_moment = jax.tree.map(jnp.zeros_like, parameters)
    second_moment = jax.tree.map(jnp.zeros_like, parameters)
    rng = np.random.default_rng(config.seed)
    lengths = batch.time_mask.sum(axis=1)
    loss_and_grad = jax.jit(
        jax.value_and_grad(partial(_loss, model=model, truncation_steps=config.truncation_steps)),
        static_argnames=(),
    )
    losses: list[float] = []
    for step in range(1, config.steps + 1):
        rows = rng.integers(0, batch.sequences, size=config.batch_size)
        # Pad each draw to a multiple of the truncation length to bound recompilation.
        longest = int(lengths[rows].max())
        width = min(
            batch.time_steps,
            int(np.ceil(longest / config.truncation_steps)) * config.truncation_steps,
        )
        loss, gradients = loss_and_grad(
            parameters,
            jnp.asarray(batch.features[rows, :width]),
            jnp.asarray(batch.time_mask[rows, :width]),
            jnp.asarray(batch.presence[rows, :width]),
            jnp.asarray(batch.direction[rows, :width]),
        )
        first_moment = jax.tree.map(
            lambda moment, gradient: 0.9 * moment + 0.1 * gradient, first_moment, gradients
        )
        second_moment = jax.tree.map(
            lambda moment, gradient: 0.999 * moment + 0.001 * jnp.square(gradient),
            second_moment,
            gradients,
        )
        parameters = jax.tree.map(
            partial(
                _adamw_update,
                learning_rate=config.learning_rate,
                weight_decay=config.weight_decay,
                first_correction=1.0 - 0.9**step,
                second_correction=1.0 - 0.999**step,
            ),
            parameters,
            first_moment,
            second_moment,
        )
        losses.append(float(loss))
        if progress is not None and (step % progress_interval == 0 or step == config.steps):
            progress(step, float(np.mean(losses[-progress_interval:])))
    return ConnectomeClipTrainingResult(parameters, tuple(losses))


def self_motion_port_values(
    context: NDArray[np.float32],
    *,
    yaw_index: int,
    signs: NDArray[np.float32],
    scale: float,
) -> NDArray[np.float32]:
    """Map measured yaw rate onto angular-velocity port neurons, signed by brain side.

    ``context`` is (B, T, C) egocentric motion context; the result is (B, T, len(signs)).
    """
    values = np.asarray(context, dtype=np.float32)
    if values.ndim != 3 or not 0 <= yaw_index < values.shape[2]:
        raise ValueError("yaw_index must name a context feature of a (B, T, C) array")
    side = np.asarray(signs, dtype=np.float32)
    if side.ndim != 1 or side.size == 0 or not np.all(np.isin(side, (-1.0, 1.0))):
        raise ValueError("signs must be a non-empty vector of +1 and -1")
    return (values[:, :, yaw_index, None] * scale * side[None, None, :]).astype(np.float32)


def _effective_parameters(
    parameters: BrainParameters, model: ConnectomeClipModel
) -> BrainParameters:
    if model.readout_mean is None or model.readout_scale is None:
        return parameters
    scale = jnp.asarray(model.readout_scale)[:, None]
    weights = parameters.readout_weights / scale
    bias = parameters.readout_bias - jnp.asarray(model.readout_mean) @ weights
    return parameters._replace(readout_weights=weights, readout_bias=bias)


def _frames(
    parameters: BrainParameters,
    state: BrainState,
    values: jax.Array,
    mask: jax.Array,
    *,
    model: ConnectomeClipModel,
) -> tuple[BrainState, jax.Array]:
    """Run frames of shape (B, T, F); return the final state and readout activity (B, T, R)."""
    readout = jnp.asarray(model.brain.readout_indexes)

    def frame(current: BrainState, item: tuple[jax.Array, jax.Array]):
        frame_values, active = item

        def transition(_: int, inner: BrainState) -> BrainState:
            return model.brain.step(parameters, inner, frame_values).state

        updated = jax.lax.fori_loop(0, model.transitions_per_frame, transition, current)
        next_state = BrainState(jnp.where(active[:, None], updated.activity, current.activity))
        return next_state, next_state.activity[:, readout]

    final, activity = jax.lax.scan(
        jax.checkpoint(frame), state, (jnp.swapaxes(values, 0, 1), jnp.swapaxes(mask, 0, 1))
    )
    return final, jnp.swapaxes(activity, 0, 1)


def _apply_truncated(
    parameters: BrainParameters,
    values: jax.Array,
    mask: jax.Array,
    *,
    model: ConnectomeClipModel,
    truncation_steps: int,
) -> jax.Array:
    effective = _effective_parameters(parameters, model)
    state = model.brain.initialize_state(values.shape[0])
    parts: list[jax.Array] = []
    for start in range(0, values.shape[1], truncation_steps):
        stop = min(start + truncation_steps, values.shape[1])
        state, activity = _frames(
            effective, state, values[:, start:stop], mask[:, start:stop], model=model
        )
        parts.append(activity @ effective.readout_weights + effective.readout_bias)
        state = jax.tree.map(jax.lax.stop_gradient, state)
    return jnp.concatenate(parts, axis=1)


def _readout_activity(
    parameters: BrainParameters, values: jax.Array, mask: jax.Array, *, model: ConnectomeClipModel
) -> jax.Array:
    _, activity = _frames(
        parameters, model.brain.initialize_state(values.shape[0]), values, mask, model=model
    )
    return activity


def _loss(
    parameters: BrainParameters,
    values: jax.Array,
    mask: jax.Array,
    presence: jax.Array,
    direction: jax.Array,
    *,
    model: ConnectomeClipModel,
    truncation_steps: int,
) -> jax.Array:
    raw = _apply_truncated(parameters, values, mask, model=model, truncation_steps=truncation_steps)
    return temporal_localisation_loss(raw, mask, presence, direction)


def _prediction(raw: NDArray[np.float32], mask: NDArray[np.bool_]) -> TemporalPrediction:
    presence = 1.0 / (1.0 + np.exp(-raw[:, :, 0]))
    direction = raw[:, :, 1:]
    direction = direction / np.maximum(np.linalg.norm(direction, axis=2, keepdims=True), 1e-6)
    return TemporalPrediction(
        presence.astype(np.float32), direction.astype(np.float32), mask.copy()
    )
