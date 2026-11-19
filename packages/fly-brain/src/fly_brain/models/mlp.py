# pyright: reportUnknownArgumentType=false, reportUnknownLambdaType=false
# pyright: reportUnknownMemberType=false, reportUnknownVariableType=false
"""Small nonlinear baseline trained end-to-end for honey localisation."""

from dataclasses import dataclass
from functools import partial
from typing import NamedTuple

import jax
import jax.numpy as jnp
import numpy as np
from numpy.typing import NDArray

from fly_brain.models.linear import LocalizationBatch
from fly_brain.tasks import HoneyLocalizationPrediction


class MLPParameters(NamedTuple):
    mean: jax.Array
    scale: jax.Array
    input_weights: jax.Array
    input_bias: jax.Array
    hidden_weights: jax.Array
    hidden_bias: jax.Array
    output_weights: jax.Array
    output_bias: jax.Array


@dataclass(frozen=True)
class MLPTrainingResult:
    parameters: MLPParameters
    losses: tuple[float, ...]


@dataclass(frozen=True)
class MLPLocalizationModel:
    input_width: int
    hidden_width: int = 64

    def __post_init__(self) -> None:
        if self.input_width < 1 or self.hidden_width < 1:
            raise ValueError("MLP dimensions must be positive")

    def initialize(
        self, seed: int, mean: NDArray[np.float32], scale: NDArray[np.float32]
    ) -> MLPParameters:
        if mean.shape != (self.input_width,) or scale.shape != (self.input_width,):
            raise ValueError("normalization statistics must match the MLP input width")
        first, second, third = jax.random.split(jax.random.key(seed), 3)
        input_weights = jax.random.normal(first, (self.input_width, self.hidden_width)) * np.sqrt(
            2.0 / self.input_width
        )
        hidden_weights = jax.random.normal(
            second, (self.hidden_width, self.hidden_width)
        ) * np.sqrt(2.0 / self.hidden_width)
        output_weights = jax.random.normal(third, (self.hidden_width, 4)) * np.sqrt(
            1.0 / self.hidden_width
        )
        return MLPParameters(
            jnp.asarray(mean),
            jnp.asarray(scale),
            input_weights,
            jnp.zeros(self.hidden_width),
            hidden_weights,
            jnp.zeros(self.hidden_width),
            output_weights,
            jnp.zeros(4),
        )

    def predict_features(
        self, parameters: MLPParameters, features: NDArray[np.float32]
    ) -> HoneyLocalizationPrediction:
        if features.ndim != 2 or features.shape[1] != self.input_width:
            raise ValueError("feature width does not match the MLP")
        raw = _apply(parameters, jnp.asarray(features))
        presence = np.asarray(jax.device_get(jax.nn.sigmoid(raw[:, 0])))
        direction_raw = raw[:, 1:4]
        direction = direction_raw / jnp.maximum(
            jnp.linalg.norm(direction_raw, axis=1, keepdims=True), 1e-6
        )
        return HoneyLocalizationPrediction(
            presence_probability=presence,
            direction=np.asarray(jax.device_get(direction)),
        )


def train_mlp(
    model: MLPLocalizationModel,
    batch: LocalizationBatch,
    *,
    steps: int = 1_000,
    batch_size: int = 128,
    learning_rate: float = 1e-3,
    seed: int = 0,
) -> MLPTrainingResult:
    if model.input_width != batch.input_width:
        raise ValueError("model dimensions do not match the localisation batch")
    if steps < 1 or batch_size < 1 or not np.isfinite(learning_rate) or learning_rate <= 0:
        raise ValueError("training settings must be positive")
    mean = batch.features.mean(axis=0, dtype=np.float64).astype(np.float32)
    standard_deviation = batch.features.std(axis=0, dtype=np.float64).astype(np.float32)
    scale = np.where(standard_deviation > 1e-6, standard_deviation, 1.0).astype(np.float32)
    parameters = model.initialize(seed, mean, scale)
    first_moment = jax.tree.map(jnp.zeros_like, parameters)
    second_moment = jax.tree.map(jnp.zeros_like, parameters)
    rng = np.random.default_rng(seed)
    value_and_grad = jax.jit(jax.value_and_grad(_loss))
    losses: list[float] = []
    for step in range(1, steps + 1):
        indices = rng.integers(0, batch.features.shape[0], size=batch_size)
        values = (
            jnp.asarray(batch.features[indices]),
            jnp.asarray(batch.presence[indices]),
            jnp.asarray(batch.direction[indices]),
        )
        loss, gradients = value_and_grad(parameters, values)
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
        first_correction = 1.0 - 0.9**step
        second_correction = 1.0 - 0.999**step
        update = partial(
            _adam_update,
            learning_rate=learning_rate,
            first_correction=first_correction,
            second_correction=second_correction,
        )
        parameters = jax.tree.map(
            update,
            parameters,
            first_moment,
            second_moment,
        )
        losses.append(float(loss))
    return MLPTrainingResult(parameters, tuple(losses))


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


def _apply(parameters: MLPParameters, features: jax.Array) -> jax.Array:
    mean = jax.lax.stop_gradient(parameters.mean)
    scale = jax.lax.stop_gradient(parameters.scale)
    normalized = (features - mean) / scale
    hidden = jax.nn.gelu(normalized @ parameters.input_weights + parameters.input_bias)
    hidden = jax.nn.gelu(hidden @ parameters.hidden_weights + parameters.hidden_bias)
    return hidden @ parameters.output_weights + parameters.output_bias


def _loss(
    parameters: MLPParameters,
    values: tuple[jax.Array, jax.Array, jax.Array],
) -> jax.Array:
    features, presence, target_direction = values
    raw = _apply(parameters, features)
    cross_entropy = jax.nn.softplus(raw[:, 0]) - presence * raw[:, 0]
    positive = presence
    negative = 1.0 - presence
    presence_loss = 0.5 * (
        jnp.sum(cross_entropy * positive) / jnp.maximum(jnp.sum(positive), 1.0)
        + jnp.sum(cross_entropy * negative) / jnp.maximum(jnp.sum(negative), 1.0)
    )
    direction_raw = raw[:, 1:4]
    direction = direction_raw / jnp.maximum(
        jnp.linalg.norm(direction_raw, axis=1, keepdims=True), 1e-6
    )
    direction_loss = jnp.sum(
        (1.0 - jnp.sum(direction * target_direction, axis=1)) * positive
    ) / jnp.maximum(jnp.sum(positive), 1.0)
    return presence_loss + direction_loss
