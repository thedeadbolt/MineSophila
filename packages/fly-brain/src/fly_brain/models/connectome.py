# pyright: reportUnknownArgumentType=false, reportUnknownLambdaType=false
# pyright: reportUnknownMemberType=false, reportUnknownVariableType=false
"""Localisation through a compiled connectome with a learned readout."""

from dataclasses import dataclass, field, replace
from functools import partial
from typing import Literal, NamedTuple, Protocol

import jax
import jax.numpy as jnp
import numpy as np
from numpy.typing import NDArray

from fly_brain.connectome import (
    BiologicalStatus,
    BrainParameters,
    BrainState,
    CompiledBrain,
    ReceptorInputMap,
)
from fly_brain.contracts import Eye
from fly_brain.contracts._arrays import readonly
from fly_brain.models.spatial import (
    SpatialBatch,
    SpatialLocalizationModel,
    SpatialParameters,
    SpatialTopology,
)
from fly_brain.tasks import HoneyLocalizationPrediction

DynamicsActivation = Literal["relu", "tanh", "graded"]


@dataclass(frozen=True)
class ConnectomeModelConfig:
    minimum_synapse_count: int = 5
    settling_steps: int = 4
    time_constant: float = 2.0
    dt: float = 1.0
    activation: DynamicsActivation = "relu"
    initial_edge_gain: float = 1.0
    train_neuron_bias: bool = False
    edge_gain_mode: Literal["global", "cell_type_pair", "per_edge"] = "global"
    maximum_edge_gain: float | None = None
    use_neurotransmitter_signs: bool = False
    components: tuple[str, ...] = (
        "photoreceptors",
        "lamina_relays",
        "medulla_relays",
        "visual_projection",
    )
    readout_components: tuple[str, ...] = ("visual_projection",)
    input_transform: Literal["receptor", "per_eye_mean", "regional_mean", "spatial_coverage"] = (
        "receptor"
    )
    regional_azimuth_bins: int = 8
    regional_elevation_bins: int = 4
    readout_normalization: Literal["none", "initial"] = "none"
    self_motion_input: Literal["none", "pen_yaw"] = "none"
    self_motion_scale: float = 0.1
    compass_operating_point: Literal["none", "ring_attractor", "fitted"] = "none"
    compass_tonic_bias: float = 0.02
    compass_gains: dict[str, float] = field(default_factory=dict[str, float])
    compass_type_bias: dict[str, float] = field(default_factory=dict[str, float])
    release_thresholds: dict[str, float] = field(default_factory=dict[str, float])
    release_threshold_sharpness: float = 20.0
    egocentric_input: Literal["none", "pfn_vector"] = "none"
    goal_operating_point: Literal["none", "fitted"] = "none"
    goal_gains: dict[str, float] = field(default_factory=dict[str, float])
    goal_type_bias: dict[str, float] = field(default_factory=dict[str, float])

    def __post_init__(self) -> None:
        self._validate_fitted_goal()
        if self.egocentric_input not in {"none", "pfn_vector"}:
            raise ValueError(f"unsupported egocentric input {self.egocentric_input!r}")
        if self.egocentric_input == "pfn_vector":
            if "vector_inputs" not in self.components:
                raise ValueError("pfn_vector egocentric input needs the vector_inputs component")
            if "visual_projection" not in self.components:
                raise ValueError("pfn_vector egocentric input reads visual_projection")
        if self.compass_operating_point not in {"none", "ring_attractor", "fitted"}:
            raise ValueError(
                f"unsupported compass operating point {self.compass_operating_point!r}"
            )
        self._validate_fitted_compass()
        if self.release_thresholds and self.activation != "graded":
            raise ValueError("release thresholds need graded dynamics")
        if not all(np.isfinite(value) for value in self.release_thresholds.values()):
            raise ValueError("release thresholds must be finite")
        if not np.isfinite(self.release_threshold_sharpness) or (
            self.release_threshold_sharpness <= 0
        ):
            raise ValueError("release_threshold_sharpness must be positive")
        if self.compass_operating_point != "none" and "compass" not in self.components:
            raise ValueError("a compass operating point requires the compass component")
        if not np.isfinite(self.compass_tonic_bias) or self.compass_tonic_bias < 0:
            raise ValueError("compass_tonic_bias must be non-negative")
        if not np.isfinite(self.self_motion_scale) or self.self_motion_scale <= 0:
            raise ValueError("self_motion_scale must be positive")
        if self.self_motion_input not in {"none", "pen_yaw"}:
            raise ValueError(f"unsupported self-motion input {self.self_motion_input!r}")
        if self.self_motion_input == "pen_yaw" and "compass" not in self.components:
            raise ValueError("pen_yaw self-motion input requires the compass component")
        if self.readout_normalization not in {"none", "initial"}:
            raise ValueError(f"unsupported readout normalization {self.readout_normalization!r}")
        if self.minimum_synapse_count < 1 or self.settling_steps < 1:
            raise ValueError("connectome model counts must be positive")
        if not np.isfinite(self.time_constant) or not 0 < self.dt <= self.time_constant:
            raise ValueError("connectome model requires 0 < dt <= time_constant")
        if self.activation not in {"relu", "tanh", "graded"}:
            raise ValueError(f"unsupported connectome activation {self.activation!r}")
        if not np.isfinite(self.initial_edge_gain) or self.initial_edge_gain < 0:
            raise ValueError("connectome initial_edge_gain must be non-negative")
        if self.edge_gain_mode not in {"global", "cell_type_pair", "per_edge"}:
            raise ValueError(f"unsupported edge gain mode {self.edge_gain_mode!r}")
        if self.maximum_edge_gain is not None and (
            not np.isfinite(self.maximum_edge_gain)
            or self.maximum_edge_gain <= 0
            or not 0 < self.initial_edge_gain < self.maximum_edge_gain
        ):
            raise ValueError("bounded edge gains require 0 < initial < maximum")
        components = tuple(self.components)
        required = {"photoreceptors", "visual_projection"}
        if len(components) != len(set(components)) or not required.issubset(components):
            raise ValueError("components must be unique and include input and readout components")
        readout_components = tuple(self.readout_components)
        if (
            not readout_components
            or len(readout_components) != len(set(readout_components))
            or not set(readout_components).issubset(components)
        ):
            raise ValueError("readout components must be unique selected components")
        if self.input_transform not in {
            "receptor",
            "per_eye_mean",
            "regional_mean",
            "spatial_coverage",
        }:
            raise ValueError(f"unsupported input transform {self.input_transform!r}")
        if self.regional_azimuth_bins < 1 or self.regional_elevation_bins < 1:
            raise ValueError("regional pooling bin counts must be positive")
        object.__setattr__(self, "components", components)
        object.__setattr__(self, "readout_components", readout_components)

    def _validate_fitted_goal(self) -> None:
        """A fitted goal memory (ADR 0030) uses effective type-pair gains keyed SOURCE>TARGET."""
        if self.goal_operating_point not in {"none", "fitted"}:
            raise ValueError(f"unsupported goal operating point {self.goal_operating_point!r}")
        if self.goal_operating_point != "fitted":
            if self.goal_gains or self.goal_type_bias:
                raise ValueError("goal_gains and goal_type_bias need a fitted goal")
            return
        if "goal_memory" not in self.components:
            raise ValueError("a fitted goal needs the goal_memory component")
        if not self.goal_gains:
            raise ValueError("a fitted goal needs goal_gains")
        _validate_type_pair_gains(
            self.goal_gains, self.goal_type_bias, self.edge_gain_mode, self.maximum_edge_gain
        )

    def _validate_fitted_compass(self) -> None:
        """Fitted gains are effective cell-type-pair gains keyed ``SOURCE>TARGET``."""
        if self.compass_operating_point != "fitted":
            if self.compass_gains or self.compass_type_bias:
                raise ValueError("compass_gains and compass_type_bias need a fitted compass")
            return
        if not self.compass_gains:
            raise ValueError("a fitted compass needs compass_gains")
        _validate_type_pair_gains(
            self.compass_gains, self.compass_type_bias, self.edge_gain_mode, self.maximum_edge_gain
        )


