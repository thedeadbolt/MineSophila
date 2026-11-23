# pyright: reportUnknownMemberType=false, reportUnknownVariableType=false
import numpy as np

from fly_brain.models import (
    SpatialBatch,
    SpatialLocalizationModel,
    SpatialModelConfig,
    SpatialTrainingConfig,
    build_angular_topology,
    train_spatial,
)


def test_angular_topology_keeps_compound_eyes_separate() -> None:
    directions = np.array(
        [[1.0, 0.0, 0.0], [0.99, 0.1, 0.0], [1.0, 0.0, 0.0], [0.99, -0.1, 0.0]],
        dtype=np.float32,
    )
    eyes = np.array(["L", "L", "R", "R"])

    topology = build_angular_topology(directions, eyes, neighbor_count=1, radius_deg=10.0)

    np.testing.assert_array_equal(topology.neighbors[:, 0], [1, 0, 3, 2])
    assert topology.neighbor_valid.all()


def test_spatial_model_learns_local_coverage_and_presence() -> None:
    directions = np.array(
        [
            [1.0, 0.0, 0.0],
            [0.5, 0.866, 0.0],
            [-0.5, 0.866, 0.0],
            [-1.0, 0.0, 0.0],
            [-0.5, -0.866, 0.0],
            [0.5, -0.866, 0.0],
        ],
        dtype=np.float32,
    )
    directions /= np.linalg.norm(directions, axis=1, keepdims=True)
    signals = np.zeros((24, 6, 3), dtype=np.float32)
    coverage = np.zeros((24, 6), dtype=np.float32)
    for row in range(18):
        site = row % 6
        signals[row, site] = 1.0
        coverage[row, site] = 1.0
    batch = SpatialBatch(
        signals=signals,
        valid=np.ones(6, dtype=np.bool_),
        coverage=coverage,
        directions=directions,
    )
    model_config = SpatialModelConfig(
        hidden_width=8,
        message_passing_layers=1,
        neighbor_count=2,
        neighbor_radius_deg=70.0,
        include_directions=False,
        presence_pooling="hidden_mean_max",
        pooling_temperature=0.5,
    )
    training_config = SpatialTrainingConfig(
        steps=300,
        batch_size=24,
        learning_rate=0.01,
        weight_decay=0.0,
        validation_interval=50,
        early_stopping_patience=6,
        threshold_search_steps=5,
    )
    topology = build_angular_topology(
        directions,
        np.full(6, "L"),
        neighbor_count=model_config.neighbor_count,
        radius_deg=model_config.neighbor_radius_deg,
    )
    model = SpatialLocalizationModel(3, 6, model_config)

    result = train_spatial(model, batch, batch, topology, training_config)
    prediction = model.predict(result.parameters, batch, topology, batch_size=24)

    assert result.losses[-1] < result.losses[0] * 0.25
    np.testing.assert_array_equal(prediction.presence_probability >= 0.5, batch.presence > 0)
    assert prediction.coverage is not None
    np.testing.assert_array_equal(np.argmax(prediction.coverage[:18], axis=1), np.arange(18) % 6)


def test_spatial_settings_reject_invalid_values() -> None:
    with np.testing.assert_raises_regex(ValueError, "threshold search bounds"):
        SpatialTrainingConfig(threshold_search_min=0.9, threshold_search_max=0.1)
    with np.testing.assert_raises_regex(ValueError, "unsupported activation"):
        SpatialModelConfig(activation="unknown")  # type: ignore[arg-type]
    with np.testing.assert_raises_regex(ValueError, "unsupported presence pooling"):
        SpatialModelConfig(presence_pooling="unknown")  # type: ignore[arg-type]


def test_spatial_loss_gradient_is_finite_when_coverage_underflows() -> None:
    import jax
    import jax.numpy as jnp

    from fly_brain.models import spatial

    directions = np.eye(3, dtype=np.float32)[[0, 1, 2, 0]]
    model_config = SpatialModelConfig(hidden_width=4, message_passing_layers=1, neighbor_count=1)
    model = SpatialLocalizationModel(3, 4, model_config)
    topology = build_angular_topology(
        directions, np.full(4, "L"), neighbor_count=1, radius_deg=180.0
    )
    # An extreme output bias drives every site's coverage to exactly zero in float32, so the
    # predicted direction vector is exactly zero (a negative sample whose coverage underflowed).
    parameters = model.initialize(0)._replace(output_bias=jnp.full((1,), -200.0))
    signals = jnp.zeros((2, 4, 3))
    target = jnp.zeros((2, 4)).at[0, 1].set(1.0)

    def loss(p: spatial.SpatialParameters) -> jax.Array:
        return spatial._loss(  # pyright: ignore[reportPrivateUsage]
            p,
            signals,
            target,
            valid=jnp.ones(4, dtype=bool),
            directions=jnp.asarray(directions),
            neighbors=jnp.asarray(topology.neighbors),
            neighbor_valid=jnp.asarray(topology.neighbor_valid),
            model_config=model_config,
            training_config=SpatialTrainingConfig(),
        )

    gradients = jax.grad(loss)(parameters)

    assert all(bool(jnp.isfinite(leaf).all()) for leaf in jax.tree.leaves(gradients))
