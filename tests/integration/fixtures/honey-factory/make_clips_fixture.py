"""Builds 1.0/clips/, the shared mini clip dataset (M12-2), from the still fixture's scenes.

The four scenes of 1.0/dataset/ each get one clip: approach clips in the two scenes with
honey, no-honey clips in the two without. Frames are 64x64; each clip has 4 ticks of 3
frames. In s000002-c00 the honey leaves view after tick 2, so the teacher re-acquires from
memory. Videos are encoded with ffmpeg as lossless H.264, as the mod writes them.

Run from the repository root with the tools package's environment:

    uv run --package honey-factory-tools python \
        tests/integration/fixtures/honey-factory/make_clips_fixture.py

The output is committed; regenerate it only when the contract changes.
"""

import hashlib
import json
import shutil
import subprocess
from pathlib import Path
from typing import Any

from honey_factory_tools import canonical, ids, records

HERE = Path(__file__).resolve().parent
SOURCE = HERE / "1.0" / "dataset"
TARGET = HERE / "1.0" / "clips"
SIZE, TICKS, PER_TICK = 64, 4, 3
FRAMES = 1 + TICKS * PER_TICK
WALK = {
    "forward": 1.0,
    "strafe": 0.0,
    "turn_rate": 4.5,
    "pitch_rate": 0.0,
    "jump": False,
    "delay_ticks": 0,
}
TURN = {
    "forward": 0.0,
    "strafe": 0.0,
    "turn_rate": 9.0,
    "pitch_rate": 0.0,
    "jump": False,
    "delay_ticks": 0,
}
IDLE = {
    "forward": 0.0,
    "strafe": 0.0,
    "turn_rate": 0.0,
    "pitch_rate": 0.0,
    "jump": False,
    "delay_ticks": 0,
}
RGB_CODEC = ["-c:v", "libx264rgb", "-qp", "0", "-preset", "medium", "-g", "48", "-pix_fmt", "rgb24"]
MASK_CODEC = ["-c:v", "libx264", "-qp", "0", "-preset", "medium", "-g", "48", "-pix_fmt", "gray"]


def digest(data: bytes) -> dict[str, Any]:
    return {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def honey_box(frame: int, hidden_after_tick: int | None) -> list[int] | None:
    """The honey's 8x8 square in a frame, sliding right; None once it has left view."""
    tick = 0 if frame == 0 else (frame - 1) // PER_TICK + 1
    if hidden_after_tick is not None and tick > hidden_after_tick:
        return None
    return [20 + frame, 28, 8, 8]


def pixels(box: list[int] | None) -> tuple[bytes, bytes]:
    rgb = bytearray()
    mask = bytearray()
    for y in range(SIZE):
        for x in range(SIZE):
            inside = (
                box is not None and box[0] <= x < box[0] + box[2] and box[1] <= y < box[1] + box[3]
            )
            mask.append(255 if inside else 0)
            rgb += bytes((230, 160, 30) if inside else (100, 130 + y, 150))
    return bytes(rgb), bytes(mask)


def encode(path: Path, pixel_format: str, codec: list[str], frames: list[bytes]) -> None:
    source = [
        "-f",
        "rawvideo",
        "-pix_fmt",
        pixel_format,
        "-s",
        f"{SIZE}x{SIZE}",
        "-r",
        "60",
        "-i",
        "-",
    ]
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", *source, *codec, "-f", "matroska", str(path)],
        input=b"".join(frames),
        check=True,
    )


def target(box: list[int] | None, present: bool) -> dict[str, Any]:
    if not present:
        return {
            "target_present": False,
            "target_in_projection": False,
            "visible_pixels": 0,
            "unoccluded_pixels": 0,
            "visibility_ratio": None,
            "target_status": "absent",
            "visible_bbox_xywh": None,
            "visible_centroid_xy": None,
        }
    if box is None:
        return {
            "target_present": True,
            "target_in_projection": False,
            "visible_pixels": 0,
            "unoccluded_pixels": 0,
            "visibility_ratio": None,
            "target_status": "out_of_view",
            "visible_bbox_xywh": None,
            "visible_centroid_xy": None,
            "distance_blocks": 6.0,
        }
    count = box[2] * box[3]
    return {
        "target_present": True,
        "target_in_projection": True,
        "visible_pixels": count,
        "unoccluded_pixels": count,
        "visibility_ratio": 1.0,
        "target_status": "fully_visible",
        "visible_bbox_xywh": box,
        "visible_centroid_xy": [box[0] + box[2] / 2 - 0.5, box[1] + box[3] / 2 - 0.5],
        "distance_blocks": 6.0,
    }


