from dataclasses import replace
from pathlib import Path

import numpy as np
import pytest

from fly_brain.datasets import CachedClipDataset, combine_clip_features


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
        metadata={
            "format": "fixture",
            "sensory_features": {"groups": {"small": [1, 3]}},
        },
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
    assert cached.clip_type_mask("test", "occlusion").tolist() == [[True, True, True]]


def test_clip_cache_selects_named_sensory_feature_groups() -> None:
    cached = dataset()

    batch = cached.batch("test", feature_group="small")

    assert batch.features.shape == (1, 3, 2)
    np.testing.assert_array_equal(batch.features[0], cached.sensory_features[2:5][:, [1, 3]])


def test_clip_cache_action_batch_requires_visible_teacher_labels() -> None:
    cached = dataset()

    batch = cached.action_batch("test")

    assert batch.features.shape == (1, 3, 6)
    assert batch.sequence_mask.tolist() == [[True, True, True]]
    assert batch.action_mask.tolist() == [[False, True, False]]


def test_clip_cache_round_trips_without_pickle(tmp_path: Path) -> None:
    original = dataset()
    path = tmp_path / "clips.npz"

    original.save(path)
    loaded = CachedClipDataset.load(path)

    assert loaded.clip_ids == original.clip_ids
    assert loaded.metadata == original.metadata
    np.testing.assert_array_equal(loaded.sensory_features, original.sensory_features)


def test_combined_clip_features_name_each_part_and_composite_groups() -> None:
    connectome = dataset()
    frontend = replace(
        connectome,
        sensory_features=-np.arange(10, dtype=np.float32).reshape(5, 2),
        metadata={"format": "fixture", "sensory_features": ["a", "b"]},
    )

    combined = combine_clip_features(
        [("frontend", frontend), ("connectome", connectome)],
        {"frontend_small": ("frontend", "small")},
    )

    assert combined.sensory_features.shape == (5, 6)
    np.testing.assert_array_equal(
        combined.batch("test", feature_group="frontend").features[0],
        frontend.sensory_features[2:5],
    )
    np.testing.assert_array_equal(
        combined.batch("test", feature_group="small").features[0],
        connectome.sensory_features[2:5][:, [1, 3]],
    )
    assert combined.sensory_feature_indexes("connectome").tolist() == [2, 3, 4, 5]
    assert combined.sensory_feature_indexes("frontend_small").tolist() == [0, 1, 3, 5]


def test_combined_clip_features_reject_misaligned_targets() -> None:
    first = dataset()
    shifted = replace(first, presence=np.array([1, 0, 0, 1, 1], dtype=np.float32))

    with pytest.raises(ValueError, match="presence"):
        combine_clip_features([("a", first), ("b", shifted)], {})


def test_combine_clip_features_command_round_trips(tmp_path: Path) -> None:
    from fly_brain.cli import main

    connectome = dataset()
    frontend = replace(
        connectome,
        sensory_features=np.zeros((5, 2), dtype=np.float32),
        metadata={"format": "fixture", "sensory_features": ["a", "b"]},
    )
    connectome.save(tmp_path / "connectome.npz")
    frontend.save(tmp_path / "frontend.npz")
    out = tmp_path / "combined.npz"

    status = main(
        [
            "combine-clip-features",
            "--part",
            f"frontend={tmp_path / 'frontend.npz'}",
            "--part",
            f"connectome={tmp_path / 'connectome.npz'}",
            "--group",
            "frontend_small=frontend,small",
            "--out",
            str(out),
        ]
    )

    combined = CachedClipDataset.load(out)
    assert status == 0
    assert combined.sensory_feature_indexes("frontend_small").tolist() == [0, 1, 3, 5]


def test_clip_cache_flattens_split_frames_for_framewise_probes() -> None:
    cached = dataset()

    frames = cached.frame_batch("test", feature_group="small")

    assert frames.features.shape == (3, 2)
    np.testing.assert_array_equal(frames.features, cached.sensory_features[2:5][:, [1, 3]])
    assert frames.presence.tolist() == [0.0, 1.0, 1.0]
    assert frames.time_mask.all()
    assert frames.coverage is None


def test_probe_clip_features_command_reports_each_group(tmp_path: Path) -> None:
    import json

    from fly_brain.cli import main

    cached = replace(
        dataset(),
        splits=("train", "validation"),
        metadata={"format": "fixture", "sensory_features": {"groups": {"a": [0], "b": [1, 2]}}},
    )
    cached.save(tmp_path / "cache.npz")
    report = tmp_path / "probe.json"

    status = main(
        [
            "probe-clip-features",
            str(tmp_path / "cache.npz"),
            "--groups",
            "a",
            "b",
            "--steps",
            "5",
            "--seeds",
            "0",
            "1",
            "--evaluation-split",
            "validation",
            "--report",
            str(report),
        ]
    )

    written = json.loads(report.read_text())
    assert status == 0
    assert [entry["group"] for entry in written["groups"]] == ["a", "b"]
    assert [entry["input_width"] for entry in written["groups"]] == [1, 2]
    assert len(written["groups"][0]["seeds"]) == 2