def _validate_type_pair_gains(
    gains: dict[str, float],
    type_bias: dict[str, float],
    edge_gain_mode: str,
    maximum_edge_gain: float | None,
) -> None:
    if edge_gain_mode != "cell_type_pair" or maximum_edge_gain is None:
        raise ValueError("fitted gains need bounded cell-type-pair gains")
    for key, value in gains.items():
        if key.count(">") != 1 or not all(key.split(">")):
            raise ValueError(f"fitted gain {key!r} must be keyed SOURCE>TARGET")
        if not np.isfinite(value) or not 0 < value < maximum_edge_gain:
            raise ValueError("fitted gains must lie between 0 and the maximum edge gain")
    if not all(np.isfinite(value) for value in type_bias.values()):
        raise ValueError("fitted type biases must be finite")


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


class ConnectomeInputTransform(Protocol):
    @property
    def biological_status(self) -> BiologicalStatus: ...

    def transform(self, batch: SpatialBatch) -> NDArray[np.float32]: ...

    @property
    def parameter_count(self) -> int: ...


@dataclass(frozen=True)
class ReceptorPoolingInputTransform:
    """Pool receptor signals within each eye or angular region, then broadcast."""

    mode: Literal["per_eye_mean", "regional_mean"]
    eyes: tuple[Eye, ...]
    input_map: ReceptorInputMap
    azimuth_bins: int = 8
    elevation_bins: int = 4
    biological_status: BiologicalStatus = BiologicalStatus.SHORTCUT

    def __post_init__(self) -> None:
        if self.mode not in {"per_eye_mean", "regional_mean"}:
            raise ValueError(f"unsupported receptor pooling mode {self.mode!r}")
        if len(self.eyes) != self.input_map.source_site_ids.size:
            raise ValueError("pooling eyes must match the receptor map sites")
        if self.azimuth_bins < 1 or self.elevation_bins < 1:
            raise ValueError("regional pooling bin counts must be positive")

    def transform(self, batch: SpatialBatch) -> NDArray[np.float32]:
        if batch.site_count != len(self.eyes):
            raise ValueError("spatial batch does not match pooling sites")
        eye_values = np.asarray(self.eyes)
        if self.mode == "per_eye_mean":
            groups = eye_values == eye_values[:, None]
        else:
            azimuth = np.arctan2(batch.directions[:, 1], batch.directions[:, 0])
            elevation = np.arcsin(np.clip(batch.directions[:, 2], -1.0, 1.0))
            azimuth_bin = np.minimum(
                ((azimuth + np.pi) / (2 * np.pi) * self.azimuth_bins).astype(np.int32),
                self.azimuth_bins - 1,
            )
            elevation_bin = np.minimum(
                ((elevation + np.pi / 2) / np.pi * self.elevation_bins).astype(np.int32),
                self.elevation_bins - 1,
            )
            groups = (
                (eye_values == eye_values[:, None])
                & (azimuth_bin == azimuth_bin[:, None])
                & (elevation_bin == elevation_bin[:, None])
            )
        groups &= batch.valid[None, :]
        denominator = np.maximum(groups.sum(axis=1), 1)[:, None]
        pooled = np.einsum("ij,bjc->bic", groups, batch.signals) / denominator[None, :, :]
        pooled[:, ~batch.valid, :] = 0.0
        return self.input_map.transform(pooled, valid=batch.valid)

    @property
    def parameter_count(self) -> int:
        return 0


