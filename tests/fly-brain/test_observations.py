import numpy as np
import pytest

from fly_brain import (
    Availability,
    FeatureBlock,
    FeatureSchema,
    FeatureSpec,
    ObservationSequence,
    SensorLayout,
)


def feature_block(availability: Availability, time_steps: int) -> FeatureBlock:
    return FeatureBlock(
        values=np.zeros((time_steps, 1), dtype=np.float32),
        schema=FeatureSchema(
            (
                FeatureSpec(
                    name="turn_rate",
                    units="degrees/tick",
                    description="Previous executed turn rate",
                    source="honey-factory",
                    availability=availability,
                ),
            )
        ),
    )


def test_still_frame_uses_the_sequence_contract(sensor_layout: SensorLayout) -> None:
    observation = ObservationSequence.from_frame(
        sensor_layout,
        receptor_signals=np.ones((4, 3), dtype=np.float32),
        receptor_valid=np.array([True, True, False, True]),
        timestamp_s=1.5,
        context=feature_block(Availability.AGENT, 1),
    )

    assert observation.is_still
    assert observation.receptor_signals.shape == (1, 4, 3)
    assert observation.context is not None
    assert observation.context.time_steps == 1


def test_video_requires_increasing_timestamps(sensor_layout: SensorLayout) -> None:
    with pytest.raises(ValueError, match="strictly increasing"):
        ObservationSequence(
            layout=sensor_layout,
            receptor_signals=np.ones((2, 4, 3), dtype=np.float32),
            receptor_valid=np.ones((2, 4), dtype=np.bool_),
            timestamps_s=np.array([0.0, 0.0]),
        )


def test_observation_rejects_teacher_context(sensor_layout: SensorLayout) -> None:
    with pytest.raises(ValueError, match="availability 'agent'"):
        ObservationSequence.from_frame(
            sensor_layout,
            receptor_signals=np.ones((4, 3), dtype=np.float32),
            receptor_valid=np.ones(4, dtype=np.bool_),
            timestamp_s=0.0,
            context=feature_block(Availability.TEACHER, 1),
        )


def test_video_requires_context_alignment(sensor_layout: SensorLayout) -> None:
    with pytest.raises(ValueError, match="context must contain 2"):
        ObservationSequence(
            layout=sensor_layout,
            receptor_signals=np.ones((2, 4, 3), dtype=np.float32),
            receptor_valid=np.ones((2, 4), dtype=np.bool_),
            timestamps_s=np.array([0.0, 0.05]),
            context=feature_block(Availability.AGENT, 1),
        )
