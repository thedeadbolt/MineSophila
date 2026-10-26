import numpy as np
from numpy.typing import NDArray

from fly_vision.spectral import (
    RHODOPSIN_PEAKS_NM,
    WAVELENGTHS_NM,
    PrimaryConfig,
    build_spectral_model,
    govardovskii_a1,
    srgb_to_linear,
)

M = build_spectral_model()


def lin(rgb8: tuple[int, int, int]) -> NDArray[np.float64]:
    return srgb_to_linear(np.array([rgb8], float) / 255)


def test_template_peaks() -> None:
    for p in RHODOPSIN_PEAKS_NM.values():
        s = govardovskii_a1(p, WAVELENGTHS_NM)
        assert abs(WAVELENGTHS_NM[np.argmax(s)] - p) <= 2
        assert np.isclose(s.max(), 1.0, atol=1e-3)


def test_white_normalisation_and_r7_small() -> None:
    r = M.receptors(np.ones((2, 3)), np.array(["pale", "yellow"]))
    assert np.allclose(r[:, 0], 1.0)
    assert np.all(r[:, 1] > 0)
    assert np.all(r[:, 1] < 0.25 * r[:, 2])


def test_r7_blue_more_than_honey_and_red() -> None:
    sub = np.array(["pale"])
    blue, red, honey = (
        M.receptors(lin(c), sub)[0, 1] for c in [(0, 0, 255), (255, 0, 0), (251, 186, 51)]
    )
    assert blue > honey
    assert blue > red


def test_subtype_mapping() -> None:
    x = np.ones((4, 3))
    r = M.receptors(x, np.array(["pale", "yellow", "dorsal_yellow", "dra"]))
    i = {k: n for n, k in enumerate(["Rh1", "Rh3", "Rh4", "Rh5", "Rh6"])}
    c = M.catch_matrix @ np.ones(3) / (M.catch_matrix @ np.ones(3))[i["Rh1"]]
    assert np.allclose(r[0, 1:], [c[i["Rh3"]], c[i["Rh5"]]])
    assert np.allclose(r[1, 1:], [c[i["Rh4"]], c[i["Rh6"]]])
    assert np.allclose(r[2, 1:], [(c[i["Rh3"]] + c[i["Rh4"]]) / 2, c[i["Rh6"]]])
    assert np.allclose(r[3, 1:], [c[i["Rh3"]], c[i["Rh3"]]])


def test_never_negative_or_nan() -> None:
    r = M.receptors(np.random.default_rng(0).random((1000, 3)), np.array(["yellow"] * 1000))
    assert np.isfinite(r).all()
    assert (r >= 0).all()


def test_hash_changes_with_primaries() -> None:
    assert build_spectral_model(PrimaryConfig(peaks_nm=(610, 545, 455))).hash != M.hash


def test_unknown_subtype_raises() -> None:
    import pytest

    with pytest.raises(ValueError, match="unknown ommatidium subtype"):
        M.receptors(np.ones((1, 3)), np.array(["bogus"]))
