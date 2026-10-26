"""Read the video files of clip datasets (M12-2).

Clip frames are stored as Matroska videos written by ``ffmpeg``: RGB in the dataset's codec
and the visible-target mask always lossless. Decoding runs ``ffmpeg`` and ``ffprobe`` as
subprocesses, so they must be on the ``PATH``.
"""

import io
import subprocess
import threading
from collections.abc import Iterator
from pathlib import Path
from typing import Literal

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