@dataclass(frozen=True)
class SpatialCoverageInputTransform:
    """Use a frozen spatial model as an explicit learned sensory shortcut."""

    model: SpatialLocalizationModel
    parameters: SpatialParameters
    topology: SpatialTopology
    input_map: ReceptorInputMap
    batch_size: int = 64
    biological_status: BiologicalStatus = BiologicalStatus.SHORTCUT

    def transform(self, batch: SpatialBatch) -> NDArray[np.float32]:
        prediction = self.model.predict(
            self.parameters,
            batch,
            self.topology,
            batch_size=self.batch_size,
        )
        if prediction.coverage is None:
            raise ValueError("spatial coverage transform requires coverage predictions")
        channels = len(self.input_map.source_channel_names)
        signals = np.repeat(prediction.coverage[:, :, None], channels, axis=2)
        return self.input_map.transform(signals, valid=batch.valid)

    @property
    def parameter_count(self) -> int:
        return sum(int(value.size) for value in self.parameters)


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
    """Settle a compiled brain on each frame and read localisation from its readout neurons.

    With readout statistics set, the trained readout weights act on z-scored readout activity.
    ``fold_readout_normalization`` converts such parameters to plain ones for the same brain.
    """

    brain: CompiledBrain
    settling_steps: int = 4
    readout_mean: NDArray[np.float32] | None = None
    readout_scale: NDArray[np.float32] | None = None

    def __post_init__(self) -> None:
        if self.brain.output_width != 4:
            raise ValueError("localisation output head must have width four")
        if self.settling_steps < 1:
            raise ValueError("settling_steps must be positive")
        if (self.readout_mean is None) != (self.readout_scale is None):
            raise ValueError("readout mean and scale must be set together")
        if self.readout_mean is not None and self.readout_scale is not None:
            shape = (self.brain.readout_indexes.size,)
            mean = np.asarray(self.readout_mean, dtype=np.float32)
            scale = np.asarray(self.readout_scale, dtype=np.float32)
            if mean.shape != shape or scale.shape != shape:
                raise ValueError("readout statistics must match the readout neurons")
            if not np.all(np.isfinite(mean)) or not np.all(np.isfinite(scale) & (scale > 0)):
                raise ValueError("readout statistics must be finite with positive scale")
            object.__setattr__(self, "readout_mean", readonly(mean))
            object.__setattr__(self, "readout_scale", readonly(scale))

    def initialize(self, seed: int = 0) -> BrainParameters:
        return self.brain.initialize_parameters(seed)

    def with_readout_normalization(
        self,
        parameters: BrainParameters,
        port_values: NDArray[np.float32],
        *,
        batch_size: int = 64,
    ) -> "ConnectomeLocalizationModel":
        """Measure settled readout activity under ``parameters`` and z-score it from now on."""
        values = np.asarray(port_values, dtype=np.float32)
        if values.ndim != 2 or values.shape[0] == 0 or values.shape[1] != self.brain.input_width:
            raise ValueError("readout statistics need port values of shape (N, input_width)")
        settle = jax.jit(partial(_settled_readout, model=self))
        parts = [
            np.asarray(
                jax.device_get(settle(parameters, jnp.asarray(values[start : start + batch_size])))
            )
            for start in range(0, values.shape[0], batch_size)
        ]
        activity = np.concatenate(parts).astype(np.float64)
        return replace(
            self,
            readout_mean=activity.mean(axis=0).astype(np.float32),
            readout_scale=readout_scale(activity.std(axis=0)),
        )

    def fold_readout_normalization(self, parameters: BrainParameters) -> BrainParameters:
        """Return plain parameters that give this model's predictions without statistics."""
        return _effective_parameters(parameters, self)

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


