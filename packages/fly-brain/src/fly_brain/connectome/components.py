"""Named, reproducible views over one connectome graph."""

from dataclasses import dataclass
from enum import StrEnum

import numpy as np
from numpy.typing import NDArray

from fly_brain.connectome.graph import ConnectomeGraph
from fly_brain.connectome.selectors import NeuronSelector, SelectorManifest
from fly_brain.contracts._arrays import readonly


class ComponentRole(StrEnum):
    SENSORY = "sensory"
    PROCESSING = "processing"
    MEMORY = "memory"
    OUTPUT = "output"
    EXPERIMENTAL = "experimental"


class BiologicalStatus(StrEnum):
    ANATOMICAL = "anatomical"
    APPROXIMATION = "approximation"
    LEARNED = "learned"
    SHORTCUT = "shortcut"


@dataclass(frozen=True)
class ComponentSpec:
    name: str
    selector: NeuronSelector
    role: ComponentRole
    purpose: str
    biological_status: BiologicalStatus

    def __post_init__(self) -> None:
        if not self.name.strip():
            raise ValueError("component name must not be empty")
        if not self.purpose.strip():
            raise ValueError(f"component {self.name!r} must define its purpose")


@dataclass(frozen=True)
class NetworkComponent:
    """A component resolved to exact IDs in one exact graph."""

    spec: ComponentSpec
    neuron_ids: NDArray[np.int64]
    graph_sha256: str

    def __post_init__(self) -> None:
        neuron_ids = np.asarray(self.neuron_ids)
        if neuron_ids.ndim != 1 or not np.issubdtype(neuron_ids.dtype, np.integer):
            raise ValueError("component neuron_ids must be a one-dimensional integer array")
        neuron_ids = np.unique(neuron_ids.astype(np.int64))
        if neuron_ids.size == 0:
            raise ValueError(f"component {self.spec.name!r} resolved to no neurons")
        if len(self.graph_sha256) != 64:
            raise ValueError("component graph_sha256 must identify a SHA-256 digest")
        object.__setattr__(self, "neuron_ids", readonly(neuron_ids))

    def to_manifest(self) -> dict[str, object]:
        selector: SelectorManifest = self.spec.selector.to_manifest()
        return {
            "name": self.spec.name,
            "role": self.spec.role.value,
            "purpose": self.spec.purpose,
            "biological_status": self.spec.biological_status.value,
            "selector": selector,
            "neuron_ids": self.neuron_ids.tolist(),
            "graph_sha256": self.graph_sha256,
        }


@dataclass(frozen=True)
class ComponentResolver:
    graph: ConnectomeGraph

    def resolve(self, spec: ComponentSpec) -> NetworkComponent:
        neuron_ids = spec.selector.resolve(self.graph)
        return NetworkComponent(
            spec=spec,
            neuron_ids=neuron_ids,
            graph_sha256=self.graph.graph_sha256,
        )
