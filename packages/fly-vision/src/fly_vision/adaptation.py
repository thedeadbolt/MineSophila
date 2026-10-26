"""Per-frame light adaptation of receptor activations.

Only the current frame is used: the channel means come from the valid ommatidia of this
frame and nothing is kept between calls. `"none"` is the default; `"frame_mean"` is
experimental (decision 11) because it rescales R7 as well.
"""

from typing import Literal

import numpy as np
from numpy.typing import NDArray

_EPS = 1e-12  # numerical guard at the point of computation; never stored


def adapt(
    activations: NDArray[np.float64],
    valid: NDArray[np.bool_],
    mode: Literal["none", "frame_mean"],
) -> NDArray[np.float64]:
    """Adapt `activations` (N, 3) using the rows flagged in `valid` (N,).

    `"none"` returns `activations` unchanged. `"frame_mean"` computes, per channel c,
    `y = x / (x + m_c + eps)` with `m_c` the mean of channel c over valid rows; invalid
    rows are returned as zero. If no row is valid, the result is all zeros.
    """
    if mode == "none":
        return activations
    if mode != "frame_mean":
        raise ValueError(f"unknown adaptation mode: {mode!r}")

    out: NDArray[np.float64] = np.zeros_like(activations, dtype=np.float64)
    if not valid.any():
        return out
    means = activations[valid].mean(axis=0)
    out[valid] = activations[valid] / (activations[valid] + means + _EPS)
    return out
