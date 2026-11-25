import io
import json
import shutil
import subprocess
import threading
import urllib.error
import urllib.request
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import numpy as np
import pytest
from PIL import Image

from honey_factory_tools.inspector import Inspector, make_server, url
from honey_factory_tools.live import LiveCatalog, LiveTraces


class Client:
    def __init__(self, base: str, token: str) -> None:
        self.base = base
        self.token = token

    def get(self, path: str) -> tuple[int, Any]:
        request = urllib.request.Request(
            self.base + path, headers={"X-Inspector-Token": self.token}
        )
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                status, kind, body = (
                    response.status,
                    response.headers["Content-Type"],
                    response.read(),
                )
        except urllib.error.HTTPError as error:
            status, kind, body = error.code, error.headers["Content-Type"], error.read()
        return status, json.loads(body) if kind == "application/json" else body


def serve(inspector: Inspector) -> Iterator[Client]:
    server = make_server(inspector)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield Client(url(server).rstrip("/"), inspector.token)
    finally:
        server.shutdown()
        server.server_close()


pytestmark = pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="needs ffmpeg")

CAPTURE = {"width": 128, "height": 64}
STRIP = 8


def row(step: int) -> dict[str, Any]:
    return {
        "step": float(step),
        "distance": 5.0 - step,
        "yaw_error_deg": -30.0,
        "visible_pixels": 0.0 if step == 0 else 40.0,
        "bumped": float(step == 2),
        "touch": float(step >= 1),
        "wall_ahead_blocks": 1.0,
        "x": 1.5 + step,
        "y": 65.0,
        "z": 2.5,
        "yaw": 0.0,
        "pitch": 30.0 - step,
        "honey_x": 6.5,
        "honey_y": 66.5,
        "honey_z": 2.5,
        "presence": 0.7,
        "forward": 0.5,
        "turn_rate": -3.0,
        "pitch_rate": 0.0,
    }


def make_run(root: Path) -> Path:
    run = root / "live-l3-p1v-t90-s778"
    (run / "videos").mkdir(parents=True)
    (run / "traces").mkdir()
    summary = {
        "format": "fly-brain-live-report-v1",
        "run_dir": "/runs/pf-p1v",
        "seed": 778,
        "policy": "brain",
        "turn_before_deg": 90.0,
        "target_height": "1-3",
        "capture": CAPTURE,
    }
    (run / "summary.json").write_text(json.dumps(summary))
    episode = {
        "seed": 778,
        "scene_index": 2,
        "steps": 3,
        "reached": False,
        "failure": "OUT_OF_ARENA",
        "touch_ticks": 2,
        "trace": [row(n) for n in range(3)],
    }
    (run / "episodes.jsonl").write_text(json.dumps(episode) + "\n")
    # Three frames at half the capture size, with a white status strip under each.
    frames = np.zeros((3, 32 + STRIP, 64, 3), np.uint8)
    for n in range(3):
        frames[n, :32] = (40 * n, 100, 200)
    frames[:, 32:] = 255
    subprocess.run(
        ["ffmpeg", "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", "64x40",
         "-r", "20", "-i", "-", "-c:v", "libx264", "-qp", "0", "-pix_fmt", "yuv444p",
         str(run / "videos" / "s778-scene2.mp4")],
        input=frames.tobytes(), check=True,
    )  # fmt: skip
    header = {
        "format": "fly-brain-clip-trace-v1",
        "source": "live",
        "dataset_id": "live:x",
        "clip_id": "s778-scene2",
    }
    np.savez(
        run / "traces" / "s778-scene2.trace.npz",
        header=np.array(json.dumps(header)),
        activity=np.ones((3, 2), np.float16),
        cell_type=np.array(["DNa02", "DNa02"]),
        live_forward=np.full(3, 0.5, np.float32),
        live_actions=np.zeros((3, 3), np.float32),
    )
    return run


@pytest.fixture
def run(tmp_path: Path) -> Path:
    return make_run(tmp_path)


@pytest.fixture
def live_client(run: Path) -> Iterator[Client]:
    catalog = LiveCatalog([run])
    yield from serve(Inspector(catalog, None, traces=LiveTraces(catalog)))  # type: ignore[arg-type]


CLIP = "live-l3-p1v-t90-s778.s778-scene2"


def test_a_live_run_lists_its_episodes_as_clips(live_client: Client) -> None:
    status, dataset = live_client.get("/api/dataset")
    assert status == 200
    assert dataset["kind"] == "clips"
    assert dataset["clip_count"] == 1
    assert dataset["workspaces"]["brain"]["traced_clips"] == [CLIP]
    status, listing = live_client.get("/api/clips")
    assert [clip["clip_id"] for clip in listing["clips"]] == [CLIP]


def test_an_episode_has_frame_records_built_from_its_trace(live_client: Client) -> None:
    status, body = live_client.get(f"/api/clips/{CLIP}")
    assert status == 200
    clip, frames = body["clip"], body["frames"]
    assert clip["frame_count"] == 3
    assert clip["live"]["failure"] == "OUT_OF_ARENA"
    assert clip["live"]["turn_before_deg"] == 90.0
    assert clip["files"]["rgb"]["width"] == 128
    assert body["scene"]["target"]["position"] == [6, 66, 2]
    assert frames[1]["pose"]["position"] == [2.5, 65.0, 2.5]
    assert frames[1]["pose"]["pitch_degrees"] == 29.0
    assert frames[0]["target"]["target_status"] == "not_visible"
    assert frames[1]["target"]["target_status"] == "visible"
    # Yaw 0 faces south (+z); the honey lies east (+x), which is to the left.
    assert frames[1]["bearing"]["azimuth_degrees"] == pytest.approx(-90.0)
    labels = frames[2]["tick_labels"]
    assert labels["driver"] == "fly"
    assert labels["executed"]["turn_rate"] == -3.0
    assert labels["senses"]["bumped"] is True
    assert labels["senses"]["touch"]["front"] is True
    assert labels["live"]["wall_ahead_blocks"] == 1.0


def test_frames_come_from_the_video_without_its_status_strip(live_client: Client) -> None:
    status, png = live_client.get(f"/api/clips/{CLIP}/frames/2.png?scale=0.5")
    assert status == 200
    image = Image.open(io.BytesIO(png)).convert("RGB")
    assert image.size == (64, 32)
    pixel = np.asarray(image)[31, 10].astype(int)
    assert np.all(np.abs(pixel - (80, 100, 200)) < 12), pixel
    status, png = live_client.get(f"/api/clips/{CLIP}/masks/2.png?scale=0.5")
    assert status == 200
    assert set(Image.open(io.BytesIO(png)).convert("L").tobytes()) == {0}


def test_an_episodes_brain_trace_carries_its_live_columns(live_client: Client) -> None:
    status, trace = live_client.get(f"/api/brain/traces/{CLIP}")
    assert status == 200
    assert trace["steps"] == 3
    assert trace["series"]["live_forward"] == [0.5, 0.5, 0.5]
    assert len(trace["series"]["live_actions"]) == 3


def test_a_run_without_videos_or_traces_still_lists_its_episodes(tmp_path: Path) -> None:
    run = make_run(tmp_path)
    shutil.rmtree(run / "videos")
    shutil.rmtree(run / "traces")
    catalog = LiveCatalog([run])
    assert list(catalog.clips) == [CLIP]
    assert LiveTraces(catalog).clip_ids(catalog.dataset_id) == []
