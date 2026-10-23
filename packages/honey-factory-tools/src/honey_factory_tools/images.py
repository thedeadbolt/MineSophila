"""Read the contract's PNG images and measure binary masks.

RGB images are 8-bit truecolour and masks 8-bit greyscale, both non-interlaced. The
header is checked here byte for byte, so Pillow's lenient conversions (for example of
2-bit greyscale to ``L``) cannot hide a wrong encoding.
"""

import io
import struct
from dataclasses import dataclass

from PIL import Image as PilImage

SIGNATURE = b"\x89PNG\r\n\x1a\n"
POSITIVE = 255
_CHANNELS = {0: 1, 2: 3}
_MODES = {1: "L", 3: "RGB"}


class ImageError(ValueError):
    """The bytes are not a PNG this contract allows."""


@dataclass(frozen=True)
class Image:
    """Row-major, top-down pixels with ``channels`` bytes per pixel."""

    width: int
    height: int
    channels: int
    data: bytes


@dataclass(frozen=True)
class MaskStatistics:
    """Positive pixel count, half-open ``[x, y, w, h]`` box and mean pixel centre."""

    positive_pixels: int
    bbox_xywh: list[int] | None
    centroid_xy: list[float] | None


def decode(png: bytes) -> Image:
    """Decode an 8-bit greyscale or RGB, non-interlaced PNG."""
    if not png.startswith(SIGNATURE) or png[12:16] != b"IHDR" or len(png) < 33:
        raise ImageError("is not a PNG file starting with its IHDR chunk")
    width, height, depth, colour, _, _, interlace = struct.unpack(">IIBBBBB", png[16:29])
    if depth != 8 or colour not in _CHANNELS:
        raise ImageError(f"is not 8-bit greyscale or RGB (bit depth {depth}, colour type {colour})")
    if interlace != 0:
        raise ImageError("is interlaced")
    channels = _CHANNELS[colour]
    try:
        with PilImage.open(io.BytesIO(png), formats=["PNG"]) as image:
            image.load()
            if image.mode != _MODES[channels] or image.size != (width, height):
                raise ImageError(f"decodes as {image.mode} {image.size}, not its header's")
            data = image.tobytes()
    except (OSError, SyntaxError, ValueError) as error:
        if isinstance(error, ImageError):
            raise
        raise ImageError(f"does not decode: {error}") from error
    return Image(width, height, channels, data)


def mask_statistics(mask: Image) -> MaskStatistics:
    """Measure a binary mask; raises ``ImageError`` for a value other than 0 or 255."""
    data = mask.data
    positive = data.count(POSITIVE)
    if positive + data.count(0) != len(data):
        index = next(i for i, value in enumerate(data) if value not in (0, POSITIVE))
        x, y = index % mask.width, index // mask.width
        raise ImageError(f"pixel ({x}, {y}) is {data[index]}, not 0 or {POSITIVE}")
    if positive == 0:
        return MaskStatistics(0, None, None)
    min_x, min_y, max_x, max_y = mask.width, mask.height, -1, -1
    sum_x = sum_y = 0
    for y in range(mask.height):
        row = data[y * mask.width : (y + 1) * mask.width]
        x = row.find(POSITIVE)
        if x < 0:
            continue
        min_y = min(min_y, y)
        max_y = y
        min_x = min(min_x, x)
        max_x = max(max_x, row.rfind(POSITIVE))
        while x >= 0:
            sum_x += x
            sum_y += y
            x = row.find(POSITIVE, x + 1)
    return MaskStatistics(
        positive,
        [min_x, min_y, max_x - min_x + 1, max_y - min_y + 1],
        [sum_x / positive + 0.5, sum_y / positive + 0.5],
    )
