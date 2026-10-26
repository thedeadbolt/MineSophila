"""Metrics of clip datasets (M12-2), for the pilot's exit gate and the final-volume decision.

Per dataset, and in total:

- clip types, and fallbacks (a detour or occlusion clip that became an approach);
- approach clips that reached the honey, all of them and those without drift; reach by
  type for approach, detour and occlusion, and why the others missed (timeout,
  unreachable, teacher_failure: the last must stay at 0);
- falls: clip attempts redrawn because the body fell or walked out of the arena (from the
  manifest's ``counts.clip_redraws``), which must stay at 0;
- search coverage: the mean share of reachable cells seen by ticks 4, 8, 16 and 32 in
  no-honey clips;
- realizability violations: ticks whose belief the validators reject (see
  :func:`honey_factory_tools.clip_dataset.frame_errors`);
- ticks the edge guard stopped short of a ledge;
- the honey's status over frames, and the teacher's mode and who drove over ticks;
- generation time per clip and frames per second, from the manifest's timestamps;
- disk use per clip, and the compression ratio against raw RGB frames.
"""

import json
from collections import Counter
from datetime import datetime
from pathlib import Path
from typing import Any

from honey_factory_tools import clip_dataset
from honey_factory_tools.schemas import Schemas

type Record = dict[str, Any]

COVERAGE_TICKS = (4, 8, 16, 32)
_REALIZABILITY = (
    "seen while",
    "is not the scene's honey",
    "differs from the last sighting",
    "without honey",
)


def report(datasets: list[Path], schemas: Schemas) -> Record:
    """The metrics of each clip dataset at ``datasets``, and their totals."""
    results = [_dataset(path, schemas) for path in datasets]
    totals: Record = {
        "clips": sum(result["clips"] for result in results),
        "frames": sum(result["frames"] for result in results),
        "video_bytes": sum(result["video_bytes"] for result in results),
        "seconds": sum(result["seconds"] for result in results),
        "realizability_violations": sum(result["realizability_violations"] for result in results),
        "guarded_ticks": sum(result["guarded_ticks"] for result in results),
        "falls": sum(result["falls"] for result in results),
        "teacher_failures": sum(
            result["miss_reasons"].get("teacher_failure", 0) for result in results
        ),
    }
    approach = Counter[str]()
    for result in results:
        approach.update(result["approach"])
    totals["approach"] = dict(approach)
    totals["seconds_per_clip"] = totals["seconds"] / totals["clips"] if totals["clips"] else None
    totals["bytes_per_clip"] = totals["video_bytes"] / totals["clips"] if totals["clips"] else None
    return {"datasets": results, "totals": totals}


