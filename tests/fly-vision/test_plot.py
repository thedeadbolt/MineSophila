import matplotlib

matplotlib.use("Agg")

import numpy as np

from fly_vision.lattice import generate_hex_lattice
from fly_vision.plot import plot_hex


def test_plot_hex() -> None:
    lat = generate_hex_lattice()
    valid = np.ones(lat.ommatidium_id.size, bool)
    valid[:20] = False
    fig = plot_hex(lat, np.random.default_rng(0).random(lat.ommatidium_id.size), valid, title="t")
    assert len(fig.axes) >= 2
