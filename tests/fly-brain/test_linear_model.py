from dataclasses import replace

import numpy as np

from fly_brain.contracts import (
    BrainExample,
    BrainTargets,
    ObservationSequence,
    Provenance,
    SensorLayout,
)
from fly_brain.models import LinearLocalizationModel, prepare_localization_batch, train_linear
from fly_brain.tasks import evaluate_localization


def example(value: float, present: bool, layout: SensorLayout, index: int) -> BrainExample:
    direction = np.array([[1.0, 0.0, 0.0] if present else [0.0, 0.0, 0.0]], dtype=np.float32)
    coverage = np.full((1, layout.site_count), float(present), dtype=np.float32)
    return BrainExample(
        observation=ObservationSequence.from_frame(
            layout,
            np.full((layout.site_count, layout.channel_count), value, dtype=np.float32),
            np.ones(layout.site_count, dtype=np.bool_),
            float(index),
        ),
        targets=BrainTargets(
            time_mask=np.array([True]),
            honey_present=np.array([present]),
            honey_direction=direction,
            honey_coverage=coverage,
        ),
        provenance=Provenance(
            "synthetic",
            f"scene-{index}",
            (f"frame-{index}",),
            layout.config_hash,
        ),
    )


def test_linear_baseline_learns_separable_synthetic_localisation(
    sensor_layout: SensorLayout,
) -> None:
    examples = [
        example(value, present, sensor_layout, index)
        for index, (value, present) in enumerate(
            [(-1.0, False), (-0.8, False), (-0.6, False), (0.6, True), (0.8, True), (1.0, True)]
        )
    ]
    batch = prepare_localization_batch(examples)
    model = LinearLocalizationModel(batch.input_width, batch.site_count)

    result = train_linear(model, batch, steps=300, learning_rate=0.1, seed=4)

    assert result.losses[-1] < result.losses[0] * 0.3
    for item in examples:
        prediction = model.predict(result.parameters, item.observation)
        metrics = evaluate_localization(prediction, item.targets)
        assert metrics.presence_accuracy == 1.0
        if item.targets.honey_present is not None and item.targets.honey_present[0]:
            assert metrics.mean_bearing_error_deg is not None
            assert metrics.mean_bearing_error_deg < 5.0


def test_invalid_receptors_are_zeroed_but_their_mask_remains_visible(
    sensor_layout: SensorLayout,
) -> None:
    item = example(1.0, True, sensor_layout, 0)
    invalid = replace(
        item,
        observation=ObservationSequence.from_frame(
            sensor_layout,
            np.ones((sensor_layout.site_count, sensor_layout.channel_count), dtype=np.float32),
            np.array([True, False, True, False]),
            0.0,
        ),
    )

    batch = prepare_localization_batch([invalid])
    receptor_width = sensor_layout.site_count * sensor_layout.channel_count

    assert np.count_nonzero(batch.features[0, :receptor_width]) == 6
    assert batch.features[0, receptor_width:].tolist() == [1.0, 0.0, 1.0, 0.0]


def test_linear_baseline_can_omit_large_coverage_head(sensor_layout: SensorLayout) -> None:
    item = example(1.0, True, sensor_layout, 0)
    batch = prepare_localization_batch([item])
    model = LinearLocalizationModel(batch.input_width, batch.site_count, predict_coverage=False)

    prediction = model.predict(model.initialize(seed=0), item.observation)

    assert model.output_width == 4
    assert prediction.coverage is None
