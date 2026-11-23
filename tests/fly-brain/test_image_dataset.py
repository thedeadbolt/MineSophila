import numpy as np
import pytest

from fly_brain.datasets import downsample_panorama


def test_downsample_panorama_area_averages_and_flattens() -> None:
    panorama = np.arange(4 * 4 * 3, dtype=np.uint8).reshape(4, 4, 3)

    result = downsample_panorama(panorama, 2)

    expected = panorama.reshape(2, 2, 2, 2, 3).mean(axis=(1, 3), dtype=np.float32) / 255.0
    np.testing.assert_allclose(result, expected.reshape(-1))
    assert result.dtype == np.float32


def test_downsample_panorama_rejects_incompatible_shape() -> None:
    with pytest.raises(ValueError, match="divisible"):
        downsample_panorama(np.zeros((3, 4, 3), dtype=np.uint8), 2)
