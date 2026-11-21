# pyright: reportUnknownArgumentType=false, reportUnknownLambdaType=false
# pyright: reportUnknownMemberType=false, reportUnknownVariableType=false
"""Explicit-state recurrent baseline for variable-length receptor sequences."""

from collections.abc import Sequence
from dataclasses import dataclass
from functools import partial
from typing import NamedTuple

import jax
import jax.numpy as jnp
import numpy as np
from numpy.typing import NDArray

from fly_brain.contracts import BrainExample
from fly_brain.contracts._arrays import readonly


@dataclass(frozen=True)
class TemporalBatch:
    """Padded model features and localisation targets, with an explicit time mask."""

    features: NDArray[np.float32]  # (B, T, F)
    time_mask: NDArray[np.bool_]  # (B, T)
    presence: NDArray[np.float32]  # (B, T)
    direction: NDArray[np.float32]  # (B, T, 3)

    def __post_init__(self) -> None:
        features = np.asarray(self.features, dtype=np.float32)
        mask = np.asarray(self.time_mask, dtype=np.bool_)
        presence = np.asarray(self.presence, dtype=np.float32)
        direction = np.asarray(self.direction, dtype=np.float32)
        if features.ndim != 3 or features.shape[0] == 0 or features.shape[2] == 0:
            raise ValueError("temporal features must have non-empty shape (B, T, F)")
        if mask.shape != features.shape[:2] or presence.shape != features.shape[:2]:
            raise ValueError("temporal masks and presence targets must match (B, T)")
        if direction.shape != (*features.shape[:2], 3):
            raise ValueError("temporal direction targets must have shape (B, T, 3)")
        if not np.all(np.any(mask, axis=1)):
            raise ValueError("every temporal sequence must contain a valid time step")
        if not all(np.isfinite(value).all() for value in (features, presence, direction)):
            raise ValueError("temporal batches must be finite")
        if np.any((presence < 0) | (presence > 1)):
            raise ValueError("temporal presence targets must lie in [0, 1]")
        object.__setattr__(self, "features", readonly(features))
        object.__setattr__(self, "time_mask", readonly(mask))
        object.__setattr__(self, "presence", readonly(presence))
        object.__setattr__(self, "direction", readonly(direction))

    @property
    def sequences(self) -> int:
        return self.features.shape[0]

    @property
    def time_steps(self) -> int:
        return self.features.shape[1]

    @property
    def input_width(self) -> int:
        return self.features.shape[2]


def prepare_temporal_batch(
    examples: Sequence[BrainExample], *, include_context: bool = False
) -> TemporalBatch:
    """Pad receptor sequences, optionally appending legal egocentric context."""
    if not examples:
        raise ValueError("at least one brain example is required")
    layout = examples[0].observation.layout
    maximum_steps = max(example.observation.time_steps for example in examples)
    receptor_width = layout.site_count * layout.channel_count
    validity_width = layout.site_count
    context_width = 0
    if include_context:
        context = examples[0].observation.context
        if context is None:
            raise ValueError("context was requested but an example has none")
        context_width = context.schema.width
    features = np.zeros(
        (len(examples), maximum_steps, receptor_width + validity_width + context_width),
        dtype=np.float32,
    )
    mask = np.zeros((len(examples), maximum_steps), dtype=np.bool_)
    presence = np.zeros((len(examples), maximum_steps), dtype=np.float32)
    direction = np.zeros((len(examples), maximum_steps, 3), dtype=np.float32)
    for row, example in enumerate(examples):
        observation = example.observation
        targets = example.targets
        if observation.layout != layout:
            raise ValueError("all temporal examples must use the same sensor layout")
        if targets.honey_present is None or targets.honey_direction is None:
            raise ValueError("temporal localisation requires presence and direction targets")
        steps = observation.time_steps
        signals = observation.receptor_signals * observation.receptor_valid[:, :, None]
        parts = [signals.reshape(steps, receptor_width), observation.receptor_valid]
        if include_context:
            if observation.context is None or observation.context.schema.width != context_width:
                raise ValueError("all temporal examples must provide the same context width")
            parts.append(observation.context.values)
        features[row, :steps] = np.concatenate(parts, axis=1)
        mask[row, :steps] = targets.time_mask
        presence[row, :steps] = targets.honey_present
        direction[row, :steps] = targets.honey_direction
    return TemporalBatch(features, mask, presence, direction)


class RecurrentState(NamedTuple):
    hidden: jax.Array


class RecurrentParameters(NamedTuple):
    mean: jax.Array
    scale: jax.Array
    gate_input_weights: jax.Array
    gate_state_weights: jax.Array
    gate_bias: jax.Array
    candidate_input_weights: jax.Array
    candidate_state_weights: jax.Array
    candidate_bias: jax.Array
    output_weights: jax.Array
    output_bias: jax.Array


