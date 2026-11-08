from itertools import pairwise

import matplotlib

matplotlib.use("Agg")

import numpy as np
import pytest
from matplotlib.collections import PathCollection
from matplotlib.figure import Figure

from fly_vision import CHANNELS
from fly_vision.lattice import generate_hex_lattice
from fly_vision.plot import CHANNEL_COLOURMAPS, plot_hex

LAT = generate_hex_lattice()
N = LAT.ommatidium_id.size
VALUES = np.random.default_rng(0).random(N)


def _valid() -> np.ndarray:
    valid = np.ones(N, bool)
    valid[:20] = False
    return valid


def _collections(fig: Figure) -> tuple[list[PathCollection], list[PathCollection]]:
    """(coloured value collections, invalid-outline collections) across both eye panels."""
    cols = [c for ax in fig.axes[:2] for c in ax.collections if isinstance(c, PathCollection)]
    coloured = [c for c in cols if c.get_array() is not None]
    return coloured, [c for c in cols if c.get_array() is None]


def test_plot_hex() -> None:
    fig = plot_hex(LAT, VALUES, _valid(), title="t")
    assert len(fig.axes) >= 2


def _luminance(rgba: tuple[float, float, float, float]) -> float:
    return 0.2126 * rgba[0] + 0.7152 * rgba[1] + 0.0722 * rgba[2]


@pytest.mark.parametrize("channel", CHANNELS)
def test_channel_colour_map_runs_from_black_to_bright(channel: str) -> None:
    cmap = CHANNEL_COLOURMAPS[channel]
    assert cmap(0.0)[:3] == (0.0, 0.0, 0.0)
    lum = [_luminance(cmap(x)) for x in np.linspace(0, 1, 11)]
    assert all(b > a for a, b in pairwise(lum))  # brighter = stronger response
    assert lum[-1] > 0.6


def test_channel_hues() -> None:
    r, g, b, _ = CHANNEL_COLOURMAPS["R1_6"](0.5)
    assert r == pytest.approx(g, abs=0.02)  # grey
    assert g == pytest.approx(b, abs=0.02)
    r, g, b, _ = CHANNEL_COLOURMAPS["R7"](0.5)
    assert b > g  # violet
    assert r > g
    r, g, b, _ = CHANNEL_COLOURMAPS["R8"](0.5)
    assert g > r  # green
    assert g > b


@pytest.mark.parametrize("channel", CHANNELS)
def test_plot_uses_the_channel_colour_map(channel: str) -> None:
    coloured, _ = _collections(plot_hex(LAT, VALUES, _valid(), channel=channel))
    assert coloured
    assert all(c.get_cmap() is CHANNEL_COLOURMAPS[channel] for c in coloured)


def test_default_colour_map_is_greyscale_not_viridis() -> None:
    coloured, _ = _collections(plot_hex(LAT, VALUES))
    assert all(c.get_cmap() is CHANNEL_COLOURMAPS["R1_6"] for c in coloured)


def test_invalid_ommatidia_are_hollow_outlines() -> None:
    _, invalid = _collections(plot_hex(LAT, VALUES, _valid(), channel="R1_6"))
    drawn = [c for c in invalid if np.asarray(c.get_offsets()).shape[0]]
    assert sum(np.asarray(c.get_offsets()).shape[0] for c in drawn) == 20
    for c in drawn:
        face = np.asarray(c.get_facecolor(), dtype=float).reshape(-1, 4)
        edge = np.asarray(c.get_edgecolor(), dtype=float).reshape(-1, 4)
        assert (face[:, 3] == 0).all()  # no fill
        assert edge.shape[0] > 0  # but an outline


def test_unknown_channel_raises() -> None:
    with pytest.raises(ValueError, match="unknown channel"):
        plot_hex(LAT, VALUES, channel="R9")
