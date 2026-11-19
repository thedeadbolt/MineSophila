from pathlib import Path

import numpy as np

from fly_brain.datasets import CachedStillDataset, combine_batches


def cached_stills() -> CachedStillDataset:
    return CachedStillDataset(
        activations=np.array(
            [
                [[1.0, 2.0], [9.0, 9.0]],
                [[3.0, 4.0], [8.0, 8.0]],
                [[5.0, 6.0], [7.0, 7.0]],
            ],
            dtype=np.float32,
        ),
        valid=np.array([True, False]),
        coverage=np.array([[1.0, 0.0], [0.0, 0.0], [0.5, 0.0]], dtype=np.float32),
        directions=np.array([[0.0, 1.0, 0.0], [1.0, 0.0, 0.0]], dtype=np.float32),
        scene_ids=("scene-train", "scene-validation", "scene-test"),
        sample_ids=("sample-train", "sample-validation", "sample-test"),
        splits=("train", "validation", "test"),
        metadata={"format": "fixture-v1"},
    )


def test_cached_stills_round_trip_without_pickle(tmp_path: Path) -> None:
    source = cached_stills()
    path = tmp_path / "stills.npz"

    source.save(path)
    loaded = CachedStillDataset.load(path)

    np.testing.assert_array_equal(loaded.activations, source.activations)
    np.testing.assert_array_equal(loaded.coverage, source.coverage)
    assert loaded.scene_ids == source.scene_ids
    assert loaded.sample_ids == source.sample_ids
    assert loaded.splits == source.splits
    assert loaded.metadata == source.metadata


def test_cached_stills_build_masked_localization_batch() -> None:
    batch = cached_stills().batch("train", include_coverage=True)

    assert batch.features.tolist() == [[1.0, 2.0, 0.0, 0.0, 1.0, 0.0]]
    assert batch.presence.tolist() == [1.0]
    assert batch.direction.tolist() == [[0.0, 1.0, 0.0]]
    assert batch.coverage is not None
    assert batch.coverage.tolist() == [[1.0, 0.0]]


def test_combine_batches_preserves_optional_coverage() -> None:
    dataset = cached_stills()

    combined = combine_batches(
        [
            dataset.batch("train", include_coverage=True),
            dataset.batch("test", include_coverage=True),
        ]
    )

    assert combined.features.shape == (2, 6)
    assert combined.coverage is not None
    assert combined.coverage.tolist() == [[1.0, 0.0], [0.5, 0.0]]
