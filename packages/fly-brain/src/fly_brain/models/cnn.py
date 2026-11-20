# pyright: reportUnknownArgumentType=false, reportUnknownLambdaType=false
# pyright: reportUnknownMemberType=false, reportUnknownVariableType=false
"""Small raw-RGB convolutional baseline for fair fly-space comparisons."""

from dataclasses import dataclass
from functools import partial
from typing import NamedTuple

import jax
import jax.numpy as jnp
import numpy as np

from fly_brain.models.linear import LocalizationBatch
from fly_brain.tasks import HoneyLocalizationPrediction


class CNNParameters(NamedTuple):
    mean: jax.Array
    scale: jax.Array
    kernels: tuple[jax.Array, ...]
    biases: tuple[jax.Array, ...]
    output_weights: jax.Array
    output_bias: jax.Array


class CNNTrainingResult(NamedTuple):
    parameters: CNNParameters
    losses: tuple[float, ...]


@dataclass(frozen=True)
class CNNLocalizationModel:
    image_height: int
    image_width: int
    channel_widths: tuple[int, ...] = (8, 16, 32)
    kernel_sizes: tuple[int, ...] = (5, 3, 3)
    strides: tuple[int, ...] = (2, 2, 2)

    def __post_init__(self) -> None:
        counts = {len(self.channel_widths), len(self.kernel_sizes), len(self.strides)}
        if self.image_height < 1 or self.image_width < 1 or counts != {len(self.channel_widths)}:
            raise ValueError("CNN image dimensions and layer settings must align")
        if not self.channel_widths or any(value < 1 for value in self.channel_widths):
            raise ValueError("CNN channel widths must be positive")
        if any(value < 1 for value in self.kernel_sizes + self.strides):
            raise ValueError("CNN kernels and strides must be positive")

    @property
    def input_width(self) -> int:
        return self.image_height * self.image_width * 3

    @property
    def trainable_parameter_count(self) -> int:
        inputs = (3, *self.channel_widths[:-1])
        convolutions = sum(
            kernel * kernel * input_channels * output_channels + output_channels
            for kernel, input_channels, output_channels in zip(
                self.kernel_sizes, inputs, self.channel_widths, strict=True
            )
        )
        return convolutions + 2 * self.channel_widths[-1] * 4 + 4

    def initialize(self, seed: int, mean: np.ndarray, scale: np.ndarray) -> CNNParameters:
        if mean.shape != (3,) or scale.shape != (3,):
            raise ValueError("CNN normalization statistics must have three channels")
        keys = jax.random.split(jax.random.key(seed), len(self.channel_widths) + 1)
        kernels: list[jax.Array] = []
        biases: list[jax.Array] = []
        input_channels = 3
        for key, size, output_channels in zip(
            keys[:-1], self.kernel_sizes, self.channel_widths, strict=True
        ):
            fan_in = size * size * input_channels
            kernels.append(
                jax.random.normal(key, (size, size, input_channels, output_channels))
                * np.sqrt(2.0 / fan_in)
            )
            biases.append(jnp.zeros(output_channels))
            input_channels = output_channels
        output_width = 2 * self.channel_widths[-1]
        output_weights = jax.random.normal(keys[-1], (output_width, 4)) * np.sqrt(
            1.0 / output_width
        )
        return CNNParameters(
            jnp.asarray(mean),
            jnp.asarray(scale),
            tuple(kernels),
            tuple(biases),
            output_weights,
            jnp.zeros(4),
        )

    def predict_features(
        self, parameters: CNNParameters, features: np.ndarray
    ) -> HoneyLocalizationPrediction:
        if features.ndim != 2 or features.shape[1] != self.input_width:
            raise ValueError("feature width does not match the CNN image dimensions")
        raw = _apply(parameters, jnp.asarray(features), model=self)
        presence = np.asarray(jax.device_get(jax.nn.sigmoid(raw[:, 0])), dtype=np.float32)
        direction_raw = raw[:, 1:4]
        direction = direction_raw / jnp.maximum(
            jnp.linalg.norm(direction_raw, axis=1, keepdims=True), 1e-6
        )
        return HoneyLocalizationPrediction(
            presence_probability=presence,
            direction=np.asarray(jax.device_get(direction), dtype=np.float32),
        )


def train_cnn(
    model: CNNLocalizationModel,
    batch: LocalizationBatch,
    *,
    steps: int,
    batch_size: int,
    learning_rate: float,
    seed: int,
) -> CNNTrainingResult:
    if batch.input_width != model.input_width:
        raise ValueError("CNN image dimensions do not match the feature width")
    if steps < 1 or batch_size < 1 or not np.isfinite(learning_rate) or learning_rate <= 0:
        raise ValueError("CNN training settings must be positive")
    images = batch.features.reshape(-1, model.image_height, model.image_width, 3)
    mean = images.mean(axis=(0, 1, 2), dtype=np.float64).astype(np.float32)
    deviation = images.std(axis=(0, 1, 2), dtype=np.float64).astype(np.float32)
    scale = np.where(deviation > 1e-6, deviation, 1.0).astype(np.float32)
    parameters = model.initialize(seed, mean, scale)
    first_moment = jax.tree.map(jnp.zeros_like, parameters)
    second_moment = jax.tree.map(jnp.zeros_like, parameters)
    rng = np.random.default_rng(seed)
    loss_and_grad = jax.jit(jax.value_and_grad(partial(_loss, model=model)))
    losses: list[float] = []
    for step in range(1, steps + 1):
        indices = rng.integers(0, batch.features.shape[0], size=batch_size)
        values = (
            jnp.asarray(batch.features[indices]),
            jnp.asarray(batch.presence[indices]),
            jnp.asarray(batch.direction[indices]),
        )
        loss, gradients = loss_and_grad(parameters, values)
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
    return CNNTrainingResult(parameters, tuple(losses))


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


def _apply(
    parameters: CNNParameters, features: jax.Array, *, model: CNNLocalizationModel
) -> jax.Array:
    images = features.reshape(-1, model.image_height, model.image_width, 3)
    hidden = (images - parameters.mean) / parameters.scale
    for kernel, bias, stride in zip(
        parameters.kernels, parameters.biases, model.strides, strict=True
    ):
        hidden = jax.lax.conv_general_dilated(
            hidden,
            kernel,
            window_strides=(stride, stride),
            padding="SAME",
            dimension_numbers=("NHWC", "HWIO", "NHWC"),
        )
        hidden = jax.nn.gelu(hidden + bias)
    pooled = jnp.concatenate((jnp.mean(hidden, axis=(1, 2)), jnp.max(hidden, axis=(1, 2))), axis=1)
    return pooled @ parameters.output_weights + parameters.output_bias


def _loss(
    parameters: CNNParameters,
    values: tuple[jax.Array, jax.Array, jax.Array],
    *,
    model: CNNLocalizationModel,
) -> jax.Array:
    features, presence, target_direction = values
    raw = _apply(parameters, features, model=model)
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
