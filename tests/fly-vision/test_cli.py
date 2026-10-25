import hashlib
from pathlib import Path

import numpy as np
import pytest
from PIL import Image

from fly_vision.cli import main
from fly_vision.config import FlyVisionConfig
from fly_vision.frame import load_frame
from fly_vision.lattice import default_lattice
from fly_vision.placement import DEFAULT_FOV_H_DEG, perspective_fov_for_object
from fly_vision.projection import PerspectiveSpec, honey_factory_panorama
from fly_vision.sampler import matrix_key


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


def _args(tmp_path: Path, *extra: str, panorama: str = "p.png") -> list[str]:
    return [
        "convert",
        "--panorama",
        str(tmp_path / panorama),
        "--scene-id",
        "s",
        "--frame-id",
        "f",
        "--out",
        str(tmp_path / "out"),
        *extra,
    ]


def test_default_path_uses_honey_factory_matrix(tmp_path: Path) -> None:
    _write_inputs(tmp_path)
    assert main(_args(tmp_path)) == 0
    frame = load_frame(tmp_path / "out" / "f.fly.npz")
    expected = matrix_key(default_lattice(), honey_factory_panorama(400, 206), FlyVisionConfig())
    assert frame.meta["matrix_key"] == expected


def test_default_path_rejects_other_aspect_ratio(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    Image.fromarray(np.full((200, 400, 3), 100, np.uint8)).save(tmp_path / "wide.png")
    assert main(_args(tmp_path, panorama="wide.png")) == 1
    err = capsys.readouterr().err
    assert "PANORAMA_310" in err
    assert "--fov-h" in err
    assert not (tmp_path / "out").exists()
    # an explicit field of view is accepted for the same image
    assert main(_args(tmp_path, "--fov-h", "360", "--fov-v", "180", panorama="wide.png")) == 0


def test_center_without_fov_is_rejected(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    _write_inputs(tmp_path)
    assert main(_args(tmp_path, "--center-el", "10")) == 1
    assert "--fov-h" in capsys.readouterr().err
    assert main(_args(tmp_path, "--center-az", "5")) == 1
    assert not (tmp_path / "out").exists()


def test_corrupt_lattice_reports_error(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    _write_inputs(tmp_path)
    (tmp_path / "bad.npz").write_bytes(b"PK\x03\x04 not really a zip")
    assert main(_args(tmp_path, "--lattice", str(tmp_path / "bad.npz"))) == 1
    err = capsys.readouterr().err
    assert err.startswith("fly-vision: error:")
    assert "Traceback" not in err


def test_frame_records_source_file_hash(tmp_path: Path) -> None:
    _write_inputs(tmp_path)
    assert main(_args(tmp_path)) == 0
    frame = load_frame(tmp_path / "out" / "f.fly.npz")
    expected = hashlib.sha256((tmp_path / "p.png").read_bytes()).hexdigest()
    assert frame.meta["source_file_sha256"] == expected


def _photo(tmp_path: Path) -> None:
    Image.fromarray(np.full((400, 300, 3), 100, np.uint8)).save(tmp_path / "photo.png")


def _persp(tmp_path: Path, *extra: str) -> list[str]:
    return _args(tmp_path, "--projection", "perspective", *extra, panorama="photo.png")


_OBJECT = ("--object-width-px", "120", "--object-size-m", "0.3", "--distance-m", "1.0")


def test_convert_perspective_fov(tmp_path: Path) -> None:
    _photo(tmp_path)
    assert main(_persp(tmp_path, "--fov-h", "50")) == 0
    meta = load_frame(tmp_path / "out" / "f.fly.npz").meta
    assert meta["projection"] == PerspectiveSpec(300, 400, 50.0).describe()
    assert meta["placement"] == {"method": "fov_h", "assumed": False}


def test_convert_perspective_object(tmp_path: Path) -> None:
    _photo(tmp_path)
    assert main(_persp(tmp_path, *_OBJECT)) == 0
    meta = load_frame(tmp_path / "out" / "f.fly.npz").meta
    assert meta["projection"]["fov_h_deg"] == perspective_fov_for_object(300, 120, 0.3, 1.0)
    assert meta["placement"]["method"] == "object"
    assert meta["placement"]["assumed"] is False
    assert meta["placement"]["object_width_px"] == 120
    assert meta["placement"]["object_size_m"] == 0.3
    assert meta["placement"]["distance_m"] == 1.0


def test_perspective_fov_and_object_agree(tmp_path: Path) -> None:
    _photo(tmp_path)
    fov = round(perspective_fov_for_object(300, 120, 0.3, 1.0), 2)
    assert main(_persp(tmp_path, "--fov-h", str(fov), *_OBJECT)) == 0
    meta = load_frame(tmp_path / "out" / "f.fly.npz").meta
    assert meta["projection"]["fov_h_deg"] == fov
    assert meta["placement"]["method"] == "fov_h+object"
    assert "object_fov_h_deg" in meta["placement"]


def test_perspective_default_fov_is_marked(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    _photo(tmp_path)
    assert main(_persp(tmp_path)) == 0
    assert "60" in capsys.readouterr().err
    meta = load_frame(tmp_path / "out" / "f.fly.npz").meta
    assert meta["projection"]["fov_h_deg"] == DEFAULT_FOV_H_DEG
    assert meta["placement"] == {"method": "default", "assumed": True}


@pytest.mark.parametrize(
    "extra",
    [
        ("--object-width-px", "120", "--object-size-m", "0.3"),
        ("--fov-h", "30", *_OBJECT),
        ("--fov-v", "40", "--fov-h", "50"),
    ],
)
def test_perspective_placement_errors(
    tmp_path: Path, capsys: pytest.CaptureFixture[str], extra: tuple[str, ...]
) -> None:
    _photo(tmp_path)
    assert main(_persp(tmp_path, *extra)) == 1
    err = capsys.readouterr().err.strip()
    assert err.startswith("fly-vision: error:")
    assert "\n" not in err
    assert not (tmp_path / "out").exists()


def test_panorama_alias_still_works(tmp_path: Path) -> None:
    _write_inputs(tmp_path)
    assert main(_args(tmp_path)) == 0
    meta = load_frame(tmp_path / "out" / "f.fly.npz").meta
    assert meta["placement"] == {"method": "honey_factory_contract", "assumed": False}


def test_omitted_projection_with_fov_selects_equirect(tmp_path: Path) -> None:
    _photo(tmp_path)
    assert main(_args(tmp_path, "--fov-h", "90", "--fov-v", "90", panorama="photo.png")) == 0
    meta = load_frame(tmp_path / "out" / "f.fly.npz").meta
    assert meta["projection"]["type"] == "equirect"


def test_perspective_center_recorded(tmp_path: Path) -> None:
    _photo(tmp_path)
    assert main(_persp(tmp_path, "--fov-h", "90", "--center-az", "10", "--center-el", "-25")) == 0
    proj = load_frame(tmp_path / "out" / "f.fly.npz").meta["projection"]
    assert proj["center_az_deg"] == 10.0
    assert proj["center_el_deg"] == -25.0
