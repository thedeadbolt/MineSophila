import gc
import warnings
from collections.abc import Callable
from pathlib import Path
from typing import Any

import numpy as np
import pytest

from fly_vision.frame import load_frame, load_labels
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
