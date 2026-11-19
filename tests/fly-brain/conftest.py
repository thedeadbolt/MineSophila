import numpy as np
import pytest

from fly_brain import Eye, SensorLayout


@pytest.fixture
def sensor_layout() -> SensorLayout:
    return SensorLayout(
        layout_id="fixture-eye-v1",
        site_ids=np.array([10, 11, 20, 21]),
        eyes=(Eye.LEFT, Eye.LEFT, Eye.RIGHT, Eye.RIGHT),
        directions=np.array(
            [
                [1.0, 0.0, 0.0],
                [0.0, 1.0, 0.0],
                [-1.0, 0.0, 0.0],
                [0.0, -1.0, 0.0],
            ],
            dtype=np.float32,
        ),
        ommatidium_subtypes=("pale", "yellow", "pale", "yellow"),
        channel_names=("R1_6", "R7", "R8"),
        model_version="fixture-1",
        geometry_hash="fixture-geometry-hash",
        config_hash="fixture-config-hash",
    )
