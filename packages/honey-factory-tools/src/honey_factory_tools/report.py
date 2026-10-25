"""The acceptance report: validation, integrity, capacity and audit evidence for M9 runs.

An acceptance folder holds one folder per run (``matrix``, ``pilot``), each with its dataset
directories, ``metrics/<dataset>.json`` written by the acceptance game tests and, when kept,
the dataset worlds under ``worlds/``. The report validates every dataset with the outside
validator, summarizes its contract facts and file sizes, folds in the run metrics and, for
an audited dataset, checks the seeded stratified audit selection's coverage and its reviews.
"""

import hashlib
import json
import math
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, cast

from honey_factory_tools import dataset, selection
from honey_factory_tools.catalog import Catalog
from honey_factory_tools.reviews import ReviewStore
from honey_factory_tools.schemas import Schemas

type Record = dict[str, Any]

RATIO_BINS = ((0.0, 0.25), (0.25, 0.5), (0.5, 0.75), (0.75, 1.0))


@dataclass
class Audit:
    """The audit of one dataset: its selection, coverage gaps and review outcomes."""

    selection: Record
    samples: list[str]
    coverage: Record
    gaps: list[str]
    outcomes: dict[str, str | None]

    @property
    def unreviewed(self) -> list[str]:
        return [i for i, outcome in self.outcomes.items() if outcome is None]

    @property
    def not_passed(self) -> list[str]:
        return [i for i, outcome in self.outcomes.items() if outcome not in (None, "pass")]

    @property
    def passed(self) -> bool:
        return not self.gaps and all(outcome == "pass" for outcome in self.outcomes.values())


@dataclass
class DatasetReport:
    run: str
    directory: Path
    manifest: Record | None
    manifest_sha256: str
    validation_errors: list[str]
    metrics: Record | None
    facts: Record = field(default_factory=dict[str, Any])
    audit: Audit | None = None

    @property
    def passed(self) -> bool:
        return (
            self.manifest is not None
            and self.manifest["lifecycle"] == "complete"
            and not self.validation_errors
            and (self.audit is None or self.audit.passed)
        )


def build(
    root: Path,
    schemas: Schemas,
    reviews: Path | None = None,
    audit_seed: int | None = None,
    audit_count: int = 200,
    audit_runs: tuple[str, ...] = ("pilot",),
) -> list[DatasetReport]:
    """Reports on every dataset under ``root``; datasets of ``audit_runs`` get an audit."""
    reports: list[DatasetReport] = []
    for run in sorted(path for path in root.iterdir() if path.is_dir()):
        metrics = _metrics(run / "metrics")
        for directory in sorted(run.iterdir()):
            if (
                directory.name in ("metrics", "worlds")
                or not (directory / dataset.MANIFEST).is_file()
            ):
                continue
            report = _dataset(run.name, directory, schemas, metrics)
            if audit_seed is not None and run.name in audit_runs and report.manifest:
                report.audit = audit(directory, schemas, reviews, audit_seed, audit_count)
            reports.append(report)
    return reports


def _metrics(directory: Path) -> dict[str, Record]:
    found: dict[str, Record] = {}
    if directory.is_dir():
        for path in sorted(directory.glob("*.json")):
            record = json.loads(path.read_text(encoding="utf-8"))
            if "dataset_id" in record:
                found[record["dataset_id"]] = record
    return found


def _dataset(
    run: str, directory: Path, schemas: Schemas, metrics: dict[str, Record]
) -> DatasetReport:
    result = dataset.validate(directory, schemas)
    manifest_bytes = (directory / dataset.MANIFEST).read_bytes()
    report = DatasetReport(
        run=run,
        directory=directory,
        manifest=result.manifest,
        manifest_sha256=hashlib.sha256(manifest_bytes).hexdigest(),
        validation_errors=result.errors,
        metrics=None,
    )
    manifest = result.manifest
    if manifest is None:
        return report
    report.metrics = metrics.get(manifest["dataset_id"])
    scenes, samples = result.scenes, result.samples
    present = sum(1 for scene in scenes if scene["target"]["present"])
    target = manifest["target"]
    report.facts = {
        "dataset_id": manifest["dataset_id"],
        "difficulty": manifest["difficulty"],
        "capture_mode": manifest["capture"]["mode"],
        "resolution": [manifest["capture"]["width"], manifest["capture"]["height"]],
        "lifecycle": manifest["lifecycle"],
        "resolved_config_sha256": manifest["resolved_config_sha256"],
        "manifest_sha256": report.manifest_sha256,
        "index_digests": cast(Record, manifest.get("integrity") or {}).get("indexes"),
        "scenes": len(scenes),
        "samples": len(samples),
        "present_scenes": present,
        "absent_scenes": len(scenes) - present,
        "quotas": [target["target_present_scenes"], target["target_absent_scenes"]],
        "quotas_met": [present, len(scenes) - present]
        == [target["target_present_scenes"], target["target_absent_scenes"]],
        "splits": (manifest["splits"].get("counts") or {}),
        "target_statuses": dict(Counter(s["target"]["target_status"] for s in samples)),
        "rgb_png_bytes": distribution([s["files"]["rgb"]["bytes"] for s in samples]),
        "mask_png_bytes": distribution([s["files"]["mask"]["bytes"] for s in samples]),
        "disk_bytes": sum(path.stat().st_size for path in directory.rglob("*") if path.is_file()),
        "source_patches": len(
            {scene["source_patch"]["patch_id"] for scene in scenes if "source_patch" in scene}
        ),
        "counts": manifest["counts"],
        "software": manifest["software"],
    }
    return report


