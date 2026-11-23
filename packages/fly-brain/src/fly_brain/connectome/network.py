"""Immutable assembly of named components over a connectome graph."""

from dataclasses import dataclass, replace

import numpy as np
from numpy.typing import NDArray

from fly_brain.connectome.components import NetworkComponent
from fly_brain.connectome.graph import ConnectomeGraph
from fly_brain.connectome.tables import SynapseTable


@dataclass(frozen=True)
class EdgePolicy:
    minimum_synapse_count: int = 1
    include_internal: bool = True
    include_cross_component: bool = True
    include_recurrent: bool = True

    def __post_init__(self) -> None:
        if isinstance(self.minimum_synapse_count, bool) or self.minimum_synapse_count < 1:
            raise ValueError("minimum_synapse_count must be a positive integer")
        if not self.include_internal and not self.include_cross_component:
            raise ValueError("edge policy must include internal or cross-component edges")


@dataclass(frozen=True)
class ComponentConnectivity:
    source: str
    target: str
    edge_count: int
    anatomical_synapse_count: int


@dataclass(frozen=True)
class ComponentOverlap:
    first: str
    second: str
    neuron_count: int


@dataclass(frozen=True)
class NetworkDescription:
    component_count: int
    neuron_count: int
    edge_count: int
    anatomical_synapse_count: int
    component_sizes: tuple[tuple[str, int], ...]
    overlaps: tuple[ComponentOverlap, ...]
    connectivity: tuple[ComponentConnectivity, ...]
    disconnected_components: tuple[str, ...]


@dataclass(frozen=True)
class BrainNetwork:
    """Compose named graph views without owning dynamics or learned parameters."""

    graph: ConnectomeGraph
    components: tuple[NetworkComponent, ...] = ()
    edge_policy: EdgePolicy = EdgePolicy()

    def __post_init__(self) -> None:
        names = [component.spec.name for component in self.components]
        if len(names) != len(set(names)):
            raise ValueError("network component names must be unique")
        known = self.graph.neurons.neuron_ids
        for component in self.components:
            if component.graph_sha256 != self.graph.graph_sha256:
                raise ValueError(f"component {component.spec.name!r} belongs to a different graph")
            if not np.isin(component.neuron_ids, known).all():
                raise ValueError(f"component {component.spec.name!r} contains unknown neuron IDs")

    def with_component(self, component: NetworkComponent) -> "BrainNetwork":
        return replace(self, components=(*self.components, component))

    @property
    def neuron_ids(self) -> NDArray[np.int64]:
        if not self.components:
            return np.array([], dtype=np.int64)
        return np.unique(np.concatenate([component.neuron_ids for component in self.components]))

    def subgraph(self) -> ConnectomeGraph:
        if not self.components:
            raise ValueError("network must contain at least one component")
        selected = self.neuron_ids
        neuron_mask = np.isin(self.graph.neurons.neuron_ids, selected)
        edge_mask = self._edge_mask()
        return ConnectomeGraph(
            self.graph.release,
            self.graph.neurons.select(neuron_mask),
            self.graph.synapses.select(edge_mask),
        )

    def _edge_mask(self) -> NDArray[np.bool_]:
        edges = self.graph.synapses
        selected = self.neuron_ids
        mask = (
            np.isin(edges.source_ids, selected)
            & np.isin(edges.target_ids, selected)
            & (edges.counts >= self.edge_policy.minimum_synapse_count)
        )
        if not self.edge_policy.include_recurrent:
            mask &= edges.source_ids != edges.target_ids
        if not self.edge_policy.include_internal or not self.edge_policy.include_cross_component:
            internal = np.zeros(edges.rows, dtype=np.bool_)
            for component in self.components:
                internal |= np.isin(edges.source_ids, component.neuron_ids) & np.isin(
                    edges.target_ids, component.neuron_ids
                )
            mask &= internal if self.edge_policy.include_internal else ~internal
        return mask

    def describe(self) -> NetworkDescription:
        subgraph = self.subgraph()
        overlaps = tuple(
            ComponentOverlap(first.spec.name, second.spec.name, count)
            for index, first in enumerate(self.components)
            for second in self.components[index + 1 :]
            if (count := int(np.intersect1d(first.neuron_ids, second.neuron_ids).size)) > 0
        )
        connectivity = tuple(
            result
            for source in self.components
            for target in self.components
            if (result := _connectivity(source, target, subgraph.synapses)).edge_count > 0
        )
        connected_names = {item.source for item in connectivity} | {
            item.target for item in connectivity
        }
        return NetworkDescription(
            component_count=len(self.components),
            neuron_count=subgraph.neurons.rows,
            edge_count=subgraph.synapses.rows,
            anatomical_synapse_count=int(subgraph.synapses.counts.sum()),
            component_sizes=tuple(
                (component.spec.name, component.neuron_ids.size) for component in self.components
            ),
            overlaps=overlaps,
            connectivity=connectivity,
            disconnected_components=tuple(
                component.spec.name
                for component in self.components
                if component.spec.name not in connected_names
            ),
        )

    def component_summary(self) -> dict[str, dict[str, dict[str, int]]]:
        """Synapse counts between components and neuron counts per cell type, for reports.

        ``component_edges[source][target]`` sums anatomical synapses on the network's edges
        from one component's neurons to another's (pairs without edges are omitted).
        ``component_cell_types[component][cell_type]`` counts that component's neurons.
        """
        description = self.describe()
        edges: dict[str, dict[str, int]] = {}
        for item in description.connectivity:
            edges.setdefault(item.source, {})[item.target] = item.anatomical_synapse_count
        neurons = self.graph.neurons
        cell_types: dict[str, dict[str, int]] = {}
        for component in self.components:
            selected = neurons.cell_types[np.isin(neurons.neuron_ids, component.neuron_ids)]
            names, counts = np.unique(selected, return_counts=True)
            cell_types[component.spec.name] = {
                str(name): int(count) for name, count in zip(names, counts, strict=True)
            }
        return {"component_edges": edges, "component_cell_types": cell_types}

    def to_manifest(self) -> dict[str, object]:
        return {
            "graph_sha256": self.graph.graph_sha256,
            "edge_policy": {
                "minimum_synapse_count": self.edge_policy.minimum_synapse_count,
                "include_internal": self.edge_policy.include_internal,
                "include_cross_component": self.edge_policy.include_cross_component,
                "include_recurrent": self.edge_policy.include_recurrent,
            },
            "components": [component.to_manifest() for component in self.components],
        }


def _connectivity(
    source: NetworkComponent, target: NetworkComponent, edges: SynapseTable
) -> ComponentConnectivity:
    mask = np.isin(edges.source_ids, source.neuron_ids) & np.isin(
        edges.target_ids, target.neuron_ids
    )
    return ComponentConnectivity(
        source.spec.name,
        target.spec.name,
        int(np.count_nonzero(mask)),
        int(edges.counts[mask].sum()),
    )
