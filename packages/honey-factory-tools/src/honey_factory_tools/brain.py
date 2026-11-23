"""The Brain workspace: fly-brain run reports and per-clip traces, read from files (ADR 0029).

Reports are the ``*.report.json`` files fly-brain writes beside its checkpoints. Each has
a ``format``; only the fields that every format shares are relied on (the split metrics,
``git_revision`` and the checkpoint digest), and everything else is passed through for
display.

Traces are ``fly-brain-clip-trace-v1`` ``.npz`` files, one per clip, written by
``fly-brain trace-clip``. ``header`` is a JSON string with ``format``, ``dataset_id``,
``clip_id``, the checkpoint's digest and ``frames``, the clip frame each trace step was
taken from. The arrays are ``activity`` (T, N) and per neuron ``cell_type``, ``side``,
``stage``, ``pb_angle`` (NaN where none) and ``body_id``; per step, optionally, ``yaw``,
``presence``, ``visible``, ``direction`` (T, 3), ``readout`` (T, 4) as
``[presence_logit, forward, right, up]`` with the decoded ``presence_prob``,
``bearing_azimuth_deg`` and ``bearing_elevation_deg``,
``teacher_actions`` (T, A) with ``teacher_mask`` (T), ``bump_phase`` and
``bump_strength``. The page gets the activity summarised by cell type, plus every
compass neuron with a bridge angle in full.
"""

import json
import math
import threading
from pathlib import Path
from typing import Any, cast

import numpy as np
from numpy.typing import NDArray

type Record = dict[str, Any]

TRACE_FORMAT = "fly-brain-clip-trace-v1"
SPLITS = ("test", "validation", "test_occluded", "test_visible")
METRICS = ("mean_bearing_error_deg", "presence_balanced_accuracy", "sample_count")
# Stages in the order signals flow; trace files may use either name for a stage.
STAGE_ORDER = (
    "photoreceptors",
    "lamina_relays",
    "medulla_relays",
    "visual_projection",
    "visual_kenyon",
    "anterior_visual_inputs",
    "MeTu",
    "tubercle_bulb",
    "TuBu",
    "ring_neurons",
    "ER",
    "compass",
    "steering",
    "PFL",
    "other",
)
PER_STEP = (
    "yaw",
    "presence",
    "visible",
    "bump_phase",
    "bump_strength",
    "teacher_mask",
    "presence_prob",
    "bearing_azimuth_deg",
    "bearing_elevation_deg",
)
PER_STEP_VECTORS = ("direction", "readout", "teacher_actions")
# Above this many neurons in one cell type, only its mean and spread are sent.
MAX_TYPE_ROWS = 64


class BrainError(ValueError):
    """A report or trace cannot be read."""


class Runs:
    """The run reports under one directory, listed once and read when opened."""

    def __init__(self, root: Path) -> None:
        if not root.is_dir():
            raise BrainError(f"{root} is not a directory of fly-brain reports")
        self.root = root

    def names(self) -> list[str]:
        return sorted(
            path.name.removesuffix(".report.json") for path in self.root.glob("*.report.json")
        )

    def summaries(self) -> list[Record]:
        out: list[Record] = []
        for name in self.names():
            try:
                report = self.report(name)
            except BrainError as error:
                out.append({"name": name, "error": str(error)})
                continue
            out.append(summary(name, report))
        return out

    def report(self, name: str) -> Record:
        if "/" in name or "\\" in name or name.startswith("."):
            raise BrainError(f"bad run name {name!r}")
        path = self.root / f"{name}.report.json"
        if not path.is_file():
            raise BrainError(f"no run {name}")
        try:
            value = json.loads(path.read_text())
        except (OSError, json.JSONDecodeError) as error:
            raise BrainError(f"{path.name}: {error}") from error
        if not isinstance(value, dict):
            raise BrainError(f"{path.name}: not a JSON object")
        return _finite(value)


def summary(name: str, report: Record) -> Record:
    """The fields every run list row shows."""
    model = _record(report.get("model"))
    dataset = _record(report.get("dataset"))
    training = _record(report.get("training"))
    splits = {
        split: {metric: report[split].get(metric) for metric in METRICS}
        for split in SPLITS
        if isinstance(report.get(split), dict)
    }
    return {
        "name": name,
        "format": report.get("format"),
        "family": family(str(report.get("format") or "")),
        "git_revision": report.get("git_revision"),
        "checkpoint_sha256": report.get("checkpoint_manifest_sha256"),
        "dataset_id": dataset.get("source_dataset_id"),
        "neurons": _record(model.get("compilation")).get("neuron_count"),
        "steps": training.get("steps"),
        "splits": splits,
    }


def family(report_format: str) -> str:
    """A short model family name from a report format."""
    name = report_format.removeprefix("fly-brain-").removesuffix("-v1")
    return name.removesuffix("-report") or "unknown"


