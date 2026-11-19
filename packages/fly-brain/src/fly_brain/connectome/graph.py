"""Validated sparse connectome graph and inspection helpers."""

import hashlib
from collections.abc import Mapping
from dataclasses import dataclass
from types import MappingProxyType
from typing import Protocol

import numpy as np
from numpy.typing import NDArray

from fly_brain.connectome.release import ConnectomeRelease
from fly_brain.connectome.tables import NeuronTable, SynapseTable


class _Digest(Protocol):
    def update(self, data: bytes, /) -> object: ...


def _hash_array(digest: _Digest, array: NDArray[np.generic]) -> None:
    digest.update(array.dtype.kind.encode())
    digest.update(array.size.to_bytes(8, "big"))
    if np.issubdtype(array.dtype, np.integer):
        digest.update(array.astype(">i8", copy=False).tobytes())
        return
    if np.issubdtype(array.dtype, np.floating):
        digest.update(array.astype(">f4", copy=False).tobytes())
        return
    for value in array.tolist():
        encoded = str(value).encode()
        digest.update(len(encoded).to_bytes(8, "big"))
        digest.update(encoded)


@dataclass(frozen=True)
class ConnectomeSummary:
    release_id: str
    graph_sha256: str
    neuron_count: int
    edge_count: int
    anatomical_synapse_count: int
    annotation_gaps: Mapping[str, int]
    memory_bytes: int


@dataclass(frozen=True)
class ConnectomeGraph:
    """One pinned release represented as validated canonical sparse tables."""

    release: ConnectomeRelease
    neurons: NeuronTable
    synapses: SynapseTable

    def __post_init__(self) -> None:
        known = set(self.neurons.neuron_ids.tolist())
        endpoints = set(self.synapses.source_ids.tolist()) | set(self.synapses.target_ids.tolist())
        missing = sorted(endpoints - known)
        if missing:
            raise ValueError(f"synapse endpoints are absent from the neuron table: {missing}")

    @property
    def graph_sha256(self) -> str:
        digest = hashlib.sha256()
        digest.update(self.release.release_id.encode())
        digest.update(bytes.fromhex(self.release.artifact_sha256))
        for column in (
            self.neurons.neuron_ids,
            self.neurons.cell_types,
            self.neurons.classes,
            self.neurons.neuropils,
            self.neurons.neurotransmitters,
            self.neurons.sides,
            self.synapses.source_ids,
            self.synapses.target_ids,
            self.synapses.counts,
        ):
            _hash_array(digest, column)
        if self.synapses.confidence is not None:
            _hash_array(digest, self.synapses.confidence)
        return digest.hexdigest()

    def neuron_index(self, neuron_id: int) -> int:
        index = int(np.searchsorted(self.neurons.neuron_ids, neuron_id))
        if index >= self.neurons.rows or self.neurons.neuron_ids[index] != neuron_id:
            raise KeyError(neuron_id)
        return index

    def induced(self, neuron_ids: NDArray[np.integer]) -> "ConnectomeGraph":
        requested = _requested_ids(neuron_ids)
        known = np.isin(requested, self.neurons.neuron_ids)
        if not known.all():
            raise ValueError(f"unknown neuron IDs: {requested[~known].tolist()}")
        neuron_mask = np.isin(self.neurons.neuron_ids, requested)
        edge_mask = np.isin(self.synapses.source_ids, requested) & np.isin(
            self.synapses.target_ids, requested
        )
        return ConnectomeGraph(
            release=self.release,
            neurons=self.neurons.select(neuron_mask),
            synapses=self.synapses.select(edge_mask),
        )

    def describe(self) -> ConnectomeSummary:
        annotation_gaps = {
            name: int(np.count_nonzero(column == ""))
            for name, column in (
                ("cell_types", self.neurons.cell_types),
                ("classes", self.neurons.classes),
                ("neuropils", self.neurons.neuropils),
                ("neurotransmitters", self.neurons.neurotransmitters),
                ("sides", self.neurons.sides),
            )
        }
        return ConnectomeSummary(
            release_id=self.release.release_id,
            graph_sha256=self.graph_sha256,
            neuron_count=self.neurons.rows,
            edge_count=self.synapses.rows,
            anatomical_synapse_count=int(self.synapses.counts.sum()),
            annotation_gaps=MappingProxyType(annotation_gaps),
            memory_bytes=self.neurons.memory_bytes + self.synapses.memory_bytes,
        )


def _requested_ids(neuron_ids: NDArray[np.integer]) -> NDArray[np.int64]:
    requested = np.asarray(neuron_ids)
    if requested.ndim != 1 or not np.issubdtype(requested.dtype, np.integer):
        raise ValueError("neuron_ids must be a one-dimensional integer array")
    requested = np.unique(requested.astype(np.int64))
    if requested.size == 0:
        raise ValueError("an induced graph must contain at least one neuron")
    return requested
