"""Sparse sampling matrix: panorama pixels to ommatidia with Gaussian acceptance weights.

Neighbourhoods are found in direction space (a KD-tree on unit rays), so a 310 degree
panorama never wraps and the image is never resized, filtered or convolved in 2D.
The same matrix serves images and labels.
"""

import hashlib
import zipfile
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from numpy.typing import NDArray
from scipy import sparse
from scipy.spatial import cKDTree

from fly_vision import __version__
from fly_vision.config import FlyVisionConfig
from fly_vision.lattice import Lattice, lattice_hash
from fly_vision.projection import Projection, to_unit

FWHM_PER_SIGMA = 2.3548  # FWHM = 2 sqrt(2 ln 2) sigma
_ALGORITHM = "gaussian-kdtree-v1"  # bump when the sampler maths changes
_CHUNK = 128  # ommatidia per KD-tree query, bounds the peak memory of the build


@dataclass(frozen=True)
class SamplingMatrix:
    """Row n of `W` weights the pixels (flattened row-major) seen by ommatidium n."""

    W: sparse.csr_matrix  # (N, H*W); valid rows sum to 1, invalid rows are zero
    valid: NDArray[np.bool_]  # (N,)
    coverage_fraction: NDArray[np.float64]  # (N,) summed weight / (2 pi sigma^2)
    key: str
    width: int
    height: int


def matrix_key(lattice: Lattice, projection: Projection, cfg: FlyVisionConfig) -> str:
    """SHA-256 hex over every input that changes the matrix."""
    parts = (
        lattice_hash(lattice),
        projection.key(),
        repr(cfg.acceptance_fwhm_deg),
        repr(cfg.cutoff_sigmas),
        repr(cfg.valid_coverage_min),
        __version__,
        _ALGORITHM,
    )
    return hashlib.sha256("|".join(parts).encode()).hexdigest()


def build_sampling_matrix(
    lattice: Lattice, projection: Projection, cfg: FlyVisionConfig
) -> SamplingMatrix:
    """Gaussian acceptance weights times pixel solid angle, normalised per ommatidium."""
    height, width = projection.height, projection.width
    n = lattice.direction_deg.shape[0]
    sigma = np.deg2rad(cfg.acceptance_fwhm_deg) / FWHM_PER_SIGMA
    cutoff = cfg.cutoff_sigmas * sigma
    chord = 2.0 * np.sin(cutoff / 2.0)

    rays = projection.rays().reshape(-1, 3)
    omega = projection.solid_angle().reshape(-1)
    pixel_tree = cKDTree(rays)
    centres = to_unit(lattice.direction_deg[:, 0], lattice.direction_deg[:, 1])

    rows: list[NDArray[np.int32]] = []
    cols: list[NDArray[np.int32]] = []
    vals: list[NDArray[np.float64]] = []
    for start in range(0, n, _CHUNK):
        sub = cKDTree(centres[start : start + _CHUNK])
        hits = sub.sparse_distance_matrix(pixel_tree, chord, output_type="ndarray")
        theta = 2.0 * np.arcsin(np.clip(hits["v"] / 2.0, 0.0, 1.0))
        j = hits["j"].astype(np.int32)
        rows.append(hits["i"].astype(np.int32) + np.int32(start))
        cols.append(j)
        vals.append(np.exp(-(theta**2) / (2.0 * sigma**2)) * omega[j])

    raw = sparse.csr_matrix(
        (np.concatenate(vals), (np.concatenate(rows), np.concatenate(cols))),
        shape=(n, height * width),
    )
    total = np.asarray(raw.sum(axis=1), dtype=np.float64).ravel()
    coverage = total / (2.0 * np.pi * sigma**2)
    valid = coverage >= cfg.valid_coverage_min
    scale = np.where(valid, 1.0 / np.where(total > 0.0, total, 1.0), 0.0)
    w = sparse.csr_matrix(sparse.diags_array(scale) @ raw)
    w.eliminate_zeros()
    return SamplingMatrix(
        W=w,
        valid=valid,
        coverage_fraction=coverage,
        key=matrix_key(lattice, projection, cfg),
        width=width,
        height=height,
    )


def apply_sampling(sm: SamplingMatrix, image: NDArray[np.float64]) -> NDArray[np.float64]:
    """Sample an (H, W, C) image into (N, C); rows of invalid ommatidia are zero."""
    if image.shape[:2] != (sm.height, sm.width):
        raise ValueError(f"image shape {image.shape[:2]} != matrix shape {(sm.height, sm.width)}")
    flat = image.reshape(sm.height * sm.width, -1)
    return np.asarray(sm.W @ flat, dtype=np.float64)


def _save(sm: SamplingMatrix, path: Path) -> None:
    tmp = path.with_name(path.name + ".tmp")
    with tmp.open("wb") as f:
        np.savez(
            f,
            data=sm.W.data,
            indices=sm.W.indices,
            indptr=sm.W.indptr,
            shape=np.array(sm.W.shape, dtype=np.int64),
            valid=sm.valid,
            coverage_fraction=sm.coverage_fraction,
            width=np.array(sm.width),
            height=np.array(sm.height),
            key=np.array(sm.key),
        )
    tmp.replace(path)


def _load(path: Path) -> SamplingMatrix:
    with np.load(path, allow_pickle=False) as d:
        shape = (int(d["shape"][0]), int(d["shape"][1]))
        w = sparse.csr_matrix((d["data"], d["indices"], d["indptr"]), shape=shape)
        return SamplingMatrix(
            W=w,
            valid=d["valid"],
            coverage_fraction=d["coverage_fraction"],
            key=str(d["key"]),
            width=int(d["width"]),
            height=int(d["height"]),
        )


def load_or_build(
    lattice: Lattice, projection: Projection, cfg: FlyVisionConfig, cache_dir: Path
) -> SamplingMatrix:
    """Load `<key>.npz` from `cache_dir`, or build and write it (a stale file is rebuilt)."""
    key = matrix_key(lattice, projection, cfg)
    path = cache_dir / f"{key}.npz"
    if path.exists():
        try:
            cached = _load(path)
        except (OSError, KeyError, ValueError, zipfile.BadZipFile, EOFError):
            cached = None
        if cached is not None and cached.key == key:
            return cached
    cache_dir.mkdir(parents=True, exist_ok=True)
    sm = build_sampling_matrix(lattice, projection, cfg)
    _save(sm, path)
    return sm
