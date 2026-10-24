"""Seed, identifier, shard and scene-slot algorithms of contract 1.x.

These mirror the Java mod's ``seed-v1``, ``ids-v1``, ``scene-slots-v1`` and
``patch-slots-v1`` definitions but are implemented independently, so shared golden vectors
check both.
"""

import hashlib
from dataclasses import dataclass
from decimal import ROUND_FLOOR, Decimal
from typing import Literal

SEEDS_ALGORITHM = "seed-v1"
IDS_ALGORITHM = "ids-v1"
SLOTS_ALGORITHM = "scene-slots-v1"
PATCH_SLOTS_ALGORITHM = "patch-slots-v1"
MAX_SEED = 2**53 - 1

type Split = Literal["train", "validation", "test"]
SPLITS: tuple[Split, ...] = ("train", "validation", "test")


def _sha256(text: str) -> str:
    return hashlib.sha256(text.encode()).hexdigest()


def derive_seed(purpose: str, dataset_seed: int, scene_index: int, attempt_index: int) -> int:
    """First 8 bytes (big-endian) of the purpose's SHA-256, shifted to 53 bits."""
    if not 0 <= dataset_seed <= MAX_SEED or scene_index < 0 or attempt_index < 0:
        raise ValueError("seed inputs out of range")
    text = f"honey-factory/{SEEDS_ALGORITHM}/{purpose}/{dataset_seed}/{scene_index}/{attempt_index}"
    return int.from_bytes(hashlib.sha256(text.encode()).digest()[:8], "big") >> 11


def dataset_id(name: str, config_sha256: str) -> str:
    return f"{name}-{config_sha256[:12]}"


def scene_id(scene_index: int) -> str:
    return f"s{scene_index:06d}"


def sample_id(scene: str, viewpoint_index: int) -> str:
    return f"{scene}-v{viewpoint_index:02d}"


def shard(sample: str) -> str:
    return _sha256(sample)[:2]


def image_path(root: Literal["rgb", "masks"], split: Split, sample: str) -> str:
    return f"{root}/{split}/{shard(sample)}/{sample}.png"


@dataclass(frozen=True)
class Slot:
    scene_index: int
    target_present: bool
    split: Split
    group: int


def plan_slots(
    dataset_seed: int,
    accepted_scenes: int,
    target_present_scenes: int,
    ratios: tuple[float, float, float],
) -> list[Slot]:
    """Fix every slot's target class and split before generation."""
    by_class = _ordered(dataset_seed, "class", list(range(accepted_scenes)))
    present = set(by_class[:target_present_scenes])
    splits: dict[int, Split] = {}
    for target_class in (True, False):
        members = [i for i in range(accepted_scenes) if (i in present) == target_class]
        counts = split_counts(len(members), ratios)
        names: list[Split] = [
            split for split, count in zip(SPLITS, counts, strict=True) for _ in range(count)
        ]
        for index, name in zip(_ordered(dataset_seed, "split", members), names, strict=True):
            splits[index] = name
    return [Slot(i, i in present, splits[i], i) for i in range(accepted_scenes)]


def plan_patch_slots(
    dataset_seed: int,
    accepted_scenes: int,
    target_present_scenes: int,
    scenes_per_patch: int,
    ratios: tuple[float, float, float],
) -> list[Slot]:
    """``patch-slots-v1``: classes as in ``scene-slots-v1``, splits per source patch."""
    by_class = _ordered(dataset_seed, "class", list(range(accepted_scenes)))
    present = set(by_class[:target_present_scenes])
    patches = -(-accepted_scenes // scenes_per_patch)
    names: list[Split] = [
        split
        for split, count in zip(SPLITS, split_counts(patches, ratios), strict=True)
        for _ in range(count)
    ]
    ordered = _ordered(dataset_seed, "split", list(range(patches)), PATCH_SLOTS_ALGORITHM)
    patch_splits = dict(zip(ordered, names, strict=True))
    return [
        Slot(i, i in present, patch_splits[i // scenes_per_patch], i // scenes_per_patch)
        for i in range(accepted_scenes)
    ]


def split_counts(total: int, ratios: tuple[float, float, float]) -> list[int]:
    """Largest-remainder apportionment; ties go to train, then validation, then test."""
    exact = [Decimal(repr(ratio)) * total for ratio in ratios]
    counts = [int(value.to_integral_value(rounding=ROUND_FLOOR)) for value in exact]
    order = sorted(range(3), key=lambda i: (-(exact[i] - counts[i]), i))
    for step in range(total - sum(counts)):
        counts[order[step % 3]] += 1
    return counts


def _ordered(
    dataset_seed: int, purpose: str, indices: list[int], algorithm: str = SLOTS_ALGORITHM
) -> list[int]:
    def key(index: int) -> tuple[str, int]:
        return (_sha256(f"honey-factory/{algorithm}/{purpose}/{dataset_seed}/{index}"), index)

    return sorted(indices, key=key)
