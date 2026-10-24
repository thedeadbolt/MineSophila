from collections.abc import Callable
from pathlib import Path
from typing import Any

import pytest

from honey_factory_tools import ids


def test_seeds_match_vectors(vectors_dir: Path, load_json: Callable[[Path], Any]) -> None:
    for case in load_json(vectors_dir / "seeds.json")["cases"]:
        args = (case["dataset_seed"], case["scene_index"], case["attempt_index"])
        for purpose in ("layout", "target", "viewpoint"):
            assert ids.derive_seed(purpose, *args) == case[purpose]


def test_seed_inputs_are_bounded() -> None:
    with pytest.raises(ValueError, match="range"):
        ids.derive_seed("layout", ids.MAX_SEED + 1, 0, 0)


def test_ids_and_paths_match_vectors(vectors_dir: Path, load_json: Callable[[Path], Any]) -> None:
    vectors = load_json(vectors_dir / "ids.json")
    for case in vectors["samples"]:
        scene = ids.scene_id(case["scene_index"])
        sample = ids.sample_id(scene, case["viewpoint_index"])
        assert (scene, sample, ids.shard(sample)) == (
            case["scene_id"],
            case["sample_id"],
            case["shard"],
        )
        assert ids.image_path("rgb", "train", sample) == case["rgb_path_train"]
        assert ids.image_path("masks", "test", sample) == case["mask_path_test"]
    for case in vectors["datasets"]:
        assert ids.dataset_id(case["name"], case["config_sha256"]) == case["dataset_id"]


def test_scene_slots_match_vectors(vectors_dir: Path, load_json: Callable[[Path], Any]) -> None:
    for case in load_json(vectors_dir / "scene-slots.json")["cases"]:
        ratios = (case["ratios"][0], case["ratios"][1], case["ratios"][2])
        slots = ids.plan_slots(
            case["dataset_seed"], case["accepted_scenes"], case["target_present_scenes"], ratios
        )
        expected = [(s["scene_index"], s["target_present"], s["split"]) for s in case["slots"]]
        assert [(s.scene_index, s.target_present, s.split) for s in slots] == expected


def test_patch_slots_match_vectors(vectors_dir: Path, load_json: Callable[[Path], Any]) -> None:
    for case in load_json(vectors_dir / "patch-slots.json")["cases"]:
        ratios = (case["ratios"][0], case["ratios"][1], case["ratios"][2])
        slots = ids.plan_patch_slots(
            case["dataset_seed"],
            case["accepted_scenes"],
            case["target_present_scenes"],
            case["scenes_per_patch"],
            ratios,
        )
        expected = [
            (s["scene_index"], s["target_present"], s["split"], s["patch"]) for s in case["slots"]
        ]
        assert [(s.scene_index, s.target_present, s.split, s.group) for s in slots] == expected
        by_patch: dict[int, set[str]] = {}
        for slot in slots:
            by_patch.setdefault(slot.group, set()).add(slot.split)
        assert all(len(splits) == 1 for splits in by_patch.values())


def test_split_counts_use_largest_remainder() -> None:
    assert ids.split_counts(3, (0.5, 0.25, 0.25)) == [1, 1, 1]
    assert ids.split_counts(5, (0.5, 0.25, 0.25)) == [3, 1, 1]
    assert ids.split_counts(10, (0.8, 0.1, 0.1)) == [8, 1, 1]
