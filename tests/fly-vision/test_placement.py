import numpy as np
import pytest

from fly_vision.placement import angular_size_deg, perspective_fov_for_object
from fly_vision.projection import PerspectiveSpec


def test_angular_size() -> None:
    assert np.isclose(angular_size_deg(1.0, 5.0), 11.421186, atol=1e-6)  # 1 m block at 5 m


def test_fov_for_object() -> None:
    fov = perspective_fov_for_object(1000, 200, 1.0, 5.0)
    assert np.isclose(fov, 53.130102, atol=1e-6)  # F = 1000 px
    p = PerspectiveSpec(1000, 801, fov)
    r = p.rays()
    span = np.degrees(np.arccos(r[400, 400] @ r[400, 599]))  # 200-px object, centred
    assert np.isclose(span, angular_size_deg(1.0, 5.0), atol=0.1)


def test_placement_validation() -> None:
    for args in (
        (0, 200, 1.0, 5.0),
        (1000, 0, 1.0, 5.0),
        (1000, 200, -1.0, 5.0),
        (1000, 200, 1.0, 0.0),
    ):
        with pytest.raises(ValueError, match="must be"):
            perspective_fov_for_object(*args)
