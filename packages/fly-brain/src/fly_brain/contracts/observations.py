"""Canonical time-major input shared by still images and video."""

from dataclasses import dataclass

import numpy as np
from numpy.typing import NDArray

from fly_brain.contracts._arrays import readonly
from fly_brain.contracts.features import Availability, FeatureBlock
from fly_brain.contracts.layouts import SensorLayout


@dataclass(frozen=True)
class ObservationSequence:
    """Model-visible receptor signals and optional agent-available context."""

    layout: SensorLayout
    receptor_signals: NDArray[np.float32]  # (T, N, C)
    receptor_valid: NDArray[np.bool_]  # (T, N)
    timestamps_s: NDArray[np.float64]  # (T,)
    context: FeatureBlock | None = None

    def __post_init__(self) -> None:
        signals = np.asarray(self.receptor_signals, dtype=np.float32)
        valid = np.asarray(self.receptor_valid, dtype=np.bool_)
        timestamps = np.asarray(self.timestamps_s, dtype=np.float64)
        expected_tail = (self.layout.site_count, self.layout.channel_count)
        if signals.ndim != 3 or signals.shape[1:] != expected_tail:
            raise ValueError(
                f"receptor_signals must have shape (T, {expected_tail[0]}, "
                f"{expected_tail[1]}), got {signals.shape}"
            )
        time_steps = signals.shape[0]
        if time_steps == 0:
            raise ValueError("receptor_signals must contain at least one time step")
        if valid.shape != (time_steps, self.layout.site_count):
            raise ValueError(
                f"receptor_valid must have shape {(time_steps, self.layout.site_count)}, "
                f"got {valid.shape}"
            )
        if timestamps.shape != (time_steps,):
            raise ValueError(
                f"timestamps_s must have shape {(time_steps,)}, got {timestamps.shape}"
            )
        if not np.isfinite(signals).all() or not np.isfinite(timestamps).all():
            raise ValueError("receptor signals and timestamps must be finite")
        if time_steps > 1 and not np.all(np.diff(timestamps) > 0):
            raise ValueError("timestamps_s must be strictly increasing")
        if self.context is not None:
            if self.context.time_steps != time_steps:
                raise ValueError(f"context must contain {time_steps} time steps")
            self.context.require_availability(Availability.AGENT)
        object.__setattr__(self, "receptor_signals", readonly(signals))
        object.__setattr__(self, "receptor_valid", readonly(valid))
        object.__setattr__(self, "timestamps_s", readonly(timestamps))

    @classmethod
    def from_frame(
        cls,
        layout: SensorLayout,
        receptor_signals: NDArray[np.floating],
        receptor_valid: NDArray[np.bool_],
        timestamp_s: float,
        context: FeatureBlock | None = None,
    ) -> "ObservationSequence":
        """Wrap one receptor frame in the same contract used for video."""
        signals = np.asarray(receptor_signals, dtype=np.float32)
        valid = np.asarray(receptor_valid, dtype=np.bool_)
        return cls(
            layout=layout,
            receptor_signals=signals[None, :, :],
            receptor_valid=valid[None, :],
            timestamps_s=np.asarray([timestamp_s], dtype=np.float64),
            context=context,
        )

    @property
    def time_steps(self) -> int:
        return self.receptor_signals.shape[0]

    @property
    def is_still(self) -> bool:
        return self.time_steps == 1
