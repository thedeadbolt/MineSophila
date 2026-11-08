"""Spectral model: linear light bands to rhodopsin catches and the R1_6, R7, R8 channels.

A fixed sensor model with no learned parameters. Rhodopsin sensitivities use the
Govardovskii et al. (2000) A1 template (Vis Neurosci 17:509-528). The peaks below are
the nomogram absorption maxima fitted to the sensitivity curves of flies expressing a
single rhodopsin in Salcedo et al. (1999), J Neurosci 19(24):10716-10726: Rh1 478,
Rh5 437 and Rh6 508 nm (Results), Rh3 345 and Rh4 375 nm (Figure 5 legend).

Two independent inputs shape the model. A `PigmentMap` says where the rhodopsin curves
sit on the wavelength axis, and a `PrimaryConfig` says which light bands the input
carries (RGB by default). The two pigment axes are listed in `SPECTRAL_MODES`:

- `remapped`, the default (ADR 0021): an affine warp evaluates each template at
  `300 + (λ - 420) · 300 / 230`, so the 300-600 nm pigment axis lies on 420-650 nm. R7
  is then no longer redundant with R1_6 and R8, but it still mostly encodes blue, and
  the fly it describes doesn't exist.
- `physical`: the Salcedo peaks on the real axis (ADR 0019). Here R7 is the Rh3/Rh4 catch
  from the long-wavelength tails of their curves over the visible primaries. It is
  small and scene-dependent, never a constant, and never rescaled.

True UV is not modelled in either mode (`uv_modelled = False` is set by the caller).
"""

import hashlib
import json
import math
from dataclasses import dataclass
from typing import Any, Literal

import numpy as np
from numpy.typing import NDArray

from fly_vision import CHANNELS
from fly_vision.config import DEFAULT_SPECTRAL_MODE, FWHM_PER_SIGMA
from fly_vision.lattice import SUBTYPES

RHODOPSIN_PEAKS_NM: dict[str, int] = {"Rh1": 478, "Rh3": 345, "Rh4": 375, "Rh5": 437, "Rh6": 508}
WAVELENGTHS_NM: NDArray[np.int_] = np.arange(300, 801)

_RECEPTORS = ("Rh1", "Rh3", "Rh4", "Rh5", "Rh6")
_V_PEAK_NM = 555.0
_V_FWHM_NM = 100.0


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


def _check_range(name: str, r: tuple[float, float]) -> tuple[float, float]:
    lo, hi = (float(v) for v in r)
    if not (math.isfinite(lo) and math.isfinite(hi) and lo < hi):
        raise ValueError(f"PigmentMap {name} must be finite and increasing, got {r}")
    return (lo, hi)


@dataclass(frozen=True)
class PigmentMap:
    """Where the rhodopsin curves sit on the wavelength axis.

    `physical` leaves them at their measured peaks. `affine` evaluates each template at the
    source wavelength that maps linearly onto `target_nm`, so `source_nm` lands on
    `target_nm` (ADR 0021). The no-argument map is the physical identity; the mode the
    model uses by default is `DEFAULT_SPECTRAL_MODE`.
    """

    kind: Literal["physical", "affine"] = "physical"
    source_nm: tuple[float, float] = (300.0, 600.0)
    target_nm: tuple[float, float] = (420.0, 650.0)

    def __post_init__(self) -> None:
        if self.kind not in ("physical", "affine"):
            raise ValueError(f"PigmentMap kind must be 'physical' or 'affine', got {self.kind!r}")
        object.__setattr__(self, "source_nm", _check_range("source_nm", self.source_nm))
        object.__setattr__(self, "target_nm", _check_range("target_nm", self.target_nm))

    def source_wavelength(self, wl: NDArray[np.float64]) -> NDArray[np.float64]:
        """The pigment-axis wavelength each real wavelength `wl` (nm) is evaluated at."""
        wl = np.asarray(wl, dtype=float)
        if self.kind == "physical":
            return wl
        (s0, s1), (t0, t1) = self.source_nm, self.target_nm
        return s0 + (wl - t0) * (s1 - s0) / (t1 - t0)

    def describe(self) -> dict[str, str | list[float]]:
        return {
            "kind": self.kind,
            "source_nm": list(self.source_nm),
            "target_nm": list(self.target_nm),
        }


SPECTRAL_MODES: dict[str, PigmentMap] = {
    "physical": PigmentMap(),
    "remapped": PigmentMap(kind="affine"),
}


