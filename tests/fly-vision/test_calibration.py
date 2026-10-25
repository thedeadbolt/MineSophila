import json
import math
from pathlib import Path

import numpy as np
import pytest
from numpy.typing import NDArray
from PIL import Image

from fly_vision.eye import FlyEye, load_mask, load_panorama
from fly_vision.lattice import default_lattice
from fly_vision.projection import honey_factory_panorama, pixel_directions, to_unit

FIX = Path(__file__).parent / "fixtures" / "calibration"
TARGETS = [(0.0, 0.0), (60.0, 0.0), (-90.0, 0.0), (0.0, 45.0), (-45.0, 20.0)]
BLOCK_RADIUS_DEG = math.degrees(math.atan(0.5 / 5.0))  # a block seen from 5 blocks away
CENTROID_WINDOW_DEG = 8.0
CENTROID_TOL_DEG = 2.0
MIRROR_WINDOW_DEG = 5.0
MIRROR_MAX = 0.05
MIRROR_MIN_AZ_DEG = 10.0


def _angles_deg(dirs: NDArray[np.float64], az: float, el: float) -> NDArray[np.float64]:
    target = to_unit(np.asarray(az, dtype=np.float64), np.asarray(el, dtype=np.float64))
    return np.degrees(np.arccos(np.clip(dirs @ target, -1.0, 1.0)))


def check_calibration(directory: Path) -> None:
    """Assert that the honey mask in `directory` lands where `targets.json` says.

    Layout: `panorama.png`, `mask.png`, `targets.json` with
    `{"fov_h", "fov_v", "center_el", "targets": [{"az", "el"}]}`. The eye is built from the
    panorama size with `honey_factory_panorama` (the path the CLI uses), and the file must
    agree with that preset.
    """
    t = json.loads((directory / "targets.json").read_text())
    pano = load_panorama(directory / "panorama.png")
    spec = honey_factory_panorama(pano.shape[1], pano.shape[0])
    assert t["fov_h"] == spec.fov_h_deg
    assert t["fov_v"] == spec.fov_v_deg
    assert t["center_el"] == spec.center_el_deg
    eye = FlyEye(default_lattice(), spec)
    lab = eye.sample(pano, load_mask(directory / "mask.png"))[1]
    assert lab is not None
    dirs = to_unit(*eye.lattice.direction_deg.T)
    valid = eye.sm.valid
    for tg in t["targets"]:
        az, el = float(tg["az"]), float(tg["el"])
        near = valid & (_angles_deg(dirs, az, el) < CENTROID_WINDOW_DEG)
        w = lab.honey_coverage[near].astype(np.float64)
        assert w.sum() > 0.0, f"no honey coverage near {tg}"
        centroid = (w[:, None] * dirs[near]).sum(axis=0)
        centroid /= np.linalg.norm(centroid)
        offset = float(_angles_deg(centroid, az, el))
        assert offset < CENTROID_TOL_DEG, (tg, offset)
        if abs(az) >= MIRROR_MIN_AZ_DEG:
            mirror = valid & (_angles_deg(dirs, -az, el) < MIRROR_WINDOW_DEG)
            assert lab.honey_coverage[mirror].max() < MIRROR_MAX, tg


@pytest.mark.skipif(
    not (FIX / "targets.json").exists(), reason="calibration capture not provided yet"
)
def test_known_directions() -> None:
    check_calibration(FIX)


def _write_synthetic(directory: Path, mirror_azimuth: bool = False) -> None:
    spec = honey_factory_panorama(620, 320)
    az, el = pixel_directions(spec)
    dirs = to_unit(az, el)
    mask = np.zeros((spec.height, spec.width), bool)
    for t_az, t_el in TARGETS:
        angle = np.degrees(
            np.arccos(
                np.clip(
                    dirs
                    @ to_unit(
                        np.asarray(-t_az if mirror_azimuth else t_az, dtype=np.float64),
                        np.asarray(t_el, dtype=np.float64),
                    ),
                    -1.0,
                    1.0,
                )
            )
        )
        mask |= angle < BLOCK_RADIUS_DEG
    pano = np.full((spec.height, spec.width, 3), 90, np.uint8)
    pano[mask] = (230, 170, 20)
    directory.mkdir(parents=True, exist_ok=True)
    Image.fromarray(pano).save(directory / "panorama.png")
    Image.fromarray(mask.astype(np.uint8) * 255, mode="L").save(directory / "mask.png")
    (directory / "targets.json").write_text(
        json.dumps(
            {
                "fov_h": spec.fov_h_deg,
                "fov_v": spec.fov_v_deg,
                "center_el": spec.center_el_deg,
                "targets": [{"az": a, "el": e} for a, e in TARGETS],
            }
        )
    )


def test_check_passes_on_synthetic_capture(tmp_path: Path) -> None:
    _write_synthetic(tmp_path)
    check_calibration(tmp_path)


def test_check_fails_when_azimuth_sign_is_flipped(tmp_path: Path) -> None:
    _write_synthetic(tmp_path, mirror_azimuth=True)
    with pytest.raises(AssertionError):
        check_calibration(tmp_path)


def test_check_rejects_preset_mismatch(tmp_path: Path) -> None:
    _write_synthetic(tmp_path)
    p = tmp_path / "targets.json"
    t = json.loads(p.read_text())
    t["center_el"] = 0
    p.write_text(json.dumps(t))
    with pytest.raises(AssertionError):
        check_calibration(tmp_path)