def distribution(values: list[int] | list[float]) -> Record:
    """Count, mean, median, 95th percentile (nearest rank), minimum and maximum."""
    if not values:
        return {"count": 0}
    ordered = sorted(values)

    def rank(fraction: float) -> float:
        return ordered[max(0, min(len(ordered) - 1, math.ceil(fraction * len(ordered)) - 1))]

    return {
        "count": len(ordered),
        "mean": sum(ordered) / len(ordered),
        "p50": rank(0.5),
        "p95": rank(0.95),
        "min": ordered[0],
        "max": ordered[-1],
    }


def audit(directory: Path, schemas: Schemas, reviews: Path | None, seed: int, count: int) -> Audit:
    """The seeded stratified audit of a dataset, its coverage and latest review outcomes."""
    catalog = Catalog(directory, schemas)
    chosen = selection.Selection(mode="stratified", seed=seed, count=count)
    picked = selection.select(catalog.manifest, catalog.samples, chosen)
    seams = selection.seam_columns(catalog.manifest)
    gaps: list[str] = []

    def covered(name: str, wanted: set[Any], got: set[Any]) -> Record:
        missing = sorted(map(str, wanted - got))
        if missing:
            gaps.append(f"{name}: {', '.join(missing)} not in the selection")
        return {"available": sorted(map(str, wanted)), "selected": sorted(map(str, got & wanted))}

    def ratio_bin(sample: Record) -> str | None:
        ratio = sample["target"]["visibility_ratio"]
        if ratio is None or not sample["target"]["target_present"]:
            return None
        for low, high in RATIO_BINS:
            if low <= ratio < high or (high == 1.0 and ratio == 1.0):
                return f"{low:.2f}-{high:.2f}"
        return None

    def patch(sample: Record) -> str | None:
        scene = catalog.scenes.get(sample["scene_id"], {})
        return scene.get("source_patch", {}).get("patch_id")

    every, sample_set = catalog.samples, picked
    coverage = {
        "splits": covered("split", {s["split"] for s in every}, {s["split"] for s in sample_set}),
        "statuses": covered(
            "target status",
            {s["target"]["target_status"] for s in every},
            {s["target"]["target_status"] for s in sample_set},
        ),
        "ratio_bins": covered(
            "visibility ratio",
            {b for s in every if (b := ratio_bin(s))},
            {b for s in sample_set if (b := ratio_bin(s))},
        ),
        "strata": covered(
            "stratum",
            {selection.stratum(s, seams, chosen.low_pixels) for s in every},
            {selection.stratum(s, seams, chosen.low_pixels) for s in sample_set},
        ),
    }
    patches_available = {p for s in every if (p := patch(s))}
    patches_selected = {p for s in sample_set if (p := patch(s))}
    coverage["source_patches"] = {
        "available": len(patches_available),
        "selected": len(patches_selected),
    }
    if len(patches_available) > 1 and len(patches_selected) < 2:
        gaps.append("source patches: the selection covers fewer than two")

    latest = ReviewStore(reviews, catalog).latest() if reviews else {}
    outcomes: dict[str, str | None] = {}
    for sample in picked:
        review = latest.get(("sample", sample["sample_id"]))
        scene_review = latest.get(("scene", sample["scene_id"]))
        outcome = review["outcome"] if review else None
        if scene_review and scene_review["outcome"] != "pass":
            outcome = scene_review["outcome"]
        outcomes[sample["sample_id"]] = outcome
    return Audit(chosen.to_json(), [s["sample_id"] for s in picked], coverage, gaps, outcomes)


def to_json(reports: list[DatasetReport]) -> Record:
    return {
        "passed": all(report.passed for report in reports),
        "datasets": [
            {
                "run": report.run,
                "directory": report.directory.name,
                "passed": report.passed,
                "validation_errors": report.validation_errors,
                "facts": report.facts,
                "metrics": report.metrics,
                "audit": None
                if report.audit is None
                else {
                    "selection": report.audit.selection,
                    "samples": report.audit.samples,
                    "coverage": report.audit.coverage,
                    "gaps": report.audit.gaps,
                    "outcomes": dict(Counter(map(str, report.audit.outcomes.values()))),
                    "unreviewed": len(report.audit.unreviewed),
                    "not_passed": report.audit.not_passed,
                },
            }
            for report in reports
        ],
    }


def _mb(value: float) -> str:
    return f"{value / 1e6:,.1f} MB"


