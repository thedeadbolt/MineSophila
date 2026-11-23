"""Framework-independent prediction tasks and metrics."""

from fly_brain.tasks.localization import (
    HoneyLocalizationPrediction,
    LocalizationMetrics,
    evaluate_localization,
)

__all__ = ["HoneyLocalizationPrediction", "LocalizationMetrics", "evaluate_localization"]