def frame_record(
    clip_id: str,
    frame: int,
    honey: list[int] | None,
    hidden_after: int | None,
    beliefs: list[dict[str, Any] | None],
    modes: list[str | None],
    commands: list[dict[str, Any] | None],
) -> dict[str, Any]:
    tick = 0 if frame == 0 else (frame - 1) // PER_TICK + 1
    sub = 0 if frame == 0 else (frame - 1) % PER_TICK + 1
    box = honey_box(frame, hidden_after) if honey is not None else None
    record: dict[str, Any] = {
        "schema_version": "1.0",
        "clip_id": clip_id,
        "frame": frame,
        "tick": tick,
        "sub_frame": sub,
        "frames_per_tick": PER_TICK,
        "partial_tick": 1.0 if sub == 0 else sub / PER_TICK,
        "pose": {
            "position": [16.5, 65.0, 10.5 + 0.07 * frame],
            "yaw_degrees": 0.0,
            "pitch_degrees": 0.0,
        },
        "target": target(box, honey is not None),
        "touches_edge": False,
        "bearing": None
        if honey is None
        else {"azimuth_degrees": -10.0 + frame, "elevation_degrees": -8.0, "distance_blocks": 6.0},
    }
    if sub in (0, PER_TICK):
        record["tick_labels"] = {
            "server_tick": 1000 + tick,
            "velocity": [0.0, 0.0, 0.0 if tick == 0 else 0.2],
            "senses": {
                "forward_speed": 0.0 if tick == 0 else 0.2,
                "strafe_speed": 0.0,
                "vertical_speed": 0.0,
                "yaw_rate": 0.0,
                "pitch_rate": 0.0,
                "pitch": 0.0,
                "down": [0.0, -1.0, 0.0],
                "on_ground": True,
                "bumped": False,
                "in_water": False,
                "last_command": IDLE if tick <= 1 else commands[tick - 1],
            },
            "executed": commands[tick],
            "teacher": commands[tick],
            "teacher_mode": modes[tick],
            "belief": beliefs[tick],
            "drifting": False,
            "driver": None if tick == 0 else "teacher",
            "coverage": round(0.1 * tick, 3),
        }
    return record


