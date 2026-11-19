# pyright: reportUnknownArgumentType=false, reportUnknownLambdaType=false
# pyright: reportUnknownMemberType=false, reportUnknownVariableType=false
"""Topology-aware per-ommatidium honey localisation model."""

from dataclasses import dataclass
from functools import partial
from typing import Literal, NamedTuple

import jax
import jax.numpy as jnp
import numpy as np
from numpy.typing import NDArray

from fly_brain.contracts._arrays import readonly
from fly_brain.tasks import HoneyLocalizationPrediction

ActivationName = Literal["gelu", "relu", "silu"]


@dataclass(frozen=True)
class SpatialModelConfig:
    hidden_width: int = 32
    message_passing_layers: int = 3
    neighbor_count: int = 6
    neighbor_radius_deg: float = 7.5
    include_directions: bool = True
    activation: ActivationName = "gelu"
    residual: bool = True
    pooling_temperature: float = 0.25
    coverage_power: float = 2.0

    def __post_init__(self) -> None:
        if self.hidden_width < 1 or self.message_passing_layers < 0:
            raise ValueError("spatial model widths and layer counts must be non-negative")
        if self.neighbor_count < 1 or not 0 < self.neighbor_radius_deg <= 180:
            raise ValueError("spatial neighbor settings must be positive")
        if self.activation not in {"gelu", "relu", "silu"}:
            raise ValueError(f"unsupported activation {self.activation!r}")
        if self.pooling_temperature <= 0 or self.coverage_power <= 0:
            raise ValueError("spatial pooling settings must be positive")


@dataclass(frozen=True)
class SpatialTrainingConfig:
    steps: int = 2_000
    batch_size: int = 64
    learning_rate: float = 1e-3
    weight_decay: float = 1e-5
    seed: int = 0
    presence_loss_weight: float = 1.0
    coverage_loss_weight: float = 2.0
    direction_loss_weight: float = 1.0
    validation_interval: int = 100
    early_stopping_patience: int = 8
    threshold_search_steps: int = 181
    threshold_search_min: float = 0.05
    threshold_search_max: float = 0.95

    def __post_init__(self) -> None:
        positive_ints = (
            self.steps,
            self.batch_size,
            self.validation_interval,
            self.early_stopping_patience,
            self.threshold_search_steps,
        )
        if any(value < 1 for value in positive_ints):
            raise ValueError("spatial training counts must be positive")
        if not np.isfinite(self.learning_rate) or self.learning_rate <= 0:
            raise ValueError("spatial learning_rate must be positive")
        if not np.isfinite(self.weight_decay) or self.weight_decay < 0:
            raise ValueError("spatial weight_decay must be non-negative")
        weights = (
            self.presence_loss_weight,
            self.coverage_loss_weight,
            self.direction_loss_weight,
        )
        if any(not np.isfinite(value) or value < 0 for value in weights) or sum(weights) == 0:
            raise ValueError("spatial loss weights must be non-negative and not all zero")
        if not 0 <= self.threshold_search_min < self.threshold_search_max <= 1:
            raise ValueError("spatial threshold search bounds must satisfy 0 <= min < max <= 1")


