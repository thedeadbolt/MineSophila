"""Independent integrity checks across raw still labels and fly-space caches."""

import hashlib
import json
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, cast

import numpy as np

from fly_brain.datasets.stills import CachedStillDataset
from fly_vision.config import FlyVisionConfig
from fly_vision.eye import FlyEye
from fly_vision.frame import SourceRef, load_mask, load_panorama
from fly_vision.lattice import default_lattice
from fly_vision.projection import honey_factory_panorama, to_unit


@dataclass(frozen=True)
class StillLabelAuditConfig:
    sample_count: int = 64
    seed: int = 0
    value_tolerance: float = 1e-6
    centroid_tolerance_deg: float = 10.0

    def __post_init__(self) -> None:
        if self.sample_count < 1:
            raise ValueError("audit sample_count must be positive")
        if (
            not np.isfinite(self.value_tolerance)
            or not np.isfinite(self.centroid_tolerance_deg)
            or self.value_tolerance < 0
            or not 0 < self.centroid_tolerance_deg <= 180
        ):
            raise ValueError("audit tolerances must be non-negative and finite")


@dataclass(frozen=True)
class StillLabelAuditReport:
    dataset_id: str
    rows: int
    audited_rows: int
    positive_rows: int
    split_scene_counts: dict[str, int]
    mean_centroid_error_deg: float | None
    max_centroid_error_deg: float | None
    errors: tuple[str, ...]

    @property
    def passed(self) -> bool:
        return not self.errors

    def as_dict(self) -> dict[str, Any]:
        return {"format": "fly-brain-still-label-audit-v1", "passed": self.passed, **asdict(self)}