def main() -> None:
    if TARGET.exists():
        shutil.rmtree(TARGET)
    TARGET.mkdir()
    manifest: dict[str, Any] = json.loads(
        (HERE / "1.0" / "valid" / "dataset-clips-complete.json").read_text(encoding="utf-8")
    )
    manifest["generator"]["clips"]["clips_per_scene"] = 1
    manifest["generator"]["clips"]["ticks"] = {
        "approach": TICKS,
        "pass_by": TICKS,
        "scan": TICKS,
        "occlusion": TICKS,
        "detour": TICKS,
        "no_honey": TICKS,
    }
    manifest["generator"]["dataset_name"] = "honey-l1-clips-mini"
    scenes = [
        json.loads(line)
        for line in (SOURCE / "scenes.jsonl").read_text(encoding="utf-8").splitlines()
    ]
    clips: list[dict[str, Any]] = []
    for scene in scenes:
        scene["accepted_sample_count"] = 0
        scene["accepted_clip_count"] = 1
        scene["generation"]["parameters"]["viewpoint_draws"] = [0]
        index = scene["generation"]["scene_index"]
        clip_id = ids.clip_id(scene["scene_id"], 0)
        present = scene["target"]["present"]
        honey = scene["target"]["position"] if present else None
        hidden_after = 2 if clip_id == "s000002-c00" else None
        beliefs: list[dict[str, Any] | None] = [None]
        modes: list[str | None] = [None]
        commands: list[dict[str, Any] | None] = [None]
        last_seen: list[int] | None = None
        for tick in range(1, TICKS + 1):
            observed = tick - 1
            seen = present and honey_box(observed * PER_TICK, hidden_after) is not None
            if seen:
                last_seen = honey
                beliefs.append({"source": "seen", "position": honey, "observed_tick": observed})
                modes.append("track")
                commands.append(WALK)
            elif last_seen is not None:
                beliefs.append(
                    {"source": "memory", "position": last_seen, "observed_tick": observed}
                )
                modes.append("reacquire")
                commands.append(WALK)
            else:
                beliefs.append({"source": "none", "position": None, "observed_tick": observed})
                modes.append("search")
                commands.append(TURN)
        directory = TARGET / ids.clip_directory(scene["split"], clip_id)
        directory.mkdir(parents=True)
        images = [
            pixels(honey_box(frame, hidden_after) if present else None) for frame in range(FRAMES)
        ]
        encode(directory / "rgb.mkv", "rgb24", RGB_CODEC, [rgb for rgb, _ in images])
        encode(directory / "visible.mkv", "gray", MASK_CODEC, [mask for _, mask in images])
        lines = "".join(
            json.dumps(frame_record(clip_id, frame, honey, hidden_after, beliefs, modes, commands))
            + "\n"
            for frame in range(FRAMES)
        )
        (directory / "frames.jsonl").write_text(lines, encoding="utf-8")
        relative = ids.clip_directory(scene["split"], clip_id)
        seeds = ids.clip_seeds(manifest["generator"]["seed"], index, 0, 0)

        def video(name: str, folder: Path = directory, path: str = relative) -> dict[str, Any]:
            data = (folder / name).read_bytes()
            described = {"path": f"{path}/{name}", "media_type": "video/x-matroska"}
            return described | {"width": SIZE, "height": SIZE, "frames": FRAMES} | digest(data)

        clip_type = "approach" if present else "no_honey"
        clips.append(
            {
                "schema_version": "1.0",
                "clip_id": clip_id,
                "scene_id": scene["scene_id"],
                "split": scene["split"],
                "clip_index": 0,
                "requested_type": clip_type,
                "clip_type": clip_type,
                "attempt": 0,
                "seeds": {"start": seeds.start, "drive": seeds.drive, "drift": seeds.drift},
                "start_pose": {
                    "position": [16.5, 65.0, 10.5],
                    "yaw_degrees": 0.0,
                    "pitch_degrees": 0.0,
                },
                "frames_per_tick": PER_TICK,
                "tick_count": TICKS,
                "frame_count": FRAMES,
                "end_reason": "length",
                "codec": {"name": "h264-lossless"},
                "files": {
                    "rgb": video("rgb.mkv"),
                    "visible_mask": video("visible.mkv"),
                    "frames": {
                        "path": f"{relative}/frames.jsonl",
                        "media_type": "application/jsonl",
                    }
                    | digest((directory / "frames.jsonl").read_bytes()),
                },
                "executed": commands[1:],
                "summary": {
                    "drift_ticks": 0,
                    "reached": False if present else None,
                    "coverage_final": round(0.1 * TICKS, 3),
                    "ticks_by_mode": {
                        mode: modes.count(mode) for mode in ("track", "reacquire", "search")
                    },
                    "ticks_by_driver": {"teacher": TICKS, "teacher_drift": 0, "script": 0},
                },
            }
        )
    scene_bytes = "".join(
        json.dumps(scene, separators=(",", ":")) + "\n" for scene in scenes
    ).encode()
    clip_bytes = "".join(json.dumps(clip, separators=(",", ":")) + "\n" for clip in clips).encode()
    (TARGET / "scenes.jsonl").write_bytes(scene_bytes)
    (TARGET / "clips.jsonl").write_bytes(clip_bytes)
    for count in manifest["splits"]["counts"].values():
        count["clips"] = count["scenes"]
    manifest["counts"]["accepted_clips"] = len(clips)
    manifest["integrity"]["indexes"] = {"scenes": digest(scene_bytes), "clips": digest(clip_bytes)}
    manifest["resolved_config_sha256"] = canonical.sha256(records.configuration(manifest))
    manifest["dataset_id"] = ids.dataset_id(
        manifest["generator"]["dataset_name"], manifest["resolved_config_sha256"]
    )
    (TARGET / "dataset.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