@dataclass(frozen=True)
class ConnectomeActivityEncoder:
    """Expose selected connectome activity over the frames of a clip.

    By default state is retained across frames. With ``reset_each_frame`` every frame settles
    from rest for ``transitions_per_frame`` steps, matching how frame-trained models run.
    """

    brain: CompiledBrain
    neuron_ids: NDArray[np.int64]
    transitions_per_frame: int = 1
    reset_each_frame: bool = False

    def __post_init__(self) -> None:
        neuron_ids = np.unique(np.asarray(self.neuron_ids, dtype=np.int64))
        if neuron_ids.size == 0 or not np.isin(neuron_ids, self.brain.neuron_ids).all():
            raise ValueError("activity encoder neurons must be a non-empty brain subset")
        if self.transitions_per_frame < 1:
            raise ValueError("transitions_per_frame must be positive")
        object.__setattr__(self, "neuron_ids", readonly(neuron_ids))

    @property
    def output_width(self) -> int:
        return self.neuron_ids.size

    def run(
        self,
        parameters: BrainParameters,
        port_values: NDArray[np.float32],
        time_mask: NDArray[np.bool_],
        state: BrainState | None = None,
    ) -> tuple[NDArray[np.float32], BrainState]:
        values = np.asarray(port_values, dtype=np.float32)
        mask = np.asarray(time_mask, dtype=np.bool_)
        if values.ndim != 3 or values.shape[2] != self.brain.input_width:
            raise ValueError("activity inputs must have shape (B, T, brain.input_width)")
        if mask.shape != values.shape[:2]:
            raise ValueError("activity time mask must match (B, T)")
        initial = state or self.brain.initialize_state(values.shape[0])
        expected_state_shape = (values.shape[0], self.brain.neuron_ids.size)
        if initial.activity.shape != expected_state_shape:
            raise ValueError("activity state does not match the input batch")
        indexes = np.searchsorted(self.brain.neuron_ids, self.neuron_ids).astype(np.int32)
        function = jax.jit(partial(_encode_activity, encoder=self, indexes=indexes))
        activity, final_state = jax.device_get(
            function(parameters, jnp.asarray(values), jnp.asarray(mask), initial)
        )
        return np.asarray(activity, dtype=np.float32), final_state


