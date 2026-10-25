import numpy as np
import pytest
from numpy.typing import NDArray

from fly_vision.adaptation import adapt

x: NDArray[np.float64] = np.random.default_rng(0).random((500, 3)) + 0.01
v: NDArray[np.bool_] = np.ones(500, bool)
v[:50] = False


def test_none_is_identity() -> None:
    assert np.array_equal(adapt(x, v, "none"), x)


def test_frame_mean_brightness_invariant() -> None:
    assert np.allclose(adapt(x, v, "frame_mean"), adapt(x * 0.01, v, "frame_mean"), atol=1e-6)


def test_frame_mean_range_and_invalid() -> None:
    y = adapt(x, v, "frame_mean")
    assert (y[v] >= 0).all()
    assert (y[v] < 1).all()
    assert (y[~v] == 0).all()


def test_uses_only_valid() -> None:
    x2 = x.copy()
    x2[~v] = 1e6
    assert np.allclose(adapt(x2, v, "frame_mean")[v], adapt(x, v, "frame_mean")[v])


def test_unknown_mode_raises() -> None:
    with pytest.raises(ValueError, match="unknown adaptation mode"):
        adapt(x, v, "frame_max")  # type: ignore[arg-type]
