"""Bulk Honey Factory still-image conversion into compact fly-space caches."""

import hashlib
import json
from collections.abc import Callable, Sequence
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path
from typing import Any, cast

import numpy as np
from numpy.typing import NDArray

from fly_brain.adapters import sensor_layout_from_frame
from fly_brain.contracts._arrays import readonly
from fly_brain.models import LocalizationBatch
from fly_vision.config import FlyVisionConfig
from fly_vision.eye import FlyEye
from fly_vision.frame import SourceRef, load_mask, load_panorama
from fly_vision.lattice import default_lattice
from fly_vision.projection import honey_factory_panorama


@dataclass(frozen=True)
class CachedStillDataset:
    activations: NDArray[np.float32]  # (B, N, C)
    valid: NDArray[np.bool_]  # (N,)
    coverage: NDArray[np.float32]  # (B, N)
    directions: NDArray[np.float32]  # (N, 3)
    scene_ids: tuple[str, ...]
    sample_ids: tuple[str, ...]
    splits: tuple[str, ...]
    metadata: dict[str, Any]

    def __post_init__(self) -> None:
        activations = np.asarray(self.activations, dtype=np.float32)
        valid = np.asarray(self.valid, dtype=np.bool_)
        coverage = np.asarray(self.coverage, dtype=np.float32)
        directions = np.asarray(self.directions, dtype=np.float32)
        if activations.ndim != 3 or activations.shape[0] == 0:
            raise ValueError("cached activations must have non-empty shape (B, N, C)")
        rows, sites, _ = activations.shape
        if valid.shape != (sites,) or coverage.shape != (rows, sites):
            raise ValueError("cached validity or coverage shape does not match activations")
        if directions.shape != (sites, 3):
            raise ValueError("cached directions must have shape (N, 3)")
        if any(len(values) != rows for values in (self.scene_ids, self.sample_ids, self.splits)):
            raise ValueError("cached identifiers and splits must name every activation row")
        if any(split not in {"train", "validation", "test"} for split in self.splits):
            raise ValueError("cached splits must be train, validation or test")
        object.__setattr__(self, "activations", readonly(activations))
        object.__setattr__(self, "valid", readonly(valid))
        object.__setattr__(self, "coverage", readonly(coverage))
        object.__setattr__(self, "directions", readonly(directions))

    @property
    def rows(self) -> int:
        return self.activations.shape[0]

    def batch(self, split: str, *, include_coverage: bool = False) -> LocalizationBatch:
        selected = np.asarray([value == split for value in self.splits], dtype=np.bool_)
        if not selected.any():
            raise ValueError(f"cached dataset has no {split!r} samples")
        activations = self.activations[selected]
        valid_rows = np.broadcast_to(self.valid, (activations.shape[0], self.valid.size))
        features = np.concatenate(
            [
                np.where(valid_rows[:, :, None], activations, 0.0).reshape(
                    activations.shape[0], -1
                ),
                valid_rows.astype(np.float32),
            ],
            axis=1,
        )
        coverage = self.coverage[selected]
        present = np.max(coverage, axis=1) > 0
        weighted = coverage @ self.directions
        norms = np.linalg.norm(weighted, axis=1)
        direction = np.zeros_like(weighted, dtype=np.float32)
        nonzero = norms > 0
        direction[nonzero] = weighted[nonzero] / norms[nonzero, None]
        return LocalizationBatch(
            features=features,
            time_mask=np.ones(activations.shape[0], dtype=np.bool_),
            presence=present.astype(np.float32),
            direction=direction,
            coverage=coverage if include_coverage else None,
            site_count=self.valid.size,
        )

    def save(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        np.savez_compressed(
            path,
            activations=self.activations,
            valid=self.valid,
            coverage=self.coverage,
            directions=self.directions,
            scene_ids=np.asarray(self.scene_ids),
            sample_ids=np.asarray(self.sample_ids),
            splits=np.asarray(self.splits),
            metadata=np.asarray(json.dumps(self.metadata, sort_keys=True)),
        )

    @classmethod
    def load(cls, path: Path) -> "CachedStillDataset":
        with np.load(path, allow_pickle=False) as values:
            return cls(
                activations=values["activations"],
                valid=values["valid"],
                coverage=values["coverage"],
                directions=values["directions"],
                scene_ids=tuple(str(value) for value in values["scene_ids"]),
                sample_ids=tuple(str(value) for value in values["sample_ids"]),
                splits=tuple(str(value) for value in values["splits"]),
                metadata=json.loads(str(values["metadata"])),
            )


def convert_honey_factory_dataset(
    dataset_dir: Path,
    output_path: Path,
    *,
    matrix_cache: Path,
    workers: int = 1,
    progress: Callable[[int, int], None] | None = None,
) -> CachedStillDataset:
    if workers < 1:
        raise ValueError("workers must be at least 1")
    manifest_path = dataset_dir / "dataset.json"
    manifest = cast(dict[str, Any], json.loads(manifest_path.read_text()))
    if manifest.get("lifecycle") != "complete":
        raise ValueError(f"{dataset_dir} is not a complete dataset")
    capture = cast(dict[str, Any], manifest["capture"])
    if capture.get("mode") != "PANORAMA_310":
        raise ValueError("bulk still conversion currently requires PANORAMA_310")
    width, height = int(capture["width"]), int(capture["height"])
    lattice = default_lattice()
    eye = FlyEye(
        lattice,
        honey_factory_panorama(width, height),
        FlyVisionConfig(),
        cache_dir=matrix_cache,
    )
    samples = [
        cast(dict[str, Any], json.loads(line))
        for line in (dataset_dir / "samples.jsonl").read_text().splitlines()
        if line
    ]
    dataset_id = str(manifest["dataset_id"])
    activations: list[NDArray[np.float32]] = []
    coverage: list[NDArray[np.float32]] = []
    scene_ids: list[str] = []
    sample_ids: list[str] = []
    splits: list[str] = []
    if not samples:
        raise ValueError("dataset contains no samples")

    def paths_and_ids(
        sample: dict[str, Any],
    ) -> tuple[Path, Path, str, str, str]:
        files = cast(dict[str, Any], sample["files"])
        rgb = dataset_dir / str(cast(dict[str, Any], files["rgb"])["path"])
        mask = dataset_dir / str(cast(dict[str, Any], files["mask"])["path"])
        raw_scene_id = str(sample["scene_id"])
        raw_sample_id = str(sample["sample_id"])
        qualified_scene = f"{dataset_id}:{raw_scene_id}"
        qualified_sample = f"{dataset_id}:{raw_sample_id}"
        return rgb, mask, qualified_scene, qualified_sample, str(sample["split"])

    first_rgb, first_mask, first_scene, first_sample, first_split = paths_and_ids(samples[0])
    first_frame, first_labels = eye.sample(
        load_panorama(first_rgb),
        load_mask(first_mask),
        SourceRef(first_scene, first_sample, str(first_rgb)),
    )
    if first_labels is None:  # pragma: no cover - mask is always supplied above
        raise RuntimeError("fly-vision did not produce labels")
    activations.append(first_frame.activations)
    coverage.append(first_labels.honey_coverage)
    scene_ids.append(first_scene)
    sample_ids.append(first_sample)
    splits.append(first_split)
    if progress is not None:
        progress(1, len(samples))

    def convert(
        sample: dict[str, Any],
    ) -> tuple[NDArray[np.float32], NDArray[np.float32], str, str, str]:
        rgb, mask, scene_id, sample_id, split = paths_and_ids(sample)
        sample_activations, sample_coverage = eye.sample_arrays(load_panorama(rgb), load_mask(mask))
        return sample_activations, sample_coverage, scene_id, sample_id, split

    with ThreadPoolExecutor(max_workers=workers) as executor:
        converted = executor.map(convert, samples[1:])
        for index, (sample_activations, sample_coverage, scene_id, sample_id, split) in enumerate(
            converted, start=2
        ):
            activations.append(sample_activations)
            coverage.append(sample_coverage)
            scene_ids.append(scene_id)
            sample_ids.append(sample_id)
            splits.append(split)
            if progress is not None:
                progress(index, len(samples))
    layout = sensor_layout_from_frame(lattice, first_frame)
    result = CachedStillDataset(
        activations=np.stack(activations),
        valid=first_frame.valid,
        coverage=np.stack(coverage),
        directions=layout.directions,
        scene_ids=tuple(scene_ids),
        sample_ids=tuple(sample_ids),
        splits=tuple(splits),
        metadata={
            "format": "fly-brain-cached-stills-v1",
            "source_dataset_id": dataset_id,
            "source_manifest_sha256": hashlib.sha256(manifest_path.read_bytes()).hexdigest(),
            "source_samples_sha256": manifest["integrity"]["indexes"]["samples"]["sha256"],
            "layout_id": layout.layout_id,
            "sensor_config_hash": layout.config_hash,
        },
    )
    result.save(output_path)
    return result


def combine_batches(batches: Sequence[LocalizationBatch]) -> LocalizationBatch:
    if not batches:
        raise ValueError("at least one batch is required")
    first = batches[0]
    if any(
        batch.input_width != first.input_width or batch.site_count != first.site_count
        for batch in batches
    ):
        raise ValueError("all localisation batches must have equal dimensions")
    have_coverage = first.coverage is not None
    if any((batch.coverage is not None) is not have_coverage for batch in batches):
        raise ValueError("coverage must be present in every batch or none")
    return LocalizationBatch(
        features=np.concatenate([batch.features for batch in batches]),
        time_mask=np.concatenate([batch.time_mask for batch in batches]),
        presence=np.concatenate([batch.presence for batch in batches]),
        direction=np.concatenate([batch.direction for batch in batches]),
        coverage=(
            np.concatenate([cast(NDArray[np.float32], batch.coverage) for batch in batches])
            if have_coverage
            else None
        ),
        site_count=first.site_count,
    )
