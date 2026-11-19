# pyright: reportUnknownArgumentType=false, reportUnknownLambdaType=false
# pyright: reportUnknownMemberType=false, reportUnknownVariableType=false
"""Small framewise linear baseline for honey localisation."""

from collections.abc import Sequence
from dataclasses import dataclass
from typing import NamedTuple

import jax
import jax.numpy as jnp
import numpy as np
from numpy.typing import NDArray

from fly_brain.contracts import BrainExample, ObservationSequence
from fly_brain.contracts._arrays import readonly
from fly_brain.tasks import HoneyLocalizationPrediction


class LinearParameters(NamedTuple):
    weights: jax.Array
    bias: jax.Array


class _JaxBatch(NamedTuple):
    features: jax.Array
    time_mask: jax.Array
    presence: jax.Array
    direction: jax.Array
    coverage: jax.Array | None


@dataclass(frozen=True)
class LocalizationBatch:
    features: NDArray[np.float32]
    time_mask: NDArray[np.bool_]
    presence: NDArray[np.float32]
    direction: NDArray[np.float32]
    coverage: NDArray[np.float32] | None
    site_count: int

    def __post_init__(self) -> None:
        features = np.asarray(self.features, dtype=np.float32)
        time_mask = np.asarray(self.time_mask, dtype=np.bool_)
        presence = np.asarray(self.presence, dtype=np.float32)
        direction = np.asarray(self.direction, dtype=np.float32)
        rows = features.shape[0] if features.ndim == 2 else 0
        if rows == 0:
            raise ValueError("localisation batch features must have non-empty shape (B, D)")
        if time_mask.shape != (rows,) or presence.shape != (rows,):
            raise ValueError("batch masks and presence targets must have shape (B,)")
        if direction.shape != (rows, 3):
            raise ValueError("batch direction targets must have shape (B, 3)")
        if self.site_count < 1:
            raise ValueError("site_count must be positive")
        object.__setattr__(self, "features", readonly(features))
        object.__setattr__(self, "time_mask", readonly(time_mask))
        object.__setattr__(self, "presence", readonly(presence))
        object.__setattr__(self, "direction", readonly(direction))
        if self.coverage is not None:
            coverage = np.asarray(self.coverage, dtype=np.float32)
            if coverage.shape != (rows, self.site_count):
                raise ValueError(
                    f"batch coverage targets must have shape {(rows, self.site_count)}"
                )
            object.__setattr__(self, "coverage", readonly(coverage))

    @property
    def input_width(self) -> int:
        return self.features.shape[1]


@dataclass(frozen=True)
class TrainingResult:
    parameters: LinearParameters
    losses: tuple[float, ...]


@dataclass(frozen=True)
class LinearLocalizationModel:
    input_width: int
    site_count: int

    def __post_init__(self) -> None:
        if self.input_width < 1 or self.site_count < 1:
            raise ValueError("linear model dimensions must be positive")

    @property
    def output_width(self) -> int:
        return 4 + self.site_count

    def initialize(self, seed: int) -> LinearParameters:
        key = jax.random.key(seed)
        weights = jax.random.normal(key, (self.input_width, self.output_width)) * 0.01
        return LinearParameters(weights, jnp.zeros(self.output_width))

    def predict(
        self, parameters: LinearParameters, observation: ObservationSequence
    ) -> HoneyLocalizationPrediction:
        features = encode_observation(observation)
        if features.shape[1] != self.input_width:
            raise ValueError("observation feature width does not match the linear model")
        outputs = _apply(parameters, jnp.asarray(features))
        return HoneyLocalizationPrediction(
            presence_probability=np.asarray(jax.device_get(outputs[0])),
            direction=np.asarray(jax.device_get(outputs[1])),
            coverage=np.asarray(jax.device_get(outputs[2])),
        )


def encode_observation(observation: ObservationSequence) -> NDArray[np.float32]:
    """Flatten direct receptors, their validity mask and optional legal context."""
    masked = np.where(
        observation.receptor_valid[:, :, None], observation.receptor_signals, 0.0
    ).reshape(observation.time_steps, -1)
    blocks = [masked, observation.receptor_valid.astype(np.float32)]
    if observation.context is not None:
        blocks.append(observation.context.values)
    return np.concatenate(blocks, axis=1).astype(np.float32, copy=False)


