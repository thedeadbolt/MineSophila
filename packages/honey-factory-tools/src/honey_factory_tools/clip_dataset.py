"""Cross-record and file rules of clip datasets (M12-2), as the mod's ClipValidator applies them.

For a clip dataset, :func:`honey_factory_tools.dataset.validate` adds, beyond each record's
own rules:

- scenes as for stills (IDs, seeds, slot classes and splits, source patches);
- clips: unique IDs, an existing scene of the same split, no-honey clips exactly in
  target-absent scenes, each scene's ``accepted_clip_count``, the manifest's counts;
- per clip, its frame records: one per frame, in order, for this clip;
- the teacher's realizability on every tick: a ``seen`` belief needs the observed frame
  (the previous tick's last) to show at least ``teacher.min_visible_pixels`` and names the
  scene's honey; ``memory`` names the last sighting; nothing is seen without honey;
- the executed commands, as the clip and its ticks record them, within the motor limits;
  drift ticks, ticks by mode and ticks by driver as the ticks record them; scripts drive
  only scan and pass-by clips and the start of an occlusion clip;
- files: present with the recorded size and SHA-256, videos holding every frame, and each
  frame's mask pixel count and box as its record states (skipped by ``quick``); folders
  under ``clips/`` no clip references are errors.
"""

import hashlib
from collections import Counter
from pathlib import Path
from typing import Any

from honey_factory_tools import clips as videos
from honey_factory_tools import ids
from honey_factory_tools.schemas import Schemas

type Record = dict[str, Any]

MANIFEST = "dataset.json"
CLIP_ROOT = "clips"
# One clip in this many has its masks decoded by a quick validation.
QUICK_SHARE = 20
_COMMANDS = ("forward", "strafe", "turn_rate", "pitch_rate", "jump", "delay_ticks")


def consistency(
    manifest: Record,
    scenes: list[Record],
    clips: list[Record],
    scene_errors: list[str],
    by_id: dict[str, Record],
) -> list[str]:
    """Clips against their scenes and the manifest.

    ``scene_errors`` and ``by_id`` come from the scene rules.
    """
    errors = list(scene_errors)
    clip_ids: set[str] = set()
    per_scene: Counter[str] = Counter()
    for clip in clips:
        label = f"clip {clip['clip_id']}: "
        if clip["clip_id"] in clip_ids:
            errors.append(label + "duplicate clip_id")
        clip_ids.add(clip["clip_id"])
        scene = by_id.get(clip["scene_id"])
        if scene is None:
            errors.append(label + f"references missing scene {clip['scene_id']}")
            continue
        per_scene[clip["scene_id"]] += 1
        if clip["split"] != scene["split"]:
            errors.append(
                label + f"split {clip['split']} differs from its scene's {scene['split']}"
            )
        present: bool = scene["target"]["present"]
        if present == (clip["clip_type"] == "no_honey"):
            errors.append(label + "no-honey clips belong exactly to target-absent scenes")
        if clip["frames_per_tick"] != manifest["generator"]["clips"]["frames_per_tick"]:
            errors.append(label + "frames_per_tick differs from the dataset's")
    for scene in scenes:
        recorded = scene.get("accepted_clip_count")
        if recorded is None:
            errors.append(f"scene {scene['scene_id']}: lacks accepted_clip_count")
        elif recorded != per_scene[scene["scene_id"]]:
            errors.append(
                f"scene {scene['scene_id']}: records {recorded} clips "
                f"but has {per_scene[scene['scene_id']]}"
            )
    counts: Record = manifest["counts"]
    if len(scenes) != counts["accepted_scenes"]:
        errors.append(
            f"{MANIFEST}: counts.accepted_scenes is {counts['accepted_scenes']} "
            f"but there are {len(scenes)} scenes"
        )
    if len(clips) != counts.get("accepted_clips"):
        errors.append(
            f"{MANIFEST}: counts.accepted_clips is {counts.get('accepted_clips')} "
            f"but there are {len(clips)} clips"
        )
    if manifest["lifecycle"] == "complete":
        target: Record = manifest["target"]
        with_honey = sum(1 for scene in scenes if scene["target"]["present"])
        quotas = (target["target_present_scenes"], target["target_absent_scenes"])
        if manifest.get("slice") is None and (with_honey, len(scenes) - with_honey) != quotas:
            errors.append(
                f"{MANIFEST}: {with_honey} present and {len(scenes) - with_honey} absent scenes "
                f"miss the quotas {quotas}"
            )
        recorded_counts: Record = manifest["splits"]["counts"]
        scene_totals = Counter(scene["split"] for scene in scenes)
        clip_totals = Counter(clip["split"] for clip in clips)
        for split in ids.SPLITS:
            entry: Record = recorded_counts[split]
            if (entry["scenes"], entry.get("clips")) != (scene_totals[split], clip_totals[split]):
                errors.append(
                    f"{MANIFEST}: splits.counts.{split} does not match the "
                    f"{scene_totals[split]} scenes and {clip_totals[split]} clips"
                )
    return errors


