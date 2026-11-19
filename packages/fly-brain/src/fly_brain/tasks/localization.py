"""Honey-localisation outputs and evaluation independent of model backend."""

from dataclasses import dataclass

import numpy as np
from numpy.typing import NDArray

from fly_brain.contracts import BrainTargets
from fly_brain.contracts._arrays import readonly


@dataclass(frozen=True)
class HoneyLocalizationPrediction:
    presence_probability: NDArray[np.float32]  # (T,)
    direction: NDArray[np.float32]  # (T, 3), head-centred unit vector or zero
    coverage: NDArray[np.float32] | None = None  # (T, N)

    def __post_init__(self) -> None:
        presence = np.asarray(self.presence_probability, dtype=np.float32)
        direction = np.asarray(self.direction, dtype=np.float32)
        if presence.ndim != 1 or presence.size == 0:
            raise ValueError("presence_probability must have shape (T,) with T > 0")
        if direction.shape != (presence.size, 3):
            expected = (presence.size, 3)
            raise ValueError(f"direction must have shape {expected}, got {direction.shape}")
        if not np.isfinite(presence).all() or np.any((presence < 0) | (presence > 1)):
            raise ValueError("presence probabilities must be finite and lie in [0, 1]")
        if not np.isfinite(direction).all():
            raise ValueError("predicted directions must be finite")
        norms = np.linalg.norm(direction, axis=1)
        if np.any((norms > 1e-6) & ~np.isclose(norms, 1.0, atol=1e-5)):
            raise ValueError("predicted directions must be unit vectors or zero")
        object.__setattr__(self, "presence_probability", readonly(presence))
        object.__setattr__(self, "direction", readonly(direction))
        if self.coverage is not None:
            coverage = np.asarray(self.coverage, dtype=np.float32)
            if coverage.ndim != 2 or coverage.shape[0] != presence.size:
                raise ValueError("predicted coverage must have shape (T, N)")
            if not np.isfinite(coverage).all() or np.any((coverage < 0) | (coverage > 1)):
                raise ValueError("predicted coverage must be finite and lie in [0, 1]")
            object.__setattr__(self, "coverage", readonly(coverage))

    @property
    def time_steps(self) -> int:
        return self.presence_probability.size


@dataclass(frozen=True)
class LocalizationMetrics:
    sample_count: int
    positive_count: int
    presence_accuracy: float
    presence_precision: float
    presence_recall: float
    mean_bearing_error_deg: float | None
    coverage_mae: float | None


def evaluate_localization(
    prediction: HoneyLocalizationPrediction,
    targets: BrainTargets,
    *,
    presence_threshold: float = 0.5,
) -> LocalizationMetrics:
    """Evaluate valid time steps, with direction scored only on positive targets."""
    if not 0 <= presence_threshold <= 1:
        raise ValueError("presence_threshold must lie in [0, 1]")
    if targets.honey_present is None or targets.honey_direction is None:
        raise ValueError("localisation evaluation requires presence and direction targets")
    if prediction.time_steps != targets.time_steps:
        raise ValueError("prediction and targets must have equal time lengths")
    mask = targets.time_mask
    truth = targets.honey_present[mask]
    sample_count = int(np.count_nonzero(mask))
    if sample_count == 0:
        raise ValueError("localisation evaluation requires at least one valid time step")
    predicted = prediction.presence_probability[mask] >= presence_threshold
    true_positive = int(np.count_nonzero(predicted & truth))
    predicted_positive = int(np.count_nonzero(predicted))
    positive_count = int(np.count_nonzero(truth))
    precision = true_positive / predicted_positive if predicted_positive else 0.0
    recall = true_positive / positive_count if positive_count else 0.0
    bearing_error = _bearing_error(
        prediction.direction[mask][truth], targets.honey_direction[mask][truth]
    )
    coverage_mae = _coverage_mae(prediction, targets, mask)
    return LocalizationMetrics(
        sample_count=sample_count,
        positive_count=positive_count,
        presence_accuracy=float(np.count_nonzero(predicted == truth) / sample_count),
        presence_precision=precision,
        presence_recall=recall,
        mean_bearing_error_deg=bearing_error,
        coverage_mae=coverage_mae,
    )


def _bearing_error(predicted: NDArray[np.float32], target: NDArray[np.float32]) -> float | None:
    if predicted.shape[0] == 0:
        return None
    predicted_norms = np.linalg.norm(predicted, axis=1)
    target_norms = np.linalg.norm(target, axis=1)
    usable = (predicted_norms > 0) & (target_norms > 0)
    if not usable.any():
        return None
    cosine = np.sum(predicted[usable] * target[usable], axis=1) / (
        predicted_norms[usable] * target_norms[usable]
    )
    return float(np.mean(np.degrees(np.arccos(np.clip(cosine, -1.0, 1.0)))))


def _coverage_mae(
    prediction: HoneyLocalizationPrediction,
    targets: BrainTargets,
    mask: NDArray[np.bool_],
) -> float | None:
    if prediction.coverage is None or targets.honey_coverage is None:
        return None
    if prediction.coverage.shape != targets.honey_coverage.shape:
        raise ValueError("predicted and target coverage must have equal shapes")
    return float(np.mean(np.abs(prediction.coverage[mask] - targets.honey_coverage[mask])))
