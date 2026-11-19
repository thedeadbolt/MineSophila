from pathlib import Path

import numpy as np

from fly_brain.experiments import load_checkpoint, save_checkpoint


def test_checkpoint_round_trip_binds_parameters_to_manifest(tmp_path: Path) -> None:
    path = tmp_path / "model.npz"
    manifest = {"model": "fixture", "seed": 4}

    digest = save_checkpoint(path, {"weights": np.array([1.0, 2.0])}, manifest)
    parameters, loaded_manifest = load_checkpoint(path)

    assert len(digest) == 64
    np.testing.assert_array_equal(parameters["weights"], [1.0, 2.0])
    assert loaded_manifest == manifest
