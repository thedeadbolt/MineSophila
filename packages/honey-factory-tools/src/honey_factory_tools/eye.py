"""The Eye workspace: a frame seen through fly-vision's virtual eye (ADR 0029).

A frame is sampled at half resolution through fly-vision's default lattice and spectral
model, as ``fly-vision convert`` would sample the panorama. The page gets the per-ommatidium
R1_6, R7 and R8 activations and honey coverage, the lattice that places them, and a map
from display pixels to their nearest ommatidium, which is how the signal is decoded back
into an image.
"""

import io
import math
import threading
from typing import Any

import numpy as np
from numpy.typing import NDArray
from PIL import Image
from scipy.spatial import cKDTree

from fly_vision import CHANNELS
from fly_vision import __version__ as fly_vision_version
from fly_vision.eye import FlyEye
from fly_vision.lattice import Lattice, default_lattice
from fly_vision.projection import honey_factory_panorama, pixel_directions, to_unit
from fly_vision.spectral import (
    RHODOPSIN_PEAKS_NM,
    SPECTRAL_MODES,
    PrimaryConfig,
    build_spectral_model,
    govardovskii_a1,
)

type Record = dict[str, Any]

PANORAMA = "PANORAMA_310"
# The eye samples frames at this fraction of the capture's size (ADR 0029).
SCALE = 0.5
# A display pixel further than this from every ommatidium axis is outside the eye's view.
MAX_DECODE_DEGREES = 6.0
NO_OMMATIDIUM = 0xFFFF
# Which rhodopsin each receptor carries, by ommatidium subtype (fly-vision's spectral model).
OPSINS: dict[str, dict[str, str]] = {
    "pale": {"R1_6": "Rh1", "R7": "Rh3", "R8": "Rh5"},
    "yellow": {"R1_6": "Rh1", "R7": "Rh4", "R8": "Rh6"},
    "dorsal_yellow": {"R1_6": "Rh1", "R7": "Rh3 + Rh4", "R8": "Rh6"},
    "dra": {"R1_6": "Rh1", "R7": "Rh3", "R8": "Rh3"},
}


class EyeError(ValueError):
    """The eye cannot sample this dataset or frame."""


class EyeModel:
    """fly-vision's default eye, built once per panorama size and shared between requests."""

    def __init__(self, lattice: Lattice | None = None) -> None:
        self.lattice = lattice or default_lattice()
        self._eyes: dict[tuple[int, int], FlyEye] = {}
        self._decode: dict[tuple[int, int], bytes] = {}
        self._lock = threading.Lock()

    def eye(self, width: int, height: int) -> FlyEye:
        with self._lock:
            eye = self._eyes.get((width, height))
            if eye is None:
                eye = FlyEye(self.lattice, honey_factory_panorama(width, height))
                self._eyes[(width, height)] = eye
            return eye

    def layout(self) -> Record:
        """The lattice, receptor names and rhodopsin curves the page draws with."""
        lattice = self.lattice
        directions = np.asarray(lattice.direction_deg, dtype=float)
        return {
            "fly_vision_version": fly_vision_version,
            "lattice_version": lattice.version,
            "channels": list(CHANNELS),
            "count": len(lattice.ommatidium_id),
            "eye": [str(e) for e in lattice.eye],
            "subtype": [str(s) for s in lattice.subtype],
            "hex_uv": np.asarray(lattice.hex_uv).tolist(),
            "azimuth": np.round(directions[:, 0], 3).tolist(),
            "elevation": np.round(directions[:, 1], 3).tolist(),
            "opsins": OPSINS,
            "spectra": spectra(),
            "receptor_matrices": receptor_matrices(),
            "scale": SCALE,
        }

    def sample(
        self, rgb: Image.Image, mask: Image.Image | None, capture: tuple[int, int]
    ) -> Record:
        """Activations and honey coverage of one panorama frame (RGB, mask L) captured at
        ``capture`` (width, height). The images may already be at the eye's scale."""
        width, height = scaled(*capture)
        pixels = np.asarray(rgb.convert("RGB").resize((width, height), Image.Resampling.BOX))
        coverage_mask = None
        if mask is not None:
            small = mask.convert("L").resize((width, height), Image.Resampling.BOX)
            coverage_mask = np.asarray(small, dtype=np.float64) / 255.0
        frame, labels = self.eye(width, height).sample(pixels, coverage_mask)
        activations = frame.activations
        valid = frame.valid
        means = [
            float(activations[valid, c].mean()) if valid.any() else 0.0
            for c in range(len(CHANNELS))
        ]
        return {
            "size": [width, height],
            "channels": list(CHANNELS),
            "activations": _rounded(activations.T),
            "valid": valid.astype(int).tolist(),
            "coverage": None if labels is None else _rounded(labels.honey_coverage),
            "means": means,
            "maxima": [float(activations[:, c].max()) for c in range(len(CHANNELS))],
            "spectral": frame.meta["spectral"],
            "adaptation": frame.meta["adaptation"],
            "low_bit_depth": frame.meta["low_bit_depth"],
        }

    def decode_map(self, width: int, height: int) -> bytes:
        """Little-endian uint16 index of the nearest ommatidium for each display pixel of a
        honey-factory panorama shown at ``width`` x ``height``, row by row.
        ``NO_OMMATIDIUM`` marks pixels no ommatidium looks at."""
        if not (16 <= width <= 2480 and 8 <= height <= 1280):
            raise EyeError(f"decode map size {width}x{height} is outside 16x8 to 2480x1280")
        with self._lock:
            cached = self._decode.get((width, height))
        if cached is not None:
            return cached
        directions = np.asarray(self.lattice.direction_deg, dtype=float)
        axes = to_unit(directions[:, 0], directions[:, 1])
        tree = cKDTree(axes)
        az, el = pixel_directions(honey_factory_panorama(width, height))
        distance, index = tree.query(to_unit(az, el).reshape(-1, 3))
        limit = 2 * math.sin(math.radians(MAX_DECODE_DEGREES) / 2)
        out = np.where(distance <= limit, index, NO_OMMATIDIUM).astype("<u2")
        data = out.tobytes()
        with self._lock:
            self._decode[(width, height)] = data
        return data


