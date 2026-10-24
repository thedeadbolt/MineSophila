"""A read-only index of one dataset directory for inspection.

Opening a dataset reads the manifest and both JSONL indexes, which hold only metadata;
image files are read only when a sample is checked or shown. Integrity is established two
ways, and neither ever changes a file:

- each sample's images are checked when it is first shown (``sample_errors``), so a
  missing, damaged or mismatched file is reported in place of the image;
- the whole dataset is validated once in the background (``start_validation``), as
  ``validate-dataset`` would, and its errors are attributed to samples and scenes where
  they name one.
"""

import hashlib
import re
import threading
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from honey_factory_tools import dataset
from honey_factory_tools.schemas import Schemas

type Record = dict[str, Any]

_SUBJECT = re.compile(r"^(sample|scene) (s\d{6}(?:-v\d{2})?): ")


@dataclass
class Validation:
    """The background validation: ``state`` is ``pending``, ``running`` or ``done``."""

    state: str = "pending"
    dataset_errors: list[str] = field(default_factory=list[str])
    subject_errors: dict[str, list[str]] = field(default_factory=dict[str, list[str]])

    @property
    def error_count(self) -> int:
        return len(self.dataset_errors) + sum(len(e) for e in self.subject_errors.values())


class Catalog:
    """Metadata of one dataset, with lazily checked images. Thread-safe for reading."""

    def __init__(self, root: Path, schemas: Schemas) -> None:
        self.root = root.resolve()
        self.schemas = schemas
        manifest_bytes = _read_bytes(self.root / dataset.MANIFEST)
        self.manifest_sha256 = hashlib.sha256(manifest_bytes).hexdigest() if manifest_bytes else ""
        report = dataset.load(self.root, schemas)
        if report.manifest is None:
            raise ValueError(f"{self.root} has no readable manifest: {'; '.join(report.errors)}")
        self.manifest: Record = report.manifest
        self.index_errors: list[str] = list(report.errors)
        self.scenes: dict[str, Record] = {}
        for scene in report.scenes:
            self.scenes.setdefault(scene["scene_id"], scene)
        self.samples: list[Record] = report.samples
        self.by_sample: dict[str, Record] = {}
        self.scene_samples: dict[str, list[Record]] = {scene_id: [] for scene_id in self.scenes}
        for sample in self.samples:
            self.by_sample.setdefault(sample["sample_id"], sample)
            self.scene_samples.setdefault(sample["scene_id"], []).append(sample)
        for samples in self.scene_samples.values():
            samples.sort(key=lambda s: (s["viewpoint_index"], s["sample_id"]))
        self.validation = Validation()
        self._checked: dict[str, list[str]] = {}
        self._lock = threading.Lock()

    @property
    def dataset_id(self) -> str:
        return self.manifest["dataset_id"]

    @property
    def scene_ids(self) -> list[str]:
        return list(self.scenes)

    def manifest_changed(self) -> bool:
        """Whether ``dataset.json`` differs from the bytes this catalog was opened with."""
        data = _read_bytes(self.root / dataset.MANIFEST)
        return data is None or hashlib.sha256(data).hexdigest() != self.manifest_sha256

    def sample_errors(self, sample_id: str) -> list[str]:
        """Integrity errors of one sample's images, checked on first request and cached;
        errors the background validation attributed to it are included."""
        sample = self.by_sample[sample_id]
        with self._lock:
            checked = self._checked.get(sample_id)
        if checked is None:
            checked = dataset.sample_file_errors(self.root, sample)
            with self._lock:
                self._checked[sample_id] = checked
        background = self.validation.subject_errors.get(sample_id, [])
        return checked + [error for error in background if error not in checked]

    def integrity(self, sample_id: str) -> str:
        """``error``, ``ok`` once checked or validated, or ``unchecked``."""
        with self._lock:
            checked = self._checked.get(sample_id)
        if checked or self.validation.subject_errors.get(sample_id):
            return "error"
        if checked is not None or self.validation.state == "done":
            return "ok"
        return "unchecked"

    def image(self, sample_id: str, kind: str) -> bytes:
        """The stored PNG bytes of a sample's ``rgb`` or ``mask``, only once its images pass.

        Raises ``IntegrityError`` naming the problems otherwise.
        """
        errors = self.sample_errors(sample_id)
        if errors:
            raise IntegrityError(errors)
        data = _read_bytes(self.root / self.by_sample[sample_id]["files"][kind]["path"])
        if data is None:
            raise IntegrityError([f"sample {sample_id}: {kind} became unreadable"])
        return data

    def start_validation(self, on_done: Callable[[], None] | None = None) -> threading.Thread:
        """Validate the whole dataset in a daemon thread."""

        def run() -> None:
            self.validation.state = "running"
            report = dataset.validate(self.root, self.schemas, allow_generating=True)
            dataset_errors: list[str] = []
            subject_errors: dict[str, list[str]] = {}
            for error in report.errors:
                match = _SUBJECT.match(error)
                if match:
                    subject_errors.setdefault(match.group(2), []).append(error)
                else:
                    dataset_errors.append(error)
            self.validation.dataset_errors = dataset_errors
            self.validation.subject_errors = subject_errors
            self.validation.state = "done"
            if on_done is not None:
                on_done()

        thread = threading.Thread(target=run, name="honey-factory-validate", daemon=True)
        thread.start()
        return thread


class IntegrityError(ValueError):
    """A sample's stored images cannot be shown as valid."""

    def __init__(self, errors: list[str]) -> None:
        super().__init__("; ".join(errors))
        self.errors = errors


def _read_bytes(path: Path) -> bytes | None:
    try:
        return path.read_bytes()
    except OSError:
        return None
