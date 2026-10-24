"""Sidecar review records: audit evidence kept outside the dataset directory.

Reviews of one dataset are appended, one JSON object per line, to
``<review root>/<dataset id>/<first 16 hex digits of the manifest SHA-256>.jsonl``, so a
file only ever holds reviews of one exact manifest. Each record names the dataset, the full
manifest hash, the reviewed scene or sample, the outcome and reason, the inspector version
and, when the sample came from a selection, that selection. Records are never rewritten:
a later review of the same subject supersedes earlier ones, which stay as history.

A review root inside the dataset directory is refused, and nothing is recorded once the
manifest on disk differs from the one the reviews were opened against.
"""

import json
import os
import threading
from collections import Counter
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from honey_factory_tools import __version__
from honey_factory_tools.catalog import Catalog

type Record = dict[str, Any]

FORMAT = "honey-factory-review-v1"
KINDS = ("sample", "scene")
OUTCOMES = ("pass", "fail", "uncertain")
REASONS = (
    "ok",
    "mask_misaligned",
    "mask_missing_pixels",
    "mask_extra_pixels",
    "wrong_target_status",
    "target_ambiguous",
    "render_artifact",
    "seam_artifact",
    "scene_invalid",
    "metadata_mismatch",
    "other",
)


class ReviewError(ValueError):
    """A review cannot be recorded."""


@dataclass(frozen=True)
class Summary:
    """Latest outcomes by subject kind and outcome, and the subjects whose latest review failed."""

    reviews: int
    latest: dict[str, dict[str, int]]
    failed: list[str]
    uncertain: list[str]


class ReviewStore:
    """Appends and reads the reviews of one catalog's dataset under ``root``."""

    def __init__(self, root: Path, catalog: Catalog, reviewer: str | None = None) -> None:
        resolved = root.resolve()
        if resolved == catalog.root or resolved.is_relative_to(catalog.root):
            raise ReviewError(f"the review root {resolved} lies inside the dataset directory")
        self.root = resolved
        self.catalog = catalog
        self.reviewer = reviewer
        self.path = resolved / catalog.dataset_id / f"{catalog.manifest_sha256[:16]}.jsonl"
        self._lock = threading.Lock()

    def record(
        self,
        kind: str,
        subject_id: str,
        outcome: str,
        reason: str,
        note: str = "",
        selection: Record | None = None,
    ) -> Record:
        """Validates and appends one review, returning the record written."""
        if kind not in KINDS:
            raise ReviewError(f"kind must be one of {', '.join(KINDS)}")
        if outcome not in OUTCOMES:
            raise ReviewError(f"outcome must be one of {', '.join(OUTCOMES)}")
        if reason not in REASONS:
            raise ReviewError(f"reason must be one of {', '.join(REASONS)}")
        if outcome != "pass" and reason == "ok":
            raise ReviewError(f"a {outcome} review needs a reason other than ok")
        if kind == "sample":
            sample = self.catalog.by_sample.get(subject_id)
            if sample is None:
                raise ReviewError(f"dataset {self.catalog.dataset_id} has no sample {subject_id}")
            scene_id: str = sample["scene_id"]
        else:
            if subject_id not in self.catalog.scenes:
                raise ReviewError(f"dataset {self.catalog.dataset_id} has no scene {subject_id}")
            scene_id = subject_id
        if self.catalog.manifest_changed():
            raise ReviewError("dataset.json changed since it was opened; reopen the dataset")

        review: Record = {
            "format": FORMAT,
            "dataset_id": self.catalog.dataset_id,
            "manifest_sha256": self.catalog.manifest_sha256,
            "subject": {"kind": kind, "id": subject_id, "scene_id": scene_id},
            "outcome": outcome,
            "reason": reason,
            "note": note,
            "reviewed_at_utc": datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%S.%fZ"),
            "inspector_version": __version__,
        }
        if self.reviewer:
            review["reviewer"] = self.reviewer
        if selection is not None:
            review["selection"] = selection
        line = json.dumps(review, ensure_ascii=False, separators=(",", ":")) + "\n"
        with self._lock:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            with self.path.open("a", encoding="utf-8") as file:
                file.write(line)
                file.flush()
                os.fsync(file.fileno())
        return review

    def all(self) -> list[Record]:
        """Every review of this manifest, oldest first."""
        try:
            text = self.path.read_text(encoding="utf-8")
        except FileNotFoundError:
            return []
        return [json.loads(line) for line in text.splitlines() if line.strip()]

    def latest(self) -> dict[tuple[str, str], Record]:
        """The latest review of each subject, keyed by (kind, id)."""
        latest: dict[tuple[str, str], Record] = {}
        for review in self.all():
            latest[(review["subject"]["kind"], review["subject"]["id"])] = review
        return latest

    def summary(self) -> Summary:
        reviews = self.all()
        latest = self.latest()
        counts: dict[str, Counter[str]] = {kind: Counter() for kind in KINDS}
        for (kind, _), review in latest.items():
            counts[kind][review["outcome"]] += 1
        return Summary(
            reviews=len(reviews),
            latest={kind: dict(sorted(counter.items())) for kind, counter in counts.items()},
            failed=sorted(f"{k} {i}" for (k, i), r in latest.items() if r["outcome"] == "fail"),
            uncertain=sorted(
                f"{k} {i}" for (k, i), r in latest.items() if r["outcome"] == "uncertain"
            ),
        )