def spectra() -> Record:
    """Rhodopsin sensitivity curves on both pigment axes, and the assumed RGB bands."""
    wavelengths = np.arange(300, 701, 5, dtype=float)
    primaries = PrimaryConfig()
    modes: Record = {}
    for name, pigments in SPECTRAL_MODES.items():
        source = pigments.source_wavelength(wavelengths)
        modes[name] = {
            opsin: _rounded(govardovskii_a1(peak, source))
            for opsin, peak in RHODOPSIN_PEAKS_NM.items()
        }
    bands = {
        band: _rounded(np.exp(-0.5 * ((wavelengths - peak) / (fwhm / 2.3548)) ** 2))
        for band, peak, fwhm in zip(
            primaries.names, primaries.peaks_nm, primaries.fwhm_nm, strict=True
        )
    }
    return {
        "wavelengths": wavelengths.astype(int).tolist(),
        "modes": modes,
        "bands": bands,
        "peaks": RHODOPSIN_PEAKS_NM,
    }


def receptor_matrices() -> Record:
    """For each pigment axis and ommatidium subtype, the 3x3 matrix taking linear R, G, B
    light to the R1_6, R7 and R8 catches, as fly-vision's spectral model computes them.
    With adaptation off, the eye is linear in light, so the page can invert these to find
    the colour that would give an ommatidium's receptor values."""
    out: Record = {}
    for name, pigments in SPECTRAL_MODES.items():
        catch = build_spectral_model(pigments=pigments).catch_matrix  # rows Rh1, Rh3-Rh6
        rh1, rh3, rh4, rh5, rh6 = (catch[row] for row in range(5))
        rows = {
            "pale": (rh1, rh3, rh5),
            "yellow": (rh1, rh4, rh6),
            "dorsal_yellow": (rh1, (rh3 + rh4) / 2, rh6),
            "dra": (rh1, rh3, rh3),
        }
        out[name] = {
            subtype: np.round(np.stack(matrix), 6).tolist() for subtype, matrix in rows.items()
        }
    return out


def check_capture(capture: Record) -> None:
    if capture.get("mode") != PANORAMA:
        raise EyeError(
            f"the eye view needs a 310° panorama capture; this dataset is {capture.get('mode')}"
        )


def open_png(data: bytes) -> Image.Image:
    image = Image.open(io.BytesIO(data))
    image.load()
    return image


def scaled(width: int, height: int) -> tuple[int, int]:
    return max(2, round(width * SCALE)), max(1, round(height * SCALE))


def _rounded(values: NDArray[Any]) -> list[Any]:
    return np.round(np.asarray(values, dtype=float), 4).tolist()
