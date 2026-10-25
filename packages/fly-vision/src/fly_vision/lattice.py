"""Eye lattice: the viewing direction and photoreceptor subtype of every ommatidium.

Directions are (azimuth, elevation) in degrees in the eye frame of `projection.py`
(level frame, azimuth positive to the right, elevation positive up).
"""

import hashlib
from dataclasses import dataclass
from importlib import resources
from pathlib import Path

import numpy as np
from numpy.typing import NDArray

SUBTYPES = ("pale", "yellow", "dorsal_yellow", "dra")
LATTICE_VERSION = "generated-v1"

_AZ_RIGHT = (-15.0, 155.0)
_EL_RANGE = (-75.0, 75.0)
_PALE_FRACTION = 0.3
_ARRAY_NAMES = (
    "ommatidium_id",
    "eye",
    "hex_uv",
    "direction_deg",
    "subtype",
    "column_id",
)


@dataclass(frozen=True)
class Lattice:
    """Per-ommatidium arrays, all of length N (see docs/assumptions.md)."""

    ommatidium_id: NDArray[np.int32]
    eye: NDArray[np.str_]
    hex_uv: NDArray[np.int32]
    direction_deg: NDArray[np.float64]
    subtype: NDArray[np.str_]
    column_id: NDArray[np.int32]
    version: str


def generate_hex_lattice(spacing_deg: float = 5.0, seed: int = 0) -> Lattice:
    """Hexagonal lattice on the sphere, right eye first built, left eye its mirror image."""
    d = spacing_deg
    el_min, el_max = _EL_RANGE
    az_lo, az_hi = _AZ_RIGHT
    n_rows = int(np.floor((el_max - el_min) / (d * np.sqrt(3) / 2) + 1e-9)) + 1
    rng = np.random.default_rng(seed)

    right_rows: list[tuple[int, int, float, float]] = []
    for k in range(n_rows):
        el = el_min + k * d * np.sqrt(3) / 2
        step = d / np.cos(np.deg2rad(el))
        n_cols = int(np.floor((az_hi - az_lo) / step - 0.5 * (k % 2) + 1e-9)) + 1
        for j in range(n_cols):
            right_rows.append((k, j, az_lo + (j + 0.5 * (k % 2)) * step, el))

    parts: dict[str, list[tuple[int, int, float, float]]] = {"L": [], "R": right_rows}
    # Mirror: az -> -az, and columns renumbered so they still increase with azimuth.
    row_len: dict[int, int] = {}
    for k, _, _, _ in right_rows:
        row_len[k] = row_len.get(k, 0) + 1
    parts["L"] = sorted(
        ((k, row_len[k] - 1 - j, -az, el) for k, j, az, el in right_rows),
        key=lambda t: (t[0], t[1]),
    )

    eyes: list[str] = []
    uv: list[tuple[int, int]] = []
    dirs: list[tuple[float, float]] = []
    subtypes: list[str] = []
    for e in "LR":
        rows = parts[e]
        draws = rng.random(len(rows))
        top = max(k for k, _, _, _ in rows)
        dorsal_el = el_max - (el_max - el_min) / 3
        for (k, j, az, el), u in zip(rows, draws, strict=True):
            eyes.append(e)
            uv.append((k, j))
            dirs.append((az, el))
            if k == top:
                subtypes.append("dra")
            elif el >= dorsal_el:
                subtypes.append("pale" if u < _PALE_FRACTION else "dorsal_yellow")
            else:
                subtypes.append("pale" if u < _PALE_FRACTION else "yellow")

    n = len(eyes)
    lattice = Lattice(
        ommatidium_id=np.arange(n, dtype=np.int32),
        eye=np.array(eyes, dtype="<U1"),
        hex_uv=np.array(uv, dtype=np.int32).reshape(n, 2),
        direction_deg=np.array(dirs, dtype=np.float64).reshape(n, 2),
        subtype=np.array(subtypes, dtype="<U13"),
        column_id=np.full(n, -1, dtype=np.int32),
        version=LATTICE_VERSION,
    )
    validate_lattice(lattice)
    return lattice


def validate_lattice(lattice: Lattice) -> None:
    """Raise ValueError when shapes, dtypes, ids, labels or directions are inconsistent."""
    n = lattice.ommatidium_id.shape[0] if lattice.ommatidium_id.ndim == 1 else -1
    expected: dict[str, tuple[tuple[int, ...], str]] = {
        "ommatidium_id": ((n,), "int32"),
        "eye": ((n,), "<U1"),
        "hex_uv": ((n, 2), "int32"),
        "direction_deg": ((n, 2), "float64"),
        "subtype": ((n,), "<U13"),
        "column_id": ((n,), "int32"),
    }
    for name, (shape, dtype) in expected.items():
        arr: NDArray[np.generic] = getattr(lattice, name)
        if arr.shape != shape:
            raise ValueError(f"{name}: shape {arr.shape}, expected {shape}")
        if arr.dtype != np.dtype(dtype):
            raise ValueError(f"{name}: dtype {arr.dtype}, expected {dtype}")
    if not np.array_equal(np.unique(lattice.ommatidium_id), np.arange(n)):
        raise ValueError("ommatidium_id must be unique and equal to 0..N-1")
    if not np.isin(lattice.eye, ["L", "R"]).all():
        raise ValueError("eye must be 'L' or 'R'")
    if not np.isin(lattice.subtype, SUBTYPES).all():
        raise ValueError(f"subtype must be one of {SUBTYPES}")
    if not np.isfinite(lattice.direction_deg).all():
        raise ValueError("direction_deg must be finite")
    az, el = lattice.direction_deg[:, 0], lattice.direction_deg[:, 1]
    if (np.abs(az) > 180).any() or (np.abs(el) > 90).any():
        raise ValueError("direction_deg out of range (|az| <= 180, |el| <= 90)")


def save_lattice(lattice: Lattice, path: str | Path) -> None:
    """Write the lattice as a plain (non-pickled) .npz."""
    arrays = {name: getattr(lattice, name) for name in _ARRAY_NAMES}
    with Path(path).open("wb") as f:
        np.savez(f, **arrays, version=np.array(lattice.version))


def _from_npz(data: "np.lib.npyio.NpzFile") -> Lattice:
    return Lattice(
        ommatidium_id=data["ommatidium_id"],
        eye=data["eye"],
        hex_uv=data["hex_uv"],
        direction_deg=data["direction_deg"],
        subtype=data["subtype"],
        column_id=data["column_id"],
        version=str(data["version"]),
    )


def load_lattice(path: str | Path) -> Lattice:
    """Read a lattice written by `save_lattice` and validate it."""
    with np.load(path, allow_pickle=False) as data:
        lattice = _from_npz(data)
    validate_lattice(lattice)
    return lattice


def lattice_hash(lattice: Lattice) -> str:
    """SHA-256 hex over every array (name, dtype, shape, bytes) and the version."""
    h = hashlib.sha256()
    for name in _ARRAY_NAMES:
        arr: NDArray[np.generic] = np.ascontiguousarray(getattr(lattice, name))
        h.update(f"{name}|{arr.dtype.str}|{arr.shape}|".encode())
        h.update(arr.tobytes())
    h.update(lattice.version.encode())
    return h.hexdigest()


def default_lattice() -> Lattice:
    """The packaged `lattices/generated_v1.npz`."""
    resource = resources.files("fly_vision").joinpath("lattices", "generated_v1.npz")
    with resources.as_file(resource) as path:
        return load_lattice(path)
