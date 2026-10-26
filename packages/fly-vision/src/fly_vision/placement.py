"""Placing a photo from real-world scale: object size, distance and its pixel span.

A photographer's object of known size at a known distance spans some pixels in the
photo. From that, the photo's horizontal field of view follows by pinhole geometry.
"""

import math

DEFAULT_FOV_H_DEG = 60.0  # chosen: assumed when a perspective image has no placement
FOV_AGREEMENT_DEG = 1.0  # --fov-h and the object-derived field of view must agree this closely


def _require_positive(name: str, value: float) -> None:
    if not math.isfinite(value) or value <= 0.0:
        raise ValueError(f"{name} must be a finite number > 0, got {value}")


def angular_size_deg(size_m: float, distance_m: float) -> float:
    """Visual angle in degrees subtended by an object of `size_m` at `distance_m`."""
    _require_positive("size_m", size_m)
    _require_positive("distance_m", distance_m)
    return math.degrees(2.0 * math.atan(size_m / (2.0 * distance_m)))


def perspective_fov_for_object(
    image_width_px: int,
    object_width_px: float,
    object_size_m: float,
    distance_m: float,
) -> float:
    """Horizontal field of view in degrees of a photo in which the object spans its pixels.

    The object is centred in the photo and spans `object_width_px` pixels, which must
    subtend `angular_size_deg(object_size_m, distance_m)`. The focal length follows from
    that span, and the field of view from the focal length and the image width.
    """
    _require_positive("image_width_px", image_width_px)
    _require_positive("object_width_px", object_width_px)
    _require_positive("object_size_m", object_size_m)
    _require_positive("distance_m", distance_m)
    half_angle = math.radians(angular_size_deg(object_size_m, distance_m)) / 2.0
    focal_px = (object_width_px / 2.0) / math.tan(half_angle)
    return math.degrees(2.0 * math.atan((image_width_px / 2.0) / focal_px))
