# pyright: reportUnknownArgumentType=false, reportUnknownMemberType=false
# pyright: reportUnknownParameterType=false, reportUnknownVariableType=false
# pyright: reportMissingParameterType=false
import jax
import jax.numpy as jnp
import numpy as np

from fly_brain.connectome import (
    BiologicalStatus,
    BrainCompiler,
    BrainModelSpec,
    BrainNetwork,
    BrainParameters,
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
from fly_brain.connectome.dynamics import ActivationName


def graph() -> ConnectomeGraph:
    return ConnectomeGraph(
        ConnectomeRelease("fixture", "1", "12" * 32, "fixture://compiler", "CC0"),
        NeuronTable(
            neuron_ids=np.arange(1, 5),
            cell_types=np.array(["R7", "R8", "relay", "output"]),
            classes=np.array(["sensory", "sensory", "relay", "visual_projection"]),
            neuropils=np.array(["retina", "retina", "optic", "optic"]),
            neurotransmitters=np.array(["His", "His", "ACh", "ACh"]),
            sides=np.array(["L", "R", "L", "L"]),
        ),
        SynapseTable(
            source_ids=np.array([1, 2, 3]),
            target_ids=np.array([3, 3, 4]),
            counts=np.array([2, 6, 4]),
        ),
    )


def network(source: ConnectomeGraph) -> BrainNetwork:
    resolver = ComponentResolver(source)
    result = BrainNetwork(source)
    for name, ids, role in (
        ("sensory", (1, 2), ComponentRole.SENSORY),
        ("relay", (3,), ComponentRole.PROCESSING),
        ("output", (4,), ComponentRole.OUTPUT),
    ):
        result = result.with_component(
            resolver.resolve(
                ComponentSpec(
                    name,
                    ExplicitIds(ids),
                    role,
                    f"Fixture {name}",
                    BiologicalStatus.ANATOMICAL,
                )
            )
        )
    return result


def compiled_brain():
    source = graph()
    spec = BrainModelSpec(
        network(source),
        (
            InputPort(
                "receptors",
                np.array([1, 2]),
                InjectionRule.CLAMPED,
                BiologicalStatus.APPROXIMATION,
            ),
        ),
        OutputHead("localisation", np.array([4]), 2),
        LeakyRateDynamics(time_constant=1.0, activation="relu"),
    )
    return BrainCompiler(spec)


def test_compiler_plans_sparse_normalized_anatomy() -> None:
    compiler = compiled_brain()

    plan = compiler.plan()
    model = compiler.compile()

    assert plan.neuron_count == 4
    assert plan.edge_count == 3
    assert plan.input_width == 2
    assert plan.trainable_parameter_count == 9
    assert plan.disconnected_ports == ()
    np.testing.assert_allclose(model.anatomical_weights, [0.25, 0.75, 1.0])


def test_run_matches_repeated_steps_and_reset_is_deterministic() -> None:
    model = compiled_brain().compile()
    parameters = model.initialize_parameters(seed=3)
    initial = model.initialize_state(batch_size=2)
    inputs = jnp.asarray(
        [
            [[1.0, 0.0], [0.0, 1.0]],
            [[1.0, 0.0], [0.0, 1.0]],
            [[0.0, 0.0], [0.0, 0.0]],
        ]
    )

    run = model.run(parameters, initial, inputs)
    state = initial
    activities: list[jax.Array] = []
    outputs: list[jax.Array] = []
    for frame in inputs:
        result = model.step(parameters, state, frame)
        state = result.state
        activities.append(result.activity)
        outputs.append(result.output)

    np.testing.assert_allclose(run.final_state.activity, state.activity)
    np.testing.assert_allclose(run.activity, jnp.stack(activities))
    np.testing.assert_allclose(run.output, jnp.stack(outputs))
    repeated = model.run(parameters, model.initialize_state(2), inputs)
    np.testing.assert_allclose(run.output, repeated.output)


def test_compiled_execution_has_finite_nonzero_gradients() -> None:
    model = compiled_brain().compile()
    parameters = model.initialize_parameters(seed=4)
    inputs = jnp.ones((4, 1, 2), dtype=jnp.float32)

    def loss(candidate: BrainParameters):
        return jnp.square(model.run(candidate, model.initialize_state(1), inputs).output[-1]).sum()

    gradients = jax.grad(loss)(parameters)

    assert all(np.isfinite(np.asarray(value)).all() for value in gradients)
    assert float(jnp.linalg.norm(gradients.edge_gain)) > 0
    assert float(jnp.linalg.norm(gradients.readout_weights)) > 0


def test_compiler_rejects_neurons_outside_network() -> None:
    source = graph()
    spec = BrainModelSpec(
        network(source),
        (
            InputPort(
                "unknown",
                np.array([99]),
                InjectionRule.ADDITIVE,
                BiologicalStatus.APPROXIMATION,
            ),
        ),
        OutputHead("output", np.array([4]), 1),
    )

    with np.testing.assert_raises_regex(ValueError, "outside the network"):
        BrainCompiler(spec).plan()


def test_compiler_groups_and_bounds_edge_gains() -> None:
    base = compiled_brain().spec
    policy = SynapseCountPolicy(
        initial_edge_gain=1.0,
        train_neuron_bias=False,
        edge_gain_mode="cell_type_pair",
        maximum_edge_gain=2.0,
        use_neurotransmitter_signs=True,
    )
    compiler = BrainCompiler(
        BrainModelSpec(
            base.network,
            base.input_ports,
            base.output_head,
            base.dynamics,
            policy,
            base.dt,
        )
    )

    plan = compiler.plan()
    model = compiler.compile()
    parameters = model.initialize_parameters(seed=0)

    assert plan.trainable_parameter_count == 7
    assert model.edge_gain_count == 3
    np.testing.assert_allclose(parameters.edge_gain, np.zeros(3), atol=1e-6)
    np.testing.assert_allclose(model.anatomical_weights, [-0.25, -0.75, 1.0])


def _loop_graph() -> ConnectomeGraph:
    base = graph()
    return ConnectomeGraph(
        base.release,
        base.neurons,
        SynapseTable(
            source_ids=np.array([1, 2, 3, 4]),
            target_ids=np.array([3, 3, 4, 3]),
            # The 4 -> 3 edge carries 80% of relay input, so the loop gain is 0.8 * gain**2.
            counts=np.array([1, 1, 4, 8]),
        ),
    )


def _signed_compiler(
    source: ConnectomeGraph, activation: ActivationName, gain: float
) -> BrainCompiler:
    spec = BrainModelSpec(
        network(source),
        (
            InputPort(
                "receptors",
                np.array([1, 2]),
                InjectionRule.CLAMPED,
                BiologicalStatus.APPROXIMATION,
            ),
        ),
        OutputHead("localisation", np.array([4]), 2),
        LeakyRateDynamics(time_constant=2.0, activation=activation),
        SynapseCountPolicy(initial_edge_gain=gain, use_neurotransmitter_signs=True),
    )
    return BrainCompiler(spec)


def _settled_activity(compiler: BrainCompiler, receptor_value: float, steps: int) -> np.ndarray:
    model = compiler.compile()
    parameters = model.initialize_parameters(seed=0)
    inputs = jnp.full((steps, 1, 2), receptor_value, dtype=jnp.float32)
    return np.asarray(model.run(parameters, model.initialize_state(1), inputs).activity)


def test_graded_dynamics_carry_inhibition_that_zero_baseline_relu_loses() -> None:
    graded = _signed_compiler(graph(), "graded", 1.0)
    relu = _signed_compiler(graph(), "relu", 1.0)

    dim, bright = (_settled_activity(graded, value, 8)[-1, 0] for value in (0.5, 2.0))
    relu_dim, relu_bright = (_settled_activity(relu, value, 8)[-1, 0] for value in (0.5, 2.0))

    # Histaminergic receptors inhibit the relay: brighter input hyperpolarises it further.
    assert bright[2] < dim[2] < 0
    # The excitatory relay passes that difference on to the output neuron.
    assert bright[3] < dim[3]
    # Zero-baseline ReLU clips the inhibited relay to zero for both inputs.
    assert relu_dim[2] == relu_bright[2] == 0
    assert relu_dim[3] == relu_bright[3]


def test_graded_dynamics_stay_bounded_where_relu_loops_diverge() -> None:
    graded = _settled_activity(_signed_compiler(_loop_graph(), "graded", 1.59), 1.0, 400)
    relu_model = BrainCompiler(
        BrainModelSpec(
            network(_loop_graph()),
            (
                InputPort(
                    "receptors",
                    np.array([1, 2]),
                    InjectionRule.CLAMPED,
                    BiologicalStatus.APPROXIMATION,
                ),
            ),
            OutputHead("localisation", np.array([4]), 2),
            LeakyRateDynamics(time_constant=2.0, activation="relu"),
            SynapseCountPolicy(initial_edge_gain=1.59),
        )
    )
    relu = _settled_activity(relu_model, 1.0, 400)

    assert np.isfinite(graded).all()
    assert np.abs(graded[:, 0, 2:]).max() <= 0.5 * 1.59 + 1e-5
    assert relu[-1, 0, 2:].max() > 1e6