def _kb(value: float) -> str:
    return f"{value / 1e3:,.0f} KB"


def _s(total: Record | None, key: str = "p50_seconds") -> str:
    return "—" if not total else f"{total[key] * 1000:,.0f} ms"


def to_markdown(reports: list[DatasetReport]) -> str:
    """A readable report: a summary table, then capacity, integrity and audit details."""
    lines = ["# Acceptance report", ""]
    verdict = "passed" if reports and all(r.passed for r in reports) else "not passed"
    lines += [f"Overall: **{verdict}** over {len(reports)} datasets.", ""]
    lines += [
        "| Run | Dataset | Level | Mode | Scenes | Samples | Present/absent | Validation "
        "| Lifecycle |",
        "|---|---|---:|---|---:|---:|---|---|---|",
    ]
    for r in reports:
        f = r.facts
        if not f:
            lines.append(f"| {r.run} | {r.directory.name} | | | | | | unreadable | |")
            continue
        quota = f"{f['present_scenes']}/{f['absent_scenes']}" + ("" if f["quotas_met"] else " ✗")
        valid = "0 errors" if not r.validation_errors else f"{len(r.validation_errors)} errors"
        lines.append(
            f"| {r.run} | {f['dataset_id']} | {f['difficulty']} | {f['capture_mode']} "
            f"| {f['scenes']} | {f['samples']} | {quota} | {valid} | {f['lifecycle']} |"
        )
    lines += ["", "## Capacity", ""]
    lines += [
        "| Dataset | Duration | Samples/h | Pose p50 / p95 | Reset p50 | Rejected "
        "| RGB PNG mean | Disk | Peak RSS | Peak VRAM |",
        "|---|---:|---:|---|---:|---:|---:|---:|---:|---:|",
    ]
    for r in reports:
        m, f = r.metrics, r.facts
        if not f:
            continue
        if not m:
            png = _kb(f["rgb_png_bytes"].get("mean", 0))
            lines.append(
                f"| {f['dataset_id']} | no metrics | | | | | {png} | {_mb(f['disk_bytes'])} | | |"
            )
            continue
        pose = m["captures"]["pose_wall"]
        memory = m["memory"]
        lines.append(
            f"| {f['dataset_id']} | {m['seconds'] / 60:,.1f} min | {m['samples_per_hour']:,.0f} "
            f"| {_s(pose)} / {_s(pose, 'p95_seconds')} | {_s(m['phases'].get('reset'))} "
            f"| {m['rejected_scenes']} | {_kb(f['rgb_png_bytes']['mean'])} "
            f"| {_mb(f['disk_bytes'])} "
            f"| {_mb(memory['peak_rss_bytes'])} | {_mb(memory['peak_device_vram_used_bytes'])} |"
        )
    for r in reports:
        if r.validation_errors:
            lines += ["", f"## Validation errors in {r.directory.name}", ""]
            lines += [f"- {error}" for error in r.validation_errors[:50]]
        if r.metrics and r.metrics.get("rejection_reasons"):
            reasons = ", ".join(
                f"{k} {v}" for k, v in sorted(r.metrics["rejection_reasons"].items())
            )
            lines += ["", f"Rejections in {r.facts['dataset_id']}: {reasons}."]
    for r in reports:
        if r.audit is None:
            continue
        a = r.audit
        outcomes = Counter(map(str, a.outcomes.values()))
        lines += ["", f"## Audit of {r.facts['dataset_id']}", ""]
        lines += [
            f"Selection `{a.selection['algorithm']}` stratified, seed {a.selection['seed']}, "
            f"{len(a.samples)} samples, selection SHA-256 `{a.selection['sha256'][:16]}…`.",
            "",
            f"Reviews: {outcomes.get('pass', 0)} pass, {outcomes.get('fail', 0)} fail, "
            f"{outcomes.get('uncertain', 0)} uncertain, {len(a.unreviewed)} not yet reviewed.",
            f"Coverage gaps: {'none' if not a.gaps else '; '.join(a.gaps)}.",
            f"Source patches in the selection: {a.coverage['source_patches']['selected']} of "
            f"{a.coverage['source_patches']['available']}.",
        ]
        if a.not_passed:
            lines.append(f"Not passed: {', '.join(a.not_passed)}.")
    lines += ["", "## Identity", ""]
    lines += ["| Dataset | Resolved config SHA-256 | Manifest SHA-256 |", "|---|---|---|"]
    for r in reports:
        if r.facts:
            config = r.facts["resolved_config_sha256"]
            lines.append(f"| {r.facts['dataset_id']} | `{config}` | `{r.manifest_sha256}` |")
    return "\n".join(lines) + "\n"


def write(reports: list[DatasetReport], path: Path) -> None:
    """Writes ``<path>.md`` and ``<path>.json``."""
    path.with_suffix(".md").write_text(to_markdown(reports), encoding="utf-8")
    path.with_suffix(".json").write_text(
        json.dumps(to_json(reports), indent=2, default=cast(Any, str)) + "\n", encoding="utf-8"
    )
