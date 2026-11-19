"""Executable learning models behind framework-independent contracts."""

from fly_brain.models.linear import (
    LinearLocalizationModel,
    LinearParameters,
    LocalizationBatch,
    TrainingResult,
    prepare_localization_batch,
    train_linear,
)

__all__ = [
    "LinearLocalizationModel",
    "LinearParameters",
    "LocalizationBatch",
    "TrainingResult",
    "prepare_localization_batch",
    "train_linear",
]
