"""Reports on the fixed sensor model: coverage, indistinguishable colours, R7 sensitivity.

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
    `R7 / (R8 + eps)`, with `eps` applied only here.
    """
    base = PrimaryConfig()
    white = np.ones((1, 3))
    pale = np.array(["pale"])
    out: dict[int, float] = {}
    for shift in shifts_nm:
        peaks = list(base.peaks_nm)
        peaks[_BLUE] += shift
        cfg = replace(base, peaks_nm=(peaks[0], peaks[1], peaks[2]))
        _, r7, r8 = build_spectral_model(cfg).receptors(white, pale)[0]
        out[shift] = float(r7 / (r8 + eps))
    return out