@dataclass(frozen=True)
class TemporalPrediction:
    presence_probability: NDArray[np.float32]  # (B, T)
    direction: NDArray[np.float32]  # (B, T, 3)
    time_mask: NDArray[np.bool_]  # (B, T)


@dataclass(frozen=True)
class RecurrentTrainingConfig:
    steps: int = 1_000
    batch_size: int = 8
    learning_rate: float = 1e-3
    truncation_steps: int = 32
    seed: int = 0

    def __post_init__(self) -> None:
        if min(self.steps, self.batch_size, self.truncation_steps) < 1:
            raise ValueError("recurrent training counts must be positive")
        if not np.isfinite(self.learning_rate) or self.learning_rate <= 0:
            raise ValueError("recurrent learning_rate must be positive")


@dataclass(frozen=True)
class RecurrentTrainingResult:
    parameters: RecurrentParameters
    losses: tuple[float, ...]


@dataclass(frozen=True)
class RecurrentLocalizationModel:
    input_width: int
    hidden_width: int = 64

    def __post_init__(self) -> None:
        if self.input_width < 1 or self.hidden_width < 1:
            raise ValueError("recurrent model dimensions must be positive")

    def initialize_state(self, batch_size: int) -> RecurrentState:
        if batch_size < 1:
            raise ValueError("recurrent state batch size must be positive")
        return RecurrentState(jnp.zeros((batch_size, self.hidden_width), dtype=jnp.float32))

    def initialize(
        self, seed: int, mean: NDArray[np.float32], scale: NDArray[np.float32]
    ) -> RecurrentParameters:
        if mean.shape != (self.input_width,) or scale.shape != (self.input_width,):
            raise ValueError("normalization statistics must match the recurrent input width")
        keys = jax.random.split(jax.random.key(seed), 5)
        return RecurrentParameters(
            jnp.asarray(mean),
            jnp.asarray(scale),
            jax.random.normal(keys[0], (self.input_width, 2 * self.hidden_width))
            * np.sqrt(1.0 / self.input_width),
            jax.random.normal(keys[1], (self.hidden_width, 2 * self.hidden_width))
            * np.sqrt(1.0 / self.hidden_width),
            jnp.zeros(2 * self.hidden_width),
            jax.random.normal(keys[2], (self.input_width, self.hidden_width))
            * np.sqrt(1.0 / self.input_width),
            jax.random.normal(keys[3], (self.hidden_width, self.hidden_width))
            * np.sqrt(1.0 / self.hidden_width),
            jnp.zeros(self.hidden_width),
            jax.random.normal(keys[4], (self.hidden_width, 4)) * np.sqrt(1.0 / self.hidden_width),
            jnp.zeros(4),
        )

    def run(
        self,
        parameters: RecurrentParameters,
        features: NDArray[np.float32],
        time_mask: NDArray[np.bool_],
        state: RecurrentState | None = None,
    ) -> tuple[TemporalPrediction, RecurrentState]:
        values = np.asarray(features, dtype=np.float32)
        mask = np.asarray(time_mask, dtype=np.bool_)
        if values.ndim != 3 or values.shape[2] != self.input_width:
            raise ValueError("recurrent features must have shape (B, T, input_width)")
        if mask.shape != values.shape[:2]:
            raise ValueError("recurrent time mask must match (B, T)")
        initial = state or self.initialize_state(values.shape[0])
        if initial.hidden.shape != (values.shape[0], self.hidden_width):
            raise ValueError("recurrent state does not match the input batch")
        raw, final_state = _run(parameters, initial, jnp.asarray(values), jnp.asarray(mask))
        raw_host = np.asarray(jax.device_get(raw), dtype=np.float32)
        presence = np.asarray(jax.nn.sigmoid(raw_host[:, :, 0]), dtype=np.float32)
        direction_raw = raw_host[:, :, 1:]
        norm = np.maximum(np.linalg.norm(direction_raw, axis=2, keepdims=True), 1e-6)
        direction = np.asarray(direction_raw / norm, dtype=np.float32)
        return TemporalPrediction(presence, direction, mask), final_state


