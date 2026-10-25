"""FlyEye: panorama (and honey mask) in, per-ommatidium frame and labels out."""

import hashlib
from dataclasses import asdict
from pathlib import Path
from typing import Any

import numpy as np
from numpy.typing import NDArray

from fly_vision import CHANNELS, __version__
from fly_vision.adaptation import adapt
from fly_vision.config import FlyVisionConfig, config_hash
from fly_vision.frame import FlyFrame, FlyLabels, SourceRef, load_mask, load_panorama
from fly_vision.lattice import Lattice, lattice_hash
from fly_vision.projection import Projection
from fly_vision.sampler import SamplingMatrix, apply_sampling, build_sampling_matrix, load_or_build
from fly_vision.spectral import SpectralModel, build_spectral_model, srgb_to_linear

__all__ = ["FlyEye", "load_mask", "load_panorama"]


class FlyEye:
    """Sample panoramas through one lattice, projection and spectral model."""

    def __init__(
        self,
        lattice: Lattice,
        projection: Projection,
        cfg: FlyVisionConfig | None = None,
        spectral: SpectralModel | None = None,
        cache_dir: Path | None = None,
    ) -> None:
        self.lattice = lattice
        self.projection = projection
        self.cfg = cfg or FlyVisionConfig()
        self.sm: SamplingMatrix = (
            build_sampling_matrix(lattice, projection, self.cfg)
            if cache_dir is None
            else load_or_build(lattice, projection, self.cfg, cache_dir)
        )
        self.spectral: SpectralModel = spectral or build_spectral_model()
        self._lattice_hash = lattice_hash(lattice)

    def reset(self) -> None:
        """Hook for temporal state (phase 4); v0 keeps no state between frames."""

    def sample(
        self,
        panorama: NDArray[np.uint8] | NDArray[np.uint16],
        mask: NDArray[np.floating[Any]] | None = None,
        source: SourceRef | None = None,
    ) -> tuple[FlyFrame, FlyLabels | None]:
        h, w = self.projection.height, self.projection.width
        if panorama.shape != (h, w, 3):
            raise ValueError(f"panorama shape {panorama.shape} != expected {(h, w, 3)}")
        if panorama.dtype == np.uint8:
            full_scale = 255.0
        elif panorama.dtype == np.uint16:
            full_scale = 65535.0
        else:
            raise ValueError(f"panorama dtype must be uint8 or uint16, got {panorama.dtype}")

        levels = [int(np.unique(panorama[..., c]).size) for c in range(3)]
        linear = srgb_to_linear(panorama.astype(np.float64) / full_scale)
        pixels = apply_sampling(self.sm, linear)
        # `receptors` returns a fresh array, so zeroing invalid rows below cannot alias
        # anything the caller (or `adapt`'s pass-through) owns.
        receptors = self.spectral.receptors(pixels, self.lattice.subtype)
        adapted = adapt(receptors, self.sm.valid, self.cfg.adaptation)
        activations = np.where(self.sm.valid[:, None], adapted, 0.0).astype(np.float32)

        source_dict = None if source is None else asdict(source)
        meta: dict[str, Any] = {
            "fly_vision_version": __version__,
            "lattice_version": self.lattice.version,
            "lattice_hash": self._lattice_hash,
            "matrix_key": self.sm.key,
            "spectral_hash": self.spectral.hash,
            "config_hash": config_hash(self.cfg),
            "adaptation": self.cfg.adaptation,
            "uv_modelled": False,
            "channels": list(CHANNELS),
            "input_dtype": str(panorama.dtype),
            "input_levels": levels,
            "low_bit_depth": bool(min(levels) < self.cfg.low_bit_depth_levels),
            "image_sha256": hashlib.sha256(np.ascontiguousarray(panorama).tobytes()).hexdigest(),
            "source": source_dict,
        }
        frame = FlyFrame(activations=activations, valid=self.sm.valid.copy(), meta=meta)
        if mask is None:
            return frame, None

        m = np.asarray(mask, dtype=np.float64)
        if m.ndim != 2:
            raise ValueError(f"mask must be 2-D (H, W), got {m.ndim}-D shape {m.shape}")
        if not np.isfinite(m).all():
            raise ValueError("mask contains NaN or infinite values")
        if m.min() < 0.0 or m.max() > 1.0:
            raise ValueError(
                "mask values must lie in [0, 1]; use load_mask to convert 0/255 mask files"
            )
        coverage = apply_sampling(self.sm, m[..., None])[:, 0]
        labels = FlyLabels(
            honey_coverage=coverage.astype(np.float32),
            meta={
                "scene_id": None if source is None else source.scene_id,
                "frame_id": None if source is None else source.frame_id,
                "image_sha256": meta["image_sha256"],
            },
        )
        return frame, labels