def frame_errors(
    manifest: Record, scene: Record | None, clip: Record, frames: list[Record]
) -> list[str]:
    """One clip's frame records: order, the teacher's realizability, commands, drift, modes."""
    label = f"clip {clip['clip_id']}: "
    errors: list[str] = []
    if len(frames) != clip["frame_count"]:
        errors.append(label + f"{len(frames)} frame records for {clip['frame_count']} frames")
    for index, frame in enumerate(frames):
        if (
            frame["clip_id"] != clip["clip_id"]
            or frame["frame"] != index
            or frame["frames_per_tick"] != clip["frames_per_tick"]
        ):
            errors.append(label + f"frame record {index} is out of place")
            return errors
    settings: Record = manifest["generator"]["clips"]
    threshold: int = settings["teacher"]["min_visible_pixels"]
    motor: Record = settings["motor"]
    honey: list[int] | None = scene["target"].get("position") if scene is not None else None
    ticks = {frame["tick"]: frame for frame in frames if "tick_labels" in frame}
    executed: list[Record] = []
    drifting = 0
    modes: Counter[str] = Counter()
    drivers: Counter[str] = Counter()
    scripted_type = clip["clip_type"] in ("scan", "pass_by")
    teacher_took_over = False
    last_seen: list[int] | None = None
    for tick in range(1, clip["tick_count"] + 1):
        frame = ticks.get(tick)
        if frame is None:
            errors.append(label + f"tick {tick} has no labelled frame")
            continue
        labels: Record = frame["tick_labels"]
        belief: Record = labels["belief"]
        position: list[int] | None = belief["position"]
        observed = ticks.get(tick - 1)
        shown = 0 if observed is None else observed["target"]["visible_pixels"]
        if belief["source"] == "seen":
            if honey is None:
                errors.append(label + f"tick {tick}: honey seen in a scene without honey")
            elif position != honey:
                errors.append(
                    label
                    + f"tick {tick}: the seen position {position} is not the scene's honey {honey}"
                )
            if shown < threshold:
                errors.append(
                    label + f"tick {tick}: honey seen while the observed frame showed {shown} "
                    f"of the {threshold} pixels needed"
                )
            last_seen = position
        elif belief["source"] == "memory" and position != last_seen:
            errors.append(
                label + f"tick {tick}: the remembered position {position} "
                f"differs from the last sighting {last_seen}"
            )
        command: Record = labels["executed"]
        executed.append(command)
        for name, value in (("executed", command), ("teacher", labels["teacher"])):
            if not _within(value, motor):
                errors.append(
                    label + f"tick {tick}: the {name} command is outside the motor limits"
                )
        drifting += 1 if labels["drifting"] else 0
        modes[labels["teacher_mode"]] += 1
        driver: str = labels["driver"]
        drivers[driver] += 1
        if driver == "script":
            if not scripted_type and clip["clip_type"] != "occlusion":
                errors.append(
                    label + f"tick {tick}: driven by a script in a {clip['clip_type']} clip"
                )
            elif teacher_took_over:
                errors.append(label + f"tick {tick}: script ticks after the teacher took over")
        elif scripted_type:
            errors.append(
                label + f"tick {tick}: a {clip['clip_type']} clip is driven by its script, "
                f"not {driver}"
            )
        else:
            teacher_took_over = True
    if [_command(command) for command in executed] != [
        _command(command) for command in clip["executed"]
    ]:
        errors.append(label + "executed commands differ from its ticks' executed commands")
    summary: Record = clip["summary"]
    if summary["drift_ticks"] != drifting:
        errors.append(
            label + f"summary.drift_ticks is {summary['drift_ticks']} but {drifting} ticks drifted"
        )
    if dict(summary["ticks_by_mode"]) != {
        mode: modes[mode] for mode in ("track", "reacquire", "search")
    }:
        errors.append(label + f"summary.ticks_by_mode differs from the ticks' modes {dict(modes)}")
    if dict(summary["ticks_by_driver"]) != {
        driver: drivers[driver] for driver in ("teacher", "teacher_drift", "script")
    }:
        errors.append(
            label + f"summary.ticks_by_driver differs from the ticks' drivers {dict(drivers)}"
        )
    return errors


