import numpy as np
import pytest

from fly_brain import (
    Availability,
    BrainExample,
    BrainTargets,
    FeatureBlock,
    FeatureSchema,
    FeatureSpec,
    ObservationSequence,
    PrivilegedState,
    Provenance,
    SensorLayout,
)


def privileged_state(time_steps: int) -> PrivilegedState:
    return PrivilegedState(
        FeatureBlock(
            values=np.zeros((time_steps, 3), dtype=np.float32),
            schema=FeatureSchema(
                tuple(
                    FeatureSpec(
                        name=name,
                        units="blocks",
                        description="True honey coordinate",
                        source="honey-factory true state",
                        availability=Availability.PRIVILEGED,
                    )
                    for name in ("honey_x", "honey_y", "honey_z")
                )
            ),
        )
    )


def test_example_keeps_privileged_state_outside_observation(
    sensor_layout: SensorLayout,
) -> None:
    observation = ObservationSequence.from_frame(
        sensor_layout,
        receptor_signals=np.ones((4, 3), dtype=np.float32),
        receptor_valid=np.ones(4, dtype=np.bool_),
        timestamp_s=0.0,
    )
    example = BrainExample(
        observation=observation,
        targets=BrainTargets(
            time_mask=np.array([True]),
            honey_present=np.array([True]),
            honey_coverage=np.array([[0.0, 0.2, 0.8, 0.0]], dtype=np.float32),
            honey_direction=np.array([[0.0, 1.0, 0.0]], dtype=np.float32),
        ),
        provenance=Provenance(
            dataset_id="dataset",
            scene_id="scene",
            frame_ids=("frame",),
            sensor_config_hash=sensor_layout.config_hash,
        ),
        privileged_state=privileged_state(1),
    )

    assert example.observation.context is None
    assert example.privileged_state is not None
    assert example.targets.honey_coverage is not None
    assert example.targets.honey_coverage.shape == (1, 4)


def test_example_rejects_mismatched_sensor_hash(sensor_layout: SensorLayout) -> None:
    observation = ObservationSequence.from_frame(
        sensor_layout,
        receptor_signals=np.ones((4, 3), dtype=np.float32),
        receptor_valid=np.ones(4, dtype=np.bool_),
        timestamp_s=0.0,
    )

    with pytest.raises(ValueError, match="sensor hash"):
        BrainExample(
            observation=observation,
            targets=BrainTargets(time_mask=np.array([True])),
            provenance=Provenance(
                dataset_id="dataset",
                scene_id="scene",
                frame_ids=("frame",),
                sensor_config_hash="other-hash",
            ),
        )


def test_targets_reject_coverage_outside_unit_interval() -> None:
    with pytest.raises(ValueError, match=r"within \[0, 1\]"):
        BrainTargets(
            time_mask=np.array([True]),
            honey_coverage=np.array([[1.1]], dtype=np.float32),
        )
