"""Reviewed first visual component selection for MaleCNS v1.0."""

from fly_brain.connectome.components import BiologicalStatus, ComponentRole, ComponentSpec
from fly_brain.connectome.selectors import AnnotationEquals

PHOTORECEPTOR_TYPES = (
    "HBeyelet",
    "R1-R6",
    "R7R8_unclear",
    "R7_unclear",
    "R7d",
    "R7p",
    "R7y",
    "R8_unclear",
    "R8d",
    "R8p",
    "R8y",
)
LAMINA_RELAY_TYPES = ("L1", "L2", "L3", "L4", "L5")
MEDULLA_RELAY_TYPES = ("Mi1", "Mi4", "Mi9", "Tm1", "Tm2", "Tm3", "Tm4", "Tm6", "Tm9", "Tm20")


def first_visual_component_specs() -> tuple[ComponentSpec, ...]:
    return (
        ComponentSpec(
            "photoreceptors",
            AnnotationEquals("cell_types", PHOTORECEPTOR_TYPES),
            ComponentRole.SENSORY,
            "Anatomical compound-eye and eyelet sensory neurons.",
            BiologicalStatus.ANATOMICAL,
        ),
        ComponentSpec(
            "lamina_relays",
            AnnotationEquals("cell_types", LAMINA_RELAY_TYPES),
            ComponentRole.PROCESSING,
            "First-order L1-L5 lamina relay neurons.",
            BiologicalStatus.ANATOMICAL,
        ),
        ComponentSpec(
            "medulla_relays",
            AnnotationEquals("cell_types", MEDULLA_RELAY_TYPES),
            ComponentRole.PROCESSING,
            "Bounded medulla relay set for the first executable circuit.",
            BiologicalStatus.APPROXIMATION,
        ),
        ComponentSpec(
            "visual_projection",
            AnnotationEquals("classes", ("visual_projection",)),
            ComponentRole.OUTPUT,
            "Curated visual projection neurons available to a learned readout.",
            BiologicalStatus.ANATOMICAL,
        ),
    )