def train_recurrent(
    model: RecurrentLocalizationModel,
    batch: TemporalBatch,
    config: RecurrentTrainingConfig,
) -> RecurrentTrainingResult:
    if model.input_width != batch.input_width:
        raise ValueError("model dimensions do not match the temporal batch")
    valid_features = batch.features[batch.time_mask]
    mean = valid_features.mean(axis=0, dtype=np.float64).astype(np.float32)
    deviation = valid_features.std(axis=0, dtype=np.float64).astype(np.float32)
    scale = np.where(deviation > 1e-6, deviation, 1.0).astype(np.float32)
    parameters = model.initialize(config.seed, mean, scale)
    first_moment = jax.tree.map(jnp.zeros_like, parameters)
    second_moment = jax.tree.map(jnp.zeros_like, parameters)
    rng = np.random.default_rng(config.seed)
    loss_and_grad = jax.jit(
        jax.value_and_grad(
            partial(
                _loss,
                model=model,
                truncation_steps=config.truncation_steps,
            )
        )
    )
    losses: list[float] = []
    for step in range(1, config.steps + 1):
        rows = rng.integers(0, batch.sequences, size=config.batch_size)
        loss, gradients = loss_and_grad(
            parameters,
            jnp.asarray(batch.features[rows]),
            jnp.asarray(batch.time_mask[rows]),
            jnp.asarray(batch.presence[rows]),
            jnp.asarray(batch.direction[rows]),
        )
        first_moment = jax.tree.map(
            lambda moment, gradient: 0.9 * moment + 0.1 * gradient,
            first_moment,
            gradients,
        )
        second_moment = jax.tree.map(
            lambda moment, gradient: 0.999 * moment + 0.001 * jnp.square(gradient),
            second_moment,
            gradients,
        )
        parameters = jax.tree.map(
            partial(
                _adam_update,
                learning_rate=config.learning_rate,
                first_correction=1.0 - 0.9**step,
                second_correction=1.0 - 0.999**step,
            ),
            parameters,
            first_moment,
            second_moment,
        )
        losses.append(float(loss))
    return RecurrentTrainingResult(parameters, tuple(losses))


def _run(
    parameters: RecurrentParameters,
    state: RecurrentState,
    features: jax.Array,
    time_mask: jax.Array,
) -> tuple[jax.Array, RecurrentState]:
    normalized = (features - jax.lax.stop_gradient(parameters.mean)) / jax.lax.stop_gradient(
        parameters.scale
    )

    def scan_step(hidden: jax.Array, item: tuple[jax.Array, jax.Array]):
        values, active = item
        gates = jax.nn.sigmoid(
            values @ parameters.gate_input_weights
            + hidden @ parameters.gate_state_weights
            + parameters.gate_bias
        )
        reset, update = jnp.split(gates, 2, axis=1)
        candidate = jnp.tanh(
            values @ parameters.candidate_input_weights
            + (reset * hidden) @ parameters.candidate_state_weights
            + parameters.candidate_bias
        )
        proposed = (1.0 - update) * hidden + update * candidate
        next_hidden = jnp.where(active[:, None], proposed, hidden)
        raw = next_hidden @ parameters.output_weights + parameters.output_bias
        return next_hidden, raw

    hidden, raw = jax.lax.scan(
        scan_step,
        state.hidden,
        (jnp.swapaxes(normalized, 0, 1), jnp.swapaxes(time_mask, 0, 1)),
    )
    return jnp.swapaxes(raw, 0, 1), RecurrentState(hidden)


def _apply_truncated(
    parameters: RecurrentParameters,
    features: jax.Array,
    time_mask: jax.Array,
    *,
    model: RecurrentLocalizationModel,
    truncation_steps: int,
) -> jax.Array:
    state = model.initialize_state(features.shape[0])
    parts: list[jax.Array] = []
    for start in range(0, features.shape[1], truncation_steps):
        stop = min(start + truncation_steps, features.shape[1])
        raw, state = _run(parameters, state, features[:, start:stop], time_mask[:, start:stop])
        parts.append(raw)
        state = jax.tree.map(jax.lax.stop_gradient, state)
    return jnp.concatenate(parts, axis=1)


def _loss(
    parameters: RecurrentParameters,
    features: jax.Array,
    time_mask: jax.Array,
    presence: jax.Array,
    target_direction: jax.Array,
    *,
    model: RecurrentLocalizationModel,
    truncation_steps: int,
) -> jax.Array:
    raw = _apply_truncated(
        parameters,
        features,
        time_mask,
        model=model,
        truncation_steps=truncation_steps,
    )
    valid = time_mask.astype(jnp.float32)
    positive = valid * presence
    negative = valid * (1.0 - presence)
    cross_entropy = jax.nn.softplus(raw[:, :, 0]) - presence * raw[:, :, 0]
    presence_loss = 0.5 * (
        jnp.sum(cross_entropy * positive) / jnp.maximum(jnp.sum(positive), 1.0)
        + jnp.sum(cross_entropy * negative) / jnp.maximum(jnp.sum(negative), 1.0)
    )
    direction_raw = raw[:, :, 1:]
    direction = direction_raw / jnp.maximum(
        jnp.linalg.norm(direction_raw, axis=2, keepdims=True), 1e-6
    )
    direction_error = 1.0 - jnp.sum(direction * target_direction, axis=2)
    direction_loss = jnp.sum(direction_error * positive) / jnp.maximum(jnp.sum(positive), 1.0)
    return presence_loss + direction_loss


def _adam_update(
    value: jax.Array,
    first: jax.Array,
    second: jax.Array,
    *,
    learning_rate: float,
    first_correction: float,
    second_correction: float,
) -> jax.Array:
    return value - learning_rate * (first / first_correction) / (
        jnp.sqrt(second / second_correction) + 1e-8
    )