def file_errors(root: Path, clip: Record, frames: list[Record], pixels: bool) -> list[str]:
    """A clip's files: present, of the recorded size and hash; videos holding every frame; masks
    as labelled. A file that differs from its record is still decoded, so every problem shows."""
    label = f"clip {clip['clip_id']}: "
    errors: list[str] = []
    present: set[str] = set()
    for key in ("rgb", "visible_mask", "frames"):
        file: Record = clip["files"][key]
        path = root / file["path"]
        if not path.is_file():
            errors.append(label + f"{file['path']} is missing")
            continue
        present.add(key)
        data = path.read_bytes()
        if len(data) != file["bytes"]:
            errors.append(label + f"{file['path']} has {len(data)} bytes, not {file['bytes']}")
        elif hashlib.sha256(data).hexdigest() != file["sha256"]:
            errors.append(label + f"{file['path']} does not match its SHA-256")
    held: dict[str, int] = {}
    for key in ("rgb", "visible_mask"):
        if key in present:
            held[key] = videos.frame_count(root / clip["files"][key]["path"])
            if held[key] != clip["frame_count"]:
                errors.append(
                    label + f"{clip['files'][key]['path']}: the video holds {held[key]} frames, "
                    f"not {clip['frame_count']}"
                )
    if pixels and "visible_mask" in held:
        errors += _mask_errors(root, clip, frames)
    return errors


def _mask_errors(root: Path, clip: Record, frames: list[Record]) -> list[str]:
    """Each frame's mask pixel count and box against its record."""
    label = f"clip {clip['clip_id']}: "
    errors: list[str] = []
    mask: Record = clip["files"]["visible_mask"]
    decoded = videos.decode_frames(root / mask["path"], mask["width"], mask["height"], "L")
    for index, image in enumerate(decoded):
        if index >= len(frames):
            break
        target: Record = frames[index]["target"]
        histogram = image.histogram()
        count = sum(histogram[1:])
        if histogram[255] != count:
            errors.append(label + f"frame {index}: mask has values other than 0 and 255")
            continue
        box = image.getbbox()
        found = None if box is None else [box[0], box[1], box[2] - box[0], box[3] - box[1]]
        if count != target["visible_pixels"] or found != target["visible_bbox_xywh"]:
            errors.append(
                label + f"frame {index}: the label ({target['visible_pixels']} pixels, "
                f"box {target['visible_bbox_xywh']}) does not match its mask "
                f"({count} pixels, box {found})"
            )
    return errors


def unreferenced(root: Path, clips: list[Record]) -> list[str]:
    """Folders under ``clips/`` no clip references, and unfinished ``.partial`` ones."""
    directory = root / CLIP_ROOT
    if not directory.is_dir():
        return []
    referenced = {clip["files"]["frames"]["path"].rsplit("/", 1)[0] for clip in clips}
    errors: list[str] = []
    for folder in sorted(directory.glob("*/*/*")):
        relative = folder.relative_to(root).as_posix()
        if relative not in referenced:
            reason = (
                "is an unfinished clip"
                if relative.endswith(".partial")
                else "no clip references this folder"
            )
            errors.append(f"{relative}: {reason}")
    return errors


def quick_pick(clip: Record) -> bool:
    """Whether a quick validation decodes this clip's masks: a fixed one in {QUICK_SHARE} clips."""
    digest = hashlib.sha256(clip["clip_id"].encode()).digest()
    return digest[0] % QUICK_SHARE == 0


def validate(
    root: Path,
    manifest: Record,
    scenes: list[Record],
    clips: list[Record],
    schemas: Schemas,
    quick: bool,
) -> list[str]:
    """Every clip rule beyond single records, the scene rules included."""
    # Imported here: the dataset module imports this one.
    from honey_factory_tools import dataset

    scene_errors: list[str] = []
    by_id, _ = dataset.scene_rules(manifest, scenes, scene_errors)
    errors = consistency(manifest, scenes, clips, scene_errors, by_id)
    for clip in clips:
        path = root / clip["files"]["frames"]["path"]
        frame_index_errors: list[str] = []
        frames = (
            dataset.read_index(path, "frame", schemas, frame_index_errors) if path.is_file() else []
        )
        errors += frame_index_errors
        errors += frame_errors(manifest, by_id.get(clip["scene_id"]), clip, frames)
        errors += file_errors(root, clip, frames, pixels=not quick or quick_pick(clip))
    errors += unreferenced(root, clips)
    return errors


def _within(command: Record | None, motor: Record) -> bool:
    if command is None:
        return False
    return (
        abs(command["forward"]) <= 1.0
        and abs(command["strafe"]) <= 1.0
        and abs(command["turn_rate"]) <= motor["max_turn_rate"]
        and abs(command["pitch_rate"]) <= motor["max_pitch_rate"]
        and command["delay_ticks"] == 0
    )


def _command(command: Record | None) -> tuple[Any, ...]:
    return () if command is None else tuple(command[name] for name in _COMMANDS)