@dataclass(frozen=True)
class PrimaryConfig:
    """Gaussian emission spectra assumed for the input's light bands (R, G, B by default).

    Each band is scaled so its luminance under V equals its `luminance` weight. A band
    without a positive weight (a real UV band) is rejected until its radiometric scaling
    is decided (ADR 0021).
    """

    peaks_nm: tuple[float, ...] = (610, 545, 465)
    fwhm_nm: tuple[float, ...] = (30, 40, 25)
    names: tuple[str, ...] = ("R", "G", "B")
    luminance: tuple[float, ...] = (0.2126, 0.7152, 0.0722)

    def __post_init__(self) -> None:
        peaks = tuple(float(v) for v in self.peaks_nm)
        fwhm = tuple(float(v) for v in self.fwhm_nm)
        names = tuple(str(v) for v in self.names)
        lum = tuple(float(v) for v in self.luminance)
        counts = {len(peaks), len(fwhm), len(names), len(lum)}
        if len(counts) != 1 or not peaks:
            raise ValueError(
                "PrimaryConfig names, peaks_nm, fwhm_nm and luminance need the same length "
                f">= 1, got {len(names)}, {len(peaks)}, {len(fwhm)}, {len(lum)}"
            )
        if not all(math.isfinite(v) for v in peaks + fwhm) or min(fwhm) <= 0:
            raise ValueError(f"PrimaryConfig peaks must be finite and fwhm > 0, got {fwhm}")
        if not all(math.isfinite(v) and v > 0 for v in lum):
            weights = dict(zip(names, lum, strict=True))
            raise ValueError(
                f"every band needs a positive luminance weight, got {weights}; "
                "a zero-weight (UV) band needs a radiometric scaling first (ADR 0021)"
            )
        object.__setattr__(self, "peaks_nm", peaks)
        object.__setattr__(self, "fwhm_nm", fwhm)
        object.__setattr__(self, "names", names)
        object.__setattr__(self, "luminance", lum)


def srgb_to_linear(x: NDArray[np.float64]) -> NDArray[np.float64]:
    """Piecewise sRGB electro-optical transfer, input floats in [0, 1]."""
    x = np.asarray(x, dtype=float)
    return np.where(x <= 0.04045, x / 12.92, ((x + 0.055) / 1.055) ** 2.4)


def _describe(primaries: PrimaryConfig, pigments: PigmentMap) -> dict[str, Any]:
    return {
        "pigment_map": pigments.describe(),
        "bands": list(primaries.names),
        "peaks_nm": list(primaries.peaks_nm),
        "fwhm_nm": list(primaries.fwhm_nm),
    }


@dataclass(frozen=True)
class SpectralModel:
    catch_matrix: NDArray[np.float64]  # (5, K), rows Rh1, Rh3, Rh4, Rh5, Rh6
    hash: str
    primaries: PrimaryConfig
    pigments: PigmentMap

    @property
    def band_names(self) -> tuple[str, ...]:
        return self.primaries.names

    def describe(self) -> dict[str, Any]:
        """The pigment map and input bands, as recorded in frame meta."""
        return _describe(self.primaries, self.pigments)

    def receptors(
        self, linear_rgb: NDArray[np.float64], subtype: NDArray[np.str_]
    ) -> NDArray[np.float64]:
        """(N, 3) channels in `CHANNELS` order from (N, K) linear band values.

        Raises ValueError on an unknown subtype or when the input has a band count other
        than the model's K.
        """
        assert CHANNELS == ("R1_6", "R7", "R8")
        rgb = np.asarray(linear_rgb, dtype=float)
        k = self.catch_matrix.shape[1]
        if rgb.ndim != 2 or rgb.shape[1] != k:
            got = rgb.shape[-1] if rgb.ndim else 0
            raise ValueError(
                f"input has {got} bands but the spectral model has {k} "
                f"({', '.join(self.band_names)}); expected shape (N, {k}), got {rgb.shape}"
            )
        c = rgb @ self.catch_matrix.T  # (N, 5)
        rh1, rh3, rh4, rh5, rh6 = (c[:, j] for j in range(5))
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
        known = np.isin(sub, SUBTYPES)
        if not known.all():
            bad = sorted({str(s) for s in sub[~known]})
            raise ValueError(f"unknown ommatidium subtype(s): {bad}")
        return np.maximum(out, 0.0)


def build_spectral_model(
    primaries: PrimaryConfig | None = None, pigments: PigmentMap | None = None
) -> SpectralModel:
    """Integrate Gaussian band spectra against the (possibly remapped) rhodopsin templates.

    `pigments=None` uses `SPECTRAL_MODES[DEFAULT_SPECTRAL_MODE]`, i.e. the remap (ADR 0021).
    V(λ) and the band spectra stay on the real wavelength axis; only the templates move.
    """
    primaries = primaries or PrimaryConfig()
    pigments = pigments or SPECTRAL_MODES[DEFAULT_SPECTRAL_MODE]
    wl = WAVELENGTHS_NM.astype(float)
    src = pigments.source_wavelength(wl)
    if not (src > 0).all():
        raise ValueError(f"pigment map {pigments.describe()} sends the grid to non-positive nm")
    v = np.exp(-0.5 * ((wl - _V_PEAK_NM) / (_V_FWHM_NM / FWHM_PER_SIGMA)) ** 2)
    k_bands = len(primaries.peaks_nm)
    emission = np.empty((wl.size, k_bands))
    for k in range(k_bands):
        sigma = primaries.fwhm_nm[k] / FWHM_PER_SIGMA
        g = np.exp(-0.5 * ((wl - primaries.peaks_nm[k]) / sigma) ** 2)
        emission[:, k] = g * primaries.luminance[k] / np.sum(v * g)
    sens = np.stack([govardovskii_a1(RHODOPSIN_PEAKS_NM[r], src) for r in _RECEPTORS])
    catch = sens @ emission  # (5, K)
    catch = catch / (catch[0] @ np.ones(k_bands))  # Rh1 catch of white = 1, one constant
    digest = hashlib.sha256(catch.round(10).tobytes())
    digest.update(json.dumps(_describe(primaries, pigments), sort_keys=True).encode())
    return SpectralModel(
        catch_matrix=catch, hash=digest.hexdigest(), primaries=primaries, pigments=pigments
    )
