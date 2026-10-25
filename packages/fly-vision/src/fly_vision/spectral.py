"""Spectral model: linear RGB to rhodopsin catches and the R1_6, R7, R8 channels.

A fixed sensor model with no learned parameters. Rhodopsin sensitivities use the
Govardovskii et al. (2000) A1 template (Vis Neurosci 17:509-528). The peaks below are
the nomogram absorption maxima fitted to the sensitivity curves of flies expressing a
single rhodopsin in Salcedo et al. (1999), J Neurosci 19(24):10716-10726: Rh1 478,
Rh5 437 and Rh6 508 nm (Results), Rh3 345 and Rh4 375 nm (Figure 5 legend).

R7 is the Rh3/Rh4 catch from the long-wavelength tails of their curves over the visible
primaries. It is small and scene-dependent, never a constant, and never rescaled.
True UV is not modelled (`uv_modelled = False` is set by the caller).
"""

import hashlib
from dataclasses import dataclass

import numpy as np
from numpy.typing import NDArray

from fly_vision import CHANNELS

RHODOPSIN_PEAKS_NM: dict[str, int] = {"Rh1": 478, "Rh3": 345, "Rh4": 375, "Rh5": 437, "Rh6": 508}
WAVELENGTHS_NM: NDArray[np.int_] = np.arange(300, 801)

_RECEPTORS = ("Rh1", "Rh3", "Rh4", "Rh5", "Rh6")
_SRGB_LUMINANCE = (0.2126, 0.7152, 0.0722)
_V_PEAK_NM = 555.0
_V_FWHM_NM = 100.0
_FWHM_PER_SIGMA = 2.3548  # FWHM = 2 sqrt(2 ln 2) sigma


def govardovskii_a1(
    peak_nm: float, wl: NDArray[np.int_] | NDArray[np.float64]
) -> NDArray[np.float64]:
    """Govardovskii A1 template (alpha + beta band), peak-normalised to 1."""

    def template(lam: NDArray[np.float64]) -> NDArray[np.float64]:
        x = peak_nm / lam
        a = 0.8795 + 0.0459 * np.exp(-((peak_nm - 300.0) ** 2) / 11940.0)
        alpha = 1.0 / (
            np.exp(69.7 * (a - x))
            + np.exp(28.0 * (0.922 - x))
            + np.exp(-14.9 * (1.104 - x))
            + 0.674
        )
        lam_mb = 189.0 + 0.315 * peak_nm
        b_b = -40.5 + 0.195 * peak_nm
        beta = 0.26 * np.exp(-(((lam - lam_mb) / b_b) ** 2))
        return alpha + beta

    s = template(np.asarray(wl, dtype=float))
    peak = template(np.linspace(peak_nm - 50.0, peak_nm + 50.0, 20001)).max()
    return s / peak


@dataclass(frozen=True)
class PrimaryConfig:
    """Gaussian emission spectra assumed for the R, G, B display primaries."""

    peaks_nm: tuple[float, float, float] = (610, 545, 465)
    fwhm_nm: tuple[float, float, float] = (30, 40, 25)


def srgb_to_linear(x: NDArray[np.float64]) -> NDArray[np.float64]:
    """Piecewise sRGB electro-optical transfer, input floats in [0, 1]."""
    x = np.asarray(x, dtype=float)
    return np.where(x <= 0.04045, x / 12.92, ((x + 0.055) / 1.055) ** 2.4)


@dataclass(frozen=True)
class SpectralModel:
    catch_matrix: NDArray[np.float64]  # (5, 3), rows Rh1, Rh3, Rh4, Rh5, Rh6
    hash: str

    def receptors(
        self, linear_rgb: NDArray[np.float64], subtype: NDArray[np.str_]
    ) -> NDArray[np.float64]:
        """(N, 3) channels in `CHANNELS` order; raises ValueError on an unknown subtype."""
        assert CHANNELS == ("R1_6", "R7", "R8")
        rgb = np.asarray(linear_rgb, dtype=float)
        c = rgb @ self.catch_matrix.T  # (N, 5)
        rh1, rh3, rh4, rh5, rh6 = (c[:, k] for k in range(5))
        sub = np.asarray(subtype)
        out = np.empty((rgb.shape[0], 3))
        out[:, 0] = rh1
        for name, r7, r8 in (
            ("pale", rh3, rh5),
            ("yellow", rh4, rh6),
            ("dorsal_yellow", (rh3 + rh4) / 2, rh6),
            ("dra", rh3, rh3),
        ):
            m = sub == name
            out[m, 1] = r7[m]
            out[m, 2] = r8[m]
        known = np.isin(sub, ("pale", "yellow", "dorsal_yellow", "dra"))
        if not known.all():
            bad = sorted({str(s) for s in sub[~known]})
            raise ValueError(f"unknown ommatidium subtype(s): {bad}")
        return np.maximum(out, 0.0)


def build_spectral_model(primaries: PrimaryConfig | None = None) -> SpectralModel:
    """Integrate Gaussian primaries against the rhodopsin templates."""
    primaries = primaries or PrimaryConfig()
    wl = WAVELENGTHS_NM.astype(float)
    v = np.exp(-0.5 * ((wl - _V_PEAK_NM) / (_V_FWHM_NM / _FWHM_PER_SIGMA)) ** 2)
    emission = np.empty((wl.size, 3))
    for k in range(3):
        sigma = primaries.fwhm_nm[k] / _FWHM_PER_SIGMA
        g = np.exp(-0.5 * ((wl - primaries.peaks_nm[k]) / sigma) ** 2)
        emission[:, k] = g * _SRGB_LUMINANCE[k] / np.sum(v * g)
    sens = np.stack([govardovskii_a1(RHODOPSIN_PEAKS_NM[r], WAVELENGTHS_NM) for r in _RECEPTORS])
    catch = sens @ emission  # (5, 3)
    catch = catch / (catch[0] @ np.ones(3))  # Rh1 catch of white = 1, one constant
    digest = hashlib.sha256(catch.round(10).tobytes()).hexdigest()
    return SpectralModel(catch_matrix=catch, hash=digest)
