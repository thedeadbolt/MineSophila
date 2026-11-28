import numpy as np
import pytest

from fly_brain.connectome import (
    ConnectomeGraph,
    ConnectomeRelease,
    NeuronTable,
    SynapseTable,
)


def release() -> ConnectomeRelease:
    return ConnectomeRelease(
        source="fixture",
        version="1.0",
        artifact_sha256="ab" * 32,
        source_uri="fixture://connectome",
        license_name="CC0-1.0",
    )


def neurons() -> NeuronTable:
    return NeuronTable(
        neuron_ids=np.array([30, 10, 20], dtype=np.int64),
        cell_types=np.array(["output", "receptor", "relay"]),
        classes=np.array(["descending", "sensory", "interneuron"]),
        neuropils=np.array(["VNC", "retina", "optic-lobe"]),
        neurotransmitters=np.array(["ACh", "histamine", ""]),
        sides=np.array(["R", "L", "L"]),
    )


def synapses() -> SynapseTable:
    return SynapseTable(
        source_ids=np.array([20, 10, 20], dtype=np.int64),
        target_ids=np.array([30, 20, 20], dtype=np.int64),
        counts=np.array([4, 7, 2], dtype=np.int64),
        confidence=np.array([0.8, 1.0, 0.7], dtype=np.float32),
    )


def test_graph_canonicalizes_tables_and_builds_stable_indexes() -> None:
    graph = ConnectomeGraph(release(), neurons(), synapses())

    assert graph.neurons.neuron_ids.tolist() == [10, 20, 30]
    assert list(zip(graph.synapses.source_ids, graph.synapses.target_ids, strict=True)) == [
        (10, 20),
        (20, 20),
        (20, 30),
    ]
    assert graph.neuron_index(20) == 1
    with pytest.raises(KeyError):
        graph.neuron_index(99)


def test_graph_checksum_is_independent_of_input_row_order() -> None:
    first = ConnectomeGraph(release(), neurons(), synapses())
    reversed_neurons = neurons()
    reversed_synapses = synapses()
    assert reversed_synapses.confidence is not None
    second = ConnectomeGraph(
        release(),
        NeuronTable(
            neuron_ids=reversed_neurons.neuron_ids[::-1],
            cell_types=reversed_neurons.cell_types[::-1],
            classes=reversed_neurons.classes[::-1],
            neuropils=reversed_neurons.neuropils[::-1],
            neurotransmitters=reversed_neurons.neurotransmitters[::-1],
            sides=reversed_neurons.sides[::-1],
        ),
        SynapseTable(
            source_ids=reversed_synapses.source_ids[::-1],
            target_ids=reversed_synapses.target_ids[::-1],
            counts=reversed_synapses.counts[::-1],
            confidence=reversed_synapses.confidence[::-1],
        ),
    )

    assert second.graph_sha256 == first.graph_sha256


def test_induced_graph_keeps_only_internal_edges() -> None:
    graph = ConnectomeGraph(release(), neurons(), synapses())

    induced = graph.induced(np.array([10, 20]))

    assert induced.neurons.neuron_ids.tolist() == [10, 20]
    assert induced.synapses.source_ids.tolist() == [10, 20]
    assert induced.synapses.target_ids.tolist() == [20, 20]
    with pytest.raises(ValueError, match="unknown neuron IDs"):
        graph.induced(np.array([10, 99]))


def test_summary_reports_counts_annotation_gaps_and_memory() -> None:
    summary = ConnectomeGraph(release(), neurons(), synapses()).describe()

    assert summary.release_id == "fixture@1.0"
    assert summary.neuron_count == 3
    assert summary.edge_count == 3
    assert summary.anatomical_synapse_count == 13
    assert summary.annotation_gaps["neurotransmitters"] == 1
    assert summary.memory_bytes > 0


def test_graph_rejects_invalid_structure() -> None:
    with pytest.raises(ValueError, match="must be unique"):
        NeuronTable(
            neuron_ids=np.array([1, 1]),
            cell_types=np.array(["a", "b"]),
            classes=np.array(["", ""]),
            neuropils=np.array(["", ""]),
            neurotransmitters=np.array(["", ""]),
            sides=np.array(["", ""]),
        )
    with pytest.raises(ValueError, match="positive"):
        SynapseTable(
            source_ids=np.array([10]),
            target_ids=np.array([20]),
            counts=np.array([0]),
        )
    with pytest.raises(ValueError, match="absent from the neuron table"):
        ConnectomeGraph(
            release(),
            neurons(),
            SynapseTable(
                source_ids=np.array([10]),
                target_ids=np.array([99]),
                counts=np.array([1]),
            ),
        )
