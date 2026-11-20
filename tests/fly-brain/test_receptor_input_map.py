import json

import numpy as np

from fly_brain import Eye, SensorLayout
from fly_brain.connectome import (
    BiologicalStatus,
    ComponentResolver,
    ComponentRole,
    ComponentSpec,
    ConnectomeGraph,
    ConnectomeRelease,
    ExplicitIds,
    InjectionRule,
    NeuronTable,
    SynapseTable,
    build_receptor_input_map,
)


def photoreceptor_graph() -> ConnectomeGraph:
    cell_types = np.tile(np.array(["R1-R6", "R1-R6", "R7d", "R7p", "R8d", "R8p"]), 2)
    return ConnectomeGraph(
        ConnectomeRelease("fixture", "1", "78" * 32, "fixture://receptors", "CC0"),
        NeuronTable(
            neuron_ids=np.arange(1, 14),
            cell_types=np.concatenate([cell_types, ["output"]]),
            classes=np.concatenate([np.full(12, "sensory"), ["visual_projection"]]),
            neuropils=np.concatenate([np.full(12, "retina"), ["optic"]]),
            neurotransmitters=np.concatenate([np.full(12, "His"), ["ACh"]]),
            sides=np.concatenate([np.full(6, "L"), np.full(6, "R"), ["L"]]),
        ),
        SynapseTable(
            source_ids=np.arange(1, 13),
            target_ids=np.full(12, 13),
            counts=np.ones(12, dtype=np.int64),
        ),
    )


def layout() -> SensorLayout:
    return SensorLayout(
        "fixture-layout",
        np.array([10, 11, 20, 21]),
        (Eye.LEFT, Eye.LEFT, Eye.RIGHT, Eye.RIGHT),
        np.array(
            [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [-1.0, 0.0, 0.0], [0.0, -1.0, 0.0]],
            dtype=np.float32,
        ),
        ("pale", "yellow", "pale", "yellow"),
        ("R1_6", "R7", "R8"),
        "fixture",
        "geometry",
        "config",
    )


def test_receptor_map_resolves_explicit_ids_without_global_pooling() -> None:
    source = photoreceptor_graph()
    component = ComponentResolver(source).resolve(
        ComponentSpec(
            "photoreceptors",
            ExplicitIds(tuple(range(1, 13))),
            ComponentRole.SENSORY,
            "Fixture photoreceptors",
            BiologicalStatus.ANATOMICAL,
        )
    )
    mapping = build_receptor_input_map(layout(), source, component)
    activations = np.arange(12, dtype=np.float32).reshape(1, 4, 3)

    transformed = mapping.transform(activations)
    port = mapping.input_port(rule=InjectionRule.CLAMPED)

    assert transformed.shape == (1, 12)
    np.testing.assert_array_equal(np.unique(transformed), np.arange(12, dtype=np.float32))
    np.testing.assert_array_equal(port.neuron_ids, mapping.target_neuron_ids)
    assert port.biological_status == BiologicalStatus.APPROXIMATION
    manifest = json.loads(json.dumps(mapping.to_manifest()))
    assert manifest["layout_id"] == "fixture-layout"
    assert manifest["method"] == "eye-receptor-stable-rank-v1"


def test_receptor_map_zeros_invalid_sites() -> None:
    source = photoreceptor_graph()
    component = ComponentResolver(source).resolve(
        ComponentSpec(
            "photoreceptors",
            ExplicitIds(tuple(range(1, 13))),
            ComponentRole.SENSORY,
            "Fixture photoreceptors",
            BiologicalStatus.ANATOMICAL,
        )
    )
    mapping = build_receptor_input_map(layout(), source, component)
    activations = np.ones((4, 3), dtype=np.float32)

    transformed = mapping.transform(
        activations, valid=np.array([True, False, True, False], dtype=np.bool_)
    )

    assert np.count_nonzero(transformed) == 6
