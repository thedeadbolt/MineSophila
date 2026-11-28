"""Explicit mappings between external receptor signals and connectome neurons."""

from dataclasses import dataclass
from enum import StrEnum

import numpy as np
from numpy.typing import NDArray

from fly_brain.connectome.components import BiologicalStatus, NetworkComponent
from fly_brain.connectome.graph import ConnectomeGraph
from fly_brain.contracts import Eye, SensorLayout
from fly_brain.contracts._arrays import readonly


class InjectionRule(StrEnum):
    ADDITIVE = "additive"
    CLAMPED = "clamped"


@dataclass(frozen=True)
class InputPort:
    name: str
    neuron_ids: NDArray[np.int64]
    rule: InjectionRule
    biological_status: BiologicalStatus

    def __post_init__(self) -> None:
        neuron_ids = np.asarray(self.neuron_ids)
        if not self.name.strip():
            raise ValueError("input port name must not be empty")
        if neuron_ids.ndim != 1 or not np.issubdtype(neuron_ids.dtype, np.integer):
            raise ValueError("input port neuron_ids must be a one-dimensional integer array")
        neuron_ids = neuron_ids.astype(np.int64)
        if neuron_ids.size == 0 or np.unique(neuron_ids).size != neuron_ids.size:
            raise ValueError("input port neuron_ids must be non-empty and unique")
        object.__setattr__(self, "neuron_ids", readonly(neuron_ids))


@dataclass(frozen=True)
class EgocentricProjection:
    """A learned two-component egocentric vector read from ``source_ids`` and added to targets.

    Each target receives ``x cos(offset) + y sin(offset)`` where ``(x, y)`` is a learned linear
    readout of source release. For PFN neurons the offsets are their heading shifts in the
    fan-shaped body, so the vector modulates each heading copy as in vector addition (ADR 0030).
    It stands in for the unmodelled noduli route: status approximation.
    """

    source_ids: NDArray[np.int64]
    target_ids: NDArray[np.int64]
    target_offsets: NDArray[np.float64]
    biological_status: BiologicalStatus = BiologicalStatus.APPROXIMATION

    def __post_init__(self) -> None:
        source = np.asarray(self.source_ids, dtype=np.int64)
        target = np.asarray(self.target_ids, dtype=np.int64)
        offsets = np.asarray(self.target_offsets, dtype=np.float64)
        if source.ndim != 1 or source.size == 0 or np.unique(source).size != source.size:
            raise ValueError("egocentric sources must be non-empty and unique")
        if target.ndim != 1 or target.size == 0 or np.unique(target).size != target.size:
            raise ValueError("egocentric targets must be non-empty and unique")
        if offsets.shape != target.shape or not np.isfinite(offsets).all():
            raise ValueError("egocentric offsets must be finite, one per target")
        object.__setattr__(self, "source_ids", readonly(source))
        object.__setattr__(self, "target_ids", readonly(target))
        object.__setattr__(self, "target_offsets", readonly(offsets))


@dataclass(frozen=True)
class OutputHead:
    name: str
    neuron_ids: NDArray[np.int64]
    output_width: int
    biological_status: BiologicalStatus = BiologicalStatus.LEARNED

    def __post_init__(self) -> None:
        neuron_ids = np.asarray(self.neuron_ids)
        if not self.name.strip():
            raise ValueError("output head name must not be empty")
        if neuron_ids.ndim != 1 or not np.issubdtype(neuron_ids.dtype, np.integer):
            raise ValueError("output head neuron_ids must be a one-dimensional integer array")
        neuron_ids = neuron_ids.astype(np.int64)
        if neuron_ids.size == 0 or np.unique(neuron_ids).size != neuron_ids.size:
            raise ValueError("output head neuron_ids must be non-empty and unique")
        if self.output_width < 1:
            raise ValueError("output head width must be positive")
        object.__setattr__(self, "neuron_ids", readonly(neuron_ids))


