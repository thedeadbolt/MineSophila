"""Executable learning models behind framework-independent contracts."""

from fly_brain.models.linear import (
    LinearLocalizationModel,
    LinearParameters,
    LocalizationBatch,
    TrainingResult,
    prepare_localization_batch,
    train_linear,
)
from fly_brain.models.mlp import MLPLocalizationModel, MLPParameters, MLPTrainingResult, train_mlp

__all__ = [
    "LinearLocalizationModel",
    "LinearParameters",
    "LocalizationBatch",
    "MLPLocalizationModel",
    "MLPParameters",
    "MLPTrainingResult",
    "TrainingResult",
    "prepare_localization_batch",
    "train_linear",
    "train_mlp",
]
