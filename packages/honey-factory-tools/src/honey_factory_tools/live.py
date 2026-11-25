"""fly-brain live runs, shown in the inspector as clip datasets (``inspect-live``).

A live run is a ``fly-brain run-live --out-dir``: ``summary.json``, ``episodes.jsonl`` (one
episode per line, with a per-tick ``trace``), ``videos/s<seed>-scene<index>.mp4`` (the fly's
camera, half size, with a status strip under each frame) and, after ``fly-brain trace-live``,
``traces/s<seed>-scene<index>.trace.npz``.

Each episode becomes a clip ``<run>.s<seed>-scene<index>`` whose frame records are built from
its trace rows in the dataset contract's shapes, so the Scene clip view (frames, map, timeline),
the Eye and the Brain play live episodes as they play generated clips:

- ``pose``: feet position, yaw and head pitch;
- ``target``: honey status from its visible pixels (there is no mask; masks are blank), and
  ``bearing`` from the honey's centre;
- ``tick_labels``: the fly's command as ``executed``, ``driver`` ``fly``, the bump and touch
  senses, and ``live`` with the rest of the trace row (wall ahead, presence, distance).

The clip record's ``live`` object carries the episode's outcome and the run's settings.
"""

from __future__ import annotations

import json
import math
import subprocess
import threading
import time
from collections import OrderedDict
from pathlib import Path
from typing import Any, cast

from PIL import Image

from honey_factory_tools.brain import BrainError, Traces, load_trace
from honey_factory_tools.catalog import Validation
from honey_factory_tools.clips import ClipFrames, decode_frames, scaled_size

type Record = dict[str, Any]

LIVE_DATASET_ID = "live"
#: The capture live runs use unless their report says otherwise (fly-brain run-live defaults).
DEFAULT_CAPTURE = {"width": 2480, "height": 1280}
EYE_HEIGHT_BLOCKS = 1.62
#: Decoded live episodes kept for playback, by bytes of PNG.
CACHE_BYTES = 512 * 1024 * 1024
#: The PANORAMA_310 projection every live capture uses (as in a dataset manifest).
PROJECTION: Record = {
    "projection": "equirectangular-310x160-v1",
    "longitude_min_degrees": -155.0,
    "longitude_max_degrees": 155.0,
    "elevation_min_degrees": -70.0,
    "elevation_max_degrees": 90.0,
    "blind_sector_degrees": 50.0,
}


class LiveFrames(ClipFrames):
    """An episode video's frames at the capture's size, its status strip cut off, and blank
    masks (live runs have no honey mask)."""

    def __init__(self, video: Path | None, width: int, height: int, count: int) -> None:
        super().__init__(video or Path(), video or Path(), width, height)
        self.video = video
        self.count = count
        self._native: list[Image.Image] | None = None

    def _frames(self, mode: Any, scale: float) -> list[bytes]:
        from honey_factory_tools.clips import _png  # pyright: ignore[reportPrivateUsage]

        size = scaled_size(self.width, self.height, scale)
        with self._lock:
            frames = self._decoded.get((mode, scale))
            if frames is None:
                if mode == "L":
                    blank = _png(Image.new("L", size, 0))
                    frames = [blank] * len(self._native_frames())
                else:
                    frames = [
                        _png(image.resize(size, Image.Resampling.BOX))
                        for image in self._native_frames()
                    ]
                self._decoded[(mode, scale)] = frames
            return frames

    def _native_frames(self) -> list[Image.Image]:
        """Every frame at the video's size, above the strip (same aspect as the capture)."""
        if self._native is None and self.video is None:
            # An episode run before videos were written: grey frames keep the views working.
            grey = Image.new("RGB", scaled_size(self.width, self.height, 0.5), (96, 96, 96))
            self._native = [grey] * self.count
        if self._native is None:
            probe = _video_size(self.paths["RGB"])
            width, height = probe
            picture = round(width * self.height / self.width)
            self._native = [
                image.crop((0, 0, width, min(picture, height)))
                for image in decode_frames(self.paths["RGB"], width, height, "RGB")
            ]
        return self._native


