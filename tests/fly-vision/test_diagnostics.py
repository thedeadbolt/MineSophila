import numpy as np
import pytest

from fly_vision.config import FlyVisionConfig
from fly_vision.diagnostics import (
    chromatic_distance,
    coverage_report,
    metamer_report,
    r7_blue_sensitivity,
    r7_independence,
)
from fly_vision.lattice import generate_hex_lattice
from fly_vision.projection import honey_factory_panorama
from fly_vision.sampler import build_sampling_matrix
from fly_vision.spectral import SPECTRAL_MODES, build_spectral_model

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


def test_r7_independence() -> None:
    phys = r7_independence(build_spectral_model(pigments=SPECTRAL_MODES["physical"]))
    remap = r7_independence(build_spectral_model())
    assert phys["pale"] < 1e-3
    assert phys["yellow"] < 0.05
    assert remap["pale"] > 0.5
    assert remap["yellow"] > 0.5


def test_r7_sensitivity_is_physical() -> None:
    s = r7_blue_sensitivity()
    assert s[0] == pytest.approx(1.38e-5, rel=0.02)  # assumptions register, physical mode


def test_metamers_remapped_default() -> None:
    rep = dict(metamer_report({"honey": (251, 186, 51), "blue": (40, 60, 220)}))
    assert rep["blue"] > 0.1  # prototype: 3.82 remapped (2.10 physical)
