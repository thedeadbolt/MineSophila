"""Validate a whole dataset directory outside Minecraft.

This shares the contract's schemas and fixtures with the mod, never its Java code. It
checks, for a complete or failed dataset:

- ``dataset.json`` and every index line against the schemas and single-record rules;
- the manifest's index digests, when it has an integrity section;
- cross-record rules: unique IDs, references, split and target-presence inheritance,
  seed derivation, every scene in the class and split ``scene-slots-v1`` assigns (so no
  grouping key reaches two splits), and the level's visibility rule;
- for a complete dataset, the target-class quotas, every viewpoint and the split counts;
- every image file: present, matching its byte count and SHA-256, an 8-bit PNG of the
  recorded size, masks binary with the recorded statistics; and no unreferenced files.

A ``generating`` manifest means the run is still going or was interrupted; it is an
error unless explicitly allowed.
"""

import hashlib
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, cast

from honey_factory_tools import canonical, ids, images, records
from honey_factory_tools.schemas import Kind, Schemas

type Record = dict[str, Any]

MANIFEST = "dataset.json"
SCENES = "scenes.jsonl"
SAMPLES = "samples.jsonl"
IMAGE_ROOTS = ("rgb", "masks")


@dataclass
class Report:
    """What was read and every violation found; an empty ``errors`` list means valid."""

    lifecycle: str | None = None
    scenes: list[Record] = field(default_factory=list[Record])
    samples: list[Record] = field(default_factory=list[Record])
    errors: list[str] = field(default_factory=list[str])

    @property
    def passed(self) -> bool:
        return not self.errors


