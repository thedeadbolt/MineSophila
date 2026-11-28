import json

import numpy as np
import pytest

from fly_brain.connectome import (
    AnnotationEquals,
    BiologicalStatus,
    ComponentResolver,
    ComponentRole,
    ComponentSpec,
    ConnectomeGraph,
    ConnectomeRelease,
    ExplicitIds,
    NeuronTable,
    SynapseTable,
)


def graph() -> ConnectomeGraph:
    return ConnectomeGraph(
        ConnectomeRelease("fixture", "1", "12" * 32, "fixture://graph", "CC0"),
        NeuronTable(
            neuron_ids=np.array([1, 2, 3]),
            cell_types=np.array(["R7", "R8", "relay"]),
            classes=np.array(["sensory", "sensory", "interneuron"]),
            neuropils=np.array(["retina", "retina", "optic-lobe"]),
            neurotransmitters=np.array(["histamine", "histamine", "ACh"]),
            sides=np.array(["L", "R", "L"]),
        ),
        SynapseTable(
            source_ids=np.array([1, 2]),
            target_ids=np.array([3, 3]),
            counts=np.array([5, 6]),
        ),
    )


def test_component_resolution_pins_ids_selector_and_graph() -> None:
    source = graph()
    spec = ComponentSpec(
        name="photoreceptor_inputs",
        selector=AnnotationEquals("classes", ("sensory",)),
        role=ComponentRole.SENSORY,
        purpose="Receive receptor drive",
        biological_status=BiologicalStatus.APPROXIMATION,
    )

    component = ComponentResolver(source).resolve(spec)
    manifest = json.loads(json.dumps(component.to_manifest()))

    assert component.neuron_ids.tolist() == [1, 2]
    assert manifest["selector"]["kind"] == "annotation_equals"
    assert manifest["graph_sha256"] == source.graph_sha256
    assert manifest["biological_status"] == "approximation"


def test_empty_and_unknown_component_selections_fail() -> None:
    source = graph()
    empty = ComponentSpec(
        "missing",
        AnnotationEquals("cell_types", ("Kenyon",)),
        ComponentRole.MEMORY,
        "Future memory circuit",
        BiologicalStatus.ANATOMICAL,
    )
    unknown = ComponentSpec(
        "unknown",
        ExplicitIds((99,)),
        ComponentRole.EXPERIMENTAL,
        "Invalid fixture selection",
        BiologicalStatus.ANATOMICAL,
    )

    with pytest.raises(ValueError, match="resolved to no neurons"):
        ComponentResolver(source).resolve(empty)
    with pytest.raises(ValueError, match="unknown neuron IDs"):
        ComponentResolver(source).resolve(unknown)


def test_component_owns_readonly_id_copy() -> None:
    source = graph()
    component = ComponentResolver(source).resolve(
        ComponentSpec(
            "relay",
            ExplicitIds((3,)),
            ComponentRole.PROCESSING,
            "Visual relay",
            BiologicalStatus.ANATOMICAL,
        )
    )

    with pytest.raises(ValueError, match="read-only"):
        component.neuron_ids[0] = 2
