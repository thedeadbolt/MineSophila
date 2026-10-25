from pathlib import Path

import numpy as np
import pytest
from PIL import Image

from fly_vision.cli import main


def _write_inputs(tmp_path: Path) -> None:
    Image.fromarray(np.full((206, 400, 3), 100, np.uint8)).save(tmp_path / "p.png")
    m = np.zeros((206, 400), np.uint8)
    m[90:110, 190:210] = 255
    Image.fromarray(m).save(tmp_path / "m.png")


def test_convert(tmp_path: Path) -> None:
    _write_inputs(tmp_path)
    rc = main(
        [
            "convert",
            "--panorama",
            str(tmp_path / "p.png"),
            "--mask",
            str(tmp_path / "m.png"),
            "--scene-id",
            "s",
            "--frame-id",
            "f",
            "--out",
            str(tmp_path / "out"),
            "--plot",
        ]
    )
    assert rc == 0
    for n in ["f.fly.npz", "f.labels.npz", "f.png"]:
        assert (tmp_path / "out" / n).exists()


def test_convert_without_mask(tmp_path: Path) -> None:
    _write_inputs(tmp_path)
    rc = main(
        [
            "convert",
            "--panorama",
            str(tmp_path / "p.png"),
            "--scene-id",
            "s",
            "--frame-id",
            "f",
            "--out",
            str(tmp_path / "out"),
        ]
    )
    assert rc == 0
    assert (tmp_path / "out" / "f.fly.npz").exists()
    assert not (tmp_path / "out" / "f.labels.npz").exists()
    assert not (tmp_path / "out" / "f.png").exists()


def test_convert_explicit_fov(tmp_path: Path) -> None:
    _write_inputs(tmp_path)
    rc = main(
        [
            "convert",
            "--panorama",
            str(tmp_path / "p.png"),
            "--fov-h",
            "310",
            "--fov-v",
            "160",
            "--center-el",
            "10",
            "--scene-id",
            "s",
            "--frame-id",
            "f",
            "--out",
            str(tmp_path / "out"),
        ]
    )
    assert rc == 0


def test_fov_must_be_given_together(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    _write_inputs(tmp_path)
    rc = main(
        [
            "convert",
            "--panorama",
            str(tmp_path / "p.png"),
            "--fov-h",
            "310",
            "--scene-id",
            "s",
            "--frame-id",
            "f",
            "--out",
            str(tmp_path / "out"),
        ]
    )
    assert rc != 0
    assert "--fov-h and --fov-v" in capsys.readouterr().err
    assert not (tmp_path / "out").exists()


def test_bad_input_reports_error(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    rc = main(
        [
            "convert",
            "--panorama",
            str(tmp_path / "missing.png"),
            "--scene-id",
            "s",
            "--frame-id",
            "f",
            "--out",
            str(tmp_path / "out"),
        ]
    )
    assert rc != 0
    assert "fly-vision:" in capsys.readouterr().err


def test_mask_shape_mismatch_reports_error(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    _write_inputs(tmp_path)
    Image.fromarray(np.zeros((10, 10), np.uint8)).save(tmp_path / "bad.png")
    rc = main(
        [
            "convert",
            "--panorama",
            str(tmp_path / "p.png"),
            "--mask",
            str(tmp_path / "bad.png"),
            "--scene-id",
            "s",
            "--frame-id",
            "f",
            "--out",
            str(tmp_path / "out"),
        ]
    )
    assert rc != 0
    assert "mask" in capsys.readouterr().err
