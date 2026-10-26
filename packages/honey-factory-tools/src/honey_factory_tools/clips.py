"""Read clip datasets (M12-2): their videos, frames and labels.

``iter_clips`` and ``open_clip`` give each clip's record, and ``Clip.frames()`` its frames as
images with their labels, so a reader need not know how clips are stored.

Clip frames are stored as Matroska videos written by ``ffmpeg``: RGB in the dataset's codec
and the visible-target mask always lossless. Decoding runs ``ffmpeg`` and ``ffprobe`` as
subprocesses, so they must be on the ``PATH``.
"""

import io
import json
import subprocess
import threading
from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Literal

from PIL import Image

type Mode = Literal["RGB", "L"]

_PIXEL_FORMATS: dict[Mode, tuple[str, int]] = {"RGB": ("rgb24", 3), "L": ("gray", 1)}
SCALES = (0.25, 0.5, 1.0)


def decode_frames(
    path: Path,
    width: int,
    height: int,
    mode: Mode,
    scaled: tuple[int, int] | None = None,
) -> Iterator[Image.Image]:
    """Yields every frame of the video at ``path`` in order, as ``width`` by ``height`` images.

    With ``scaled``, frames are resized to that size: area-averaged for RGB, nearest
    neighbour for masks, so a mask stays binary.
    """
    if not path.is_file():
        raise FileNotFoundError(f"clip video {path} does not exist")
    pixel_format, channels = _PIXEL_FORMATS[mode]
    out_width, out_height = scaled or (width, height)
    size = out_width * out_height * channels
    resize: list[str] = []
    if scaled is not None and scaled != (width, height):
        flags = "area" if mode == "RGB" else "neighbor"
        resize = ["-vf", f"scale={out_width}:{out_height}:flags={flags}"]
    command = [
        "ffmpeg",
        "-nostdin",
        "-v",
        "error",
        "-i",
        str(path),
        *resize,
        "-f",
        "rawvideo",
        "-pix_fmt",
        pixel_format,
        "-",
    ]
    with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE) as process:
        assert process.stdout is not None
        assert process.stderr is not None
        try:
            while frame := process.stdout.read(size):
                if len(frame) != size:
                    raise OSError(
                        f"{path} ended inside a frame: expected frames of {out_width}x{out_height}"
                    )
                yield Image.frombytes(mode, (out_width, out_height), frame)
        finally:
            process.stdout.close()
            errors = process.stderr.read().decode(errors="replace").strip()
            code = process.wait()
        if code != 0:
            raise OSError(f"ffmpeg could not decode {path}: {errors}")


def frame_count(path: Path) -> int:
    """The number of frames in the video at ``path``, counted by decoding."""
    result = subprocess.run(
        [
            "ffprobe",
            "-v",
            "error",
            "-count_frames",
            "-select_streams",
            "v:0",
            "-show_entries",
            "stream=nb_read_frames",
            "-of",
            "csv=p=0",
            str(path),
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise OSError(f"ffprobe could not read {path}: {result.stderr.strip()}")
    return int(result.stdout.strip())


def scaled_size(width: int, height: int, scale: float) -> tuple[int, int]:
    """The size of a ``width`` by ``height`` frame at ``scale``, one of ``SCALES``."""
    if scale not in SCALES:
        raise ValueError(f"scale must be one of {', '.join(str(value) for value in SCALES)}")
    return max(1, round(width * scale)), max(1, round(height * scale))


class ClipFrames:
    """One clip's frames as PNG bytes, decoded once per scale and kept for playback."""

    def __init__(self, rgb: Path, mask: Path, width: int, height: int) -> None:
        self.paths: dict[Mode, Path] = {"RGB": rgb, "L": mask}
        self.width = width
        self.height = height
        self._decoded: dict[tuple[Mode, float], list[bytes]] = {}
        self._lock = threading.Lock()

    def frame_png(self, frame: int, scale: float) -> bytes:
        return self._frames("RGB", scale)[frame]

    def mask_png(self, frame: int, scale: float) -> bytes:
        return self._frames("L", scale)[frame]

    def frame_count(self, scale: float = 0.25) -> int:
        return len(self._frames("RGB", scale))

    @property
    def cached_bytes(self) -> int:
        with self._lock:
            return sum(len(png) for frames in self._decoded.values() for png in frames)

    def _frames(self, mode: Mode, scale: float) -> list[bytes]:
        size = scaled_size(self.width, self.height, scale)
        with self._lock:
            frames = self._decoded.get((mode, scale))
            if frames is None:
                frames = [
                    _png(image)
                    for image in decode_frames(
                        self.paths[mode], self.width, self.height, mode, size
                    )
                ]
                self._decoded[(mode, scale)] = frames
            return frames


def _png(image: Image.Image) -> bytes:
    buffer = io.BytesIO()
    image.save(buffer, format="PNG", compress_level=1)
    return buffer.getvalue()


@dataclass(frozen=True)
class ClipFrame:
    """One frame of a clip: its RGB image, its visible-target mask and its frame record.

    ``driver`` names who chose the executed command of the frame's tick (``teacher``,
    ``teacher_drift`` or ``script``; None on the reset frame). Train a policy on the
    teacher's command, ``tick_labels.teacher``, never on a scripted tick's executed command.
    """

    index: int
    rgb: Image.Image
    visible_mask: Image.Image
    labels: dict[str, Any]
    driver: str | None


@dataclass(frozen=True)
class Clip:
    """One clip of a clip dataset: its record, and its frames read on demand."""

    dataset: Path
    record: dict[str, Any]

    @property
    def width(self) -> int:
        return int(self.record["files"]["rgb"]["width"])

    @property
    def height(self) -> int:
        return int(self.record["files"]["rgb"]["height"])

    def labels(self) -> list[dict[str, Any]]:
        """Every frame record, in frame order."""
        path = self.dataset / self.record["files"]["frames"]["path"]
        return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]

    def frames(self) -> Iterator[ClipFrame]:
        """Yields each frame with its images and labels, decoding both videos as it goes."""
        files: dict[str, Any] = self.record["files"]
        rgb = decode_frames(self.dataset / files["rgb"]["path"], self.width, self.height, "RGB")
        mask_file = self.dataset / files["visible_mask"]["path"]
        masks = decode_frames(mask_file, self.width, self.height, "L")
        records = self.labels()
        drivers = {
            record["tick"]: record["tick_labels"]["driver"]
            for record in records
            if "tick_labels" in record
        }
        for index, (image, mask, labels) in enumerate(zip(rgb, masks, records, strict=True)):
            yield ClipFrame(index, image, mask, labels, drivers.get(labels["tick"]))


def iter_clips(dataset: Path, split: str | None = None) -> Iterator[Clip]:
    """The clips of a clip dataset in ``clips.jsonl`` order, optionally of one split."""
    for line in (dataset / "clips.jsonl").read_text(encoding="utf-8").splitlines():
        record: dict[str, Any] = json.loads(line)
        if split is None or record["split"] == split:
            yield Clip(dataset, record)


def open_clip(dataset: Path, clip_id: str) -> Clip:
    """Clip ``clip_id`` of the clip dataset at ``dataset``; raises ``KeyError`` when absent."""
    for clip in iter_clips(dataset):
        if clip.record["clip_id"] == clip_id:
            return clip
    raise KeyError(f"{dataset} has no clip {clip_id}")
