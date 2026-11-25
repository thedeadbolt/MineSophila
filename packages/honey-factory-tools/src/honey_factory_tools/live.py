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
import threading
from collections import OrderedDict
from pathlib import Path
from typing import Any, cast

from PIL import Image

from honey_factory_tools.brain import Traces, load_trace
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

    def __init__(self, video: Path, width: int, height: int) -> None:
        super().__init__(video, video, width, height)
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


class LiveCatalog:
    """Live runs' episodes as one clip dataset, with the members the inspector reads."""

    def __init__(self, runs: list[Path]) -> None:
        self.root = runs[0].resolve() if runs else Path()
        self.dataset_id = LIVE_DATASET_ID
        self.manifest_sha256 = ""
        self.kind = "clips"
        self.index_errors: list[str] = []
        self.samples: list[Record] = []
        self.by_sample: dict[str, Record] = {}
        self.scene_samples: dict[str, list[Record]] = {}
        self.scenes: dict[str, Record] = {}
        self.clips: dict[str, Record] = {}
        self.traces: dict[str, Path] = {}
        self._frames: dict[str, list[Record]] = {}
        self._videos: dict[str, Path] = {}
        self._decoded: OrderedDict[str, LiveFrames] = OrderedDict()
        self._lock = threading.Lock()
        self.validation = Validation()
        self.validation.state = "done"
        capture = DEFAULT_CAPTURE
        self.level: int | str = "live"
        for run in runs:
            capture = self._add_run(run.resolve())
        self.manifest: Record = {
            # "complete" keeps the page's not-for-training banner off; these are evaluation runs.
            "lifecycle": "complete",
            "difficulty": self.level,
            "capture": {
                "mode": "PANORAMA_310",
                "width": capture["width"],
                "height": capture["height"],
                "projection": PROJECTION,
            },
            "target": {"block_id": "minecraft:honey_block"},
            "counts": {"clips": len(self.clips)},
            "splits": {},
            "software": {},
            "render_environment": {},
        }

    def _add_run(self, run: Path) -> dict[str, int]:
        # A run still going has no summary yet; its episodes still show.
        summary = run / "summary.json"
        report: Record = json.loads(summary.read_text()) if summary.is_file() else {}
        capture = cast(dict[str, int], report.get("capture") or DEFAULT_CAPTURE)
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
            self.scenes[clip_id] = {
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
            self.clips[clip_id] = {
                "clip_id": clip_id,
                "scene_id": clip_id,
                "split": run.name,
                "clip_index": len(self.clips),
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
                "live": {
                    **{
                        key: value
                        for key, value in episode.items()
                        if key not in ("trace", "scene")
                    },
                    **settings,
                },
            }
            self._frames[clip_id] = frames
            if video.is_file():
                self._videos[clip_id] = video
            trace = run / "traces" / f"{name}.trace.npz"
            if trace.is_file():
                self.traces[clip_id] = trace
        return capture

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
        return self._frames[clip_id]

    def clip_frames(self, clip_id: str) -> LiveFrames:
        with self._lock:
            frames = self._decoded.get(clip_id)
            if frames is None:
                video = self._videos.get(clip_id)
                if video is None:
                    raise FileNotFoundError(f"episode {clip_id} has no video")
                files = self.clips[clip_id]["files"]["rgb"]
                frames = LiveFrames(video, files["width"], files["height"])
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
    """The episodes' ``trace-live`` files, by clip id."""

    def __init__(self, catalog: LiveCatalog) -> None:
        self.catalog = catalog
        self._lock = threading.Lock()
        self._index = None

    def index(self) -> dict[tuple[str, str], Path]:
        return {(LIVE_DATASET_ID, clip_id): path for clip_id, path in self.catalog.traces.items()}

    def trace(self, dataset_id: str, clip_id: str) -> Record | None:
        path = self.catalog.traces.get(clip_id) if dataset_id == LIVE_DATASET_ID else None
        return None if path is None else load_trace(path)
