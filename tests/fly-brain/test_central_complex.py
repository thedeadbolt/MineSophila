import numpy as np
import pytest

from fly_brain.connectome import (
    CENTRAL_COMPLEX_COMPONENTS,
    ConnectomeGraph,
    ConnectomeRelease,
    NeuronTable,
    SynapseTable,
    build_first_visual_network,
    central_complex_cell_types,
    central_complex_component_specs,
)


def _graph() -> ConnectomeGraph:
    types = ["R7d", "MeTu1", "LC10a", "TuBu01", "ER4d", "EPG", "Delta7", "PFL3", "FB5A"]
    classes = [
        "sensory",
        "visual_projection",
        "visual_projection",
        "",
        "CX",
        "CX",
        "CX",
        "CX",
        "CX",
    ]
    return ConnectomeGraph(
        ConnectomeRelease("fixture", "1", "56" * 32, "fixture://cx", "CC0"),
        NeuronTable(
            neuron_ids=np.arange(1, 10),
            cell_types=np.array(types),
            classes=np.array(classes),
            neuropils=np.array(["ME", "ME", "LO", "AOTU", "EB", "EB", "PB", "PB", "FB"]),
            neurotransmitters=np.array(
                ["histamine", "ACh", "ACh", "ACh", "GABA", "ACh", "glutamate", "ACh", "ACh"]
            ),
            sides=np.array(["L"] * 9),
        ),
        SynapseTable(
            source_ids=np.array([1, 2, 4, 5, 6, 7]),
            target_ids=np.array([2, 4, 5, 6, 8, 6]),
            counts=np.array([8, 9, 7, 6, 5, 5]),
        ),
    )


def test_central_complex_components_select_reviewed_cell_types() -> None:
    graph = _graph()
    specs = {spec.name: spec for spec in central_complex_component_specs()}

    assert tuple(specs) == CENTRAL_COMPLEX_COMPONENTS
    assert specs["anterior_visual_inputs"].selector.resolve(graph).tolist() == [2]
    assert specs["tubercle_bulb"].selector.resolve(graph).tolist() == [4]
    assert specs["ring_neurons"].selector.resolve(graph).tolist() == [5]
    assert specs["compass"].selector.resolve(graph).tolist() == [6, 7]
    assert specs["steering"].selector.resolve(graph).tolist() == [8]
    assert specs["compass"].role.value == "memory"
    assert specs["steering"].role.value == "output"


def test_central_complex_cell_types_cover_every_component_except_inputs() -> None:
    types = central_complex_cell_types()

    assert {"TuBu01", "ER4d", "EPG", "PEN_a(PEN1)", "Delta7", "PFL3"} <= set(types)
    assert "MeTu1" not in types
    assert "FB5A" not in types


def test_central_complex_components_are_opt_in_and_named() -> None:
    names = ("photoreceptors", "visual_projection", *CENTRAL_COMPLEX_COMPONENTS)

    network = build_first_visual_network(_graph(), minimum_synapse_count=5, component_names=names)

    assert [item.spec.name for item in network.components] == list(names)
    with pytest.raises(ValueError, match="unknown"):
        build_first_visual_network(_graph(), component_names=("photoreceptors", "fan_shaped"))


def test_fan_shaped_body_components_are_separate_opt_in_selections() -> None:
    from fly_brain.connectome import (
        FAN_SHAPED_BODY_COMPONENTS,
        fan_shaped_body_cell_types,
        fan_shaped_body_component_specs,
    )

    specs = {spec.name: spec for spec in fan_shaped_body_component_specs()}
    types = fan_shaped_body_cell_types()

    assert tuple(specs) == FAN_SHAPED_BODY_COMPONENTS
    assert {"FC2A", "FC2B", "FC2C", "hDeltaA", "hDeltaM", "PFNd", "PFNv"} <= set(types)
    assert not {"PFNp_a", "FB4L"} & set(types)
    assert not set(types) & set(central_complex_cell_types())
    assert specs["goal_memory"].role.value == "memory"
    # The names are known to the network builder (this fixture has no FB neurons to resolve).
    with pytest.raises(ValueError, match="resolved to no neurons"):
        build_first_visual_network(
            _graph(), component_names=("photoreceptors", "visual_projection", "goal_memory")
        )


def test_goal_tangentials_component_selects_fb5a() -> None:
    from fly_brain.connectome import FAN_SHAPED_BODY_COMPONENTS, fan_shaped_body_cell_types

    assert "FB5A" in fan_shaped_body_cell_types()
    assert "FB5A" not in central_complex_cell_types()
    assert FAN_SHAPED_BODY_COMPONENTS[-1] == "goal_tangentials"


def test_cell_types_without_fan_shaped_body_are_unchanged() -> None:
    from fly_brain.cli import _connectome_cell_types  # pyright: ignore[reportPrivateUsage]
    from fly_brain.connectome import FAN_SHAPED_BODY_COMPONENTS, fan_shaped_body_cell_types
    from fly_brain.models import ConnectomeModelConfig

    names = ("photoreceptors", "visual_projection", *CENTRAL_COMPLEX_COMPONENTS)
    plain = _connectome_cell_types(ConnectomeModelConfig(components=names))
    with_fb = _connectome_cell_types(
        ConnectomeModelConfig(components=(*names, *FAN_SHAPED_BODY_COMPONENTS))
    )

    assert not set(plain) & set(fan_shaped_body_cell_types())
    assert set(central_complex_cell_types()) <= set(plain)
    assert set(with_fb) == set(plain) | set(fan_shaped_body_cell_types())
