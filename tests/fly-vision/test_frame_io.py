import gc
import warnings
from collections.abc import Callable
from pathlib import Path
from typing import Any

import numpy as np
import pytest
from PIL import Image

from fly_vision.frame import load_frame, load_labels, load_mask, load_panorama
from fly_vision.lattice import load_lattice


def _truncated_npz(path: Path) -> Path:
    np.savez(path, a=np.arange(1000))
    path.write_bytes(path.read_bytes()[:50])
    return path


def _assert_no_leak(load: Callable[[Path], Any], path: Path) -> None:
    with warnings.catch_warnings():
        warnings.simplefilter("error")
        with pytest.raises(Exception):  # noqa: B017, PT011 - any corrupt-zip error
            load(path)
        gc.collect()


def test_load_frame_closes_file_when_corrupt(tmp_path: Path) -> None:
    _assert_no_leak(load_frame, _truncated_npz(tmp_path / "f.npz"))


def test_load_labels_closes_file_when_corrupt(tmp_path: Path) -> None:
    _assert_no_leak(load_labels, _truncated_npz(tmp_path / "l.npz"))


def test_load_lattice_closes_file_when_corrupt(tmp_path: Path) -> None:
    _assert_no_leak(load_lattice, _truncated_npz(tmp_path / "x.npz"))


def _oriented(path: Path, im: Image.Image, orientation: int, **kw: Any) -> None:
    exif = Image.Exif()
    exif[0x0112] = orientation
    im.save(path, exif=exif, **kw)


def test_load_panorama_applies_exif_orientation(tmp_path: Path) -> None:
    arr = np.zeros((300, 400, 3), np.uint8)  # stored 400 wide x 300 tall
    arr[:] = (10, 10, 10)
    arr[-1, 0] = (255, 0, 0)  # stored bottom-left
    arr[0, 0] = (0, 255, 0)  # stored top-left
    _oriented(tmp_path / "o6.png", Image.fromarray(arr), 6)
    out = load_panorama(tmp_path / "o6.png")
    assert out.shape == (400, 300, 3)
    # orientation 6 rotates the stored image 90 degrees clockwise: bottom-left -> top-left
    assert tuple(out[0, 0]) == (255, 0, 0)
    assert tuple(out[0, -1]) == (0, 255, 0)


def test_load_panorama_orientation_jpeg_and_flip(tmp_path: Path) -> None:
    arr = np.full((300, 400, 3), 128, np.uint8)
    _oriented(tmp_path / "o6.jpg", Image.fromarray(arr), 6, quality=95)
    assert load_panorama(tmp_path / "o6.jpg").shape == (400, 300, 3)
    arr = np.zeros((300, 400, 3), np.uint8)
    arr[:, 0] = 255  # stored left column
    _oriented(tmp_path / "o2.png", Image.fromarray(arr), 2)  # mirrored horizontally
    out = load_panorama(tmp_path / "o2.png")
    assert out.shape == (300, 400, 3)
    assert out[:, -1].min() == 255
    assert out[:, 0].max() == 0


def test_load_mask_applies_exif_orientation(tmp_path: Path) -> None:
    m = np.zeros((300, 400), np.uint8)
    m[-1, 0] = 255
    _oriented(tmp_path / "m.png", Image.fromarray(m), 6)
    out = load_mask(tmp_path / "m.png")
    assert out.shape == (400, 300)
    assert out[0, 0] == 1.0
    assert out.sum() == 1.0
