import numpy as np
import pytest

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
from fly_brain.models import (
    ConnectomeClipModel,
    ConnectomeClipTrainingConfig,
    ConnectomeModelConfig,
    TemporalBatch,
    self_motion_port_values,
    train_connectome_clips,
)


def compiled_brain():
    source = ConnectomeGraph(
        ConnectomeRelease("fixture", "1", "78" * 32, "fixture://clips", "CC0"),
        NeuronTable(
            neuron_ids=np.arange(1, 6),
            cell_types=np.array(["R7", "R8", "relay", "ring", "output"]),
            classes=np.array(["sensory", "sensory", "relay", "CX", "visual_projection"]),
            neuropils=np.array(["retina", "retina", "optic", "EB", "optic"]),
            neurotransmitters=np.array(["ACh", "ACh", "ACh", "ACh", "ACh"]),
            sides=np.array(["L", "R", "L", "L", "L"]),
        ),
        SynapseTable(
            source_ids=np.array([1, 2, 3, 4, 4]),
            target_ids=np.array([3, 3, 4, 4, 5]),
            counts=np.array([2, 6, 4, 6, 4]),
        ),
    )
    resolver = ComponentResolver(source)
    network = BrainNetwork(source)
    for name, ids, role in (
        ("sensory", (1, 2), ComponentRole.SENSORY),
        ("relay", (3, 4), ComponentRole.PROCESSING),
        ("output", (5,), ComponentRole.OUTPUT),
    ):
        network = network.with_component(
            resolver.resolve(
                ComponentSpec(name, ExplicitIds(ids), role, name, BiologicalStatus.ANATOMICAL)
            )
        )
    return BrainCompiler(
        BrainModelSpec(
            network,
            (
                InputPort(
                    "receptors", np.array([1, 2]), InjectionRule.CLAMPED, BiologicalStatus.LEARNED
                ),
            ),
            OutputHead("localisation", np.array([4, 5]), 4),
            LeakyRateDynamics(time_constant=2.0, activation="graded"),
            SynapseCountPolicy(
                initial_edge_gain=1.0,
                train_neuron_bias=False,
                edge_gain_mode="cell_type_pair",
                maximum_edge_gain=4.0,
            ),
        )
    ).compile()


def clips(rows: int = 6, steps: int = 7) -> TemporalBatch:
    rng = np.random.default_rng(0)
    features = np.zeros((rows, steps, 2), dtype=np.float32)
    presence = np.zeros((rows, steps), dtype=np.float32)
    direction = np.zeros((rows, steps, 3), dtype=np.float32)
    for row in range(rows):
        if row % 2 == 0:
            features[row, :, 0] = rng.uniform(0.5, 1.0, steps)
            presence[row] = 1.0
            direction[row] = [1.0, 0.0, 0.0]
    mask = np.ones((rows, steps), dtype=np.bool_)
    mask[1, 5:] = False
    return TemporalBatch(features, mask, presence, direction)


def test_clip_model_truncation_changes_gradients_not_predictions() -> None:
    model = ConnectomeClipModel(compiled_brain(), transitions_per_frame=2)
    parameters = model.initialize(seed=1)
    batch = clips()

    whole = model.run(parameters, batch.features, batch.time_mask)
    chunked = model.run(parameters, batch.features, batch.time_mask, truncation_steps=3)

    assert whole.presence_probability.shape == batch.time_mask.shape
    np.testing.assert_allclose(chunked.presence_probability, whole.presence_probability, rtol=1e-6)
    np.testing.assert_allclose(chunked.direction, whole.direction, atol=1e-6)


def test_clip_model_carries_state_across_frames() -> None:
    model = ConnectomeClipModel(compiled_brain(), transitions_per_frame=1)
    parameters = model.initialize(seed=1)
    features = np.zeros((1, 4, 2), dtype=np.float32)
    features[0, 0, 0] = 1.0
    mask = np.ones((1, 4), dtype=np.bool_)

    pulse = model.run(parameters, features, mask)
    silent = model.run(parameters, np.zeros_like(features), mask)

    # A pulse on the first frame still changes the output three frames later.
    assert not np.allclose(pulse.direction[0, 3], silent.direction[0, 3])


def test_clip_readout_normalization_folds_into_plain_parameters() -> None:
    model = ConnectomeClipModel(compiled_brain(), transitions_per_frame=2)
    parameters = model.initialize(seed=2)
    batch = clips()

    normalized = model.with_readout_normalization(parameters, batch.features, batch.time_mask)
    expected = normalized.run(parameters, batch.features, batch.time_mask)
    actual = model.run(
        normalized.fold_readout_normalization(parameters), batch.features, batch.time_mask
    )

    np.testing.assert_allclose(
        actual.presence_probability, expected.presence_probability, rtol=1e-5
    )


def test_clip_training_reduces_loss_and_rejects_bad_settings() -> None:
    model = ConnectomeClipModel(compiled_brain(), transitions_per_frame=2)
    batch = clips()
    config = ConnectomeClipTrainingConfig(
        steps=60, batch_size=4, learning_rate=0.05, weight_decay=0.0, truncation_steps=4, seed=0
    )

    reports: list[tuple[int, float]] = []

    result = train_connectome_clips(
        model,
        batch,
        config,
        progress=lambda step, loss: reports.append((step, loss)),
        progress_interval=25,
    )

    assert [step for step, _ in reports] == [25, 50, 60]
    assert np.mean(result.losses[-10:]) < np.mean(result.losses[:10])
    assert np.isfinite(result.losses).all()
    with pytest.raises(ValueError, match="truncation"):
        ConnectomeClipTrainingConfig(truncation_steps=0)


def test_self_motion_port_values_sign_yaw_by_brain_side() -> None:
    context = np.zeros((1, 2, 4), dtype=np.float32)
    context[0, :, 3] = [10.0, -5.0]
    signs = np.array([1.0, -1.0, 1.0], dtype=np.float32)

    values = self_motion_port_values(context, yaw_index=3, signs=signs, scale=0.1)

    np.testing.assert_allclose(values[0], [[1.0, -1.0, 1.0], [-0.5, 0.5, -0.5]])
    with pytest.raises(ValueError, match="yaw"):
        self_motion_port_values(context, yaw_index=4, signs=signs, scale=0.1)


def test_self_motion_scale_is_configurable_and_positive() -> None:
    assert ConnectomeModelConfig().self_motion_scale == 0.1
    assert ConnectomeModelConfig(self_motion_scale=0.002).self_motion_scale == 0.002
    for bad in (0.0, -1.0, float("nan")):
        with pytest.raises(ValueError, match="self_motion_scale"):
            ConnectomeModelConfig(self_motion_scale=bad)