def audit_still_labels(
    dataset_dir: Path,
    cache: CachedStillDataset,
    *,
    matrix_cache: Path,
    config: StillLabelAuditConfig,
) -> StillLabelAuditReport:
    manifest_path = dataset_dir / "dataset.json"
    manifest = cast(dict[str, Any], json.loads(manifest_path.read_text()))
    samples_path = dataset_dir / "samples.jsonl"
    samples_bytes = samples_path.read_bytes()
    samples = [
        cast(dict[str, Any], json.loads(line))
        for line in samples_bytes.decode().splitlines()
        if line
    ]
    dataset_id = str(manifest["dataset_id"])
    errors: list[str] = []
    if manifest.get("lifecycle") != "complete":
        errors.append("source dataset lifecycle is not complete")
    if cache.metadata.get("source_dataset_id") != dataset_id:
        errors.append("cache source_dataset_id does not match the source manifest")
    manifest_sha = hashlib.sha256(manifest_path.read_bytes()).hexdigest()
    if cache.metadata.get("source_manifest_sha256") != manifest_sha:
        errors.append("cache source manifest hash does not match the source file")
    samples_sha = hashlib.sha256(samples_bytes).hexdigest()
    if cache.metadata.get("source_samples_sha256") != samples_sha:
        errors.append("cache source samples hash does not match samples.jsonl")
    if cache.rows != len(samples):
        errors.append(f"cache has {cache.rows} rows but samples.jsonl has {len(samples)}")
    limit = min(cache.rows, len(samples))
    expected_samples = tuple(f"{dataset_id}:{sample['sample_id']}" for sample in samples[:limit])
    expected_scenes = tuple(f"{dataset_id}:{sample['scene_id']}" for sample in samples[:limit])
    expected_splits = tuple(str(sample["split"]) for sample in samples[:limit])
    if cache.sample_ids[:limit] != expected_samples:
        errors.append("cached sample IDs or row order differ from samples.jsonl")
    if cache.scene_ids[:limit] != expected_scenes:
        errors.append("cached scene IDs or row order differ from samples.jsonl")
    if cache.splits[:limit] != expected_splits:
        errors.append("cached splits or row order differ from samples.jsonl")

    scene_splits: dict[str, set[str]] = {}
    for sample in samples:
        scene_splits.setdefault(str(sample["scene_id"]), set()).add(str(sample["split"]))
    leaking = sorted(scene for scene, splits in scene_splits.items() if len(splits) != 1)
    if leaking:
        errors.append(f"{len(leaking)} scenes occur in more than one split")
    split_scene_counts = {
        split: sum(1 for splits in scene_splits.values() if split in splits)
        for split in ("train", "validation", "test")
    }

    invalid = ~cache.valid
    if np.any(np.abs(cache.activations[:, invalid]) > config.value_tolerance):
        errors.append("invalid receptor sites contain non-zero activations")
    if np.any(np.abs(cache.coverage[:, invalid]) > config.value_tolerance):
        errors.append("invalid receptor sites contain non-zero honey coverage")
    if not np.isfinite(cache.activations).all() or not np.isfinite(cache.coverage).all():
        errors.append("cache contains non-finite values")
    if np.any((cache.coverage < 0) | (cache.coverage > 1)):
        errors.append("cached honey coverage lies outside [0, 1]")

    rng = np.random.default_rng(config.seed)
    count = min(config.sample_count, limit)
    indices = np.sort(rng.choice(limit, size=count, replace=False)) if count else np.array([])
    capture = cast(dict[str, Any], manifest["capture"])
    projection = honey_factory_panorama(int(capture["width"]), int(capture["height"]))
    eye = FlyEye(default_lattice(), projection, FlyVisionConfig(), cache_dir=matrix_cache)
    centroid_errors: list[float] = []
    for index_value in indices:
        index = int(index_value)
        sample = samples[index]
        files = cast(dict[str, Any], sample["files"])
        rgb_path = dataset_dir / str(cast(dict[str, Any], files["rgb"])["path"])
        mask_path = dataset_dir / str(cast(dict[str, Any], files["mask"])["path"])
        mask = load_mask(mask_path)
        target = cast(dict[str, Any], sample["target"])
        visible_pixels = int(target["visible_pixels"])
        if int(np.count_nonzero(mask)) != visible_pixels:
            errors.append(f"row {index}: mask pixels differ from target.visible_pixels")
        frame, labels = eye.sample(
            load_panorama(rgb_path),
            mask,
            SourceRef(str(sample["scene_id"]), str(sample["sample_id"]), str(rgb_path)),
        )
        if labels is None:  # pragma: no cover - the audit always supplies a mask
            raise RuntimeError("fly-vision returned no labels for an audited mask")
        if not np.allclose(
            frame.activations, cache.activations[index], atol=config.value_tolerance, rtol=0
        ):
            errors.append(f"row {index}: cached activations do not reproduce from the RGB image")
        if not np.allclose(
            labels.honey_coverage, cache.coverage[index], atol=config.value_tolerance, rtol=0
        ):
            errors.append(f"row {index}: cached coverage does not reproduce from the mask")
        sampled_visible = bool(np.any(cache.coverage[index] > 0))
        if sampled_visible != (visible_pixels > 0):
            errors.append(f"row {index}: sampled presence disagrees with raw mask visibility")
        centroid_value = target.get("visible_centroid_xy")
        if sampled_visible and isinstance(centroid_value, list):
            centroid = cast(list[Any], centroid_value)
            if len(centroid) != 2:
                errors.append(f"row {index}: visible centroid does not have two coordinates")
                continue
            expected = _centroid_direction(projection, float(centroid[0]), float(centroid[1]))
            weighted = np.asarray(cache.coverage[index] @ cache.directions, dtype=np.float64)
            weighted /= np.linalg.norm(weighted)
            error = float(np.degrees(np.arccos(np.clip(np.dot(expected, weighted), -1.0, 1.0))))
            centroid_errors.append(error)
            if error > config.centroid_tolerance_deg:
                errors.append(
                    f"row {index}: coverage direction differs from the mask centroid by "
                    f"{error:.3f} degrees"
                )
    return StillLabelAuditReport(
        dataset_id=dataset_id,
        rows=cache.rows,
        audited_rows=count,
        positive_rows=int(np.count_nonzero(np.any(cache.coverage > 0, axis=1))),
        split_scene_counts=split_scene_counts,
        mean_centroid_error_deg=(float(np.mean(centroid_errors)) if centroid_errors else None),
        max_centroid_error_deg=(float(np.max(centroid_errors)) if centroid_errors else None),
        errors=tuple(errors),
    )


def _centroid_direction(projection: Any, x: float, y: float) -> np.ndarray:
    azimuth = projection.center_az_deg + ((x + 0.5) / projection.width - 0.5) * projection.fov_h_deg
    elevation = (
        projection.center_el_deg + (0.5 - (y + 0.5) / projection.height) * projection.fov_v_deg
    )
    return np.asarray(
        to_unit(np.asarray(azimuth, dtype=np.float64), np.asarray(elevation, dtype=np.float64)),
        dtype=np.float64,
    )