def validate(root: Path, schemas: Schemas, *, allow_generating: bool = False) -> Report:
    report = Report()
    manifest = _manifest(root, schemas, report.errors)
    digests: dict[str, Record] = {}
    indexes: tuple[tuple[str, Kind, list[Record]], ...] = (
        (SCENES, "scene", report.scenes),
        (SAMPLES, "sample", report.samples),
    )
    for name, kind, target in indexes:
        data = _read(root / name, name, report.errors)
        if data is not None:
            digests[name] = {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
            target += _index(data, name, kind, schemas, report.errors)

    if manifest is not None:
        report.lifecycle = manifest["lifecycle"]
        if manifest["lifecycle"] == "generating" and not allow_generating:
            report.errors.append(
                f"{MANIFEST}: lifecycle is generating; the run is unfinished or was interrupted"
            )
        integrity: Record | None = manifest.get("integrity")
        if integrity is not None:
            for name, key in ((SCENES, "scenes"), (SAMPLES, "samples")):
                if name in digests and integrity["indexes"][key] != digests[name]:
                    report.errors.append(
                        f"{MANIFEST}: integrity.indexes.{key} does not match {name}"
                    )
        report.errors.extend(consistency(manifest, report.scenes, report.samples))

    report.errors.extend(_files(root, report.samples))
    return report


def _manifest(root: Path, schemas: Schemas, errors: list[str]) -> Record | None:
    data = _read(root / MANIFEST, MANIFEST, errors)
    if data is None:
        return None
    record = _parse(data, MANIFEST, errors)
    if record is None or not _record_passes(record, "dataset", schemas, MANIFEST, errors):
        return None
    return record


def _read(path: Path, name: str, errors: list[str]) -> bytes | None:
    try:
        return path.read_bytes()
    except OSError as error:
        errors.append(f"{name}: cannot read: {error.strerror or error}")
        return None


def _parse(data: bytes, location: str, errors: list[str]) -> Record | None:
    try:
        value = canonical.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as error:
        errors.append(f"{location}: invalid JSON: {error}")
        return None
    if not isinstance(value, dict):
        errors.append(f"{location}: expected a JSON object")
        return None
    return cast(Record, value)


def _record_passes(
    record: Record, kind: Kind, schemas: Schemas, location: str, errors: list[str]
) -> bool:
    found = [f"{location}: {error}" for error in schemas.errors(kind, cast(Any, record))]
    if not found:
        rules = {
            "dataset": records.manifest_errors,
            "scene": records.scene_errors,
            "sample": records.sample_errors,
        }
        found = [f"{location}: {error}" for error in rules[kind](record)]
    errors.extend(found)
    return not found


def _index(data: bytes, name: str, kind: Kind, schemas: Schemas, errors: list[str]) -> list[Record]:
    if data.startswith(b"\xef\xbb\xbf"):
        errors.append(f"{name}: must not start with a byte-order mark")
        return []
    if data and not data.endswith(b"\n"):
        errors.append(f"{name}: every record, including the last, must end with \\n")
        return []
    valid: list[Record] = []
    for number, line in enumerate(data.split(b"\n")[:-1], 1):
        location = f"{name}:{number}"
        record = _parse(line, location, errors)
        if record is not None and _record_passes(record, kind, schemas, location, errors):
            valid.append(record)
    return valid


def consistency(manifest: Record, scenes: list[Record], samples: list[Record]) -> list[str]:
    """Cross-record rules over individually valid records."""
    errors: list[str] = []
    generator: Record = manifest["generator"]
    seed: int = generator["seed"]
    difficulty: int = manifest["difficulty"]
    block_id: str = manifest["target"]["block_id"]
    viewpoints: int = manifest["capture"]["viewpoints"]["count"]
    grouping: str = manifest["splits"]["grouping"]
    slots: list[ids.Slot] | None = None
    if grouping == "scene":
        ratios: Record = manifest["splits"]["ratios"]
        slots = ids.plan_slots(
            seed,
            generator["accepted_scenes"],
            manifest["target"]["target_present_scenes"],
            (ratios["train"], ratios["validation"], ratios["test"]),
        )

    by_id: dict[str, Record] = {}
    for scene in scenes:
        label = f"scene {scene['scene_id']}: "
        if scene["scene_id"] in by_id:
            errors.append(label + "duplicate scene_id")
        by_id[scene["scene_id"]] = scene
        if scene["difficulty"] != difficulty:
            errors.append(
                label + f"difficulty {scene['difficulty']} differs from the dataset's {difficulty}"
            )
        if scene["target"]["block_id"] != block_id:
            errors.append(label + f"target block differs from the dataset's {block_id}")
        generation: Record = scene["generation"]
        index: int = generation["scene_index"]
        attempt: int = generation["attempt_index"]
        seeds: Record = generation["seeds"]
        if any(
            seeds[purpose] != ids.derive_seed(purpose, seed, index, attempt)
            for purpose in ("layout", "target", "viewpoint")
        ):
            errors.append(
                label + "seeds do not derive from the dataset seed, scene index and attempt index"
            )
        if slots is not None:
            if index >= len(slots):
                errors.append(
                    label + f"scene_index {index} exceeds the requested {len(slots)} scenes"
                )
            else:
                slot = slots[index]
                if scene["split"] != slot.split:
                    errors.append(
                        label + f"split {scene['split']} differs from the {slot.split} "
                        "that scene-slots-v1 assigns"
                    )
                if scene["target"]["present"] != slot.target_present:
                    errors.append(
                        label + "target presence differs from the class scene-slots-v1 assigns"
                    )

    visibility: Record = generator["visibility"]
    sample_ids: set[str] = set()
    base_viewpoints: dict[str, set[int]] = {}
    for sample in samples:
        label = f"sample {sample['sample_id']}: "
        if sample["sample_id"] in sample_ids:
            errors.append(label + "duplicate sample_id")
        sample_ids.add(sample["sample_id"])
        scene = by_id.get(sample["scene_id"])
        if scene is None:
            errors.append(label + f"scene_id {sample['scene_id']} does not exist")
            continue
        if sample["split"] != scene["split"]:
            errors.append(
                label + f"split {sample['split']} differs from its scene's {scene['split']}"
            )
        target: Record = sample["target"]
        if target["target_present"] != scene["target"]["present"]:
            errors.append(label + "target_present differs from its scene")
        if sample["projection"]["mode"] != manifest["capture"]["mode"]:
            errors.append(label + "capture mode differs from the dataset's")
        if sample["viewpoint_index"] >= viewpoints:
            errors.append(label + f"viewpoint_index exceeds the configured {viewpoints} viewpoints")
        rgb: Record = sample["files"]["rgb"]
        overrides: Record = sample["projection"].get("overrides", {})
        size = (manifest["capture"]["width"], manifest["capture"]["height"])
        if not overrides and (rgb["width"], rgb["height"]) != size:
            errors.append(label + f"image size differs from the configured {size[0]}x{size[1]}")
        augmentation: Record | None = sample.get("augmentation")
        if augmentation is None:
            seen = base_viewpoints.setdefault(scene["scene_id"], set())
            if sample["viewpoint_index"] in seen:
                errors.append(label + f"duplicate base viewpoint {sample['viewpoint_index']}")
            seen.add(sample["viewpoint_index"])
            if target["target_present"] and not _visible_enough(visibility, target):
                errors.append(
                    label + f"target_status {target['target_status']} with visibility_ratio "
                    f"{target['visibility_ratio']} fails the dataset's {visibility['rule']} rule"
                )
        elif augmentation["source_sample_id"] not in sample_ids:
            errors.append(
                label
                + f"augmentation source {augmentation['source_sample_id']} does not precede it"
            )

    base_samples = {scene_id: len(seen) for scene_id, seen in base_viewpoints.items()}
    for scene in scenes:
        count = base_samples.get(scene["scene_id"], 0)
        if count != scene["accepted_sample_count"]:
            errors.append(
                f"scene {scene['scene_id']}: accepted_sample_count "
                f"{scene['accepted_sample_count']} but {count} base samples reference it"
            )
    counts: Record = manifest["counts"]
    if len(scenes) != counts["accepted_scenes"]:
        errors.append(
            f"{MANIFEST}: counts.accepted_scenes is {counts['accepted_scenes']} "
            f"but there are {len(scenes)} scenes"
        )
    if sum(base_samples.values()) != counts["accepted_samples"]:
        errors.append(
            f"{MANIFEST}: counts.accepted_samples does not match the "
            f"{sum(base_samples.values())} base samples"
        )
    if manifest["lifecycle"] == "complete":
        errors += _complete(manifest, scenes, base_samples)
    return errors


def _visible_enough(visibility: Record, target: Record) -> bool:
    status: str = target["target_status"]
    rule: str = visibility["rule"]
    allowed = {
        "fully_visible": {"fully_visible"},
        "partially_visible": {"partially_visible"},
        "at_least_partly_visible": {"fully_visible", "partially_visible"},
    }[rule]
    if status not in allowed:
        return False
    if rule == "fully_visible":
        return True
    ratio: float = target["visibility_ratio"]
    low: float | None = visibility["min_ratio"]
    high: float | None = visibility["max_ratio"]
    return (low is None or ratio >= low) and (high is None or ratio <= high)


def _complete(manifest: Record, scenes: list[Record], base_samples: dict[str, int]) -> list[str]:
    errors: list[str] = []
    target: Record = manifest["target"]
    present = sum(1 for scene in scenes if scene["target"]["present"])
    quotas = (target["target_present_scenes"], target["target_absent_scenes"])
    if (present, len(scenes) - present) != quotas:
        errors.append(
            f"{MANIFEST}: {present} present and {len(scenes) - present} absent scenes "
            f"miss the quotas {quotas}"
        )
    viewpoints: int = manifest["capture"]["viewpoints"]["count"]
    for scene in scenes:
        if scene["accepted_sample_count"] != viewpoints:
            errors.append(
                f"scene {scene['scene_id']}: has {scene['accepted_sample_count']} "
                f"base samples, not {viewpoints}"
            )
    recorded: Record = manifest["splits"]["counts"]
    scene_totals = Counter(scene["split"] for scene in scenes)
    sample_totals: Counter[str] = Counter()
    for scene in scenes:
        sample_totals[scene["split"]] += base_samples.get(scene["scene_id"], 0)
    for split in ids.SPLITS:
        if (recorded[split]["scenes"], recorded[split]["samples"]) != (
            scene_totals[split],
            sample_totals[split],
        ):
            errors.append(f"{MANIFEST}: splits.counts.{split} does not match the records")
    if manifest["splits"]["grouping"] == "source_world":
        worlds: dict[int, str] = {}
        for scene in scenes:
            world: int = scene["source_patch"]["world_seed"]
            if worlds.setdefault(world, scene["split"]) != scene["split"]:
                errors.append(
                    f"scene {scene['scene_id']}: source world {world} appears in two splits"
                )
    return errors


def _files(root: Path, samples: list[Record]) -> list[str]:
    errors: list[str] = []
    referenced: set[str] = set()
    for sample in samples:
        label = f"sample {sample['sample_id']}: "
        rgb: Record = sample["files"]["rgb"]
        mask: Record = sample["files"]["mask"]
        referenced.update((rgb["path"], mask["path"]))
        _image(root, rgb, 3, label + "rgb", errors)
        decoded = _image(root, mask, 1, label + "mask", errors)
        if decoded is None:
            continue
        try:
            statistics = images.mask_statistics(decoded)
        except images.ImageError as error:
            errors.append(f"{label}mask {error}")
            continue
        target: Record = sample["target"]
        recorded = (
            target["visible_pixels"],
            target["visible_bbox_xywh"],
            target["visible_centroid_xy"],
        )
        if (statistics.positive_pixels, statistics.bbox_xywh, statistics.centroid_xy) != recorded:
            errors.append(
                label + "visible_pixels, visible_bbox_xywh or visible_centroid_xy "
                "differ from the saved mask"
            )

    for image_root in IMAGE_ROOTS:
        directory = root / image_root
        if directory.is_dir():
            for path in sorted(directory.rglob("*")):
                relative = path.relative_to(root).as_posix()
                if path.is_file() and relative not in referenced:
                    errors.append(f"{relative}: no sample references this file")
    return errors


def _image(
    root: Path, file: Record, channels: int, label: str, errors: list[str]
) -> images.Image | None:
    path = root / file["path"]
    try:
        data = path.read_bytes()
    except OSError:
        errors.append(f"{label}: {file['path']} is missing")
        return None
    if len(data) != file["bytes"] or hashlib.sha256(data).hexdigest() != file["sha256"]:
        errors.append(f"{label}: {file['path']} does not match its recorded byte count and SHA-256")
        return None
    try:
        image = images.decode(data)
    except images.ImageError as error:
        errors.append(f"{label}: {file['path']} {error}")
        return None
    if (image.width, image.height, image.channels) != (file["width"], file["height"], channels):
        found = f"{image.width}x{image.height} with {image.channels} channels"
        errors.append(f"{label}: {file['path']} is {found}")
        return None
    return image
