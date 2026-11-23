# pyright: reportUnknownMemberType=false
import jax.numpy as jnp
import numpy as np

from fly_brain.connectome import (
    BiologicalStatus,
    BrainCompiler,
    BrainModelSpec,
    BrainNetwork,
    ComponentResolver,
    ComponentRole,
    ComponentSpec,
    ConnectomeGraph,
    ConnectomeRelease,
    ExplicitIds,
    InjectionRule,
    InputPort,
    LeakyRateDynamics,
    NeuronTable,
    OutputHead,
    SynapseCountPolicy,
    SynapseTable,
)
from fly_brain.connectome.subcircuit import SubCircuit, extract_subcircuit, run_subcircuit


def fixture_brain():
    graph = ConnectomeGraph(
        ConnectomeRelease("fixture", "1", "78" * 32, "fixture://subcircuit", "CC0"),
        NeuronTable(
            neuron_ids=np.arange(1, 5),
            cell_types=np.array(["A", "A", "B", "C"]),
            classes=np.array(["x", "x", "x", "x"]),
            neuropils=np.array(["n", "n", "n", "n"]),
            neurotransmitters=np.array(["ACh", "ACh", "ACh", "ACh"]),
            sides=np.array(["L", "R", "L", "L"]),
        ),
        SynapseTable(
            source_ids=np.array([1, 1, 3, 3, 4]),
            target_ids=np.array([2, 3, 2, 4, 1]),
            counts=np.array([5, 5, 5, 5, 5]),
        ),
    )
    resolver = ComponentResolver(graph)
    network = BrainNetwork(graph).with_component(
        resolver.resolve(
            ComponentSpec(
                "all",
                ExplicitIds((1, 2, 3, 4)),
                ComponentRole.PROCESSING,
                "all",
                BiologicalStatus.ANATOMICAL,
            )
        )
    )
    spec = BrainModelSpec(
        network,
        (InputPort("in", np.array([1]), InjectionRule.ADDITIVE, BiologicalStatus.APPROXIMATION),),
        OutputHead("out", np.array([4]), 2),
        LeakyRateDynamics(time_constant=2.0, activation="graded"),
        SynapseCountPolicy(edge_gain_mode="cell_type_pair", maximum_edge_gain=4.0),
    )
    return BrainCompiler(spec).compile(), graph.neurons.cell_types


def test_extract_keeps_only_internal_edges_and_names_pairs() -> None:
    brain, types = fixture_brain()

    circuit = extract_subcircuit(brain, types, ("A", "B"))

    assert circuit.neuron_index.tolist() == [0, 1, 2]
    assert circuit.source.size == 3
    assert circuit.group_pairs == ("A>A", "A>B", "B>A")
    assert circuit.cell_types.tolist() == ["A", "A", "B"]
    assert not circuit.gate.any()
    assert circuit.leak == 0.5
    assert circuit.maximum_gain == 4.0


def test_run_matches_a_hand_rolled_two_neuron_chain() -> None:
    circuit = SubCircuit(
        cell_types=np.array(["A", "B"]),
        neuron_index=np.array([0, 1]),
        source=np.array([0], dtype=np.int32),
        target=np.array([1], dtype=np.int32),
        group=np.array([0], dtype=np.int32),
        weight=np.array([0.5], dtype=np.float32),
        group_pairs=("A>B",),
        global_groups=np.array([0]),
        gate=np.array([False, False]),
        leak=0.5,
        maximum_gain=4.0,
    )
    external = np.zeros((1, 2, 2), dtype=np.float32)
    external[:, :, 0] = 1.0

    states = np.asarray(
        run_subcircuit(
            circuit,
            jnp.zeros(1),  # raw 0 -> gain 4 * sigmoid(0) = 2
            jnp.zeros(2),
            jnp.zeros(2),
            jnp.asarray(external),
            transitions=1,
            sharpness=20.0,
        )
    )

    np.testing.assert_allclose(states[0, 0], [0.5, 0.0], atol=1e-6)
    np.testing.assert_allclose(states[0, 1], [0.75, 0.25 * np.tanh(1.0)], atol=1e-6)
