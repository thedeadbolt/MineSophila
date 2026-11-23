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


def test_old_checkpoints_load_without_egocentric_weights(tmp_path: Path) -> None:
    from fly_brain.connectome import BrainParameters

    path = tmp_path / "old.npz"
    old = {
        "edge_gain": np.zeros(3, dtype=np.float32),
        "neuron_bias": np.zeros(4, dtype=np.float32),
        "readout_weights": np.zeros((2, 4), dtype=np.float32),
        "readout_bias": np.zeros(4, dtype=np.float32),
    }
    save_checkpoint(path, old, {"model": "old"})
    values, _ = load_checkpoint(path)

    parameters = BrainParameters(**values)  # pyright: ignore[reportArgumentType]

    assert parameters.egocentric_weights.shape == (0, 2)
