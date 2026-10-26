import numpy as np

from fly_vision.config import FlyVisionConfig
from fly_vision.diagnostics import (
    chromatic_distance,
    coverage_report,
    metamer_report,
    r7_blue_sensitivity,
)
from fly_vision.lattice import generate_hex_lattice
from fly_vision.projection import honey_factory_panorama
from fly_vision.sampler import build_sampling_matrix

L = generate_hex_lattice()
SM = build_sampling_matrix(L, honey_factory_panorama(620, 320), FlyVisionConfig())


def test_coverage_report() -> None:
    r = coverage_report(SM, L)
    assert 0.9 <= r["fraction_ommatidia_valid"] <= 1.0
    assert 0 < r["fraction_image_used"] <= 1.0
    assert set(r["per_eye_valid"]) == {"L", "R"}


def test_metamers() -> None:
    rep = dict(
        metamer_report({"honey": (251, 186, 51), "same": (251, 186, 51), "blue": (40, 60, 220)})
    )
    assert rep["same"] < 1e-9
    assert rep["blue"] > 0.1


def test_brightness_free_distance() -> None:
    a = np.array([1.0, 0.1, 0.5])
    assert chromatic_distance(a, 0.2 * a) < 1e-6


def test_r7_sensitivity_reports() -> None:
    s = r7_blue_sensitivity()
    assert set(s) == {-10, 0, 10}
    assert all(np.isfinite(v) and v > 0 for v in s.values())
