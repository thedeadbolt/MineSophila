"""Hexagon-marker plots of per-ommatidium values for the left and right eye."""

import matplotlib.pyplot as plt
import numpy as np
from matplotlib.cm import ScalarMappable
from matplotlib.figure import Figure
from numpy.typing import NDArray

from fly_vision.lattice import Lattice


def plot_hex(
    lattice: Lattice,
    values: NDArray[np.floating],
    valid: NDArray[np.bool_] | None = None,
    title: str = "",
) -> Figure:
    """Two panels (L, R) of azimuth / elevation in degrees; invalid ommatidia are grey."""
    n = lattice.ommatidium_id.size
    if values.shape != (n,):
        raise ValueError(f"values must have shape ({n},), got {values.shape}")
    ok = np.ones(n, dtype=bool) if valid is None else valid
    if ok.shape != (n,):
        raise ValueError(f"valid must have shape ({n},), got {ok.shape}")
    vals = values.astype(np.float64)
    finite = vals[ok & np.isfinite(vals)]
    vmin, vmax = (float(finite.min()), float(finite.max())) if finite.size else (0.0, 1.0)

    fig, axes = plt.subplots(1, 2, figsize=(12, 5), sharey=True, layout="constrained")
    mappable: ScalarMappable | None = None
    for ax, eye in zip(axes, "LR", strict=True):
        m = lattice.eye == eye
        az, el = lattice.direction_deg[m, 0], lattice.direction_deg[m, 1]
        bad = ~ok[m]
        ax.scatter(az[bad], el[bad], c="lightgrey", marker="h", s=40)
        mappable = ax.scatter(
            az[~bad], el[~bad], c=vals[m][~bad], marker="h", s=40, vmin=vmin, vmax=vmax
        )
        ax.set_title(f"{eye} eye")
        ax.set_xlabel("azimuth (deg)")
        ax.set_aspect("equal")
    axes[0].set_ylabel("elevation (deg)")
    assert mappable is not None
    fig.colorbar(mappable, ax=list(axes), shrink=0.8)  # pyright: ignore[reportUnknownMemberType]
    if title:
        fig.suptitle(title)  # pyright: ignore[reportUnknownMemberType]
    return fig
