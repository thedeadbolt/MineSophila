import json

import numpy as np
import pytest

from fly_brain.connectome import (
    AnnotationEquals,
    ConnectomeGraph,
    ConnectomeRelease,
    Exclude,
    ExplicitIds,
    Intersection,
    NeuronTable,
    SynapseTable,
    Union,
)


@pytest.fixture
def graph() -> ConnectomeGraph:
    return ConnectomeGraph(
        release=ConnectomeRelease("fixture", "1", "00" * 32, "fixture://graph", "CC0"),
        neurons=NeuronTable(
            neuron_ids=np.array([1, 2, 3, 4]),
            cell_types=np.array(["R7", "R8", "relay", "output"]),
            classes=np.array(["sensory", "sensory", "interneuron", "descending"]),
            neuropils=np.array(["retina", "retina", "optic-lobe", "VNC"]),
            neurotransmitters=np.array(["histamine", "histamine", "ACh", "ACh"]),
            sides=np.array(["L", "R", "L", "L"]),
        ),
        synapses=SynapseTable(
            source_ids=np.array([1, 2, 3]),
            target_ids=np.array([3, 3, 4]),
            counts=np.array([5, 6, 3]),
        ),
    )


def test_explicit_and_annotation_selectors_resolve_stably(graph: ConnectomeGraph) -> None:
    assert ExplicitIds((3, 1, 3)).resolve(graph).tolist() == [1, 3]
    assert AnnotationEquals("classes", ("sensory",)).resolve(graph).tolist() == [1, 2]
    with pytest.raises(ValueError, match="unknown neuron IDs"):
        ExplicitIds((99,)).resolve(graph)


def test_selector_composition_uses_set_semantics(graph: ConnectomeGraph) -> None:
    sensory = AnnotationEquals("classes", ("sensory",))
    left = AnnotationEquals("sides", ("L",))
    relay = ExplicitIds((3,))

    assert Union((sensory, relay)).resolve(graph).tolist() == [1, 2, 3]
    assert Intersection((sensory, left)).resolve(graph).tolist() == [1]
    assert Exclude(sensory, left).resolve(graph).tolist() == [2]


def test_selector_manifest_is_json_serializable(graph: ConnectomeGraph) -> None:
    selector = Exclude(
        Union((AnnotationEquals("neuropils", ("retina",)), ExplicitIds((3,)))),
        AnnotationEquals("sides", ("R",)),
    )

    assert selector.resolve(graph).tolist() == [1, 3]
    assert json.loads(json.dumps(selector.to_manifest()))["kind"] == "exclude"