def _video_size(path: Path) -> tuple[int, int]:
    import subprocess

    out = subprocess.run(
        [
            "ffprobe",
            "-v",
            "error",
            "-select_streams",
            "v:0",
            "-show_entries",
            "stream=width,height",
            "-of",
            "csv=p=0",
            str(path),
        ],
        capture_output=True,
        check=True,
        text=True,
    ).stdout
    width, height = (int(value) for value in out.strip().split(",")[:2])
    return width, height


def _wrap(degrees: float) -> float:
    return (degrees + 180.0) % 360.0 - 180.0


def _number(value: Any) -> float | None:
    return float(value) if isinstance(value, int | float) and math.isfinite(value) else None


def frame_record(clip_id: str, step: int, row: Record) -> Record:
    """One trace row as a frame record (the dataset contract's fields the views read)."""
    x, y, z = (_number(row.get(name)) for name in ("x", "y", "z"))
    yaw = _number(row.get("yaw")) or 0.0
    pitch = _number(row.get("pitch")) or 0.0
    pixels = _number(row.get("visible_pixels")) or 0.0
    honey = [_number(row.get(name)) for name in ("honey_x", "honey_y", "honey_z")]
    bearing: Record | None = None
    if None not in honey and None not in (x, y, z):
        hx, hy, hz = cast(list[float], honey)
        dx, dz = hx - cast(float, x), hz - cast(float, z)
        horizontal = math.hypot(dx, dz)
        bearing = {
            "azimuth_degrees": _wrap(math.degrees(math.atan2(-dx, dz)) - yaw),
            "elevation_degrees": math.degrees(
                math.atan2(hy - (cast(float, y) + EYE_HEIGHT_BLOCKS), horizontal)
            ),
            "distance_blocks": math.hypot(horizontal, hy - cast(float, y)),
        }
    touching = bool(_number(row.get("touch")))
    command = {
        "forward": _number(row.get("forward")) or 0.0,
        "strafe": 0.0,
        "turn_rate": _number(row.get("turn_rate")) or 0.0,
        "pitch_rate": _number(row.get("pitch_rate")) or 0.0,
        "jump": False,
        "delay_ticks": 0,
    }
    live = {
        name: value
        for name, value in row.items()
        if name not in ("x", "y", "z", "yaw", "pitch", "forward", "turn_rate", "pitch_rate")
    }
    return {
        "clip_id": clip_id,
        "frame": step,
        "tick": step,
        "sub_frame": 0,
        "frames_per_tick": 1,
        "partial_tick": 0.0,
        "pose": {"position": [x, y, z], "yaw_degrees": yaw, "pitch_degrees": pitch},
        "target": {
            "target_present": True,
            "target_in_projection": pixels > 0,
            "visible_pixels": int(pixels),
            "unoccluded_pixels": None,
            "visibility_ratio": None,
            "target_status": "visible" if pixels > 0 else "not_visible",
            "distance_blocks": _number(row.get("distance")),
        },
        "touches_edge": False,
        "bearing": bearing,
        "tick_labels": {
            "senses": {
                "pitch": pitch,
                "bumped": bool(_number(row.get("bumped"))),
                # The trace keeps whether any antenna touched; it is shown on the front one.
                "touch": {"left": False, "front": touching, "right": False},
                "forward_speed": 0.0,
                "on_ground": True,
            },
            "executed": command,
            "teacher": None,
            "teacher_mode": None,
            "belief": None,
            "drifting": False,
            "driver": "fly",
            "guarded": False,
            "coverage": 0.0,
            "live": live,
        },
    }


