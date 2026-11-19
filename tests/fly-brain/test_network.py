import json

import numpy as np
import pytest

from fly_brain.connectome import (
    BiologicalStatus,
    BrainNetwork,
    ComponentResolver,
    ComponentRole,
    ComponentSpec,
    ConnectomeGraph,
    ConnectomeRelease,
    EdgePolicy,
    ExplicitIds,
    NeuronTable,
    SynapseTable,
)


def graph() -> ConnectomeGraph:
    return ConnectomeGraph(
        ConnectomeRelease("fixture", "1", "34" * 32, "fixture://graph", "CC0"),
        NeuronTable(
            neuron_ids=np.arange(1, 6),
            cell_types=np.array(["R7", "R8", "relay", "output", "unused"]),
            classes=np.array(["sensory", "sensory", "interneuron", "descending", "other"]),
            neuropils=np.array(["retina", "retina", "optic", "VNC", "other"]),
            neurotransmitters=np.array(["His", "His", "ACh", "ACh", ""]),
            sides=np.array(["L", "R", "L", "L", "R"]),
        ),
        SynapseTable(
            source_ids=np.array([1, 2, 3, 3, 4]),
            target_ids=np.array([3, 3, 3, 4, 5]),
            counts=np.array([5, 6, 2, 4, 9]),
        ),
    )


def component(source: ConnectomeGraph, name: str, ids: tuple[int, ...], role: ComponentRole):
    return ComponentResolver(source).resolve(
        ComponentSpec(
            name,
            ExplicitIds(ids),
            role,
            f"Fixture {name}",
            BiologicalStatus.ANATOMICAL,
        )
    )


def test_functional_assembly_preserves_original_network() -> None:
    source = graph()
    empty = BrainNetwork(source)
    sensory = component(source, "sensory", (1, 2), ComponentRole.SENSORY)
    relay = component(source, "relay", (3,), ComponentRole.PROCESSING)

    network = empty.with_component(sensory).with_component(relay)

    assert empty.components == ()
    assert [item.spec.name for item in network.components] == ["sensory", "relay"]
    assert network.neuron_ids.tolist() == [1, 2, 3]
    assert network.subgraph().synapses.counts.tolist() == [5, 6, 2]


def test_edge_policy_filters_weak_recurrent_and_cross_edges() -> None:
    source = graph()
    network = BrainNetwork(
        source,
        (
            component(source, "sensory", (1, 2), ComponentRole.SENSORY),
            component(source, "relay", (3,), ComponentRole.PROCESSING),
        ),
        EdgePolicy(minimum_synapse_count=6, include_internal=False, include_recurrent=False),
    )

    filtered = network.subgraph().synapses

    assert filtered.source_ids.tolist() == [2]
    assert filtered.target_ids.tolist() == [3]


def test_description_reports_overlap_connectivity_and_disconnection() -> None:
    source = graph()
    network = BrainNetwork(source)
    for item in (
        component(source, "sensory", (1, 2), ComponentRole.SENSORY),
        component(source, "relay", (2, 3), ComponentRole.PROCESSING),
        component(source, "isolated", (5,), ComponentRole.EXPERIMENTAL),
    ):
        network = network.with_component(item)

    description = network.describe()

    assert description.neuron_count == 4
    assert description.edge_count == 3
    assert description.overlaps[0].neuron_count == 1
    assert description.disconnected_components == ("isolated",)
    assert any(
        item.source == "sensory" and item.target == "relay" for item in description.connectivity
    )
    assert json.loads(json.dumps(network.to_manifest()))["components"][0]["name"] == "sensory"


def test_network_rejects_duplicate_names_and_foreign_components() -> None:
    source = graph()
    sensory = component(source, "sensory", (1, 2), ComponentRole.SENSORY)
    with pytest.raises(ValueError, match="names must be unique"):
        BrainNetwork(source, (sensory, sensory))

    other = ConnectomeGraph(
        ConnectomeRelease("other", "1", "56" * 32, "fixture://other", "CC0"),
        source.neurons,
        source.synapses,
    )
    with pytest.raises(ValueError, match="different graph"):
        BrainNetwork(other, (sensory,))
