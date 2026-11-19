import numpy as np
import pytest

from fly_brain import Eye, SensorLayout


def test_layout_requires_unit_directions() -> None:
    with pytest.raises(ValueError, match="unit vectors"):
        SensorLayout(
            layout_id="bad",
            site_ids=np.array([1]),
            eyes=(Eye.LEFT,),
            directions=np.array([[2.0, 0.0, 0.0]]),
            ommatidium_subtypes=("pale",),
            channel_names=("R1_6", "R7", "R8"),
            model_version="v1",
            geometry_hash="geometry",
            config_hash="hash",
        )


def test_layout_owns_immutable_array_copies() -> None:
    site_ids = np.array([1, 2])
    layout = SensorLayout(
        layout_id="copy",
        site_ids=site_ids,
        eyes=(Eye.LEFT, Eye.RIGHT),
        directions=np.array([[1.0, 0.0, 0.0], [-1.0, 0.0, 0.0]]),
        ommatidium_subtypes=("pale", "yellow"),
        channel_names=("R1_6", "R7", "R8"),
        model_version="v1",
        geometry_hash="geometry",
        config_hash="hash",
    )
    site_ids[0] = 99

    assert layout.site_ids.tolist() == [1, 2]
    assert not layout.site_ids.flags.writeable
