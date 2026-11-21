import numpy as np

from fly_brain.connectome import (
    ConnectomeGraph,
    ConnectomeRelease,
    NeuronTable,
    SynapseTable,
    build_first_visual_network,
    visual_kenyon_component_spec,
    visual_kenyon_input_component_spec,
)


def _graph() -> ConnectomeGraph:
    return ConnectomeGraph(
        ConnectomeRelease("fixture", "1", "34" * 32, "fixture://graph", "CC0"),
        NeuronTable(
            neuron_ids=np.array([1, 2, 3, 4]),
            cell_types=np.array(["R7d", "VPN", "KCg-d", "KCg-d"]),
            classes=np.array(["sensory", "visual_projection", "Kenyon_Cell", "Kenyon_Cell"]),
            neuropils=np.array(["ME", "LO", "MB", "MB"]),
            neurotransmitters=np.array(["histamine", "ACh", "ACh", "ACh"]),
            sides=np.array(["L", "L", "L", "R"]),
        ),
        SynapseTable(
            source_ids=np.array([1, 2, 2]),
            target_ids=np.array([2, 3, 4]),
            counts=np.array([8, 6, 4]),
        ),
    )


def test_visual_kenyon_component_selects_direct_strong_targets() -> None:
    component = visual_kenyon_component_spec(5)

    assert component.selector.resolve(_graph()).tolist() == [3]
    assert component.role.value == "memory"


def test_visual_kenyon_input_component_selects_matched_sources() -> None:
    component = visual_kenyon_input_component_spec(5)

    assert component.selector.resolve(_graph()).tolist() == [2]
    assert component.role.value == "processing"


def test_visual_kenyon_component_is_opt_in() -> None:
    default = build_first_visual_network(
        _graph(), minimum_synapse_count=5, component_names=("photoreceptors", "visual_projection")
    )
    expanded = build_first_visual_network(
        _graph(),
        minimum_synapse_count=5,
        component_names=("photoreceptors", "visual_projection", "visual_kenyon"),
    )

    assert [item.spec.name for item in default.components] == [
        "photoreceptors",
        "visual_projection",
    ]
    assert expanded.components[-1].spec.name == "visual_kenyon"
    assert expanded.components[-1].neuron_ids.tolist() == [3]
