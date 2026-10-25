"""Pixel to ray projection: the geometry between a panorama and the eye frame.

The eye frame is (forward, right, up). Azimuth is positive to the right and
elevation positive up; azimuth 0, elevation 0 is forward on the horizon.
"""

import json
from dataclasses import dataclass
from typing import Protocol

import numpy as np
from numpy.typing import NDArray

type FloatArray = NDArray[np.float64]


class Projection(Protocol):
    """Maps every image pixel to a unit direction and a solid angle."""

    @property
    def width(self) -> int:
        """Image width in pixels."""
        ...

    @property
    def height(self) -> int:
        """Image height in pixels."""
        ...

    def rays(self) -> FloatArray:
        """Unit vectors in the eye frame, shape (H, W, 3)."""
        ...

    def solid_angle(self) -> FloatArray:
        """Pixel solid angles in steradians, shape (H, W)."""
        ...

    def describe(self) -> dict[str, str | int | float]:
        """Plain JSON record of the projection: `"type"` plus every field."""
        ...

    def key(self) -> str:
        """Stable text naming the projection type and every field (for cache keys).

        Always `json.dumps(self.describe(), sort_keys=True)`, so the two cannot drift.
        """
        ...


@dataclass(frozen=True)
class EquirectSpec:
    """Equirectangular layout: azimuth is linear in x, elevation linear in y."""

    width: int
    height: int
    fov_h_deg: float
    fov_v_deg: float
    center_az_deg: float = 0.0
    center_el_deg: float = 0.0

    def __post_init__(self) -> None:
        if self.width <= 0 or self.height <= 0:
            raise ValueError(f"width and height must be > 0, got {self.width}x{self.height}")
        if not 0.0 < self.fov_h_deg <= 360.0:
            raise ValueError(f"fov_h_deg must be in (0, 360], got {self.fov_h_deg}")
        if not 0.0 < self.fov_v_deg <= 180.0:
            raise ValueError(f"fov_v_deg must be in (0, 180], got {self.fov_v_deg}")
        half = self.fov_v_deg / 2.0
        low, high = self.center_el_deg - half, self.center_el_deg + half
        if low < -90.0 - 1e-9 or high > 90.0 + 1e-9:
            raise ValueError(
                f"elevation edges {low}..{high} degrees must lie within -90..+90 "
                f"(center_el_deg={self.center_el_deg}, fov_v_deg={self.fov_v_deg})"
            )

    def rays(self) -> FloatArray:
        az, el = pixel_directions(self)
        return to_unit(az, el)

    def solid_angle(self) -> FloatArray:
        return pixel_solid_angle(self)

    def describe(self) -> dict[str, str | int | float]:
        return {
            "type": "equirect",
            "width": self.width,
            "height": self.height,
            "fov_h_deg": self.fov_h_deg,
            "fov_v_deg": self.fov_v_deg,
            "center_az_deg": self.center_az_deg,
            "center_el_deg": self.center_el_deg,
        }

    def key(self) -> str:
        return json.dumps(self.describe(), sort_keys=True)


def honey_factory_panorama(width: int, height: int) -> EquirectSpec:
    """The `equirectangular-310x160-v1` capture of honey-factory.

    Contract: `schemas/honey-factory/README.md`, section
    "`equirectangular-310x160-v1` and `cube-faces-v1`: panoramic capture".
    The image is level and spans longitude -155..+155 and elevation -70..+90 degrees,
    so its centre is at elevation +10 degrees.
    """
    return EquirectSpec(
        width=width,
        height=height,
        fov_h_deg=310.0,
        fov_v_deg=160.0,
        center_az_deg=0.0,
        center_el_deg=10.0,
    )


def pixel_directions(spec: EquirectSpec) -> tuple[FloatArray, FloatArray]:
    """Azimuth and elevation in degrees of each pixel centre, each shape (H, W)."""
    x = (np.arange(spec.width) + 0.5) / spec.width
    y = (np.arange(spec.height) + 0.5) / spec.height
    az_row = spec.center_az_deg + (x - 0.5) * spec.fov_h_deg
    el_col = spec.center_el_deg + (0.5 - y) * spec.fov_v_deg
    az = np.broadcast_to(az_row[None, :], (spec.height, spec.width)).copy()
    el = np.broadcast_to(el_col[:, None], (spec.height, spec.width)).copy()
    return az, el


def pixel_solid_angle(spec: EquirectSpec) -> FloatArray:
    """Exact solid angle of each pixel, shape (H, W): d_az * (sin el_top - sin el_bottom)."""
    d_az = np.deg2rad(spec.fov_h_deg) / spec.width
    edges = spec.center_el_deg + (0.5 - np.arange(spec.height + 1) / spec.height) * spec.fov_v_deg
    sin_edges = np.sin(np.deg2rad(edges))
    band = sin_edges[:-1] - sin_edges[1:]
    return np.broadcast_to((d_az * band)[:, None], (spec.height, spec.width)).copy()


def direction_to_pixel(
    az_deg: FloatArray, el_deg: FloatArray, spec: EquirectSpec
) -> tuple[FloatArray, FloatArray]:
    """Float pixel coordinates (x, y) of directions; pixel i covers [i, i + 1)."""
    x = ((az_deg - spec.center_az_deg) / spec.fov_h_deg + 0.5) * spec.width
    y = (0.5 - (el_deg - spec.center_el_deg) / spec.fov_v_deg) * spec.height
    return x, y


def to_unit(az_deg: FloatArray, el_deg: FloatArray) -> FloatArray:
    """Unit vectors (forward, right, up), shape (..., 3)."""
    az = np.deg2rad(az_deg)
    el = np.deg2rad(el_deg)
    return np.stack([np.cos(el) * np.cos(az), np.cos(el) * np.sin(az), np.sin(el)], axis=-1)
