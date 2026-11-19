"""Targets, privileged values and provenance kept outside model observations."""

from dataclasses import dataclass

import numpy as np
from numpy.typing import NDArray

from fly_brain.contracts._arrays import readonly
from fly_brain.contracts.features import Availability, FeatureBlock
from fly_brain.contracts.observations import ObservationSequence


@dataclass(frozen=True)
class BrainTargets:
    """Optional supervised targets aligned to one observation sequence."""

    time_mask: NDArray[np.bool_]  # (T,)
    honey_present: NDArray[np.bool_] | None = None  # (T,)
    honey_coverage: NDArray[np.float32] | None = None  # (T, N)
    honey_direction: NDArray[np.float32] | None = None  # (T, 3)
    teacher_actions: FeatureBlock | None = None

    def __post_init__(self) -> None:
        time_mask = np.asarray(self.time_mask, dtype=np.bool_)
        if time_mask.ndim != 1 or time_mask.size == 0:
            raise ValueError(f"time_mask must have non-empty shape (T,), got {time_mask.shape}")
        time_steps = time_mask.size
        object.__setattr__(self, "time_mask", readonly(time_mask))
        if self.honey_present is not None:
            present = np.asarray(self.honey_present, dtype=np.bool_)
            if present.shape != (time_steps,):
                raise ValueError(
                    f"honey_present must have shape {(time_steps,)}, got {present.shape}"
                )
            object.__setattr__(self, "honey_present", readonly(present))
        if self.honey_coverage is not None:
            coverage = np.asarray(self.honey_coverage, dtype=np.float32)
            if coverage.ndim != 2 or coverage.shape[0] != time_steps:
                raise ValueError(f"honey_coverage must have shape (T, N), got {coverage.shape}")
            if not np.isfinite(coverage).all() or np.any((coverage < 0) | (coverage > 1)):
                raise ValueError("honey_coverage must be finite and within [0, 1]")
            object.__setattr__(self, "honey_coverage", readonly(coverage))
        if self.honey_direction is not None:
            direction = np.asarray(self.honey_direction, dtype=np.float32)
            if direction.shape != (time_steps, 3):
                raise ValueError(
                    f"honey_direction must have shape {(time_steps, 3)}, got {direction.shape}"
                )
            if not np.isfinite(direction).all():
                raise ValueError("honey_direction must be finite")
            object.__setattr__(self, "honey_direction", readonly(direction))
        if self.teacher_actions is not None:
            if self.teacher_actions.time_steps != time_steps:
                raise ValueError(f"teacher_actions must contain {time_steps} time steps")
            self.teacher_actions.require_availability(Availability.TEACHER)

    @property
    def time_steps(self) -> int:
        return self.time_mask.size


@dataclass(frozen=True)
class Provenance:
    dataset_id: str
    scene_id: str
    frame_ids: tuple[str, ...]
    sensor_config_hash: str
    clip_id: str | None = None
    capture_config_hash: str | None = None

    def __post_init__(self) -> None:
        if not self.dataset_id.strip() or not self.scene_id.strip():
            raise ValueError("dataset_id and scene_id must not be empty")
        if not self.frame_ids or any(not frame_id.strip() for frame_id in self.frame_ids):
            raise ValueError("frame_ids must contain non-empty IDs")
        if not self.sensor_config_hash.strip():
            raise ValueError("sensor_config_hash must not be empty")


@dataclass(frozen=True)
class PrivilegedState:
    features: FeatureBlock

    def __post_init__(self) -> None:
        self.features.require_availability(Availability.PRIVILEGED)


@dataclass(frozen=True)
class BrainExample:
    observation: ObservationSequence
    targets: BrainTargets
    provenance: Provenance
    privileged_state: PrivilegedState | None = None

    def __post_init__(self) -> None:
        time_steps = self.observation.time_steps
        if self.targets.time_steps != time_steps:
            raise ValueError("targets and observation must have the same time steps")
        if len(self.provenance.frame_ids) != time_steps:
            raise ValueError("provenance must name every observation frame")
        if self.provenance.sensor_config_hash != self.observation.layout.config_hash:
            raise ValueError("provenance sensor hash does not match the observation layout")
        coverage = self.targets.honey_coverage
        if coverage is not None and coverage.shape[1] != self.observation.layout.site_count:
            raise ValueError("honey_coverage site count does not match the observation layout")
        if (
            self.privileged_state is not None
            and self.privileged_state.features.time_steps != time_steps
        ):
            raise ValueError("privileged state and observation must have the same time steps")