@dataclass(frozen=True)
class SpatialBatch:
    signals: NDArray[np.float32]
    valid: NDArray[np.bool_]
    coverage: NDArray[np.float32]
    directions: NDArray[np.float32]

    def __post_init__(self) -> None:
        signals = np.asarray(self.signals, dtype=np.float32)
        valid = np.asarray(self.valid, dtype=np.bool_)
        coverage = np.asarray(self.coverage, dtype=np.float32)
        directions = np.asarray(self.directions, dtype=np.float32)
        if signals.ndim != 3 or signals.shape[0] == 0:
            raise ValueError("spatial signals must have non-empty shape (B, N, C)")
        rows, sites, _ = signals.shape
        if valid.shape != (sites,) or coverage.shape != (rows, sites):
            raise ValueError("spatial validity and coverage must match the signal sites")
        if directions.shape != (sites, 3):
            raise ValueError("spatial directions must have shape (N, 3)")
        if not np.isfinite(signals).all() or not np.isfinite(coverage).all():
            raise ValueError("spatial batches must be finite")
        if np.any((coverage < 0) | (coverage > 1)):
            raise ValueError("spatial coverage must lie in [0, 1]")
        object.__setattr__(self, "signals", readonly(signals))
        object.__setattr__(self, "valid", readonly(valid))
        object.__setattr__(self, "coverage", readonly(coverage))
        object.__setattr__(self, "directions", readonly(directions))

    @property
    def rows(self) -> int:
        return self.signals.shape[0]

    @property
    def site_count(self) -> int:
        return self.signals.shape[1]

    @property
    def channel_count(self) -> int:
        return self.signals.shape[2]

    @property
    def presence(self) -> NDArray[np.float32]:
        return np.any(self.coverage > 0, axis=1).astype(np.float32)


@dataclass(frozen=True)
class SpatialTopology:
    neighbors: NDArray[np.int32]
    neighbor_valid: NDArray[np.bool_]

    def __post_init__(self) -> None:
        neighbors = np.asarray(self.neighbors, dtype=np.int32)
        neighbor_valid = np.asarray(self.neighbor_valid, dtype=np.bool_)
        if neighbors.ndim != 2 or neighbors.shape != neighbor_valid.shape:
            raise ValueError("spatial topology arrays must have equal shape (N, K)")
        if np.any(neighbors < 0) or np.any(neighbors >= neighbors.shape[0]):
            raise ValueError("spatial topology contains an invalid site index")
        object.__setattr__(self, "neighbors", readonly(neighbors))
        object.__setattr__(self, "neighbor_valid", readonly(neighbor_valid))


class SpatialParameters(NamedTuple):
    input_weights: jax.Array
    input_bias: jax.Array
    self_weights: jax.Array
    neighbor_weights: jax.Array
    layer_bias: jax.Array
    output_weights: jax.Array
    output_bias: jax.Array


@dataclass(frozen=True)
class SpatialTrainingResult:
    parameters: SpatialParameters
    losses: tuple[float, ...]
    validation_losses: tuple[tuple[int, float], ...]
    best_step: int


@dataclass(frozen=True)
class SpatialLocalizationModel:
    channel_count: int
    site_count: int
    config: SpatialModelConfig

    def __post_init__(self) -> None:
        if self.channel_count < 1 or self.site_count < 1:
            raise ValueError("spatial model dimensions must be positive")

    @property
    def input_width(self) -> int:
        return self.channel_count + (3 if self.config.include_directions else 0)

    def initialize(self, seed: int) -> SpatialParameters:
        keys = jax.random.split(jax.random.key(seed), 4)
        width = self.config.hidden_width
        layers = self.config.message_passing_layers
        return SpatialParameters(
            jax.random.normal(keys[0], (self.input_width, width)) * np.sqrt(2.0 / self.input_width),
            jnp.zeros(width),
            jax.random.normal(keys[1], (layers, width, width)) * np.sqrt(1.0 / width),
            jax.random.normal(keys[2], (layers, width, width)) * np.sqrt(1.0 / width),
            jnp.zeros((layers, width)),
            jax.random.normal(keys[3], (width,)) * np.sqrt(1.0 / width),
            jnp.asarray(0.0),
        )

    def predict(
        self,
        parameters: SpatialParameters,
        batch: SpatialBatch,
        topology: SpatialTopology,
        *,
        batch_size: int = 256,
    ) -> HoneyLocalizationPrediction:
        _validate_model_inputs(self, batch, topology)
        if batch_size < 1:
            raise ValueError("prediction batch_size must be positive")
        apply = jax.jit(partial(_apply, config=self.config))
        presence_parts: list[NDArray[np.float32]] = []
        direction_parts: list[NDArray[np.float32]] = []
        coverage_parts: list[NDArray[np.float32]] = []
        for start in range(0, batch.rows, batch_size):
            stop = min(start + batch_size, batch.rows)
            output = apply(
                parameters,
                jnp.asarray(batch.signals[start:stop]),
                jnp.asarray(batch.valid),
                jnp.asarray(batch.directions),
                jnp.asarray(topology.neighbors),
                jnp.asarray(topology.neighbor_valid),
            )
            presence, direction, coverage = jax.device_get(output)
            presence_parts.append(np.asarray(presence, dtype=np.float32))
            direction_parts.append(np.asarray(direction, dtype=np.float32))
            coverage_parts.append(np.asarray(coverage, dtype=np.float32))
        return HoneyLocalizationPrediction(
            presence_probability=np.concatenate(presence_parts),
            direction=np.concatenate(direction_parts),
            coverage=np.concatenate(coverage_parts),
        )


