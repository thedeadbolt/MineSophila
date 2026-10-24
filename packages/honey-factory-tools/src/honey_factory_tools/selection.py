"""Filters and reproducible review selections over sample records.

Selections never depend on file order or the machine. Each sample has the key
``SHA-256("honey-factory/review-selection-v1/<seed>/<sample_id>")``, compared as lowercase
hex:

- ``random`` takes the filtered samples in key order;
- ``scene`` takes them by scene ID, then viewpoint, ignoring the seed;
- ``stratified`` puts each filtered sample in its first matching stratum (``STRATA``, in
  order), orders each stratum by key, and takes one sample from each non-empty stratum in
  turn until enough are taken or none remain.

The strata are the review cases the specification names: absent targets, fully occluded
ones, other non-visible statuses, targets at the image edge or across a panorama's
vertical cube-face seam, low pixel counts, then the remaining partially and fully visible
targets.
"""

import hashlib
from collections.abc import Iterable, Sequence
from dataclasses import asdict, dataclass
from typing import Any

from honey_factory_tools import canonical

type Record = dict[str, Any]

ALGORITHM = "review-selection-v1"
MODES = ("random", "stratified", "scene")
STRATA = (
    "absent",
    "fully_occluded",
    "not_visible",
    "edge_or_seam",
    "low_pixel_count",
    "partially_visible",
    "fully_visible",
)
DEFAULT_LOW_PIXELS = 64


@dataclass(frozen=True)
class Filters:
    """Conditions a sample must meet; ``None`` leaves a condition out. Ranges are inclusive,
    and a range on a value a sample lacks (such as the ratio of an absent target) excludes it."""

    split: str | None = None
    status: str | None = None
    present: bool | None = None
    scene_id: str | None = None
    sample_id: str | None = None
    viewpoint: int | None = None
    min_ratio: float | None = None
    max_ratio: float | None = None
    min_pixels: int | None = None
    max_pixels: int | None = None
    min_distance: float | None = None
    max_distance: float | None = None

    def matches(self, sample: Record) -> bool:
        target: Record = sample["target"]
        exact = (
            (self.split, sample["split"]),
            (self.status, target["target_status"]),
            (self.present, target["target_present"]),
            (self.scene_id, sample["scene_id"]),
            (self.sample_id, sample["sample_id"]),
            (self.viewpoint, sample["viewpoint_index"]),
        )
        if any(wanted is not None and wanted != value for wanted, value in exact):
            return False
        ranges = (
            (self.min_ratio, self.max_ratio, target["visibility_ratio"]),
            (self.min_pixels, self.max_pixels, target["visible_pixels"]),
            (self.min_distance, self.max_distance, target.get("distance_blocks")),
        )
        for low, high, value in ranges:
            if low is None and high is None:
                continue
            if value is None or (low is not None and value < low):
                return False
            if high is not None and value > high:
                return False
        return True

    def to_json(self) -> Record:
        return {name: value for name, value in asdict(self).items() if value is not None}


@dataclass(frozen=True)
class Selection:
    """How samples were chosen; ``to_json`` identifies it in review records."""

    mode: str = "random"
    seed: int = 0
    count: int | None = None
    filters: Filters = Filters()
    low_pixels: int = DEFAULT_LOW_PIXELS

    def __post_init__(self) -> None:
        if self.mode not in MODES:
            raise ValueError(f"mode must be one of {', '.join(MODES)}, not {self.mode!r}")
        if self.count is not None and self.count < 0:
            raise ValueError("count must not be negative")

    def to_json(self) -> Record:
        record: Record = {
            "algorithm": ALGORITHM,
            "mode": self.mode,
            "seed": self.seed,
            "filters": self.filters.to_json(),
        }
        if self.count is not None:
            record["count"] = self.count
        if self.mode == "stratified":
            record["low_pixels"] = self.low_pixels
        record["sha256"] = canonical.sha256(record)
        return record


def key(seed: int, sample_id: str) -> str:
    return hashlib.sha256(f"honey-factory/{ALGORITHM}/{seed}/{sample_id}".encode()).hexdigest()


def stratum(sample: Record, seams: Sequence[float], low_pixels: int) -> str:
    """The first of ``STRATA`` that ``sample`` belongs to; ``seams`` are the x coordinates of
    vertical cube-face seams in its image."""
    target: Record = sample["target"]
    status: str = target["target_status"]
    if status in ("absent", "fully_occluded"):
        return status
    if status not in ("partially_visible", "fully_visible"):
        return "not_visible"
    box: list[int] | None = target["visible_bbox_xywh"]
    width: int = sample["files"]["mask"]["width"]
    height: int = sample["files"]["mask"]["height"]
    if box is not None:
        x, y, w, h = box
        if x == 0 or y == 0 or x + w == width or y + h == height:
            return "edge_or_seam"
        if any(x < seam < x + w for seam in seams):
            return "edge_or_seam"
    if target["visible_pixels"] < low_pixels:
        return "low_pixel_count"
    return status


def seam_columns(manifest: Record) -> list[float]:
    """Image x coordinates of a 310-degree panorama's vertical cube-face seams, at longitudes
    of ±45 and ±135 degrees; none for other projections."""
    capture: Record = manifest["capture"]
    projection: Record = capture.get("projection", {})
    if capture["mode"] != "PANORAMA_310" or "longitude_min_degrees" not in projection:
        return []
    low: float = projection["longitude_min_degrees"]
    high: float = projection["longitude_max_degrees"]
    width: int = capture["width"]
    return [
        (longitude - low) / (high - low) * width
        for longitude in (-135.0, -45.0, 45.0, 135.0)
        if low < longitude < high
    ]


def select(manifest: Record, samples: Iterable[Record], selection: Selection) -> list[Record]:
    """The selected samples, in selection order."""
    chosen = sorted(
        (sample for sample in samples if selection.filters.matches(sample)),
        key=lambda sample: key(selection.seed, sample["sample_id"]),
    )
    if selection.mode == "scene":
        chosen.sort(
            key=lambda sample: (sample["scene_id"], sample["viewpoint_index"], sample["sample_id"])
        )
    elif selection.mode == "stratified":
        seams = seam_columns(manifest)
        groups: dict[str, list[Record]] = {name: [] for name in STRATA}
        for sample in chosen:
            groups[stratum(sample, seams, selection.low_pixels)].append(sample)
        interleaved: list[Record] = []
        depth = 0
        while any(depth < len(group) for group in groups.values()):
            interleaved += [group[depth] for group in groups.values() if depth < len(group)]
            depth += 1
        chosen = interleaved
    return chosen if selection.count is None else chosen[: selection.count]
