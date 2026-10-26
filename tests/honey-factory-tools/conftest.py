import hashlib
import json
import shutil
import subprocess
from collections.abc import Callable
from pathlib import Path
from typing import Any

import pytest

from honey_factory_tools import canonical, ids, records
from honey_factory_tools.schemas import Schemas

TESTS = Path(__file__).resolve().parents[1]
FIXTURES = TESTS / "integration" / "fixtures" / "honey-factory"
SCHEMA_DIR = TESTS.parent / "schemas" / "honey-factory" / "1.0"


def load(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


@pytest.fixture(scope="session")
def load_json() -> Callable[[Path], Any]:
    return load


@pytest.fixture(scope="session")
def schema_dir() -> Path:
    return SCHEMA_DIR


@pytest.fixture(scope="session")
def schemas() -> Schemas:
    return Schemas(SCHEMA_DIR)


@pytest.fixture(scope="session")
def contract_dir() -> Path:
    return FIXTURES / "1.0"


@pytest.fixture(scope="session")
def vectors_dir() -> Path:
    return FIXTURES / "vectors"


def _lines(path: Path) -> list[str]:
    return path.read_text(encoding="utf-8").splitlines(keepends=True)


def _digest(data: bytes) -> dict[str, Any]:
    return {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def _make_slice(
    source: Path, target: Path, index: int, count: int, rejected: int, world: str | None = None
) -> Path:
    """A slice of ``source``, still or clip dataset: its scenes with ``scene_index % count ==
    index``, as one of ``count`` instances would have generated them, with ``rejected``
    rejected attempts."""
    manifest: dict[str, Any] = json.loads((source / "dataset.json").read_text(encoding="utf-8"))
    clips = manifest.get("kind") == "clips"
    items_name = "clips.jsonl" if clips else "samples.jsonl"
    items_key = "clips" if clips else "samples"
    root = target / f"{manifest['dataset_id']}.slice-{index}-of-{count}"
    root.mkdir(parents=True)
    scenes = [
        line
        for line in _lines(source / "scenes.jsonl")
        if json.loads(line)["generation"]["scene_index"] % count == index
    ]
    ids_kept = {json.loads(line)["scene_id"] for line in scenes}
    items = [
        line for line in _lines(source / items_name) if json.loads(line)["scene_id"] in ids_kept
    ]
    for line in items:
        for file in json.loads(line)["files"].values():
            destination = root / file["path"]
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source / file["path"], destination)
    scene_bytes = "".join(scenes).encode()
    item_bytes = "".join(items).encode()
    (root / "scenes.jsonl").write_bytes(scene_bytes)
    (root / items_name).write_bytes(item_bytes)

    slots = sorted(json.loads(line)["generation"]["scene_index"] for line in scenes)
    split_counts = {
        split: {"scenes": 0, "samples": 0} | ({"clips": 0} if clips else {})
        for split in ("train", "validation", "test")
    }
    for line in scenes:
        scene = json.loads(line)
        split_counts[scene["split"]]["scenes"] += 1
        split_counts[scene["split"]]["samples"] += scene["accepted_sample_count"]
        if clips:
            split_counts[scene["split"]]["clips"] += scene["accepted_clip_count"]
    manifest["splits"]["counts"] = split_counts
    manifest["counts"] = {
        "requested_scenes": len(slots),
        "attempted_scenes": len(slots) + rejected,
        "accepted_scenes": len(slots),
        "rejected_scenes": rejected,
        "failed_scenes": 0,
        "accepted_samples": sum(count["samples"] for count in split_counts.values()),
        "rejection_reasons": {"visibility": rejected} if rejected else {},
    }
    if clips:
        manifest["counts"]["accepted_clips"] = len(items)
    manifest["integrity"]["indexes"] = {
        "scenes": _digest(scene_bytes),
        items_key: _digest(item_bytes),
    }
    manifest["slice"] = {
        "index": index,
        "count": count,
        "world": world or f"world-{index}",
        "slots": slots,
    }
    (root / "dataset.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return root


@pytest.fixture(scope="session")
def make_slice() -> Callable[..., Path]:
    """Cuts a slice out of a complete dataset, as one of several instances would have
    generated it (M12-1)."""
    return _make_slice


CLIP_WIDTH, CLIP_HEIGHT, CLIP_TICKS, CLIP_FRAMES_PER_TICK = 64, 32, 4, 3
_COMMAND = {
    "forward": 1.0,
    "strafe": 0.0,
    "turn_rate": 4.5,
    "pitch_rate": 0.0,
    "jump": False,
    "delay_ticks": 0,
}
_IDLE = {
    "forward": 0.0,
    "strafe": 0.0,
    "turn_rate": 0.0,
    "pitch_rate": 0.0,
    "jump": False,
    "delay_ticks": 0,
}


def _clip_frame_pixels(frame: int) -> tuple[bytes, bytes]:
    """RGB and visible mask of a fixture clip frame: a 4x4 honey square sliding right."""
    rgb = bytearray(CLIP_WIDTH * CLIP_HEIGHT * 3)
    mask = bytearray(CLIP_WIDTH * CLIP_HEIGHT)
    for y in range(CLIP_HEIGHT):
        for x in range(CLIP_WIDTH):
            honey = frame <= x < frame + 4 and 10 <= y < 14
            p = y * CLIP_WIDTH + x
            mask[p] = 255 if honey else 0
            rgb[p * 3 : p * 3 + 3] = bytes((230, 160, 30) if honey else (90, 120 + y, 140))
    return bytes(rgb), bytes(mask)


def _encode(path: Path, pixel_format: str, codec: list[str], frames: list[bytes]) -> None:
    size = f"{CLIP_WIDTH}x{CLIP_HEIGHT}"
    source = ["-f", "rawvideo", "-pix_fmt", pixel_format, "-s", size, "-r", "60", "-i", "-"]
    command = ["ffmpeg", "-v", "error", "-y", *source, *codec, "-f", "matroska", str(path)]
    subprocess.run(command, input=b"".join(frames), check=True)


def _frame_record(clip_id: str, frame: int) -> dict[str, Any]:
    tick = 0 if frame == 0 else (frame - 1) // CLIP_FRAMES_PER_TICK + 1
    sub = 0 if frame == 0 else (frame - 1) % CLIP_FRAMES_PER_TICK + 1
    record: dict[str, Any] = {
        "schema_version": "1.0",
        "clip_id": clip_id,
        "frame": frame,
        "tick": tick,
        "sub_frame": sub,
        "frames_per_tick": CLIP_FRAMES_PER_TICK,
        "partial_tick": 1.0 if sub == 0 else sub / CLIP_FRAMES_PER_TICK,
        "pose": {
            "position": [16.5, 65.0, 24.5 - 0.07 * frame],
            "yaw_degrees": 180.0,
            "pitch_degrees": 0.0,
        },
        "target": {
            "target_present": True,
            "target_in_projection": True,
            "visible_pixels": 16,
            "unoccluded_pixels": 16,
            "visibility_ratio": 1.0,
            "target_status": "fully_visible",
            "visible_bbox_xywh": [frame, 10, 4, 4],
            "visible_centroid_xy": [frame + 1.5, 11.5],
            "distance_blocks": 7.0,
        },
        "touches_edge": frame == 0,
        "bearing": {
            "azimuth_degrees": -10.0 + frame,
            "elevation_degrees": -2.0,
            "distance_blocks": 7.0,
        },
    }
    if sub in (0, CLIP_FRAMES_PER_TICK):
        decided = tick > 0
        record["tick_labels"] = {
            "server_tick": 1000 + tick,
            "velocity": [0.0, 0.0, -0.2 if decided else 0.0],
            "senses": {
                "forward_speed": 0.2 if decided else 0.0,
                "strafe_speed": 0.0,
                "vertical_speed": 0.0,
                "yaw_rate": 0.0,
                "pitch_rate": 0.0,
                "pitch": 0.0,
                "down": [0.0, -1.0, 0.0],
                "on_ground": True,
                "bumped": False,
                "in_water": False,
                "last_command": _COMMAND if tick > 1 else _IDLE,
            },
            "executed": _COMMAND if decided else None,
            "teacher": _COMMAND if decided else None,
            "teacher_mode": "track" if decided else None,
            "belief": {"source": "seen", "position": [16, 65, 17], "observed_tick": tick - 1}
            if decided
            else None,
            "drifting": False,
            "coverage": 0.1 * tick,
        }
    return record


def _video_file(directory: Path, relative: str, name: str, frames: int) -> dict[str, Any]:
    data = (directory / name).read_bytes()
    return {
        "path": f"{relative}/{name}",
        "media_type": "video/x-matroska",
        "width": CLIP_WIDTH,
        "height": CLIP_HEIGHT,
        "frames": frames,
        "bytes": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
    }


def _make_clip_dataset(target: Path, codec: str = "h264-lossless") -> Path:
    """A small, real clip dataset: one scene with two 13-frame clips encoded by ffmpeg,
    its manifest still generating so no completion counts apply."""
    manifest: dict[str, Any] = load(FIXTURES / "1.0" / "valid" / "dataset-clips-complete.json")
    manifest["lifecycle"] = "generating"
    manifest["completed_at_utc"] = None
    del manifest["integrity"]
    del manifest["splits"]["counts"]
    manifest["counts"] = {
        "requested_scenes": manifest["counts"]["requested_scenes"],
        "attempted_scenes": 0,
        "accepted_scenes": 0,
        "rejected_scenes": 0,
        "failed_scenes": 0,
        "accepted_samples": 0,
        "accepted_clips": 0,
        "rejection_reasons": {},
    }
    root = target / manifest["dataset_id"]
    root.mkdir(parents=True)
    scene: dict[str, Any] = load(FIXTURES / "1.0" / "valid" / "scene-present.json")
    scene["accepted_sample_count"] = 0
    scene["accepted_clip_count"] = 2
    arguments = {
        "h264-lossless": (
            ["-c:v", "libx264rgb", "-qp", "0", "-g", "48", "-pix_fmt", "rgb24"],
            ["-c:v", "libx264", "-qp", "0", "-pix_fmt", "gray"],
        ),
        "ffv1": (
            ["-c:v", "ffv1", "-level", "3", "-g", "1", "-pix_fmt", "gbrp"],
            ["-c:v", "ffv1", "-level", "3", "-g", "1", "-pix_fmt", "gray"],
        ),
    }[codec]
    frames = 1 + CLIP_TICKS * CLIP_FRAMES_PER_TICK
    clips: list[str] = []
    for index in range(2):
        clip_id = ids.clip_id(scene["scene_id"], index)
        directory = root / ids.clip_directory(scene["split"], clip_id)
        directory.mkdir(parents=True)
        pixels = [_clip_frame_pixels(frame) for frame in range(frames)]
        _encode(directory / "rgb.mkv", "rgb24", arguments[0], [rgb for rgb, _ in pixels])
        _encode(directory / "visible.mkv", "gray", arguments[1], [mask for _, mask in pixels])
        lines = "".join(json.dumps(_frame_record(clip_id, frame)) + "\n" for frame in range(frames))
        (directory / "frames.jsonl").write_text(lines, encoding="utf-8")
        relative = ids.clip_directory(scene["split"], clip_id)
        seeds = ids.clip_seeds(manifest["generator"]["seed"], 0, index, 0)

        frame_bytes = (directory / "frames.jsonl").read_bytes()
        clip = {
            "schema_version": "1.0",
            "clip_id": clip_id,
            "scene_id": scene["scene_id"],
            "split": scene["split"],
            "clip_index": index,
            "requested_type": "approach",
            "clip_type": "approach",
            "attempt": 0,
            "seeds": {"start": seeds.start, "drive": seeds.drive, "drift": seeds.drift},
            "start_pose": {
                "position": [16.5, 65.0, 24.5],
                "yaw_degrees": 180.0,
                "pitch_degrees": 0.0,
            },
            "frames_per_tick": CLIP_FRAMES_PER_TICK,
            "tick_count": CLIP_TICKS,
            "frame_count": frames,
            "end_reason": "length",
            "codec": {"name": codec},
            "files": {
                "rgb": _video_file(directory, relative, "rgb.mkv", frames),
                "visible_mask": _video_file(directory, relative, "visible.mkv", frames),
                "frames": {
                    "path": f"{relative}/frames.jsonl",
                    "media_type": "application/jsonl",
                    "bytes": len(frame_bytes),
                    "sha256": hashlib.sha256(frame_bytes).hexdigest(),
                },
            },
            "executed": [_COMMAND] * CLIP_TICKS,
            "summary": {
                "drift_ticks": 0,
                "reached": False,
                "coverage_final": 0.4,
                "ticks_by_mode": {"track": CLIP_TICKS, "reacquire": 0, "search": 0},
            },
        }
        clips.append(json.dumps(clip) + "\n")
    if codec != "h264-lossless":
        manifest["generator"]["clips"]["codec"] = {"name": codec}
        manifest["storage"]["encodings"] = {"rgb": codec, "mask": codec, "frames": "jsonl"}
        manifest["resolved_config_sha256"] = canonical.sha256(records.configuration(manifest))
        manifest["dataset_id"] = ids.dataset_id(
            manifest["generator"]["dataset_name"], manifest["resolved_config_sha256"]
        )
        root = root.rename(target / manifest["dataset_id"])
    (root / "scenes.jsonl").write_text(json.dumps(scene) + "\n", encoding="utf-8")
    (root / "clips.jsonl").write_text("".join(clips), encoding="utf-8")
    (root / "dataset.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return root


@pytest.fixture(scope="session")
def make_clip_dataset() -> Callable[..., Path]:
    """Builds a small clip dataset with real videos (M12-2)."""
    return _make_clip_dataset


@pytest.fixture(scope="session")
def clip_frame_pixels() -> Callable[[int], tuple[bytes, bytes]]:
    """RGB and visible mask of frame ``n`` of every fixture clip."""
    return _clip_frame_pixels
