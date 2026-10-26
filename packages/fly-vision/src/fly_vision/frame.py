"""Frame and label containers, their files, and the image loaders.

Frames (model inputs) and labels are written to separate files. Files are plain `.npz`
(no pickle); `meta` is stored as a JSON string.
"""

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import numpy as np
from numpy.typing import NDArray
from PIL import Image, ImageOps

_PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


@dataclass(frozen=True)
class SourceRef:
    """Where a frame came from; `capture_meta` is honey-factory metadata, copied verbatim."""

    scene_id: str
    frame_id: str
    image_path: str | None = None
    capture_meta: dict[str, Any] = field(default_factory=dict[str, Any])


@dataclass(frozen=True)
class FlyFrame:
    activations: NDArray[np.float32]  # (N, 3) in CHANNELS order
    valid: NDArray[np.bool_]  # (N,)
    meta: dict[str, Any]


@dataclass(frozen=True)
class FlyLabels:
    honey_coverage: NDArray[np.float32]  # (N,)
    meta: dict[str, Any]  # ids only


def save_frame(
    out_dir: str | Path, frame: FlyFrame, labels: FlyLabels | None
) -> tuple[Path, Path | None]:
    """Write `<frame_id>.fly.npz` and, if given, `<frame_id>.labels.npz`."""
    source = frame.meta.get("source")
    if not source or not source.get("frame_id"):
        raise ValueError("frame has no source frame_id, which names the output files")
    frame_id = str(source["frame_id"])
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    fly_path = out / f"{frame_id}.fly.npz"
    np.savez(
        fly_path,
        activations=frame.activations,
        valid=frame.valid,
        meta=np.array(json.dumps(frame.meta, sort_keys=True)),
    )
    labels_path: Path | None = None
    if labels is not None:
        labels_path = out / f"{frame_id}.labels.npz"
        np.savez(
            labels_path,
            honey_coverage=labels.honey_coverage,
            meta=np.array(json.dumps(labels.meta, sort_keys=True)),
        )
    return fly_path, labels_path


def load_frame(path: str | Path) -> FlyFrame:
    with Path(path).open("rb") as fh, np.load(fh, allow_pickle=False) as d:
        return FlyFrame(
            activations=d["activations"],
            valid=d["valid"],
            meta=json.loads(str(d["meta"])),
        )


def load_labels(path: str | Path) -> FlyLabels:
    with Path(path).open("rb") as fh, np.load(fh, allow_pickle=False) as d:
        if "honey_coverage" not in d.files:
            raise ValueError(f"{path}: labels file has no honey_coverage")
        return FlyLabels(
            honey_coverage=d["honey_coverage"],
            meta=json.loads(str(d["meta"])),
        )


def _png_bit_depth(path: Path) -> int | None:
    """Bit depth from the PNG IHDR chunk, or None if the file is not a PNG."""
    with path.open("rb") as f:
        head = f.read(26)
    if len(head) < 26 or head[:8] != _PNG_SIGNATURE:
        return None
    return head[24]


def load_panorama(path: str | Path) -> NDArray[np.uint8]:
    """Read an 8-bit RGB image (RGBA loses its alpha) as `(H, W, 3)` uint8.

    Pillow truncates 16-bit RGB PNGs to 8 bits, so those raise `ValueError` instead.
    Grayscale and palette files raise too. The EXIF Orientation tag is applied, so the
    array is upright (a phone JPEG stored sideways is rotated back, never sampled rolled).
    """
    p = Path(path)
    if _png_bit_depth(p) == 16:
        raise ValueError(f"{p}: 16-bit PNG is not supported (Pillow would truncate it to 8 bits)")
    with Image.open(p) as raw:
        im = ImageOps.exif_transpose(raw)
        if im.mode not in ("RGB", "RGBA"):
            raise ValueError(f"{p}: expected an RGB or RGBA image, got mode {im.mode!r}")
        arr = np.asarray(im.convert("RGB"), dtype=np.uint8)
    return arr


def load_mask(path: str | Path) -> NDArray[np.float64]:
    """Read a mask as `(H, W)` float: 1.0 where the file is > 0, else 0.0.

    The EXIF Orientation tag is applied, as for `load_panorama`.
    """
    with Image.open(Path(path)) as raw:
        im = ImageOps.exif_transpose(raw)
        arr = np.asarray(im.convert("L") if im.mode not in ("L", "I;16", "I", "F") else im)
    if arr.ndim != 2:
        raise ValueError(f"{path}: mask must be single channel")
    return (arr > 0).astype(np.float64)
