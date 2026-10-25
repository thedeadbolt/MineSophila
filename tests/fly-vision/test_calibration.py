import json
from pathlib import Path

import numpy as np
import pytest

from fly_vision.eye import FlyEye, load_mask, load_panorama
from fly_vision.lattice import default_lattice
from fly_vision.projection import EquirectSpec, to_unit

FIX = Path(__file__).parent / "fixtures" / "calibration"


@pytest.mark.skipif(
    not (FIX / "targets.json").exists(), reason="calibration capture not provided yet"
)
def test_known_directions() -> None:
    # {"fov_h": .., "fov_v": .., "center_el": .., "targets": [{"az": .., "el": ..}]}
    t = json.loads((FIX / "targets.json").read_text())
    pano = load_panorama(FIX / "panorama.png")
    eye = FlyEye(
        default_lattice(),
        EquirectSpec(
            pano.shape[1], pano.shape[0], t["fov_h"], t["fov_v"], center_el_deg=t["center_el"]
        ),
    )
    lab = eye.sample(pano, load_mask(FIX / "mask.png"))[1]
    assert lab is not None
    dirs = to_unit(*eye.lattice.direction_deg.T)
    for tg in t["targets"]:
        near = np.degrees(np.arccos(np.clip(dirs @ to_unit(tg["az"], tg["el"]), -1, 1))) < 5.0
        assert lab.honey_coverage[near].max() >= 0.2, tg