class Traces:
    """The trace files under one directory, indexed by dataset and clip on first use."""

    def __init__(self, root: Path) -> None:
        if not root.is_dir():
            raise BrainError(f"{root} is not a directory of fly-brain traces")
        self.root = root
        self._index: dict[tuple[str, str], Path] | None = None
        self._lock = threading.Lock()

    def index(self) -> dict[tuple[str, str], Path]:
        with self._lock:
            if self._index is None:
                index: dict[tuple[str, str], Path] = {}
                for path in sorted(self.root.rglob("*.npz")):
                    try:
                        header = read_header(path)
                    except BrainError:
                        continue
                    index[(str(header.get("dataset_id")), str(header.get("clip_id")))] = path
                self._index = index
            return self._index

    def clip_ids(self, dataset_id: str) -> list[str]:
        return sorted(clip for dataset, clip in self.index() if dataset == dataset_id)

    def trace(self, dataset_id: str, clip_id: str) -> Record | None:
        path = self.index().get((dataset_id, clip_id))
        return None if path is None else load_trace(path)


def read_header(path: Path) -> Record:
    try:
        with path.open("rb") as handle, np.load(handle, allow_pickle=False) as data:
            if "header" not in data.files:
                raise BrainError(f"{path.name}: no header")
            header: Any = json.loads(str(data["header"]))
    except (OSError, ValueError) as error:
        raise BrainError(f"{path.name}: {error}") from error
    if not isinstance(header, dict) or cast(Record, header).get("format") != TRACE_FORMAT:
        raise BrainError(f"{path.name}: not a {TRACE_FORMAT} file")
    return cast(Record, header)


def load_trace(path: Path) -> Record:
    """A trace summarised for the page."""
    header = read_header(path)
    with path.open("rb") as handle, np.load(handle, allow_pickle=False) as data:
        arrays = {name: data[name] for name in data.files if name != "header"}
    if "activity" not in arrays or arrays["activity"].ndim != 2:
        raise BrainError(f"{path.name}: activity must be a (steps, neurons) array")
    activity = arrays["activity"].astype(np.float32)
    steps, neurons = activity.shape
    cell_type = _strings(arrays, "cell_type", neurons, "unknown")
    stage = _strings(arrays, "stage", neurons, "other")
    side = _strings(arrays, "side", neurons, "")
    pb_angle = (
        arrays["pb_angle"].astype(float) if "pb_angle" in arrays else np.full(neurons, math.nan)
    )
    listed: Any = header.get("frames")
    frames: list[Any] = (
        cast(list[Any], listed)
        if isinstance(listed, list) and len(cast(list[Any], listed)) == steps
        else list(range(steps))
    )

    groups: list[Record] = []
    for type_name in sorted(set(cell_type), key=lambda t: (_stage_rank(stage, cell_type, t), t)):
        members = np.flatnonzero(cell_type == type_name)
        values = activity[:, members]
        group: Record = {
            "cell_type": type_name,
            "stage": str(stage[members[0]]),
            "count": int(members.size),
            "mean": _rounded(values.mean(axis=1)),
            "spread": _rounded(values.std(axis=1)),
        }
        if members.size <= MAX_TYPE_ROWS:
            group["rows"] = _rounded(values.T)
            group["sides"] = [str(side[m]) for m in members]
        groups.append(group)

    ring = np.flatnonzero(np.isfinite(pb_angle))
    compass = {
        "angle": _rounded(pb_angle[ring]),
        "cell_type": [str(cell_type[i]) for i in ring],
        "side": [str(side[i]) for i in ring],
        "activity": _rounded(activity[:, ring].T),
    }
    series: Record = {}
    for name in PER_STEP:
        if name in arrays and arrays[name].shape[:1] == (steps,):
            series[name] = _rounded(arrays[name].reshape(steps))
    for name in PER_STEP_VECTORS:
        if name in arrays and arrays[name].ndim == 2 and arrays[name].shape[0] == steps:
            series[name] = _rounded(arrays[name])
    return {
        "header": header,
        "file": path.name,
        "steps": steps,
        "neurons": neurons,
        "frames": frames,
        "range": [float(np.nanmin(activity)), float(np.nanmax(activity))],
        "groups": groups,
        "compass": compass,
        "series": series,
    }


def _stage_rank(stage: NDArray[Any], cell_type: NDArray[Any], type_name: str) -> int:
    name = str(stage[np.flatnonzero(cell_type == type_name)[0]])
    return STAGE_ORDER.index(name) if name in STAGE_ORDER else len(STAGE_ORDER)


def _strings(arrays: dict[str, NDArray[Any]], name: str, count: int, default: str) -> NDArray[Any]:
    if name in arrays and arrays[name].shape == (count,):
        return arrays[name].astype(str)
    return np.full(count, default)


def _rounded(values: NDArray[Any]) -> list[Any]:
    """Values rounded for the page, with NaN and infinities as None (strict JSON)."""
    array = np.round(np.asarray(values, dtype=float), 4)
    finite = np.isfinite(array)
    if finite.all():
        return cast(list[Any], array.tolist())
    out = array.astype(object)
    out[~finite] = None
    return cast(list[Any], out.tolist())


def _record(value: Any) -> Record:
    return cast(Record, value) if isinstance(value, dict) else {}


def _finite(value: Any) -> Any:
    """``value`` with NaN and infinities replaced by None, so it serialises as strict JSON."""
    if isinstance(value, float):
        return value if math.isfinite(value) else None
    if isinstance(value, dict):
        return {key: _finite(item) for key, item in value.items()}  # pyright: ignore[reportUnknownVariableType]
    if isinstance(value, list):
        return [_finite(item) for item in value]  # pyright: ignore[reportUnknownVariableType]
    return value
