# pyright: reportUnknownArgumentType=false, reportUnknownLambdaType=false
# pyright: reportUnknownMemberType=false, reportUnknownVariableType=false
"""Localisation through a compiled connectome with a learned readout."""

from dataclasses import dataclass
from functools import partial
from typing import Literal, NamedTuple

import jax
import jax.numpy as jnp
import numpy as np
from numpy.typing import NDArray

from fly_brain.connectome import BrainParameters, CompiledBrain
from fly_brain.contracts._arrays import readonly
from fly_brain.tasks import HoneyLocalizationPrediction

DynamicsActivation = Literal["relu", "tanh"]


@dataclass(frozen=True)
class ConnectomeModelConfig:
    minimum_synapse_count: int = 5
    settling_steps: int = 4
    time_constant: float = 2.0
    dt: float = 1.0
    activation: DynamicsActivation = "relu"
    initial_edge_gain: float = 1.0
    train_neuron_bias: bool = False

    def __post_init__(self) -> None:
        if self.minimum_synapse_count < 1 or self.settling_steps < 1:
            raise ValueError("connectome model counts must be positive")
        if not np.isfinite(self.time_constant) or not 0 < self.dt <= self.time_constant:
            raise ValueError("connectome model requires 0 < dt <= time_constant")
        if self.activation not in {"relu", "tanh"}:
            raise ValueError(f"unsupported connectome activation {self.activation!r}")
        if not np.isfinite(self.initial_edge_gain) or self.initial_edge_gain < 0:
            raise ValueError("connectome initial_edge_gain must be non-negative")


@dataclass(frozen=True)
class ConnectomeBatch:
    port_values: NDArray[np.float32]
    presence: NDArray[np.float32]
    direction: NDArray[np.float32]

    def __post_init__(self) -> None:
        values = np.asarray(self.port_values, dtype=np.float32)
        presence = np.asarray(self.presence, dtype=np.float32)
        direction = np.asarray(self.direction, dtype=np.float32)
        if values.ndim != 2 or values.shape[0] == 0:
            raise ValueError("connectome port values must have non-empty shape (B, P)")
        if presence.shape != (values.shape[0],) or direction.shape != (values.shape[0], 3):
            raise ValueError("connectome targets must match the port-value rows")
        if not all(np.isfinite(item).all() for item in (values, presence, direction)):
            raise ValueError("connectome batches must be finite")
        if np.any((presence < 0) | (presence > 1)):
            raise ValueError("connectome presence targets must lie in [0, 1]")
        object.__setattr__(self, "port_values", readonly(values))
        object.__setattr__(self, "presence", readonly(presence))
        object.__setattr__(self, "direction", readonly(direction))

    @property
    def rows(self) -> int:
        return self.port_values.shape[0]

    @property
    def input_width(self) -> int:
        return self.port_values.shape[1]


@dataclass(frozen=True)
class ConnectomeTrainingConfig:
    steps: int = 1_000
    batch_size: int = 16
    learning_rate: float = 1e-3
    weight_decay: float = 1e-5
    seed: int = 0
    presence_loss_weight: float = 1.0
    direction_loss_weight: float = 1.0
    validation_interval: int = 100
    early_stopping_patience: int = 8
    threshold_search_steps: int = 181
    threshold_search_min: float = 0.05
    threshold_search_max: float = 0.95

    def __post_init__(self) -> None:
        counts = (
            self.steps,
            self.batch_size,
            self.validation_interval,
            self.early_stopping_patience,
            self.threshold_search_steps,
        )
        if any(value < 1 for value in counts):
            raise ValueError("connectome training counts must be positive")
        if not np.isfinite(self.learning_rate) or self.learning_rate <= 0:
            raise ValueError("connectome learning_rate must be positive")
        if not np.isfinite(self.weight_decay) or self.weight_decay < 0:
            raise ValueError("connectome weight_decay must be non-negative")
        weights = (self.presence_loss_weight, self.direction_loss_weight)
        if any(not np.isfinite(value) or value < 0 for value in weights) or sum(weights) == 0:
            raise ValueError("connectome loss weights must be non-negative and not all zero")
        if not 0 <= self.threshold_search_min < self.threshold_search_max <= 1:
            raise ValueError("connectome threshold search bounds must satisfy 0 <= min < max <= 1")


class ConnectomeTrainingResult(NamedTuple):
    parameters: BrainParameters
    losses: tuple[float, ...]
    validation_losses: tuple[tuple[int, float], ...]
    best_step: int


@dataclass(frozen=True)
class ConnectomeLocalizationModel:
    brain: CompiledBrain
    settling_steps: int = 4

    def __post_init__(self) -> None:
        if self.brain.output_width != 4:
            raise ValueError("localisation output head must have width four")
        if self.settling_steps < 1:
            raise ValueError("settling_steps must be positive")

    def initialize(self, seed: int = 0) -> BrainParameters:
        return self.brain.initialize_parameters(seed)

    def predict(
        self, parameters: BrainParameters, batch: ConnectomeBatch, *, batch_size: int = 64
    ) -> HoneyLocalizationPrediction:
        if batch.input_width != self.brain.input_width:
            raise ValueError("connectome batch input width does not match the compiled brain")
        if batch_size < 1:
            raise ValueError("batch_size must be positive")
        apply = jax.jit(partial(_apply, model=self))
        presence_parts: list[NDArray[np.float32]] = []
        direction_parts: list[NDArray[np.float32]] = []
        for start in range(0, batch.rows, batch_size):
            values = jnp.asarray(batch.port_values[start : start + batch_size])
            presence, direction = jax.device_get(apply(parameters, values))
            presence_parts.append(np.asarray(presence, dtype=np.float32))
            direction_parts.append(np.asarray(direction, dtype=np.float32))
        return HoneyLocalizationPrediction(
            np.concatenate(presence_parts),
            np.concatenate(direction_parts),
            coverage=None,
        )


