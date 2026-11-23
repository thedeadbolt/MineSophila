# pyright: reportUnknownMemberType=false
"""Compile an immutable structural network into sparse JAX execution arrays."""

from dataclasses import dataclass, field
from typing import Literal, NamedTuple

import jax
import jax.numpy as jnp
import numpy as np
from numpy.typing import NDArray

from fly_brain.connectome.dynamics import LeakyRateDynamics
from fly_brain.connectome.network import BrainNetwork
from fly_brain.connectome.ports import EgocentricProjection, InjectionRule, InputPort, OutputHead
from fly_brain.contracts._arrays import readonly

EdgeGainMode = Literal["global", "cell_type_pair", "per_edge"]


@dataclass(frozen=True)
class SynapseCountPolicy:
    """Configure trainable gains over normalized anatomical synapse counts."""

    initial_edge_gain: float = 1.0
    train_neuron_bias: bool = True
    edge_gain_mode: EdgeGainMode = "global"
    maximum_edge_gain: float | None = None
    use_neurotransmitter_signs: bool = False

    def __post_init__(self) -> None:
        if not np.isfinite(self.initial_edge_gain) or self.initial_edge_gain < 0:
            raise ValueError("initial_edge_gain must be finite and non-negative")
        if self.edge_gain_mode not in {"global", "cell_type_pair", "per_edge"}:
            raise ValueError(f"unsupported edge gain mode {self.edge_gain_mode!r}")
        if self.maximum_edge_gain is not None and (
            not np.isfinite(self.maximum_edge_gain)
            or self.maximum_edge_gain <= 0
            or not 0 < self.initial_edge_gain < self.maximum_edge_gain
        ):
            raise ValueError("bounded edge gains require 0 < initial < maximum")


@dataclass(frozen=True)
class BrainModelSpec:
    network: BrainNetwork
    input_ports: tuple[InputPort, ...]
    output_head: OutputHead
    dynamics: LeakyRateDynamics = field(default_factory=LeakyRateDynamics)
    parameter_policy: SynapseCountPolicy = field(default_factory=SynapseCountPolicy)
    dt: float = 1.0
    egocentric: EgocentricProjection | None = None

    def __post_init__(self) -> None:
        if not self.input_ports:
            raise ValueError("brain model must have at least one input port")
        names = [port.name for port in self.input_ports]
        if len(names) != len(set(names)):
            raise ValueError("input port names must be unique")
        all_targets = np.concatenate([port.neuron_ids for port in self.input_ports])
        if np.unique(all_targets).size != all_targets.size:
            raise ValueError("input ports must not target the same neuron more than once")
        if not 0 < self.dt <= self.dynamics.time_constant:
            raise ValueError("dt must satisfy 0 < dt <= dynamics time_constant")


@dataclass(frozen=True)
class CompilationPlan:
    neuron_count: int
    edge_count: int
    input_width: int
    readout_neuron_count: int
    output_width: int
    trainable_parameter_count: int
    topology_bytes: int
    disconnected_ports: tuple[str, ...]


class BrainParameters(NamedTuple):
    edge_gain: jax.Array
    neuron_bias: jax.Array
    readout_weights: jax.Array
    readout_bias: jax.Array
    # (egocentric sources, 2); empty for brains without an egocentric projection and for
    # checkpoints written before it existed.
    egocentric_weights: jax.Array = jnp.zeros((0, 2), dtype=jnp.float32)


class BrainState(NamedTuple):
    activity: jax.Array


class BrainStep(NamedTuple):
    state: BrainState
    activity: jax.Array
    output: jax.Array


class BrainRun(NamedTuple):
    final_state: BrainState
    activity: jax.Array
    output: jax.Array


