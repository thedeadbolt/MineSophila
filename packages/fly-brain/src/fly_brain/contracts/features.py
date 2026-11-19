"""Named time-aligned features with explicit availability boundaries."""

from dataclasses import dataclass
from enum import StrEnum

import numpy as np
from numpy.typing import NDArray

from fly_brain.contracts._arrays import readonly


class Availability(StrEnum):
    AGENT = "agent"
    TEACHER = "teacher"
    PRIVILEGED = "privileged"
    PROVENANCE = "provenance"


@dataclass(frozen=True)
class FeatureSpec:
    name: str
    units: str
    description: str
    source: str
    availability: Availability

    def __post_init__(self) -> None:
        if not self.name.strip():
            raise ValueError("feature name must not be empty")
        if not self.units.strip() or not self.source.strip():
            raise ValueError(f"feature {self.name!r} must define units and source")


@dataclass(frozen=True)
class FeatureSchema:
    features: tuple[FeatureSpec, ...]

    def __post_init__(self) -> None:
        names = [feature.name for feature in self.features]
        if not names:
            raise ValueError("feature schema must not be empty")
        if len(set(names)) != len(names):
            raise ValueError("feature names must be unique")

    @property
    def names(self) -> tuple[str, ...]:
        return tuple(feature.name for feature in self.features)

    @property
    def width(self) -> int:
        return len(self.features)


@dataclass(frozen=True)
class FeatureBlock:
    values: NDArray[np.float32]  # (T, K)
    schema: FeatureSchema

    def __post_init__(self) -> None:
        values = np.asarray(self.values, dtype=np.float32)
        if values.ndim != 2 or values.shape[1] != self.schema.width:
            raise ValueError(
                f"feature values must have shape (T, {self.schema.width}), got {values.shape}"
            )
        if values.shape[0] == 0:
            raise ValueError("feature values must contain at least one time step")
        if not np.isfinite(values).all():
            raise ValueError("feature values must be finite")
        object.__setattr__(self, "values", readonly(values))

    @property
    def time_steps(self) -> int:
        return self.values.shape[0]

    def require_availability(self, availability: Availability) -> None:
        invalid = [
            feature.name
            for feature in self.schema.features
            if feature.availability is not availability
        ]
        if invalid:
            raise ValueError(
                f"features must have availability {availability.value!r}: {', '.join(invalid)}"
            )
