"""Reports on the fixed sensor model: coverage, indistinguishable colours, R7 sensitivity
and R7 independence.

These are reports for humans, not part of the per-frame output. Receptor values come from
`SpectralModel.receptors`; nothing spectral is computed here.
"""

from dataclasses import replace
from typing import Any

import numpy as np
from numpy.typing import NDArray

from fly_vision.lattice import SUBTYPES, Lattice
from fly_vision.sampler import SamplingMatrix
from fly_vision.spectral import (
    SPECTRAL_MODES,
    PrimaryConfig,
    SpectralModel,
    build_spectral_model,
    srgb_to_linear,
)

_SUBTYPES = SUBTYPES[:2]  # pale, yellow: the main-retina subtypes
_BLUE = 2  # index of the blue primary in `PrimaryConfig.peaks_nm`


def coverage_report(sm: SamplingMatrix, lattice: Lattice) -> dict[str, Any]:
    """Fractions of valid ommatidia (overall and per eye) and of image pixels with weight."""
    column_weight = np.asarray(sm.W.sum(axis=0)).ravel()
    return {
        "fraction_ommatidia_valid": float(sm.valid.mean()),
        "fraction_image_used": float((column_weight > 0).mean()),
        "per_eye_valid": {eye: float(sm.valid[lattice.eye == eye].mean()) for eye in ("L", "R")},
    }


def receptor_table(
    colors_srgb8: dict[str, tuple[int, int, int]], model: SpectralModel
) -> dict[str, dict[str, NDArray[np.float64]]]:
    """Per colour and subtype (`pale`, `yellow`), the (3,) receptor catch in `CHANNELS` order."""
    table: dict[str, dict[str, NDArray[np.float64]]] = {}
    for name, rgb8 in colors_srgb8.items():
        linear = srgb_to_linear(np.asarray(rgb8, dtype=float)[None, :] / 255.0)
        table[name] = {sub: model.receptors(linear, np.array([sub]))[0] for sub in _SUBTYPES}
    return table


def chromatic_distance(a: NDArray[np.float64], b: NDArray[np.float64], eps: float = 1e-9) -> float:
    """Euclidean distance between `log(x + eps) - log(x[0] + eps)`: brightness-free.

    `eps` is applied here, at the point of computation; inputs are never modified.
    """
    va: NDArray[np.float64] = np.log(a + eps) - np.log(a[0] + eps)
    vb: NDArray[np.float64] = np.log(b + eps) - np.log(b[0] + eps)
    return float(np.sqrt(np.sum((va - vb) ** 2)))


def metamer_report(
    colors_srgb8: dict[str, tuple[int, int, int]],
    target: str = "honey",
    model: SpectralModel | None = None,
) -> list[tuple[str, float]]:
    """Every colour but `target`, ascending by its smallest chromatic distance to `target`.

    The minimum is taken over the two subtypes; small values are colours the fly cannot
    tell from the target.
    """
    model = model or build_spectral_model()
    table = receptor_table(colors_srgb8, model)
    ref = table[target]
    report = [
        (name, min(chromatic_distance(rec[sub], ref[sub]) for sub in _SUBTYPES))
        for name, rec in table.items()
        if name != target
    ]
    return sorted(report, key=lambda item: item[1])


def r7_blue_sensitivity(
    shifts_nm: tuple[int, ...] = (-10, 0, 10), eps: float = 1e-9
) -> dict[int, float]:
    """R7/R8 for white on a pale ommatidium, per shift of the blue primary's peak (nm).

    Only the blue peak moves; the model is rebuilt for each shift. R8 is regularised as
    `R7 / (R8 + eps)`, with `eps` applied only here. This measures the physical R7 tail,
    so it always uses physical pigments, whatever the default mode (ADR 0021).
    """
    base = PrimaryConfig()
    white = np.ones((1, 3))
    pale = np.array(["pale"])
    out: dict[int, float] = {}
    for shift in shifts_nm:
        peaks = list(base.peaks_nm)
        peaks[_BLUE] += shift
        cfg = replace(base, peaks_nm=tuple(peaks))
        model = build_spectral_model(cfg, SPECTRAL_MODES["physical"])
        _, r7, r8 = model.receptors(white, pale)[0]
        out[shift] = float(r7 / (r8 + eps))
    return out


def r7_independence(model: SpectralModel) -> dict[str, float]:
    """How far R7 lies outside what R1_6 and R8 already carry, per main-retina subtype.

    The relative least-squares residual `|r7 - A x| / |r7|` of the R7 catch row against
    span(R1_6 row, R8 row): pale is Rh3 against Rh1 and Rh5, yellow is Rh4 against Rh1 and
    Rh6. Near 0 means R7 is predictable from the other two channels (ADR 0021, D7).
    """
    c: NDArray[np.float64] = np.asarray(model.catch_matrix, dtype=np.float64)
    out: dict[str, float] = {}
    for name, r7_row, r8_row in (("pale", 1, 3), ("yellow", 2, 4)):  # Rh1, Rh3, Rh4, Rh5, Rh6
        r7: NDArray[np.float64] = c[r7_row]
        a: NDArray[np.float64] = c[[0, r8_row]].T
        x = np.linalg.lstsq(a, r7, rcond=None)[0]
        out[name] = float(np.linalg.norm(r7 - a @ x) / np.linalg.norm(r7))
    return out