@dataclass(frozen=True)
class ReceptorInputMap:
    """A versioned approximation from virtual receptor values to explicit neuron IDs."""

    layout_id: str
    graph_sha256: str
    target_neuron_ids: NDArray[np.int64]
    assignment_target_indices: NDArray[np.int32]
    assignment_source_indices: NDArray[np.int32]
    assignment_weights: NDArray[np.float32]
    source_site_ids: NDArray[np.int64]
    source_channel_names: tuple[str, ...]
    source_width: int
    biological_status: BiologicalStatus = BiologicalStatus.APPROXIMATION
    method: str = "eye-receptor-stable-rank-v1"

    def __post_init__(self) -> None:
        target_ids = np.asarray(self.target_neuron_ids)
        assignment_targets = np.asarray(self.assignment_target_indices)
        assignment_sources = np.asarray(self.assignment_source_indices)
        assignment_weights = np.asarray(self.assignment_weights, dtype=np.float32)
        source_site_ids = np.asarray(self.source_site_ids)
        if not self.layout_id.strip() or len(self.graph_sha256) != 64:
            raise ValueError("receptor map must identify one layout and graph")
        if target_ids.ndim != 1 or not np.issubdtype(target_ids.dtype, np.integer):
            raise ValueError("target_neuron_ids must be a one-dimensional integer array")
        assignments = assignment_targets.size
        if (
            assignment_targets.ndim != 1
            or assignment_sources.shape != (assignments,)
            or assignment_weights.shape != (assignments,)
            or not np.issubdtype(assignment_targets.dtype, np.integer)
            or not np.issubdtype(assignment_sources.dtype, np.integer)
        ):
            raise ValueError("receptor assignments must be equal-length one-dimensional arrays")
        target_ids = target_ids.astype(np.int64)
        assignment_targets = assignment_targets.astype(np.int32)
        assignment_sources = assignment_sources.astype(np.int32)
        if target_ids.size == 0 or np.unique(target_ids).size != target_ids.size:
            raise ValueError("target_neuron_ids must be non-empty and unique")
        if (
            assignments == 0
            or np.any(assignment_targets < 0)
            or np.any(assignment_targets >= target_ids.size)
            or np.unique(assignment_targets).size != target_ids.size
        ):
            raise ValueError("every target neuron must have at least one receptor assignment")
        if (
            self.source_width < 1
            or np.any(assignment_sources < 0)
            or np.any(assignment_sources >= self.source_width)
        ):
            raise ValueError("receptor map contains an invalid source index")
        if (
            source_site_ids.ndim != 1
            or not np.issubdtype(source_site_ids.dtype, np.integer)
            or source_site_ids.size == 0
            or np.unique(source_site_ids).size != source_site_ids.size
        ):
            raise ValueError("source_site_ids must be a non-empty unique integer array")
        if (
            not self.source_channel_names
            or len(set(self.source_channel_names)) != len(self.source_channel_names)
            or any(not name.strip() for name in self.source_channel_names)
            or source_site_ids.size * len(self.source_channel_names) != self.source_width
        ):
            raise ValueError("source IDs and channels must describe every source value")
        if np.unique(assignment_sources).size != self.source_width:
            raise ValueError("every source receptor value must contribute to the map")
        if not np.isfinite(assignment_weights).all() or np.any(assignment_weights <= 0):
            raise ValueError("receptor assignment weights must be finite and positive")
        if not self.method.strip():
            raise ValueError("receptor map method must not be empty")
        order = np.argsort(assignment_targets, kind="stable")
        object.__setattr__(self, "target_neuron_ids", readonly(target_ids))
        object.__setattr__(self, "assignment_target_indices", readonly(assignment_targets[order]))
        object.__setattr__(self, "assignment_source_indices", readonly(assignment_sources[order]))
        object.__setattr__(self, "assignment_weights", readonly(assignment_weights[order]))
        object.__setattr__(self, "source_site_ids", readonly(source_site_ids.astype(np.int64)))

    def transform(
        self, activations: NDArray[np.floating], *, valid: NDArray[np.bool_] | None = None
    ) -> NDArray[np.float32]:
        values = np.asarray(activations, dtype=np.float32)
        expected = (self.source_site_ids.size, len(self.source_channel_names))
        if values.ndim < 2 or values.shape[-2:] != expected:
            raise ValueError("activations do not match the receptor map source width")
        if valid is not None:
            site_valid = np.asarray(valid, dtype=np.bool_)
            if site_valid.shape != (values.shape[-2],):
                raise ValueError("valid mask must name every receptor site")
            values = np.where(site_valid[:, None], values, 0.0)
        flat = values.reshape(*values.shape[:-2], self.source_width)
        contributions = flat[..., self.assignment_source_indices] * self.assignment_weights
        starts = np.concatenate(
            (
                np.array([0]),
                np.flatnonzero(np.diff(self.assignment_target_indices)) + 1,
            )
        )
        return np.asarray(np.add.reduceat(contributions, starts, axis=-1), dtype=np.float32)

    def input_port(
        self, *, name: str = "compound_eye_receptors", rule: InjectionRule = InjectionRule.CLAMPED
    ) -> InputPort:
        return InputPort(name, self.target_neuron_ids, rule, self.biological_status)

    def to_manifest(self) -> dict[str, object]:
        return {
            "layout_id": self.layout_id,
            "graph_sha256": self.graph_sha256,
            "target_neuron_ids": self.target_neuron_ids.tolist(),
            "assignment_target_indices": self.assignment_target_indices.tolist(),
            "assignment_source_indices": self.assignment_source_indices.tolist(),
            "assignment_weights": self.assignment_weights.tolist(),
            "source_site_ids": self.source_site_ids.tolist(),
            "source_channel_names": list(self.source_channel_names),
            "source_width": self.source_width,
            "biological_status": self.biological_status.value,
            "method": self.method,
        }


