"""Framework-independent data contracts for MineSophila brain models."""

from importlib.metadata import version

from fly_brain.contracts import (
    Availability,
    BrainExample,
    BrainTargets,
    Eye,
    FeatureBlock,
    FeatureSchema,
    FeatureSpec,
    ObservationSequence,
    PrivilegedState,
    Provenance,
    SensorLayout,
)

__version__ = version("fly-brain")

__all__ = [
    "Availability",
    "BrainExample",
    "BrainTargets",
    "Eye",
    "FeatureBlock",
    "FeatureSchema",
    "FeatureSpec",
    "ObservationSequence",
    "PrivilegedState",
    "Provenance",
    "SensorLayout",
    "__version__",
]
