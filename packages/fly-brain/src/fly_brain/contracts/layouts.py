"""Static receptor layout shared by still and temporal observations."""

from dataclasses import dataclass
from enum import StrEnum

import numpy as np
from numpy.typing import NDArray

from fly_brain.contracts._arrays import readonly


class Eye(StrEnum):
    LEFT = "left"
    RIGHT = "right"


@dataclass(frozen=True)
class SensorLayout:
    """Stable receptor-site geometry and channel meaning for one virtual eye model."""

    layout_id: str
    site_ids: NDArray[np.int64]  # (N,)
    eyes: tuple[Eye, ...]  # (N,)
    directions: NDArray[np.float32]  # (N, 3), head-centred unit vectors
    ommatidium_subtypes: tuple[str, ...]  # (N,)
    channel_names: tuple[str, ...]  # (C,)
    model_version: str
    geometry_hash: str
    config_hash: str

    def __post_init__(self) -> None:
        site_ids = np.asarray(self.site_ids, dtype=np.int64)
        directions = np.asarray(self.directions, dtype=np.float32)
        if not self.layout_id.strip():
            raise ValueError("layout_id must not be empty")
        if site_ids.ndim != 1 or site_ids.size == 0:
            raise ValueError(f"site_ids must have non-empty shape (N,), got {site_ids.shape}")
        if len(set(site_ids.tolist())) != site_ids.size:
            raise ValueError("site_ids must be unique")
        count = site_ids.size
        if len(self.eyes) != count:
            raise ValueError(f"eyes must contain {count} entries")
        if directions.shape != (count, 3):
            raise ValueError(f"directions must have shape {(count, 3)}, got {directions.shape}")
        if not np.isfinite(directions).all():
            raise ValueError("directions must be finite")
        norms = np.linalg.norm(directions, axis=1)
        if not np.allclose(norms, 1.0, atol=1e-4):
            raise ValueError("directions must be unit vectors")
        if len(self.ommatidium_subtypes) != count:
            raise ValueError(f"ommatidium_subtypes must contain {count} entries")
        if any(not subtype.strip() for subtype in self.ommatidium_subtypes):
            raise ValueError("ommatidium_subtypes must not contain empty names")
        if not self.channel_names or len(set(self.channel_names)) != len(self.channel_names):
            raise ValueError("channel_names must be non-empty and unique")
        if not self.model_version.strip() or not self.geometry_hash.strip():
            raise ValueError("model_version and geometry_hash must not be empty")
        if not self.config_hash.strip():
            raise ValueError("config_hash must not be empty")
        object.__setattr__(self, "site_ids", readonly(site_ids))
        object.__setattr__(self, "directions", readonly(directions))

    @property
    def site_count(self) -> int:
        return self.site_ids.size

    @property
    def channel_count(self) -> int:
        return len(self.channel_names)
