"""Serializable neuron-selection expressions."""

from dataclasses import dataclass
from typing import Literal, Protocol

import numpy as np
from numpy.typing import NDArray

from fly_brain.connectome.graph import ConnectomeGraph

type AnnotationField = Literal["cell_types", "classes", "neuropils", "neurotransmitters", "sides"]
type SelectorManifest = dict[str, object]


class NeuronSelector(Protocol):
    """Resolve a deterministic set of neuron IDs from one graph."""

    def resolve(self, graph: ConnectomeGraph) -> NDArray[np.int64]: ...

    def to_manifest(self) -> SelectorManifest: ...


@dataclass(frozen=True)
class ExplicitIds:
    neuron_ids: tuple[int, ...]

    def __post_init__(self) -> None:
        if not self.neuron_ids:
            raise ValueError("explicit selector must contain at least one neuron ID")
        if any(type(value) is not int for value in self.neuron_ids):
            raise ValueError("explicit neuron IDs must be integers")
        object.__setattr__(self, "neuron_ids", tuple(sorted(set(self.neuron_ids))))

    def resolve(self, graph: ConnectomeGraph) -> NDArray[np.int64]:
        requested = np.asarray(self.neuron_ids, dtype=np.int64)
        known = np.isin(requested, graph.neurons.neuron_ids)
        if not known.all():
            missing = requested[~known].tolist()
            raise ValueError(f"explicit selector contains unknown neuron IDs: {missing}")
        return requested

    def to_manifest(self) -> SelectorManifest:
        return {"kind": "explicit_ids", "neuron_ids": list(self.neuron_ids)}


@dataclass(frozen=True)
class AnnotationEquals:
    field: AnnotationField
    values: tuple[str, ...]

    def __post_init__(self) -> None:
        allowed = {"cell_types", "classes", "neuropils", "neurotransmitters", "sides"}
        if self.field not in allowed:
            raise ValueError(f"unsupported neuron annotation field: {self.field}")
        values = tuple(sorted(set(self.values)))
        if not values or any(not value for value in values):
            raise ValueError("annotation selector values must be non-empty strings")
        object.__setattr__(self, "values", values)

    def resolve(self, graph: ConnectomeGraph) -> NDArray[np.int64]:
        column = getattr(graph.neurons, self.field)
        return graph.neurons.neuron_ids[np.isin(column, self.values)].astype(np.int64, copy=False)

    def to_manifest(self) -> SelectorManifest:
        return {"kind": "annotation_equals", "field": self.field, "values": list(self.values)}


@dataclass(frozen=True)
class SynapticTargets:
    """Select candidate neurons receiving sufficiently strong edges from a source set."""

    sources: NeuronSelector
    candidates: NeuronSelector
    minimum_synapse_count: int = 1

    def __post_init__(self) -> None:
        if (
            isinstance(self.minimum_synapse_count, bool)
            or self.minimum_synapse_count < 1
        ):
            raise ValueError("minimum_synapse_count must be a positive integer")

    def resolve(self, graph: ConnectomeGraph) -> NDArray[np.int64]:
        edges = graph.synapses
        mask = (
            np.isin(edges.source_ids, self.sources.resolve(graph))
            & np.isin(edges.target_ids, self.candidates.resolve(graph))
            & (edges.counts >= self.minimum_synapse_count)
        )
        return np.unique(edges.target_ids[mask]).astype(np.int64, copy=False)

    def to_manifest(self) -> SelectorManifest:
        return {
            "kind": "synaptic_targets",
            "sources": self.sources.to_manifest(),
            "candidates": self.candidates.to_manifest(),
            "minimum_synapse_count": self.minimum_synapse_count,
        }


@dataclass(frozen=True)
class Union:
    selectors: tuple[NeuronSelector, ...]

    def __post_init__(self) -> None:
        if len(self.selectors) < 2:
            raise ValueError("union requires at least two selectors")

    def resolve(self, graph: ConnectomeGraph) -> NDArray[np.int64]:
        return np.unique(np.concatenate([selector.resolve(graph) for selector in self.selectors]))

    def to_manifest(self) -> SelectorManifest:
        return {"kind": "union", "selectors": [item.to_manifest() for item in self.selectors]}


@dataclass(frozen=True)
class Intersection:
    selectors: tuple[NeuronSelector, ...]

    def __post_init__(self) -> None:
        if len(self.selectors) < 2:
            raise ValueError("intersection requires at least two selectors")

    def resolve(self, graph: ConnectomeGraph) -> NDArray[np.int64]:
        resolved = [selector.resolve(graph) for selector in self.selectors]
        result = resolved[0]
        for values in resolved[1:]:
            result = np.intersect1d(result, values, assume_unique=True)
        return result

    def to_manifest(self) -> SelectorManifest:
        return {
            "kind": "intersection",
            "selectors": [item.to_manifest() for item in self.selectors],
        }


@dataclass(frozen=True)
class Exclude:
    base: NeuronSelector
    excluded: NeuronSelector

    def resolve(self, graph: ConnectomeGraph) -> NDArray[np.int64]:
        return np.setdiff1d(
            self.base.resolve(graph), self.excluded.resolve(graph), assume_unique=True
        )

    def to_manifest(self) -> SelectorManifest:
        return {
            "kind": "exclude",
            "base": self.base.to_manifest(),
            "excluded": self.excluded.to_manifest(),
        }


type Selector = ExplicitIds | AnnotationEquals | SynapticTargets | Union | Intersection | Exclude
