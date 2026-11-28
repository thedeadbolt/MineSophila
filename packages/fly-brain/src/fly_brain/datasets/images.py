"""Raw-panorama baselines using audited still-cache targets."""

import json
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any, cast

import numpy as np
from numpy.typing import NDArray

from fly_brain.datasets.stills import CachedStillDataset
from fly_brain.models import LocalizationBatch
from fly_vision.frame import load_panorama


def downsample_panorama(panorama: NDArray[np.uint8], factor: int) -> NDArray[np.float32]:
    """Area-average an RGB panorama by an integer factor and flatten it."""
    if factor < 1:
        raise ValueError("image downsample factor must be positive")
    height, width, channels = panorama.shape
    if channels != 3 or height % factor or width % factor:
        raise ValueError("RGB panorama dimensions must be divisible by the downsample factor")
    reduced = panorama.reshape(height // factor, factor, width // factor, factor, channels).mean(
        axis=(1, 3), dtype=np.float32
    )
    return (reduced / 255.0).reshape(-1).astype(np.float32, copy=False)


def load_image_localization_batches(
    dataset_dir: Path,
    labels: CachedStillDataset,
    *,
    downsample_factor: int,
    workers: int,
) -> dict[str, LocalizationBatch]:
    """Load raw RGB inputs while reusing audited targets and split assignments."""
    if workers < 1:
        raise ValueError("image loading workers must be positive")
    samples = [
        cast(dict[str, Any], json.loads(line))
        for line in (dataset_dir / "samples.jsonl").read_text().splitlines()
        if line
    ]
    raw_ids = tuple(str(sample["sample_id"]) for sample in samples)
    cached_ids = tuple(value.rsplit(":", 1)[-1] for value in labels.sample_ids)
    if raw_ids != cached_ids:
        raise ValueError("raw image rows do not match the audited label cache")

    def load(sample: dict[str, Any]) -> NDArray[np.float32]:
        files = cast(dict[str, Any], sample["files"])
        relative = str(cast(dict[str, Any], files["rgb"])["path"])
        return downsample_panorama(load_panorama(dataset_dir / relative), downsample_factor)

    with ThreadPoolExecutor(max_workers=workers) as executor:
        features = np.stack(list(executor.map(load, samples)))
    weighted = labels.coverage @ labels.directions
    norms = np.linalg.norm(weighted, axis=1)
    directions = np.zeros_like(weighted, dtype=np.float32)
    nonzero = norms > 0
    directions[nonzero] = weighted[nonzero] / norms[nonzero, None]
    presence = (np.max(labels.coverage, axis=1) > 0).astype(np.float32)
    batches: dict[str, LocalizationBatch] = {}
    for split in ("train", "validation", "test"):
        selected = np.asarray(labels.splits) == split
        batches[split] = LocalizationBatch(
            features=features[selected],
            time_mask=np.ones(np.count_nonzero(selected), dtype=np.bool_),
            presence=presence[selected],
            direction=directions[selected],
            coverage=None,
            site_count=1,
        )
    return batches