def train_connectome(
    model: ConnectomeLocalizationModel,
    train: ConnectomeBatch,
    validation: ConnectomeBatch,
    config: ConnectomeTrainingConfig,
) -> ConnectomeTrainingResult:
    if (
        train.input_width != model.brain.input_width
        or validation.input_width != model.brain.input_width
    ):
        raise ValueError("connectome batch input width does not match the compiled brain")
    parameters = model.initialize(config.seed)
    first_moment = jax.tree.map(jnp.zeros_like, parameters)
    second_moment = jax.tree.map(jnp.zeros_like, parameters)
    rng = np.random.default_rng(config.seed)
    loss_function = partial(_loss, model=model, config=config)
    value_and_grad = jax.jit(jax.value_and_grad(loss_function))
    losses: list[float] = []
    validation_losses: list[tuple[int, float]] = []
    best_parameters = parameters
    best_loss = np.inf
    best_step = 0
    stale_checks = 0
    for step in range(1, config.steps + 1):
        indexes = rng.integers(0, train.rows, size=config.batch_size)
        loss, gradients = value_and_grad(
            parameters,
            jnp.asarray(train.port_values[indexes]),
            jnp.asarray(train.presence[indexes]),
            jnp.asarray(train.direction[indexes]),
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
        if step % config.validation_interval == 0 or step == config.steps:
            validation_loss = _batched_loss(model, parameters, validation, config)
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
    return ConnectomeTrainingResult(
        best_parameters, tuple(losses), tuple(validation_losses), best_step
    )


def _apply(
    parameters: BrainParameters,
    values: jax.Array,
    *,
    model: ConnectomeLocalizationModel,
) -> tuple[jax.Array, jax.Array]:
    sequence = jnp.broadcast_to(values, (model.settling_steps, *values.shape))
    raw = model.brain.run(
        parameters, model.brain.initialize_state(values.shape[0]), sequence
    ).output[-1]
    presence = jax.nn.sigmoid(raw[:, 0])
    direction_raw = raw[:, 1:]
    norm = jnp.sqrt(jnp.sum(jnp.square(direction_raw), axis=1, keepdims=True) + 1e-12)
    direction = direction_raw / norm
    return presence, direction


def _loss(
    parameters: BrainParameters,
    values: jax.Array,
    target_presence: jax.Array,
    target_direction: jax.Array,
    *,
    model: ConnectomeLocalizationModel,
    config: ConnectomeTrainingConfig,
) -> jax.Array:
    presence, direction = _apply(parameters, values, model=model)
    positive_fraction = jnp.clip(jnp.mean(target_presence), 1e-3, 1.0 - 1e-3)
    weights = jnp.where(
        target_presence > 0.5,
        0.5 / positive_fraction,
        0.5 / (1.0 - positive_fraction),
    )
    binary = -target_presence * jnp.log(jnp.maximum(presence, 1e-7))
    binary -= (1.0 - target_presence) * jnp.log(jnp.maximum(1.0 - presence, 1e-7))
    presence_loss = jnp.mean(weights * binary)
    positive = target_presence > 0.5
    direction_error = 1.0 - jnp.sum(direction * target_direction, axis=1)
    direction_loss = jnp.sum(jnp.where(positive, direction_error, 0.0)) / jnp.maximum(
        jnp.sum(positive), 1
    )
    return (
        config.presence_loss_weight * presence_loss + config.direction_loss_weight * direction_loss
    )


def _batched_loss(
    model: ConnectomeLocalizationModel,
    parameters: BrainParameters,
    batch: ConnectomeBatch,
    config: ConnectomeTrainingConfig,
) -> float:
    function = jax.jit(partial(_loss, model=model, config=config))
    total = 0.0
    for start in range(0, batch.rows, config.batch_size):
        stop = min(start + config.batch_size, batch.rows)
        value = function(
            parameters,
            jnp.asarray(batch.port_values[start:stop]),
            jnp.asarray(batch.presence[start:stop]),
            jnp.asarray(batch.direction[start:stop]),
        )
        total += float(value) * (stop - start)
    return total / batch.rows


def _adamw_update(
    parameter: jax.Array,
    first_moment: jax.Array,
    second_moment: jax.Array,
    *,
    learning_rate: float,
    weight_decay: float,
    first_correction: float,
    second_correction: float,
) -> jax.Array:
    first = first_moment / first_correction
    second = second_moment / second_correction
    update = first / (jnp.sqrt(second) + 1e-8) + weight_decay * parameter
    return parameter - learning_rate * update
