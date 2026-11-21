from pathlib import Path

import numpy as np

from fly_brain.datasets import CachedClipDataset


def dataset() -> CachedClipDataset:
    frames = 5
    return CachedClipDataset(
        sensory_features=np.arange(frames * 4, dtype=np.float32).reshape(frames, 4),
        motion_context=np.ones((frames, 2), dtype=np.float32),
        presence=np.array([1, 1, 0, 1, 1], dtype=np.float32),
        direction=np.tile(np.array([1.0, 0.0, 0.0], dtype=np.float32), (frames, 1)),
        visible=np.array([True, False, False, True, True]),
        teacher_actions=np.zeros((frames, 6), dtype=np.float32),
        teacher_action_mask=np.array([True, False, True, True, False]),
        offsets=np.array([0, 2, 5]),
        clip_ids=("train-clip", "test-clip"),
        scene_ids=("train-scene", "test-scene"),
        splits=("train", "test"),
        clip_types=("approach", "occlusion"),
        metadata={"format": "fixture"},
    )


def test_clip_cache_batches_variable_sequences_with_optional_context() -> None:
    cached = dataset()

    vision = cached.batch("test")
    context = cached.batch("test", include_context=True)

    assert vision.features.shape == (1, 3, 4)
    assert context.features.shape == (1, 3, 6)
    assert vision.presence.tolist() == [[0.0, 1.0, 1.0]]


def test_clip_cache_exposes_visible_and_occluded_intervals() -> None:
    cached = dataset()

    assert cached.interval_mask("test", "visible").tolist() == [[False, True, True]]
    assert cached.interval_mask("test", "occluded").tolist() == [[True, False, False]]


def test_clip_cache_round_trips_without_pickle(tmp_path: Path) -> None:
    original = dataset()
    path = tmp_path / "clips.npz"

    original.save(path)
    loaded = CachedClipDataset.load(path)

    assert loaded.clip_ids == original.clip_ids
    assert loaded.metadata == original.metadata
    np.testing.assert_array_equal(loaded.sensory_features, original.sensory_features)