class _Run:
    """One live run's episodes, read from its files; reread when ``episodes.jsonl`` changes."""

    def __init__(self, path: Path, include_unfilmed: bool = False) -> None:
        self.path = path
        self.include_unfilmed = include_unfilmed
        self.stamp: tuple[float, int] | None = None
        self.capture = DEFAULT_CAPTURE
        self.level: int | None = None
        self.clips: dict[str, Record] = {}
        self.scenes: dict[str, Record] = {}
        self.frames: dict[str, list[Record]] = {}
        self.names: dict[str, str] = {}

    def refresh(self) -> bool:
        """Rereads the run if its episodes changed; True when it did."""
        stat = (self.path / "episodes.jsonl").stat()
        stamp = (stat.st_mtime, stat.st_size)
        if stamp == self.stamp:
            return False
        loaded = _Run(self.path, self.include_unfilmed)
        loaded.read()
        self.__dict__.update({**loaded.__dict__, "stamp": stamp})
        return True

    def read(self) -> None:
        loaded = self
        run = self.path
        # A run still going has no summary yet; its episodes still show.
        summary = run / "summary.json"
        report: Record = json.loads(summary.read_text()) if summary.is_file() else {}
        capture = cast(dict[str, int], report.get("capture") or DEFAULT_CAPTURE)
        self.capture = capture
        preset = str(cast(Record, report.get("scene") or {}).get("preset", ""))
        if preset.startswith("level") and preset[5:].isdigit():
            self.level = int(preset[5:])
        settings = {
            key: report.get(key)
            for key in ("run_dir", "policy", "turn_before_deg", "target_height", "seed")
        }
        for line in (run / "episodes.jsonl").read_text().splitlines():
            if not line.strip():
                continue
            episode: Record = json.loads(line)
            name = f"s{episode['seed']}-scene{episode['scene_index']}"
            clip_id = f"{run.name}.{name}"
            rows: list[Record] = episode.get("trace") or []
            frames = [frame_record(clip_id, step, row) for step, row in enumerate(rows)]
            video = run / "videos" / f"{name}.mp4"
            if not video.is_file() and not self.include_unfilmed:
                continue  # recorded before videos: nothing to look at
            honey = next(
                (
                    [
                        math.floor(row["honey_x"]),
                        math.floor(row["honey_y"]),
                        math.floor(row["honey_z"]),
                    ]
                    for row in rows
                    if _number(row.get("honey_x")) is not None
                ),
                None,
            )
            loaded.scenes[clip_id] = {
                "scene_id": clip_id,
                "split": run.name,
                "target": {
                    "present": honey is not None,
                    "block_id": "minecraft:honey_block",
                    "position": honey,
                },
            }
            first = (
                frames[0]["pose"]
                if frames
                else {"position": [None, None, None], "yaw_degrees": 0.0, "pitch_degrees": 0.0}
            )
            loaded.clips[clip_id] = {
                "clip_id": clip_id,
                "scene_id": clip_id,
                "split": run.name,
                "clip_index": len(loaded.clips),
                "requested_type": "live",
                "clip_type": "live",
                "attempt": 0,
                "start_pose": first,
                "frames_per_tick": 1,
                "tick_count": len(frames),
                "frame_count": len(frames),
                "end_reason": "reached"
                if episode.get("reached")
                else (str(episode.get("failure") or "timeout")).lower(),
                "codec": {"name": "h264-live" if video.is_file() else "no video"},
                "files": {
                    "rgb": {
                        "path": str(video),
                        "width": capture["width"],
                        "height": capture["height"],
                        "frames": len(frames),
                    }
                },
                "summary": {
                    "reached": bool(episode.get("reached")),
                    "miss_reason": None
                    if episode.get("reached")
                    else str(episode.get("failure") or "timeout").lower(),
                    "drift_ticks": 0,
                    "guarded_ticks": 0,
                    "coverage_final": 0.0,
                    "ticks_by_mode": {},
                    "ticks_by_driver": {"fly": len(frames)},
                },
                # The episode's own fields win; the run's settings fill the rest.
                "live": {
                    **{key: value for key, value in settings.items() if value is not None},
                    **{
                        key: value
                        for key, value in episode.items()
                        if key not in ("trace", "scene")
                    },
                },
            }
            loaded.frames[clip_id] = frames
            loaded.names[clip_id] = name