def _dataset(path: Path, schemas: Schemas) -> Record:
    manifest: Record = json.loads((path / "dataset.json").read_text(encoding="utf-8"))
    scenes = {
        record["scene_id"]: record
        for record in (
            json.loads(line)
            for line in (path / "scenes.jsonl").read_text(encoding="utf-8").splitlines()
        )
    }
    clips = [
        json.loads(line) for line in (path / "clips.jsonl").read_text(encoding="utf-8").splitlines()
    ]
    types: Counter[str] = Counter()
    fallbacks: Counter[str] = Counter()
    approach: Counter[str] = Counter(
        {"clips": 0, "reached": 0, "without_drift": 0, "reached_without_drift": 0}
    )
    statuses: Counter[str] = Counter()
    modes: Counter[str] = Counter()
    drivers: Counter[str] = Counter()
    coverage: dict[int, list[float]] = {tick: [] for tick in COVERAGE_TICKS}
    violations = 0
    guarded = 0
    reach: dict[str, Counter[str]] = {}
    misses: Counter[str] = Counter()
    frames_total = 0
    video_bytes = 0
    raw_bytes = 0
    for clip in clips:
        types[clip["clip_type"]] += 1
        if clip["requested_type"] != clip["clip_type"]:
            fallbacks[f"{clip['requested_type']}->{clip['clip_type']}"] += 1
        frames = [
            json.loads(line)
            for line in (path / clip["files"]["frames"]["path"])
            .read_text(encoding="utf-8")
            .splitlines()
        ]
        frames_total += len(frames)
        for key in ("rgb", "visible_mask"):
            video_bytes += clip["files"][key]["bytes"]
        raw_bytes += (
            len(frames) * clip["files"]["rgb"]["width"] * clip["files"]["rgb"]["height"] * 3
        )
        guarded += clip["summary"]["guarded_ticks"]
        if clip["summary"]["reached"] is not None:
            tally = reach.setdefault(clip["clip_type"], Counter({"clips": 0, "reached": 0}))
            tally["clips"] += 1
            tally["reached"] += clip["summary"]["reached"] is True
        if clip["summary"]["miss_reason"] is not None:
            misses[clip["summary"]["miss_reason"]] += 1
        if clip["clip_type"] == "approach":
            reached = clip["summary"]["reached"] is True
            calm = clip["summary"]["drift_ticks"] == 0
            approach["clips"] += 1
            approach["reached"] += reached
            approach["without_drift"] += calm
            approach["reached_without_drift"] += reached and calm
        for frame in frames:
            statuses[frame["target"]["target_status"]] += 1
            labels: Record | None = frame.get("tick_labels")
            if labels is None or frame["tick"] == 0:
                continue
            modes[labels["teacher_mode"]] += 1
            drivers[labels["driver"]] += 1
            if clip["clip_type"] == "no_honey" and frame["tick"] in coverage:
                coverage[frame["tick"]].append(labels["coverage"])
        errors = clip_dataset.frame_errors(manifest, scenes.get(clip["scene_id"]), clip, frames)
        violations += sum(
            1 for error in errors if any(phrase in error for phrase in _REALIZABILITY)
        )
    seconds = (
        (_time(manifest["completed_at_utc"]) - _time(manifest["created_at_utc"])).total_seconds()
        if manifest.get("completed_at_utc")
        else 0.0
    )
    return {
        "dataset": manifest["dataset_id"],
        "level": manifest["difficulty"],
        "directory": str(path),
        "clips": len(clips),
        "frames": frames_total,
        "types": dict(types),
        "fallbacks": dict(fallbacks),
        "approach": dict(approach),
        "realizability_violations": violations,
        "guarded_ticks": guarded,
        "reach": {kind: dict(tally) for kind, tally in sorted(reach.items())},
        "miss_reasons": dict(sorted(misses.items())),
        "falls": sum(
            number
            for key, number in manifest["counts"].get("clip_redraws", {}).items()
            if key.split("/")[0] in ("fall", "out_of_arena")
        ),
        "search_coverage": {
            str(tick): round(sum(values) / len(values), 4)
            for tick, values in coverage.items()
            if values
        },
        "statuses": dict(statuses),
        "ticks_by_mode": {mode: modes[mode] for mode in ("track", "reacquire", "search")},
        "ticks_by_driver": {
            driver: drivers[driver] for driver in ("teacher", "teacher_drift", "script")
        },
        "seconds": seconds,
        "seconds_per_clip": seconds / len(clips) if clips and seconds else None,
        "frames_per_second": frames_total / seconds if seconds else None,
        "video_bytes": video_bytes,
        "bytes_per_clip": video_bytes / len(clips) if clips else None,
        "bytes_per_frame": video_bytes / frames_total if frames_total else None,
        "raw_ratio": raw_bytes / video_bytes if video_bytes else None,
        "codec": manifest["generator"]["clips"]["codec"]["name"],
    }


def table(result: Record) -> str:
    """A plain-text summary, one dataset per line and the totals."""
    header = ["dataset", "lvl", "clips", "approach reached", "no drift", "viol", "cov@16"]
    header += ["s/clip", "MB/clip", "vs raw"]
    widths = (34, 3, 5, 18, 9, 5, 7, 7, 8, 7)
    rows: list[list[str]] = [header]
    for entry in result["datasets"]:
        approach = entry["approach"]
        coverage = entry["search_coverage"].get("16")
        per_clip = entry["seconds_per_clip"]
        ratio = entry["raw_ratio"]
        rows.append(
            [
                str(entry["dataset"]),
                str(entry["level"]),
                str(entry["clips"]),
                f"{approach['reached']}/{approach['clips']}",
                f"{approach['reached_without_drift']}/{approach['without_drift']}",
                str(entry["realizability_violations"]),
                "" if coverage is None else f"{coverage:.2f}",
                "" if per_clip is None else f"{per_clip:.1f}",
                f"{(entry['bytes_per_clip'] or 0) / 1e6:.1f}",
                "" if ratio is None else f"{ratio:.0f}x",
            ]
        )
    lines = [
        " ".join(
            cell.ljust(width) if index == 0 else cell.rjust(width)
            for index, (cell, width) in enumerate(zip(row, widths, strict=True))
        )
        for row in rows
    ]
    totals = result["totals"]
    lines.append(
        f"total: {totals['clips']} clips, {totals['realizability_violations']} realizability "
        f"violations, {totals['falls']} falls, {totals['teacher_failures']} teacher failures, "
        f"{totals['video_bytes'] / 1e9:.2f} GB"
    )
    return "\n".join(lines)


def _time(value: str) -> datetime:
    return datetime.fromisoformat(value)