@dataclass(frozen=True)
class CompiledBrain:
    neuron_ids: NDArray[np.int64]
    source_indexes: NDArray[np.int32]
    target_indexes: NDArray[np.int32]
    anatomical_weights: NDArray[np.float32]
    edge_group_indexes: NDArray[np.int32]
    edge_gain_count: int
    port_target_indexes: tuple[NDArray[np.int32], ...]
    port_offsets: tuple[int, ...]
    port_rules: tuple[InjectionRule, ...]
    readout_indexes: NDArray[np.int32]
    dynamics: LeakyRateDynamics
    parameter_policy: SynapseCountPolicy
    dt: float
    output_width: int
    release_gate: NDArray[np.bool_] | None = None
    release_threshold: NDArray[np.float32] | None = None
    egocentric_source_indexes: NDArray[np.int32] | None = None
    egocentric_target_indexes: NDArray[np.int32] | None = None
    egocentric_target_offsets: NDArray[np.float32] | None = None

    def release(self, activity: jax.Array) -> jax.Array:
        """Presynaptic release per neuron: graded, or thresholded for gated cell types."""
        graded = self.dynamics.release(activity)
        if self.release_gate is None or self.release_threshold is None:
            return graded
        gated = self.dynamics.thresholded_release(
            activity, jnp.asarray(self.release_threshold)[None, :]
        )
        return jnp.where(jnp.asarray(self.release_gate)[None, :], gated, graded)

    def initialize_parameters(self, seed: int = 0) -> BrainParameters:
        key = jax.random.key(seed)
        scale = np.sqrt(1.0 / self.readout_indexes.size)
        neuron_bias = jnp.zeros(self.neuron_ids.size)
        if not self.parameter_policy.train_neuron_bias:
            neuron_bias = jax.lax.stop_gradient(neuron_bias)
        initial_gain = self.parameter_policy.initial_edge_gain
        if self.parameter_policy.maximum_edge_gain is not None:
            ratio = initial_gain / self.parameter_policy.maximum_edge_gain
            initial_gain = float(np.log(ratio / (1.0 - ratio)))
        egocentric = jnp.zeros((0, 2), dtype=jnp.float32)
        if self.egocentric_source_indexes is not None:
            egocentric = 1e-3 * jax.random.normal(
                jax.random.fold_in(key, 1), (self.egocentric_source_indexes.size, 2)
            )
        return BrainParameters(
            edge_gain=jnp.full(self.edge_gain_count, initial_gain, dtype=jnp.float32),
            neuron_bias=neuron_bias,
            readout_weights=jax.random.normal(key, (self.readout_indexes.size, self.output_width))
            * scale,
            readout_bias=jnp.zeros(self.output_width),
            egocentric_weights=egocentric,
        )

    def initialize_state(self, batch_size: int) -> BrainState:
        if batch_size < 1:
            raise ValueError("batch_size must be positive")
        return BrainState(jnp.zeros((batch_size, self.neuron_ids.size), dtype=jnp.float32))

    @property
    def input_width(self) -> int:
        return self.port_offsets[-1]

    def step(
        self, parameters: BrainParameters, state: BrainState, external_input: jax.Array
    ) -> BrainStep:
        values = jnp.asarray(external_input, dtype=jnp.float32)
        if values.ndim != 2 or values.shape != (state.activity.shape[0], self.input_width):
            raise ValueError(
                f"external_input must have shape {(state.activity.shape[0], self.input_width)}"
            )
        edge_gain = parameters.edge_gain[self.edge_group_indexes]
        if self.parameter_policy.maximum_edge_gain is not None:
            edge_gain = self.parameter_policy.maximum_edge_gain * jax.nn.sigmoid(edge_gain)
        release = self.release(state.activity)
        recurrent = (
            release[:, self.source_indexes]
            * jnp.asarray(self.anatomical_weights)[None, :]
            * edge_gain[None, :]
        )
        drive = jnp.zeros_like(state.activity)
        drive = drive.at[:, self.target_indexes].add(recurrent)
        if (
            self.egocentric_source_indexes is not None
            and self.egocentric_target_indexes is not None
            and self.egocentric_target_offsets is not None
        ):
            vector = release[:, self.egocentric_source_indexes] @ parameters.egocentric_weights
            offsets = jnp.asarray(self.egocentric_target_offsets)[None, :]
            drive = drive.at[:, self.egocentric_target_indexes].add(
                vector[:, :1] * jnp.cos(offsets) + vector[:, 1:] * jnp.sin(offsets)
            )
        clamped: list[tuple[jax.Array, jax.Array]] = []
        for index, targets in enumerate(self.port_target_indexes):
            start, stop = self.port_offsets[index], self.port_offsets[index + 1]
            port_values = values[:, start:stop]
            if self.port_rules[index] == InjectionRule.ADDITIVE:
                drive = drive.at[:, targets].add(port_values)
            else:
                clamped.append((jnp.asarray(targets), port_values))
        bias = parameters.neuron_bias
        if not self.parameter_policy.train_neuron_bias:
            bias = jax.lax.stop_gradient(bias)
        activity = self.dynamics.transition(state.activity, drive + bias, self.dt)
        for targets, port_values in clamped:
            activity = activity.at[:, targets].set(port_values)
        output = activity[:, self.readout_indexes] @ parameters.readout_weights
        output = output + parameters.readout_bias
        next_state = BrainState(activity)
        return BrainStep(next_state, activity, output)

    def run(
        self, parameters: BrainParameters, state: BrainState, external_input: jax.Array
    ) -> BrainRun:
        values = jnp.asarray(external_input, dtype=jnp.float32)
        if values.ndim != 3 or values.shape[1:] != (state.activity.shape[0], self.input_width):
            raise ValueError(
                "external_input must have time-major shape "
                f"(T, {state.activity.shape[0]}, {self.input_width})"
            )

        def scan_step(current: BrainState, item: jax.Array):
            result = self.step(parameters, current, item)
            return result.state, (result.activity, result.output)

        final_state, (activity, output) = jax.lax.scan(scan_step, state, values)
        return BrainRun(final_state, activity, output)


