import subprocess
from pathlib import Path

import pytest

from honey_factory_tools.clips import decode_frames, frame_count

WIDTH, HEIGHT, FRAMES = 64, 32, 60

CODECS = {
    "h264-lossless": ["-c:v", "libx264rgb", "-qp", "0", "-g", "48", "-pix_fmt", "rgb24"],
    "ffv1": ["-c:v", "ffv1", "-level", "3", "-g", "1", "-pix_fmt", "gbrp"],
}


def _frame(index: int) -> bytes:
    """A bright square sliding across a gradient."""
    pixels = bytearray(WIDTH * HEIGHT * 3)
    for y in range(HEIGHT):
        for x in range(WIDTH):
            square = index <= x < index + 8 and 10 <= y < 18
            for c in range(3):
                pixels[(y * WIDTH + x) * 3 + c] = (
                    250 - 40 * c if square else (x * 3 + y * 2 + c * 20) % 256
                )
    return bytes(pixels)


def _encode(path: Path, pixel_format: str, codec: list[str], data: bytes) -> None:
    size = f"{WIDTH}x{HEIGHT}"
    source = ["-f", "rawvideo", "-pix_fmt", pixel_format, "-s", size, "-i", "-"]
    subprocess.run(["ffmpeg", "-v", "error", *source, *codec, str(path)], input=data, check=True)


@pytest.mark.parametrize("codec", sorted(CODECS))
def test_decode_matches_written_pixels(codec: str, tmp_path: Path) -> None:
    frames = [_frame(index) for index in range(FRAMES)]
    path = tmp_path / "rgb.mkv"
    _encode(path, "rgb24", CODECS[codec], b"".join(frames))

    decoded = list(decode_frames(path, WIDTH, HEIGHT, "RGB"))

    assert [image.tobytes() for image in decoded] == frames
    assert all(image.size == (WIDTH, HEIGHT) and image.mode == "RGB" for image in decoded)
    assert frame_count(path) == FRAMES


def test_decode_gray_mask(tmp_path: Path) -> None:
    masks = [
        bytes(255 if (i + index) % 9 == 0 else 0 for i in range(WIDTH * HEIGHT))
        for index in range(5)
    ]
    path = tmp_path / "visible.mkv"
    _encode(path, "gray", ["-c:v", "libx264", "-qp", "0", "-pix_fmt", "gray"], b"".join(masks))

    decoded = list(decode_frames(path, WIDTH, HEIGHT, "L"))

    assert [image.tobytes() for image in decoded] == masks
    assert decoded[0].mode == "L"


def test_missing_file_raises(tmp_path: Path) -> None:
    with pytest.raises(OSError, match=r"missing\.mkv"):
        list(decode_frames(tmp_path / "missing.mkv", WIDTH, HEIGHT, "RGB"))
