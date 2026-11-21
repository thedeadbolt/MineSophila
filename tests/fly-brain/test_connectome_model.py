from dataclasses import replace

import numpy as np

from fly_brain import Eye
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
    ReceptorInputMap,
    SynapseCountPolicy,
    SynapseTable,
)
from fly_brain.models import (
    ConnectomeBatch,
    ConnectomeLocalizationModel,
    ConnectomeTrainingConfig,
    ReceptorPoolingInputTransform,
    SpatialBatch,
    SpatialCoverageInputTransform,
    SpatialLocalizationModel,
    SpatialModelConfig,
    SpatialTopology,
    train_connectome,
)


def compiled_brain():
    source = ConnectomeGraph(
        ConnectomeRelease("fixture", "1", "12" * 32, "fixture://model", "CC0"),
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
    resolver = ComponentResolver(source)
    network = BrainNetwork(source)
    for name, ids, role in (
        ("sensory", (1, 2), ComponentRole.SENSORY),
        ("relay", (3,), ComponentRole.PROCESSING),
        ("output", (4,), ComponentRole.OUTPUT),
    ):
        network = network.with_component(
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
    return BrainCompiler(
        BrainModelSpec(
            network,
            (
                InputPort(
                    "receptors",
                    np.array([1, 2]),
                    InjectionRule.CLAMPED,
                    BiologicalStatus.APPROXIMATION,
                ),
            ),
            OutputHead("localisation", np.array([4]), 4),
            LeakyRateDynamics(time_constant=1.0, activation="relu"),
        )
    )


def test_connectome_readout_learns_presence() -> None:
    compiled = compiled_brain().compile()
    compiled = replace(
        compiled,
        parameter_policy=SynapseCountPolicy(initial_edge_gain=1.0, train_neuron_bias=False),
    )
    values = np.array(
        [[0.0, 0.0], [1.0, 0.0], [0.0, 1.0], [1.0, 1.0]] * 8,
        dtype=np.float32,
    )
    presence = np.any(values > 0, axis=1).astype(np.float32)
    direction = np.zeros((values.shape[0], 3), dtype=np.float32)
    direction[presence > 0] = [1.0, 0.0, 0.0]
    batch = ConnectomeBatch(values, presence, direction)
    model = ConnectomeLocalizationModel(compiled, settling_steps=3)
    config = ConnectomeTrainingConfig(
        steps=200,
        batch_size=32,
        learning_rate=0.02,
        weight_decay=0.0,
        direction_loss_weight=0.1,
        validation_interval=50,
        early_stopping_patience=4,
    )

    result = train_connectome(model, batch, batch, config)
    prediction = model.predict(result.parameters, batch, batch_size=32)

    assert result.losses[-1] < result.losses[0] * 0.5
    np.testing.assert_array_equal(prediction.presence_probability >= 0.5, presence > 0)
    assert prediction.coverage is None


def test_spatial_coverage_transform_maps_frozen_predictions() -> None:
    spatial_model = SpatialLocalizationModel(
        3,
        2,
        SpatialModelConfig(hidden_width=2, message_passing_layers=0),
    )
    spatial_parameters = spatial_model.initialize(seed=2)
    topology = SpatialTopology(
        np.array([[1], [0]], dtype=np.int32),
        np.ones((2, 1), dtype=np.bool_),
    )
    input_map = ReceptorInputMap(
        "layout",
        "12" * 32,
        np.arange(10, 16, dtype=np.int64),
        np.arange(6, dtype=np.int32),
        np.arange(6, dtype=np.int32),
        np.ones(6, dtype=np.float32),
        np.array([100, 200], dtype=np.int64),
        ("R1_6", "R7", "R8"),
        6,
    )
    batch = SpatialBatch(
        np.ones((1, 2, 3), dtype=np.float32),
        np.ones(2, dtype=np.bool_),
        np.zeros((1, 2), dtype=np.float32),
        np.array([[1.0, 0.0, 0.0], [0.0, 1.0, 0.0]], dtype=np.float32),
    )
    transform = SpatialCoverageInputTransform(
        spatial_model, spatial_parameters, topology, input_map
    )

    values = transform.transform(batch)

    assert values.shape == (1, 6)
    assert np.isfinite(values).all()
    np.testing.assert_allclose(values[:, 0:3], np.repeat(values[:, 0:1], 3, axis=1))
    np.testing.assert_allclose(values[:, 3:6], np.repeat(values[:, 3:4], 3, axis=1))
    assert transform.parameter_count == 22


def test_receptor_pooling_is_scoped_to_each_eye() -> None:
    input_map = ReceptorInputMap(
        "layout",
        "12" * 32,
        np.arange(10, 22, dtype=np.int64),
        np.arange(12, dtype=np.int32),
        np.arange(12, dtype=np.int32),
        np.ones(12, dtype=np.float32),
        np.array([100, 101, 200, 201], dtype=np.int64),
        ("R1_6", "R7", "R8"),
        12,
    )
    signals = np.repeat(
        np.array([0.0, 2.0, 10.0, 14.0], dtype=np.float32)[None, :, None],
        3,
        axis=2,
    )
    batch = SpatialBatch(
        signals,
        np.ones(4, dtype=np.bool_),
        np.zeros((1, 4), dtype=np.float32),
        np.array(
            [
                [1.0, 0.0, 0.0],
                [0.0, 1.0, 0.0],
                [-1.0, 0.0, 0.0],
                [0.0, -1.0, 0.0],
            ],
            dtype=np.float32,
        ),
    )
    transform = ReceptorPoolingInputTransform(
        "per_eye_mean",
        (Eye.LEFT, Eye.LEFT, Eye.RIGHT, Eye.RIGHT),
        input_map,
    )

    values = transform.transform(batch).reshape(1, 4, 3)

    np.testing.assert_allclose(values[:, :2], 1.0)
    np.testing.assert_allclose(values[:, 2:], 12.0)