def build_receptor_input_map(
    layout: SensorLayout, graph: ConnectomeGraph, photoreceptors: NetworkComponent
) -> ReceptorInputMap:
    """Match eye and receptor class, then align virtual sites by stable rank."""
    if photoreceptors.graph_sha256 != graph.graph_sha256:
        raise ValueError("photoreceptor component belongs to a different graph")
    channel_indexes = {name: index for index, name in enumerate(layout.channel_names)}
    missing = {"R1_6", "R7", "R8"} - channel_indexes.keys()
    if missing:
        raise ValueError(f"sensor layout is missing receptor channels: {sorted(missing)}")

    selected = np.isin(graph.neurons.neuron_ids, photoreceptors.neuron_ids)
    neuron_ids = graph.neurons.neuron_ids[selected]
    cell_types = graph.neurons.cell_types[selected]
    sides = graph.neurons.sides[selected]
    target_parts: list[NDArray[np.int64]] = []
    assignment_target_ids: list[NDArray[np.int64]] = []
    assignment_sources: list[NDArray[np.int32]] = []
    assignment_weights: list[NDArray[np.float32]] = []
    channels = len(layout.channel_names)
    for eye, side in ((Eye.LEFT, "L"), (Eye.RIGHT, "R")):
        site_indexes = np.flatnonzero(np.asarray(layout.eyes) == eye)
        if site_indexes.size == 0:
            raise ValueError(f"sensor layout has no {eye.value} eye sites")
        for channel in ("R1_6", "R7", "R8"):
            target_mask = (sides == side) & _cell_type_mask(cell_types, channel)
            targets = np.sort(neuron_ids[target_mask])
            if targets.size == 0:
                raise ValueError(f"photoreceptor component has no {side} {channel} neurons")
            target_parts.append(targets.astype(np.int64, copy=False))
            sources = site_indexes * channels + channel_indexes[channel]
            if targets.size >= sources.size:
                source_ranks = np.floor(
                    np.arange(targets.size) * sources.size / targets.size
                ).astype(np.int64)
                assigned_targets = targets
                assigned_sources = sources[source_ranks]
            else:
                target_ranks = np.floor(
                    np.arange(sources.size) * targets.size / sources.size
                ).astype(np.int64)
                assigned_targets = targets[target_ranks]
                assigned_sources = sources
            unique_targets, counts = np.unique(assigned_targets, return_counts=True)
            target_ranks = np.searchsorted(unique_targets, assigned_targets)
            assignment_target_ids.append(assigned_targets.astype(np.int64, copy=False))
            assignment_sources.append(assigned_sources.astype(np.int32))
            assignment_weights.append((1.0 / counts[target_ranks]).astype(np.float32))
    targets = np.concatenate(target_parts)
    targets.sort()
    assigned_target_ids = np.concatenate(assignment_target_ids)
    return ReceptorInputMap(
        layout.layout_id,
        graph.graph_sha256,
        targets,
        np.searchsorted(targets, assigned_target_ids).astype(np.int32),
        np.concatenate(assignment_sources),
        np.concatenate(assignment_weights),
        layout.site_ids,
        layout.channel_names,
        layout.site_count * channels,
    )


def _cell_type_mask(cell_types: NDArray[np.str_], channel: str) -> NDArray[np.bool_]:
    if channel == "R1_6":
        return cell_types == "R1-R6"
    return np.char.startswith(cell_types, channel)
