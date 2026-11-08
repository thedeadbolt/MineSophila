"""Hexagon-marker plots of per-ommatidium values for the left and right eye.

Each output channel has its own colour map, running from black (no response) to bright
(strong response), so brightness reads as activation and the hue names the channel:

- R1_6: black to white. R1-6 is the fly's achromatic brightness and motion channel.
- R7: black through violet to lavender. Violet stands in for R7's UV (Rh3, Rh4).
- R8: black through green to pale green. R8 is the blue-green receptor (Rh5, Rh6).

Ommatidia the image does not reach are drawn as hollow grey outlines.
"""

import matplotlib.pyplot as plt
import numpy as np
from matplotlib.cm import ScalarMappable
from matplotlib.colors import Colormap, LinearSegmentedColormap
from matplotlib.figure import Figure
from numpy.typing import NDArray

from fly_vision import CHANNELS
from fly_vision.lattice import Lattice

CHANNEL_COLOURMAPS: dict[str, Colormap] = {
    "R1_6": LinearSegmentedColormap.from_list("fly_R1_6", ["#000000", "#ffffff"]),
    "R7": LinearSegmentedColormap.from_list("fly_R7", ["#000000", "#7a2fe0", "#efe4ff"]),
    "R8": LinearSegmentedColormap.from_list("fly_R8", ["#000000", "#1e9e3c", "#e3f7da"]),
}
assert set(CHANNEL_COLOURMAPS) == set(CHANNELS)

_INVALID_EDGE = "0.45"
_BACKGROUND = "0.78"


def plot_hex(
    lattice: Lattice,
    values: NDArray[np.floating],
    valid: NDArray[np.bool_] | None = None,
    title: str = "",
    channel: str | None = None,
) -> Figure:
    """Two panels (L, R) of azimuth / elevation in degrees.

    `channel` (one of `CHANNELS`) picks that channel's colour map; without it the values
    are drawn in greyscale. Invalid ommatidia are hollow outlines.
    """
    n = lattice.ommatidium_id.size
    if values.shape != (n,):
        raise ValueError(f"values must have shape ({n},), got {values.shape}")
    ok = np.ones(n, dtype=bool) if valid is None else valid
    if ok.shape != (n,):
        raise ValueError(f"valid must have shape ({n},), got {ok.shape}")
    if channel is not None and channel not in CHANNEL_COLOURMAPS:
        raise ValueError(f"unknown channel {channel!r}; expected one of {list(CHANNELS)}")
    cmap = CHANNEL_COLOURMAPS[channel or "R1_6"]
    vals = values.astype(np.float64)
    finite = vals[ok & np.isfinite(vals)]
    vmin, vmax = (float(finite.min()), float(finite.max())) if finite.size else (0.0, 1.0)

    fig, axes = plt.subplots(1, 2, figsize=(12, 5), sharey=True, layout="constrained")
    mappable: ScalarMappable | None = None
    for ax, eye in zip(axes, "LR", strict=True):
        m = lattice.eye == eye
        az, el = lattice.direction_deg[m, 0], lattice.direction_deg[m, 1]
        bad = ~ok[m]
        ax.scatter(
            az[bad],
            el[bad],
            marker="h",
            s=40,
            facecolors="none",
            edgecolors=_INVALID_EDGE,
            linewidths=0.8,
        )
        mappable = ax.scatter(
            az[~bad],
            el[~bad],
            c=vals[m][~bad],
            marker="h",
            s=40,
            cmap=cmap,
            vmin=vmin,
            vmax=vmax,
        )
        ax.set_title(f"{eye} eye")
        ax.set_xlabel("azimuth (deg)")
        ax.set_aspect("equal")
        ax.set_facecolor(_BACKGROUND)  # mid grey: both the black and the bright ends show
    axes[0].set_ylabel("elevation (deg)")
    assert mappable is not None
    label = channel or ""
    fig.colorbar(mappable, ax=list(axes), shrink=0.8, label=label)  # pyright: ignore[reportUnknownMemberType]
    if title:
        fig.suptitle(title)  # pyright: ignore[reportUnknownMemberType]
    return fig
