"""Read the video files of clip datasets (M12-2).

Clip frames are stored as Matroska videos written by ``ffmpeg``: RGB in the dataset's codec
and the visible-target mask always lossless. Decoding runs ``ffmpeg`` and ``ffprobe`` as
subprocesses, so they must be on the ``PATH``.
"""

import subprocess
from collections.abc import Iterator
from pathlib import Path
from typing import Literal

from PIL import Image

type Mode = Literal["RGB", "L"]

_PIXEL_FORMATS: dict[Mode, tuple[str, int]] = {"RGB": ("rgb24", 3), "L": ("gray", 1)}


def decode_frames(path: Path, width: int, height: int, mode: Mode) -> Iterator[Image.Image]:
    """Yields every frame of the video at ``path`` in order, as ``width`` by ``height`` images."""
    if not path.is_file():
        raise FileNotFoundError(f"clip video {path} does not exist")
    pixel_format, channels = _PIXEL_FORMATS[mode]
    size = width * height * channels
    command = [
        "ffmpeg",
        "-nostdin",
        "-v",
        "error",
        "-i",
        str(path),
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
                        f"{path} ended inside a frame: expected frames of {width}x{height}"
                    )
                yield Image.frombytes(mode, (width, height), frame)
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