def _runs(paths: list[Path]) -> list[Path]:
    """Live run directories: each path that is one (it holds ``episodes.jsonl``), and the ones
    directly inside each path that is not."""
    found: list[Path] = []
    for path in paths:
        path = path.resolve()
        if (path / "episodes.jsonl").is_file():
            found.append(path)
        elif path.is_dir():
            found.extend(
                sorted(child for child in path.iterdir() if (child / "episodes.jsonl").is_file())
            )
    return found


class LiveCatalog:
    """Live runs' episodes as one clip dataset, with the members the inspector reads.

    ``paths`` are run directories or folders of them; runs and episodes that appear later are
    picked up when the clips are next read, at most every ``refresh_seconds``. Episodes without
    a video (recorded before videos were written) are left out unless ``include_unfilmed``. With a
    ``trace_command`` (for example ``fly-brain trace-live``), an episode with recorded inputs but
    no trace is traced when its trace is first asked for.
    """

    def __init__(
        self,
        paths: list[Path],
        trace_command: list[str] | None = None,
        refresh_seconds: float = 5.0,
        include_unfilmed: bool = False,
    ) -> None:
        self.paths = paths
        self.include_unfilmed = include_unfilmed
        self.trace_command = trace_command
        self.refresh_seconds = refresh_seconds
        self.root = paths[0].resolve() if paths else Path()
        self.dataset_id = LIVE_DATASET_ID
        self.manifest_sha256 = ""
        self.kind = "clips"
        self.index_errors: list[str] = []
        self.samples: list[Record] = []
        self.by_sample: dict[str, Record] = {}
        self.scene_samples: dict[str, list[Record]] = {}
        self.validation = Validation()
        self.validation.state = "done"
        self._runs: dict[Path, _Run] = {}
        self._clips: dict[str, Record] = {}
        self._scenes: dict[str, Record] = {}
        self._owner: dict[str, _Run] = {}
        self._checked = -math.inf
        self._decoded: OrderedDict[str, LiveFrames] = OrderedDict()
        self._lock = threading.Lock()
        self._tracing: dict[Path, threading.Lock] = {}
        self.manifest: Record = {}
        self._refresh(force=True)

    def _refresh(self, force: bool = False) -> None:
        with self._lock:
            now = time.monotonic()
            if not force and now - self._checked < self.refresh_seconds:
                return
            self._checked = now
            changed = False
            for path in _runs(self.paths):
                run = self._runs.setdefault(path, _Run(path, self.include_unfilmed))
                try:
                    changed |= run.refresh()
                except (OSError, ValueError) as error:
                    self.index_errors.append(f"{path.name}: {error}")
            if changed or force:
                self._clips = {}
                self._scenes = {}
                self._owner = {}
                for run in self._runs.values():
                    self._clips.update(run.clips)
                    self._scenes.update(run.scenes)
                    self._owner.update(dict.fromkeys(run.clips, run))
                runs = list(self._runs.values())
                capture = runs[-1].capture if runs else DEFAULT_CAPTURE
                levels = {run.level for run in runs if run.level is not None}
                self.manifest = {
                    # "complete" keeps the not-for-training banner off; these are evaluation runs.
                    "lifecycle": "complete",
                    "difficulty": levels.pop() if len(levels) == 1 else "live",
                    "capture": {
                        "mode": "PANORAMA_310",
                        "width": capture["width"],
                        "height": capture["height"],
                        "projection": PROJECTION,
                    },
                    "target": {"block_id": "minecraft:honey_block"},
                    "counts": {"clips": len(self._clips)},
                    "splits": {},
                    "software": {},
                    "render_environment": {},
                }

    @property
    def clips(self) -> dict[str, Record]:
        self._refresh()
        return self._clips

    @property
    def scenes(self) -> dict[str, Record]:
        self._refresh()
        return self._scenes

    def scene_ids(self) -> list[str]:
        return list(self.scenes)

    def manifest_changed(self) -> bool:
        return False

    def sample_errors(self, sample_id: str) -> list[str]:
        return []

    def integrity(self, sample_id: str) -> str:
        return "ok"

    def image(self, sample_id: str, kind: str) -> bytes:
        raise KeyError(sample_id)

    def clip_ids(self) -> list[str]:
        return list(self.clips)

    def frames(self, clip_id: str) -> list[Record]:
        return self._owner[clip_id].frames[clip_id]

    def _file(self, clip_id: str, folder: str, suffix: str) -> Path:
        run = self._owner[clip_id]
        return run.path / folder / f"{run.names[clip_id]}{suffix}"

    def traceable(self) -> list[str]:
        """Episodes with a trace, or with recorded inputs and a way to trace them."""
        return [
            clip_id
            for clip_id in self.clips
            if self._file(clip_id, "traces", ".trace.npz").is_file()
            or (self.trace_command and self._file(clip_id, "inputs", ".npz").is_file())
        ]

    def trace_path(self, clip_id: str) -> Path | None:
        """The episode's trace, made first if it has inputs and none yet; None if it cannot be."""
        if clip_id not in self.clips:
            return None
        trace = self._file(clip_id, "traces", ".trace.npz")
        if trace.is_file():
            return trace
        inputs = self._file(clip_id, "inputs", ".npz")
        if not self.trace_command or not inputs.is_file():
            return None
        run = self._owner[clip_id]
        with self._lock:
            lock = self._tracing.setdefault(run.path, threading.Lock())
        with lock:
            if not trace.is_file():
                done = subprocess.run(
                    [*self.trace_command, str(run.path), "--episodes", run.names[clip_id]],
                    capture_output=True,
                    text=True,
                    check=False,
                )
                if done.returncode != 0 or not trace.is_file():
                    raise BrainError(
                        f"tracing {clip_id} failed: {(done.stderr or done.stdout).strip()[-800:]}"
                    )
        return trace

    def clip_frames(self, clip_id: str) -> LiveFrames:
        with self._lock:
            frames = self._decoded.get(clip_id)
            if frames is None:
                video = self._file(clip_id, "videos", ".mp4")
                files = self._clips[clip_id]["files"]["rgb"]
                frames = LiveFrames(
                    video if video.is_file() else None,
                    files["width"],
                    files["height"],
                    self._clips[clip_id]["frame_count"],
                )
                self._decoded[clip_id] = frames
            self._decoded.move_to_end(clip_id)
            while (
                len(self._decoded) > 1
                and sum(f.cached_bytes for f in self._decoded.values()) > CACHE_BYTES
            ):
                self._decoded.popitem(last=False)
            return frames

    def start_validation(self, on_done: Any = None) -> None:
        if on_done is not None:
            on_done()


class LiveTraces(Traces):
    """The episodes' ``trace-live`` files, by clip id; traced on first request when possible."""

    def __init__(self, catalog: LiveCatalog) -> None:
        self.catalog = catalog
        self._lock = threading.Lock()
        self._index = None

    def index(self) -> dict[tuple[str, str], Path]:
        return {
            (LIVE_DATASET_ID, clip_id): self.catalog._file(clip_id, "traces", ".trace.npz")  # pyright: ignore[reportPrivateUsage]
            for clip_id in self.catalog.traceable()
        }

    def clip_ids(self, dataset_id: str) -> list[str]:
        return self.catalog.traceable() if dataset_id == LIVE_DATASET_ID else []

    def trace(self, dataset_id: str, clip_id: str) -> Record | None:
        path = self.catalog.trace_path(clip_id) if dataset_id == LIVE_DATASET_ID else None
        return None if path is None else load_trace(path)
