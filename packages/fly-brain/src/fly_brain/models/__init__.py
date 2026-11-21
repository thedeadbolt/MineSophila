"""Executable learning models behind framework-independent contracts."""

from fly_brain.models.cnn import (
    CNNLocalizationModel,
    CNNParameters,
    CNNTrainingResult,
    train_cnn,
)
from fly_brain.models.connectome import (
    ConnectomeBatch,
    ConnectomeInputTransform,
    ConnectomeLocalizationModel,
    ConnectomeModelConfig,
    ConnectomeTrainingConfig,
    ConnectomeTrainingResult,
    ReceptorPoolingInputTransform,
    SpatialCoverageInputTransform,
    train_connectome,
)
from fly_brain.models.linear import (
    LinearLocalizationModel,
    LinearParameters,
    LocalizationBatch,
    TrainingResult,
    prepare_localization_batch,
    train_linear,
)
from fly_brain.models.mlp import MLPLocalizationModel, MLPParameters, MLPTrainingResult, train_mlp
from fly_brain.models.spatial import (
    SpatialBatch,
    SpatialLocalizationModel,
    SpatialModelConfig,
    SpatialParameters,
    SpatialTopology,
    SpatialTrainingConfig,
    SpatialTrainingResult,
    build_angular_topology,
    train_spatial,
)

__all__ = [
    "CNNLocalizationModel",
    "CNNParameters",
    "CNNTrainingResult",
    "ConnectomeBatch",
    "ConnectomeInputTransform",
    "ConnectomeLocalizationModel",
    "ConnectomeModelConfig",
    "ConnectomeTrainingConfig",
    "ConnectomeTrainingResult",
    "LinearLocalizationModel",
    "LinearParameters",
    "LocalizationBatch",
    "MLPLocalizationModel",
    "MLPParameters",
    "MLPTrainingResult",
    "ReceptorPoolingInputTransform",
    "SpatialBatch",
    "SpatialCoverageInputTransform",
    "SpatialLocalizationModel",
    "SpatialModelConfig",
    "SpatialParameters",
    "SpatialTopology",
    "SpatialTrainingConfig",
    "SpatialTrainingResult",
    "TrainingResult",
    "build_angular_topology",
    "prepare_localization_batch",
    "train_cnn",
    "train_connectome",
    "train_linear",
    "train_mlp",
    "train_spatial",
]
