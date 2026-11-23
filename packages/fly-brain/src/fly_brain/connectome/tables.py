"""Canonical immutable columnar connectome tables."""

from dataclasses import dataclass

import numpy as np
from numpy.typing import NDArray

from fly_brain.contracts._arrays import readonly


def _integer_column(values: NDArray[np.integer], name: str) -> NDArray[np.int64]:
    column = np.asarray(values)
    if column.ndim != 1 or not np.issubdtype(column.dtype, np.integer):
        raise ValueError(f"{name} must be a one-dimensional integer column")
    return column.astype(np.int64)


def _text_column(values: NDArray[np.str_], rows: int, name: str) -> NDArray[np.str_]:
    column = np.asarray(values, dtype=np.str_)
    if column.shape != (rows,):
        raise ValueError(f"{name} must have shape {(rows,)}, got {column.shape}")
    return column


@dataclass(frozen=True)
class NeuronTable:
    """Neuron annotations ordered canonically by neuron ID."""

    neuron_ids: NDArray[np.integer]
    cell_types: NDArray[np.str_]
    classes: NDArray[np.str_]
    neuropils: NDArray[np.str_]
    neurotransmitters: NDArray[np.str_]
    sides: NDArray[np.str_]
    # Total incoming synapses per neuron in the full release (edges at or above the loader's
    # threshold, from any presynaptic neuron, loaded or not); None when not requested.
    input_synapse_counts: NDArray[np.integer] | None = None

    def __post_init__(self) -> None:
        neuron_ids = _integer_column(self.neuron_ids, "neuron_ids")
        if neuron_ids.size == 0:
            raise ValueError("neuron table must not be empty")
        if np.unique(neuron_ids).size != neuron_ids.size:
            raise ValueError("neuron_ids must be unique")
        rows = neuron_ids.size
        columns = {
            "cell_types": _text_column(self.cell_types, rows, "cell_types"),
            "classes": _text_column(self.classes, rows, "classes"),
            "neuropils": _text_column(self.neuropils, rows, "neuropils"),
            "neurotransmitters": _text_column(self.neurotransmitters, rows, "neurotransmitters"),
            "sides": _text_column(self.sides, rows, "sides"),
        }
        order = np.argsort(neuron_ids, kind="stable")
        object.__setattr__(self, "neuron_ids", readonly(neuron_ids[order]))
        for name, column in columns.items():
            object.__setattr__(self, name, readonly(column[order]))
        if self.input_synapse_counts is not None:
            totals = _integer_column(self.input_synapse_counts, "input_synapse_counts")
            if totals.shape != (rows,) or np.any(totals < 0):
                raise ValueError("input_synapse_counts must be non-negative, one per neuron")
            object.__setattr__(self, "input_synapse_counts", readonly(totals[order]))

    @property
    def rows(self) -> int:
        return self.neuron_ids.size

    @property
    def memory_bytes(self) -> int:
        return sum(
            column.nbytes
            for column in (
                self.neuron_ids,
                self.cell_types,
                self.classes,
                self.neuropils,
                self.neurotransmitters,
                self.sides,
            )
        )

    def select(self, mask: NDArray[np.bool_]) -> "NeuronTable":
        selected = np.asarray(mask, dtype=np.bool_)
        if selected.shape != (self.rows,):
            raise ValueError(f"neuron mask must have shape {(self.rows,)}, got {selected.shape}")
        return NeuronTable(
            neuron_ids=self.neuron_ids[selected],
            cell_types=self.cell_types[selected],
            classes=self.classes[selected],
            neuropils=self.neuropils[selected],
            neurotransmitters=self.neurotransmitters[selected],
            sides=self.sides[selected],
            input_synapse_counts=(
                None if self.input_synapse_counts is None else self.input_synapse_counts[selected]
            ),
        )


@dataclass(frozen=True)
class SynapseTable:
    """Aggregated directed edges ordered canonically by endpoint IDs."""

    source_ids: NDArray[np.integer]
    target_ids: NDArray[np.integer]
    counts: NDArray[np.integer]
    confidence: NDArray[np.floating] | None = None

    def __post_init__(self) -> None:
        source_ids = _integer_column(self.source_ids, "source_ids")
        target_ids = _integer_column(self.target_ids, "target_ids")
        counts = _integer_column(self.counts, "counts")
        rows = source_ids.size
        if target_ids.shape != (rows,) or counts.shape != (rows,):
            raise ValueError("source_ids, target_ids and counts must have equal lengths")
        if np.any(counts <= 0):
            raise ValueError("synapse counts must be positive")
        if len(set(zip(source_ids.tolist(), target_ids.tolist(), strict=True))) != rows:
            raise ValueError("directed synapse endpoints must be unique")
        confidence: NDArray[np.float32] | None = None
        if self.confidence is not None:
            confidence = np.asarray(self.confidence, dtype=np.float32)
            if confidence.shape != (rows,):
                raise ValueError(f"confidence must have shape {(rows,)}, got {confidence.shape}")
            if not np.isfinite(confidence).all() or np.any((confidence < 0) | (confidence > 1)):
                raise ValueError("confidence values must be finite and lie in [0, 1]")
        order = np.lexsort((target_ids, source_ids))
        object.__setattr__(self, "source_ids", readonly(source_ids[order]))
        object.__setattr__(self, "target_ids", readonly(target_ids[order]))
        object.__setattr__(self, "counts", readonly(counts[order]))
        if confidence is not None:
            object.__setattr__(self, "confidence", readonly(confidence[order]))

    @property
    def rows(self) -> int:
        return self.source_ids.size

    @property
    def memory_bytes(self) -> int:
        total = self.source_ids.nbytes + self.target_ids.nbytes + self.counts.nbytes
        return total + (0 if self.confidence is None else self.confidence.nbytes)

    def select(self, mask: NDArray[np.bool_]) -> "SynapseTable":
        selected = np.asarray(mask, dtype=np.bool_)
        if selected.shape != (self.rows,):
            raise ValueError(f"synapse mask must have shape {(self.rows,)}, got {selected.shape}")
        confidence = None if self.confidence is None else self.confidence[selected]
        return SynapseTable(
            source_ids=self.source_ids[selected],
            target_ids=self.target_ids[selected],
            counts=self.counts[selected],
            confidence=confidence,
        )
