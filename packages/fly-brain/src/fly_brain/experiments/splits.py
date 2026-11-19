"""Deterministic scene-grouped dataset splitting."""

import hashlib
from collections.abc import Sequence
from dataclasses import dataclass

import numpy as np
from numpy.typing import NDArray

from fly_brain.contracts import BrainExample
from fly_brain.contracts._arrays import readonly


@dataclass(frozen=True)
class SplitFractions:
    train: float = 0.7
    validation: float = 0.15
    test: float = 0.15

    def __post_init__(self) -> None:
        values = (self.train, self.validation, self.test)
        if any(not np.isfinite(value) or value <= 0 for value in values):
            raise ValueError("split fractions must be finite and positive")
        if not np.isclose(sum(values), 1.0):
            raise ValueError("split fractions must sum to 1")


@dataclass(frozen=True)
class DatasetSplit:
    train_indices: NDArray[np.int64]
    validation_indices: NDArray[np.int64]
    test_indices: NDArray[np.int64]
    train_scenes: tuple[str, ...]
    validation_scenes: tuple[str, ...]
    test_scenes: tuple[str, ...]

    def __post_init__(self) -> None:
        for name in ("train_indices", "validation_indices", "test_indices"):
            values = np.asarray(getattr(self, name))
            if values.ndim != 1 or not np.issubdtype(values.dtype, np.integer):
                raise ValueError(f"{name} must be a one-dimensional integer array")
            object.__setattr__(self, name, readonly(values.astype(np.int64)))
        scene_sets = [set(self.train_scenes), set(self.validation_scenes), set(self.test_scenes)]
        if any(scene_sets[i] & scene_sets[j] for i in range(3) for j in range(i + 1, 3)):
            raise ValueError("dataset split scene groups must be disjoint")


def split_examples(
    examples: Sequence[BrainExample],
    *,
    seed: int,
    fractions: SplitFractions | None = None,
) -> DatasetSplit:
    """Assign complete scenes to splits using a stable seeded hash order."""
    fractions = SplitFractions() if fractions is None else fractions
    if not examples:
        raise ValueError("at least one example is required")
    scene_ids = sorted({example.provenance.scene_id for example in examples})
    if len(scene_ids) < 3:
        raise ValueError("at least three distinct scenes are required for train/validation/test")
    ordered = sorted(scene_ids, key=lambda scene_id: _scene_key(scene_id, seed))
    counts = _scene_counts(len(ordered), fractions)
    train_end = counts[0]
    validation_end = train_end + counts[1]
    train_scenes = tuple(sorted(ordered[:train_end]))
    validation_scenes = tuple(sorted(ordered[train_end:validation_end]))
    test_scenes = tuple(sorted(ordered[validation_end:]))
    assignments = {
        scene_id: split
        for split, scenes in enumerate((train_scenes, validation_scenes, test_scenes))
        for scene_id in scenes
    }
    indices: list[list[int]] = [[], [], []]
    for index, example in enumerate(examples):
        indices[assignments[example.provenance.scene_id]].append(index)
    return DatasetSplit(
        train_indices=np.asarray(indices[0], dtype=np.int64),
        validation_indices=np.asarray(indices[1], dtype=np.int64),
        test_indices=np.asarray(indices[2], dtype=np.int64),
        train_scenes=train_scenes,
        validation_scenes=validation_scenes,
        test_scenes=test_scenes,
    )


def _scene_key(scene_id: str, seed: int) -> bytes:
    return hashlib.sha256(f"{seed}:{scene_id}".encode()).digest()


def _scene_counts(total: int, fractions: SplitFractions) -> tuple[int, int, int]:
    weights = np.asarray([fractions.train, fractions.validation, fractions.test])
    shares = total * weights
    counts = np.floor(shares).astype(np.int64)
    unassigned = total - int(counts.sum())
    for index in np.argsort(-(shares - counts), kind="stable")[:unassigned]:
        counts[index] += 1
    for empty in np.flatnonzero(counts == 0):
        donor = int(np.argmax(counts))
        counts[donor] -= 1
        counts[empty] += 1
    return int(counts[0]), int(counts[1]), int(counts[2])