@dataclass(frozen=True)
class ConnectomeClipActivityTransform:
    """Convert one spatial clip into causal activity features for selected neurons."""

    frontend: ConnectomeInputTransform
    encoder: ConnectomeActivityEncoder
    parameters: BrainParameters

    def transform(self, batch: SpatialBatch) -> NDArray[np.float32]:
        port_values = self.frontend.transform(batch)
        mask = np.ones((1, batch.rows), dtype=np.bool_)
        activity, _ = self.encoder.run(
            self.parameters,
            port_values[None, :, :],
            mask,
        )
        return activity[0]


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
        _effective_parameters(parameters, model),
        model.brain.initialize_state(values.shape[0]),
        sequence,
    ).output[-1]
    presence = jax.nn.sigmoid(raw[:, 0])
    direction_raw = raw[:, 1:]
    norm = jnp.sqrt(jnp.sum(jnp.square(direction_raw), axis=1, keepdims=True) + 1e-12)
    direction = direction_raw / norm
    return presence, direction


def readout_scale(deviation: NDArray[np.floating]) -> NDArray[np.float32]:
    """Per-neuron readout scale: the activity spread, floored at the median active spread.

    Flooring keeps nearly silent neurons from being amplified by orders of magnitude, which
    would make the readout explode as soon as training shifts their activity.
    """
    values = np.asarray(deviation, dtype=np.float64)
    active = values[values > 0]
    if active.size == 0:
        return np.ones(values.shape, dtype=np.float32)
    return np.maximum(values, np.median(active)).astype(np.float32)


def _effective_parameters(
    parameters: BrainParameters, model: ConnectomeLocalizationModel
) -> BrainParameters:
    if model.readout_mean is None or model.readout_scale is None:
        return parameters
    scale = jnp.asarray(model.readout_scale)[:, None]
    weights = parameters.readout_weights / scale
    bias = parameters.readout_bias - jnp.asarray(model.readout_mean) @ weights
    return parameters._replace(readout_weights=weights, readout_bias=bias)


def _settled_readout(
    parameters: BrainParameters, values: jax.Array, *, model: ConnectomeLocalizationModel
) -> jax.Array:
    sequence = jnp.broadcast_to(values, (model.settling_steps, *values.shape))
    final = model.brain.run(
        parameters, model.brain.initialize_state(values.shape[0]), sequence
    ).final_state
    return final.activity[:, model.brain.readout_indexes]


def _encode_activity(
    parameters: BrainParameters,
    values: jax.Array,
    time_mask: jax.Array,
    state: BrainState,
    *,
    encoder: ConnectomeActivityEncoder,
    indexes: NDArray[np.int32],
) -> tuple[jax.Array, BrainState]:
    def scan_step(current: BrainState, item: tuple[jax.Array, jax.Array]):
        frame_values, active = item

        def transition(_: int, inner_state: BrainState) -> BrainState:
            return encoder.brain.step(parameters, inner_state, frame_values).state

        start = (
            BrainState(jnp.zeros_like(current.activity)) if encoder.reset_each_frame else current
        )
        updated = jax.lax.fori_loop(0, encoder.transitions_per_frame, transition, start)
        next_state = BrainState(jnp.where(active[:, None], updated.activity, current.activity))
        return next_state, next_state.activity[:, indexes]

    final_state, activity = jax.lax.scan(
        scan_step,
        state,
        (jnp.swapaxes(values, 0, 1), jnp.swapaxes(time_mask, 0, 1)),
    )
    return jnp.swapaxes(activity, 0, 1), final_state


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