def prepare_localization_batch(examples: Sequence[BrainExample]) -> LocalizationBatch:
    if not examples:
        raise ValueError("at least one example is required")
    layout_id = examples[0].observation.layout.layout_id
    have_coverage = examples[0].targets.honey_coverage is not None
    features: list[NDArray[np.float32]] = []
    masks: list[NDArray[np.bool_]] = []
    presence: list[NDArray[np.float32]] = []
    directions: list[NDArray[np.float32]] = []
    coverage: list[NDArray[np.float32]] = []
    for example in examples:
        targets = example.targets
        if example.observation.layout.layout_id != layout_id:
            raise ValueError("all examples in a batch must use the same sensor layout")
        if targets.honey_present is None or targets.honey_direction is None:
            raise ValueError("linear localisation requires presence and direction targets")
        if (targets.honey_coverage is not None) is not have_coverage:
            raise ValueError("coverage targets must be present for every example or none")
        features.append(encode_observation(example.observation))
        masks.append(targets.time_mask)
        presence.append(targets.honey_present.astype(np.float32))
        directions.append(targets.honey_direction)
        if targets.honey_coverage is not None:
            coverage.append(targets.honey_coverage)
    return LocalizationBatch(
        features=np.concatenate(features),
        time_mask=np.concatenate(masks),
        presence=np.concatenate(presence),
        direction=np.concatenate(directions),
        coverage=np.concatenate(coverage) if have_coverage else None,
        site_count=examples[0].observation.layout.site_count,
    )


def train_linear(
    model: LinearLocalizationModel,
    batch: LocalizationBatch,
    *,
    steps: int = 200,
    learning_rate: float = 0.05,
    seed: int = 0,
) -> TrainingResult:
    if model.input_width != batch.input_width or model.site_count != batch.site_count:
        raise ValueError("model dimensions do not match the localisation batch")
    if steps < 1 or not np.isfinite(learning_rate) or learning_rate <= 0:
        raise ValueError("training steps and learning_rate must be positive")
    parameters = model.initialize(seed)
    jax_batch = _to_jax(batch)
    value_and_grad = jax.jit(jax.value_and_grad(_loss))
    losses: list[float] = []
    for _ in range(steps):
        loss, gradients = value_and_grad(parameters, jax_batch)
        parameters = jax.tree.map(
            lambda value, gradient: value - learning_rate * gradient,
            parameters,
            gradients,
        )
        losses.append(float(loss))
    return TrainingResult(parameters, tuple(losses))


def _to_jax(batch: LocalizationBatch) -> _JaxBatch:
    return _JaxBatch(
        jnp.asarray(batch.features),
        jnp.asarray(batch.time_mask),
        jnp.asarray(batch.presence),
        jnp.asarray(batch.direction),
        None if batch.coverage is None else jnp.asarray(batch.coverage),
    )


def _apply(
    parameters: LinearParameters, features: jax.Array
) -> tuple[jax.Array, jax.Array, jax.Array]:
    raw = features @ parameters.weights + parameters.bias
    presence = jax.nn.sigmoid(raw[:, 0])
    direction_raw = raw[:, 1:4]
    norms = jnp.linalg.norm(direction_raw, axis=1, keepdims=True)
    direction = jnp.where(norms > 1e-6, direction_raw / jnp.maximum(norms, 1e-6), 0.0)
    coverage = jax.nn.sigmoid(raw[:, 4:])
    return presence, direction, coverage


def _loss(parameters: LinearParameters, batch: _JaxBatch) -> jax.Array:
    raw = batch.features @ parameters.weights + parameters.bias
    valid = batch.time_mask.astype(jnp.float32)
    denominator = jnp.maximum(jnp.sum(valid), 1.0)
    presence_loss = (
        jnp.sum((jax.nn.softplus(raw[:, 0]) - batch.presence * raw[:, 0]) * valid) / denominator
    )
    direction_raw = raw[:, 1:4]
    direction = direction_raw / jnp.maximum(
        jnp.linalg.norm(direction_raw, axis=1, keepdims=True), 1e-6
    )
    positive = valid * batch.presence
    direction_loss = jnp.sum(
        (1.0 - jnp.sum(direction * batch.direction, axis=1)) * positive
    ) / jnp.maximum(jnp.sum(positive), 1.0)
    coverage_loss = 0.0
    if batch.coverage is not None:
        coverage_error = jax.nn.sigmoid(raw[:, 4:]) - batch.coverage
        coverage_loss = jnp.sum(jnp.square(coverage_error) * valid[:, None]) / (
            denominator * batch.coverage.shape[1]
        )
    return presence_loss + direction_loss + coverage_loss