def build_angular_topology(
    directions: NDArray[np.float32],
    eyes: NDArray[np.str_],
    *,
    neighbor_count: int,
    radius_deg: float,
) -> SpatialTopology:
    """Build a fixed nearest-neighbour table without joining the two compound eyes."""
    directions64 = np.asarray(directions, dtype=np.float64)
    eyes = np.asarray(eyes)
    if (
        directions64.ndim != 2
        or directions64.shape[1] != 3
        or eyes.shape != (directions64.shape[0],)
    ):
        raise ValueError("topology directions and eyes must describe the same sites")
    if neighbor_count < 1 or neighbor_count >= directions64.shape[0]:
        raise ValueError("neighbor_count must be smaller than the site count")
    normalized = directions64 / np.maximum(
        np.linalg.norm(directions64, axis=1, keepdims=True), 1e-12
    )
    cosine = np.clip(normalized @ normalized.T, -1.0, 1.0)
    angular = np.degrees(np.arccos(cosine))
    angular[eyes[:, None] != eyes[None, :]] = np.inf
    np.fill_diagonal(angular, np.inf)
    neighbors = np.argpartition(angular, neighbor_count - 1, axis=1)[:, :neighbor_count]
    distances = np.take_along_axis(angular, neighbors, axis=1)
    order = np.argsort(distances, axis=1)
    neighbors = np.take_along_axis(neighbors, order, axis=1).astype(np.int32)
    distances = np.take_along_axis(distances, order, axis=1)
    valid = distances <= radius_deg
    site_ids = np.arange(directions64.shape[0], dtype=np.int32)[:, None]
    neighbors = np.where(valid, neighbors, site_ids)
    return SpatialTopology(neighbors, valid)


