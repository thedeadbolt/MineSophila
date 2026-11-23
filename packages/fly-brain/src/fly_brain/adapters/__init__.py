"""Boundary adapters from MineSophila artifacts into fly-brain contracts."""

from fly_brain.adapters.examples import CachedExampleAdapter
from fly_brain.adapters.fly_vision import (
    CachedFlyVisionAdapter,
    HoneyCoverageTargetAdapter,
    sensor_layout_from_frame,
)
from fly_brain.adapters.honey_factory import (
    EgocentricMotionContextAdapter,
    NoContextAdapter,
    TeacherActionTargetAdapter,
)

__all__ = [
    "CachedExampleAdapter",
    "CachedFlyVisionAdapter",
    "EgocentricMotionContextAdapter",
    "HoneyCoverageTargetAdapter",
    "NoContextAdapter",
    "TeacherActionTargetAdapter",
    "sensor_layout_from_frame",
]
