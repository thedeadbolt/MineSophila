import numpy as np
import pytest
from numpy.typing import NDArray

from fly_vision.config import DEFAULT_SPECTRAL_MODE
from fly_vision.spectral import (
    RHODOPSIN_PEAKS_NM,
    SPECTRAL_MODES,
    WAVELENGTHS_NM,
    PigmentMap,
    PrimaryConfig,
    SpectralModel,
    build_spectral_model,
    govardovskii_a1,
    srgb_to_linear,
)

# Physical pigments, explicitly: the tests below pin the physical numbers (ADR 0019).
M = build_spectral_model(pigments=SPECTRAL_MODES["physical"])
REMAP = build_spectral_model(pigments=SPECTRAL_MODES["remapped"])
_FOUR_BANDS = PrimaryConfig(
    names=("R", "G", "B", "C"),
    peaks_nm=(610, 545, 465, 500),
    fwhm_nm=(30, 40, 25, 30),
    luminance=(0.2, 0.6, 0.1, 0.1),
)


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


@pytest.mark.parametrize("model", [M, REMAP], ids=["physical", "remapped"])
def test_never_negative_or_nan(model: SpectralModel) -> None:
    r = model.receptors(np.random.default_rng(0).random((1000, 3)), np.array(["yellow"] * 1000))
    assert np.isfinite(r).all()
    assert (r >= 0).all()


def test_hash_changes_with_primaries() -> None:
    assert build_spectral_model(PrimaryConfig(peaks_nm=(610, 545, 455))).hash != M.hash


def test_unknown_subtype_raises() -> None:
    import pytest

    with pytest.raises(ValueError, match="unknown ommatidium subtype"):
        M.receptors(np.ones((1, 3)), np.array(["bogus"]))


def test_default_model_is_remapped() -> None:
    assert DEFAULT_SPECTRAL_MODE == "remapped"
    assert build_spectral_model().hash == REMAP.hash
    assert build_spectral_model().describe()["pigment_map"]["kind"] == "affine"


def test_remapped_peaks_move_into_visible() -> None:
    expected = {"Rh1": 556.5, "Rh3": 454.5, "Rh4": 477.5, "Rh5": 525.0, "Rh6": 579.5}
    src = SPECTRAL_MODES["remapped"].source_wavelength(WAVELENGTHS_NM.astype(float))
    for name, peak in expected.items():
        s = govardovskii_a1(RHODOPSIN_PEAKS_NM[name], src)
        # prototype: Rh3 lands at 453 (1.5 nm off), the tightest margin
        assert abs(WAVELENGTHS_NM[np.argmax(s)] - peak) <= 2


def test_remapped_r7_is_large_and_white_normalised() -> None:
    r = REMAP.receptors(np.ones((2, 3)), np.array(["pale", "yellow"]))
    assert np.allclose(r[:, 0], 1.0, atol=1e-9)
    assert r[0, 1] == pytest.approx(0.500, abs=0.01)
    assert r[1, 1] == pytest.approx(0.598, abs=0.01)


def test_physical_model_unchanged() -> None:
    assert M.catch_matrix.shape == (5, 3)
    assert M.catch_matrix[1, 2] == pytest.approx(8.06e-6, rel=0.01)  # Rh3 · blue
    assert M.catch_matrix[2, 2] == pytest.approx(1.555e-3, rel=0.01)  # Rh4 · blue


def test_mode_changes_hash_and_describe() -> None:
    assert REMAP.hash != M.hash
    assert REMAP.describe()["pigment_map"]["kind"] == "affine"
    assert M.describe()["pigment_map"]["kind"] == "physical"


def test_four_visible_bands() -> None:
    m = build_spectral_model(_FOUR_BANDS)
    assert m.catch_matrix.shape == (5, 4)
    assert m.receptors(np.ones((1, 4)), np.array(["pale"]))[0, 0] == pytest.approx(1.0)


def test_zero_luminance_band_is_rejected() -> None:
    with pytest.raises(ValueError, match="ADR 0021"):
        PrimaryConfig(
            names=("R", "G", "B", "UV"),
            peaks_nm=(610, 545, 465, 365),
            fwhm_nm=(30, 40, 25, 20),
            luminance=(0.2126, 0.7152, 0.0722, 0.0),
        )


def test_receptors_reject_band_count_mismatch() -> None:
    m4 = build_spectral_model(_FOUR_BANDS)
    with pytest.raises(ValueError, match=r"3.*4|4.*3"):
        m4.receptors(np.ones((1, 3)), np.array(["pale"]))


@pytest.mark.parametrize(
    ("src", "tgt"),
    [
        ((600.0, 300.0), (420.0, 650.0)),
        ((300.0, 300.0), (420.0, 650.0)),
        ((300.0, 600.0), (float("nan"), 650.0)),
    ],
)
def test_pigment_map_validates_ranges(src: tuple[float, float], tgt: tuple[float, float]) -> None:
    with pytest.raises(ValueError, match="finite and increasing"):
        PigmentMap(kind="affine", source_nm=src, target_nm=tgt)