def train_spatial(
    model: SpatialLocalizationModel,
    train: SpatialBatch,
    validation: SpatialBatch,
    topology: SpatialTopology,
    config: SpatialTrainingConfig,
) -> SpatialTrainingResult:
    _validate_model_inputs(model, train, topology)
    _validate_model_inputs(model, validation, topology)
    parameters = model.initialize(config.seed)
    first_moment = jax.tree.map(jnp.zeros_like, parameters)
    second_moment = jax.tree.map(jnp.zeros_like, parameters)
    rng = np.random.default_rng(config.seed)
    loss_function = partial(
        _loss,
        valid=jnp.asarray(train.valid),
        directions=jnp.asarray(train.directions),
        neighbors=jnp.asarray(topology.neighbors),
        neighbor_valid=jnp.asarray(topology.neighbor_valid),
        model_config=model.config,
        training_config=config,
    )
    value_and_grad = jax.jit(jax.value_and_grad(loss_function))
    losses: list[float] = []
    validation_losses: list[tuple[int, float]] = []
    best_parameters = parameters
    best_loss = np.inf
    best_step = 0
    stale_checks = 0
    for step in range(1, config.steps + 1):
        indices = rng.integers(0, train.rows, size=config.batch_size)
        loss, gradients = value_and_grad(
            parameters,
            jnp.asarray(train.signals[indices]),
            jnp.asarray(train.coverage[indices]),
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
        update = partial(
            _adamw_update,
            learning_rate=config.learning_rate,
            weight_decay=config.weight_decay,
            first_correction=1.0 - 0.9**step,
            second_correction=1.0 - 0.999**step,
        )
        parameters = jax.tree.map(update, parameters, first_moment, second_moment)
        losses.append(float(loss))
        should_validate = step % config.validation_interval == 0 or step == config.steps
        if should_validate:
            validation_loss = _batched_loss(parameters, validation, topology, model.config, config)
            validation_losses.append((step, validation_loss))
            if validation_loss < best_loss:
                best_loss = validation_loss
                best_parameters = parameters
                best_step = step
                stale_checks = 0
            else:
                stale_checks += 1
                if stale_checks >= config.early_stopping_patience:
                    break
    return SpatialTrainingResult(
        best_parameters,
        tuple(losses),
        tuple(validation_losses),
        best_step,
    )


def _validate_model_inputs(
    model: SpatialLocalizationModel, batch: SpatialBatch, topology: SpatialTopology
) -> None:
    if batch.channel_count != model.channel_count or batch.site_count != model.site_count:
        raise ValueError("spatial model dimensions do not match the batch")
    if topology.neighbors.shape[0] != model.site_count:
        raise ValueError("spatial topology does not match the model site count")


def _batched_loss(
    parameters: SpatialParameters,
    batch: SpatialBatch,
    topology: SpatialTopology,
    model_config: SpatialModelConfig,
    training_config: SpatialTrainingConfig,
) -> float:
    loss_function = jax.jit(
        partial(
            _loss,
            valid=jnp.asarray(batch.valid),
            directions=jnp.asarray(batch.directions),
            neighbors=jnp.asarray(topology.neighbors),
            neighbor_valid=jnp.asarray(topology.neighbor_valid),
            model_config=model_config,
            training_config=training_config,
        )
    )
    total = 0.0
    for start in range(0, batch.rows, training_config.batch_size):
        stop = min(start + training_config.batch_size, batch.rows)
        value = loss_function(
            parameters,
            jnp.asarray(batch.signals[start:stop]),
            jnp.asarray(batch.coverage[start:stop]),
        )
        total += float(value) * (stop - start)
    return total / batch.rows


def _apply(
    parameters: SpatialParameters,
    signals: jax.Array,
    valid: jax.Array,
    directions: jax.Array,
    neighbors: jax.Array,
    neighbor_valid: jax.Array,
    *,
    config: SpatialModelConfig,
) -> tuple[jax.Array, jax.Array, jax.Array]:
    inputs = signals
    if config.include_directions:
        positions = jnp.broadcast_to(directions, (*signals.shape[:2], 3))
        inputs = jnp.concatenate((signals, positions), axis=2)
    hidden = _activation(inputs @ parameters.input_weights + parameters.input_bias, config)
    mask = valid[None, :, None].astype(hidden.dtype)
    hidden *= mask
    neighbor_site_valid = valid[neighbors]
    neighbor_mask = (neighbor_valid & neighbor_site_valid)[None, :, :, None].astype(hidden.dtype)
    neighbor_denominator = jnp.maximum(jnp.sum(neighbor_mask, axis=2), 1.0)
    for layer in range(parameters.self_weights.shape[0]):
        gathered = hidden[:, neighbors, :]
        neighbor_mean = jnp.sum(gathered * neighbor_mask, axis=2) / neighbor_denominator
        updated = (
            hidden @ parameters.self_weights[layer]
            + neighbor_mean @ parameters.neighbor_weights[layer]
            + parameters.layer_bias[layer]
        )
        updated = _activation(updated, config) * mask
        hidden = hidden + updated if config.residual else updated
    logits = hidden @ parameters.output_weights + parameters.output_bias
    logits = jnp.where(valid[None, :], logits, -30.0)
    coverage = jax.nn.sigmoid(logits)
    pooling_weights = jax.nn.softmax(logits / config.pooling_temperature, axis=1)
    presence_logit = jnp.sum(pooling_weights * logits, axis=1)
    presence = jax.nn.sigmoid(presence_logit)
    direction_weights = jnp.power(coverage, config.coverage_power) * valid[None, :]
    direction_raw = direction_weights @ directions
    direction_norm = jnp.linalg.norm(direction_raw, axis=1, keepdims=True)
    direction = jnp.where(
        direction_norm > 1e-6,
        direction_raw / jnp.maximum(direction_norm, 1e-6),
        0.0,
    )
    return presence, direction, coverage


def _loss(
    parameters: SpatialParameters,
    signals: jax.Array,
    target_coverage: jax.Array,
    *,
    valid: jax.Array,
    directions: jax.Array,
    neighbors: jax.Array,
    neighbor_valid: jax.Array,
    model_config: SpatialModelConfig,
    training_config: SpatialTrainingConfig,
) -> jax.Array:
    presence, direction, coverage = _apply(
        parameters,
        signals,
        valid,
        directions,
        neighbors,
        neighbor_valid,
        config=model_config,
    )
    target_presence = jnp.any(target_coverage > 0, axis=1).astype(jnp.float32)
    presence_bce = -target_presence * jnp.log(jnp.maximum(presence, 1e-7)) - (
        1.0 - target_presence
    ) * jnp.log(jnp.maximum(1.0 - presence, 1e-7))
    positive = target_presence
    negative = 1.0 - target_presence
    presence_loss = 0.5 * (
        jnp.sum(presence_bce * positive) / jnp.maximum(jnp.sum(positive), 1.0)
        + jnp.sum(presence_bce * negative) / jnp.maximum(jnp.sum(negative), 1.0)
    )
    coverage_bce = -target_coverage * jnp.log(jnp.maximum(coverage, 1e-7)) - (
        1.0 - target_coverage
    ) * jnp.log(jnp.maximum(1.0 - coverage, 1e-7))
    site_valid = valid[None, :].astype(jnp.float32)
    target_positive = target_coverage * site_valid
    target_negative = (1.0 - target_coverage) * site_valid
    coverage_loss = 0.5 * jnp.mean(
        jnp.sum(coverage_bce * target_positive, axis=1)
        / jnp.maximum(jnp.sum(target_positive, axis=1), 1.0)
        + jnp.sum(coverage_bce * target_negative, axis=1)
        / jnp.maximum(jnp.sum(target_negative, axis=1), 1.0)
    )
    target_direction_raw = target_coverage @ directions
    target_norm = jnp.linalg.norm(target_direction_raw, axis=1, keepdims=True)
    target_direction = target_direction_raw / jnp.maximum(target_norm, 1e-6)
    direction_loss = jnp.sum(
        (1.0 - jnp.sum(direction * target_direction, axis=1)) * positive
    ) / jnp.maximum(jnp.sum(positive), 1.0)
    return (
        training_config.presence_loss_weight * presence_loss
        + training_config.coverage_loss_weight * coverage_loss
        + training_config.direction_loss_weight * direction_loss
    )


def _activation(values: jax.Array, config: SpatialModelConfig) -> jax.Array:
    if config.activation == "relu":
        return jax.nn.relu(values)
    if config.activation == "silu":
        return jax.nn.silu(values)
    return jax.nn.gelu(values)


def _adamw_update(
    value: jax.Array,
    first: jax.Array,
    second: jax.Array,
    *,
    learning_rate: float,
    weight_decay: float,
    first_correction: float,
    second_correction: float,
) -> jax.Array:
    gradient_update = (first / first_correction) / (jnp.sqrt(second / second_correction) + 1e-8)
    return value - learning_rate * (gradient_update + weight_decay * value)
