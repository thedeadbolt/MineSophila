"""Adapters for cached `fly-vision` receptor frames and labels."""

from collections.abc import Sequence
from dataclasses import dataclass
from typing import Any, cast

import numpy as np

from fly_brain.contracts import BrainTargets, Eye, FeatureBlock, ObservationSequence, SensorLayout
from fly_vision import CHANNELS
from fly_vision.frame import FlyFrame, FlyLabels
from fly_vision.lattice import Lattice, lattice_hash, validate_lattice
from fly_vision.projection import to_unit


def _required_string(meta: dict[str, Any], name: str) -> str:
    value = meta.get(name)
    if not isinstance(value, str) or not value:
        raise ValueError(f"fly-vision metadata has no non-empty {name!r}")
    return value


def _channels(meta: dict[str, Any]) -> tuple[str, ...]:
    value = meta.get("channels")
    if not isinstance(value, list):
        raise ValueError("fly-vision metadata has no string channel list")
    items = cast(list[object], value)
    if not all(isinstance(item, str) for item in items):
        raise ValueError("fly-vision metadata has no string channel list")
    return tuple(cast(str, item) for item in items)


def sensor_layout_from_frame(lattice: Lattice, frame: FlyFrame) -> SensorLayout:
    """Build the static brain sensor layout and verify it matches one cached frame."""
    validate_lattice(lattice)
    geometry_hash = lattice_hash(lattice)
    if _required_string(frame.meta, "lattice_hash") != geometry_hash:
        raise ValueError("fly-vision frame lattice_hash does not match the supplied lattice")
    channels = _channels(frame.meta)
    if channels != CHANNELS:
        raise ValueError(f"fly-vision frame channels {channels} do not match {CHANNELS}")
    config_hash = _required_string(frame.meta, "config_hash")
    model_version = _required_string(frame.meta, "fly_vision_version")
    directions = to_unit(lattice.direction_deg[:, 0], lattice.direction_deg[:, 1]).astype(
        np.float32
    )
    eyes = tuple(Eye.LEFT if eye == "L" else Eye.RIGHT for eye in lattice.eye.tolist())
    return SensorLayout(
        layout_id=f"{lattice.version}:{geometry_hash}:{config_hash}",
        site_ids=lattice.ommatidium_id.astype(np.int64),
        eyes=eyes,
        directions=directions,
        ommatidium_subtypes=tuple(str(value) for value in lattice.subtype.tolist()),
        channel_names=channels,
        model_version=model_version,
        geometry_hash=geometry_hash,
        config_hash=config_hash,
    )


@dataclass(frozen=True)
class CachedFlyVisionAdapter:
    """Stack cached receptor frames after checking one sensor configuration."""

    layout: SensorLayout

    @classmethod
    def from_frame(cls, lattice: Lattice, frame: FlyFrame) -> "CachedFlyVisionAdapter":
        return cls(sensor_layout_from_frame(lattice, frame))

    def convert(
        self,
        frames: Sequence[FlyFrame],
        timestamps_s: Sequence[float],
        context: FeatureBlock | None = None,
    ) -> ObservationSequence:
        if not frames:
            raise ValueError("at least one fly-vision frame is required")
        if len(frames) != len(timestamps_s):
            raise ValueError("frames and timestamps_s must have equal length")
        for index, frame in enumerate(frames):
            if _required_string(frame.meta, "lattice_hash") != self.layout.geometry_hash:
                raise ValueError(f"frame {index} uses a different lattice")
            if _required_string(frame.meta, "config_hash") != self.layout.config_hash:
                raise ValueError(f"frame {index} uses a different fly-vision configuration")
            if _required_string(frame.meta, "fly_vision_version") != self.layout.model_version:
                raise ValueError(f"frame {index} uses a different fly-vision version")
            if _channels(frame.meta) != self.layout.channel_names:
                raise ValueError(f"frame {index} uses different receptor channels")
        return ObservationSequence(
            layout=self.layout,
            receptor_signals=np.stack([frame.activations for frame in frames]),
            receptor_valid=np.stack([frame.valid for frame in frames]),
            timestamps_s=np.asarray(timestamps_s, dtype=np.float64),
            context=context,
        )


@dataclass(frozen=True)
class HoneyCoverageTargetAdapter:
    """Derive presence, coverage and head-centred direction from sampled masks."""

    presence_threshold: float = 0.0

    def __post_init__(self) -> None:
        if not 0.0 <= self.presence_threshold <= 1.0:
            raise ValueError("presence_threshold must lie in [0, 1]")

    def convert(
        self,
        labels: Sequence[FlyLabels],
        frames: Sequence[FlyFrame],
        observation: ObservationSequence,
    ) -> BrainTargets:
        if len(labels) != observation.time_steps or len(frames) != observation.time_steps:
            raise ValueError("labels, frames and observation must have equal time lengths")
        coverage_rows: list[np.ndarray[Any, np.dtype[np.float32]]] = []
        for index, (label, frame) in enumerate(zip(labels, frames, strict=True)):
            if label.meta.get("lattice_hash") != observation.layout.geometry_hash:
                raise ValueError(f"label {index} uses a different lattice")
            if label.meta.get("matrix_key") != frame.meta.get("matrix_key"):
                raise ValueError(f"label {index} uses a different sampling matrix")
            if label.meta.get("image_sha256") != frame.meta.get("image_sha256"):
                raise ValueError(f"label {index} belongs to a different source image")
            coverage_rows.append(np.asarray(label.honey_coverage, dtype=np.float32))
        coverage = np.stack(coverage_rows)
        present = np.max(coverage, axis=1) > self.presence_threshold
        weighted = coverage @ observation.layout.directions
        norms = np.linalg.norm(weighted, axis=1)
        directions = np.zeros_like(weighted, dtype=np.float32)
        nonzero = norms > 0
        directions[nonzero] = weighted[nonzero] / norms[nonzero, None]
        return BrainTargets(
            time_mask=np.ones(observation.time_steps, dtype=np.bool_),
            honey_present=present,
            honey_coverage=coverage,
            honey_direction=directions,
        )