@dataclass(frozen=True)
class BrainCompiler:
    spec: BrainModelSpec

    def plan(self) -> CompilationPlan:
        graph = self.spec.network.subgraph()
        self._validate_ids(graph.neurons.neuron_ids)
        connected = set(graph.synapses.source_ids.tolist()) | set(
            graph.synapses.target_ids.tolist()
        )
        disconnected = tuple(
            port.name
            for port in self.spec.input_ports
            if not connected.intersection(port.neuron_ids)
        )
        input_width = sum(port.neuron_ids.size for port in self.spec.input_ports)
        readout_count = self.spec.output_head.neuron_ids.size
        edge_gain_count = int(self._edge_groups(graph.neurons.cell_types).max(initial=-1) + 1)
        parameter_count = (
            edge_gain_count
            + (graph.neurons.rows if self.spec.parameter_policy.train_neuron_bias else 0)
            + (readout_count + 1) * self.spec.output_head.output_width
        )
        topology_bytes = graph.neurons.neuron_ids.nbytes + graph.synapses.memory_bytes
        return CompilationPlan(
            graph.neurons.rows,
            graph.synapses.rows,
            input_width,
            readout_count,
            self.spec.output_head.output_width,
            parameter_count,
            topology_bytes,
            disconnected,
        )

    def compile(self) -> CompiledBrain:
        graph = self.spec.network.subgraph()
        self._validate_ids(graph.neurons.neuron_ids)
        neuron_ids = graph.neurons.neuron_ids
        sources = np.searchsorted(neuron_ids, graph.synapses.source_ids).astype(np.int32)
        targets = np.searchsorted(neuron_ids, graph.synapses.target_ids).astype(np.int32)
        counts = graph.synapses.counts.astype(np.float32)
        incoming = np.zeros(neuron_ids.size, dtype=np.float32)
        np.add.at(incoming, targets, counts)
        weights = counts / np.maximum(incoming[targets], 1.0)
        if self.spec.parameter_policy.use_neurotransmitter_signs:
            inhibitory = np.isin(
                np.char.lower(graph.neurons.neurotransmitters[sources]),
                ("gaba", "glutamate", "histamine", "his"),
            )
            weights = weights * np.where(inhibitory, -1.0, 1.0)
        edge_groups = self._edge_groups(graph.neurons.cell_types)
        port_indexes = tuple(
            readonly(np.searchsorted(neuron_ids, port.neuron_ids).astype(np.int32))
            for port in self.spec.input_ports
        )
        offsets = [0]
        for port in self.spec.input_ports:
            offsets.append(offsets[-1] + port.neuron_ids.size)
        return CompiledBrain(
            readonly(neuron_ids.astype(np.int64, copy=False)),
            readonly(sources),
            readonly(targets),
            readonly(weights.astype(np.float32)),
            readonly(edge_groups),
            int(edge_groups.max(initial=-1) + 1),
            port_indexes,
            tuple(offsets),
            tuple(port.rule for port in self.spec.input_ports),
            readonly(
                np.searchsorted(neuron_ids, self.spec.output_head.neuron_ids).astype(np.int32)
            ),
            self.spec.dynamics,
            self.spec.parameter_policy,
            self.spec.dt,
            self.spec.output_head.output_width,
            *self._release_gate(graph.neurons.cell_types),
            *self._egocentric(neuron_ids),
        )

    def _egocentric(
        self, neuron_ids: NDArray[np.integer]
    ) -> tuple[NDArray[np.int32] | None, NDArray[np.int32] | None, NDArray[np.float32] | None]:
        projection = self.spec.egocentric
        if projection is None:
            return None, None, None
        for name, ids in (("sources", projection.source_ids), ("targets", projection.target_ids)):
            if not np.isin(ids, neuron_ids).all():
                raise ValueError(f"egocentric {name} contain neurons outside the network")
        return (
            readonly(np.searchsorted(neuron_ids, projection.source_ids).astype(np.int32)),
            readonly(np.searchsorted(neuron_ids, projection.target_ids).astype(np.int32)),
            readonly(projection.target_offsets.astype(np.float32)),
        )

    def _release_gate(
        self, cell_types: NDArray[np.str_]
    ) -> tuple[NDArray[np.bool_] | None, NDArray[np.float32] | None]:
        thresholds = dict(self.spec.dynamics.release_thresholds)
        if not thresholds:
            return None, None
        gate = np.isin(cell_types, list(thresholds))
        if not gate.any():
            raise ValueError("release thresholds name no cell type in the network")
        values = np.array([thresholds.get(str(name), 0.0) for name in cell_types], np.float32)
        return readonly(gate), readonly(values)

    def _edge_groups(self, cell_types: NDArray[np.str_]) -> NDArray[np.int32]:
        graph = self.spec.network.subgraph()
        mode = self.spec.parameter_policy.edge_gain_mode
        if mode == "global":
            return np.zeros(graph.synapses.rows, dtype=np.int32)
        if mode == "per_edge":
            return np.arange(graph.synapses.rows, dtype=np.int32)
        sources = np.searchsorted(graph.neurons.neuron_ids, graph.synapses.source_ids)
        targets = np.searchsorted(graph.neurons.neuron_ids, graph.synapses.target_ids)
        pairs = np.stack((cell_types[sources], cell_types[targets]), axis=1)
        _, inverse = np.unique(pairs, axis=0, return_inverse=True)
        return inverse.astype(np.int32)

    def _validate_ids(self, known_ids: NDArray[np.integer]) -> None:
        for name, ids in (
            *((port.name, port.neuron_ids) for port in self.spec.input_ports),
            (self.spec.output_head.name, self.spec.output_head.neuron_ids),
        ):
            if not np.isin(ids, known_ids).all():
                raise ValueError(f"port or head {name!r} contains neurons outside the network")
