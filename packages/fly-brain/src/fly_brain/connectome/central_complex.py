"""Reviewed central-complex components for target-direction memory in MaleCNS v1.0.

The anterior visual pathway carries visual features from the medulla to the compass:
MeTu (medulla to anterior optic tubercle) -> TuBu (tubercle to bulb) -> ER ring neurons ->
EPG/PEN/PEG/Delta7 heading circuit -> PFL steering neurons. In the fly this circuit keeps a
heading estimate through darkness, and PFL3 neurons steer toward a stored goal direction.
"""

from fly_brain.connectome.components import BiologicalStatus, ComponentRole, ComponentSpec
from fly_brain.connectome.selectors import AnnotationEquals

ANTERIOR_VISUAL_INPUT_TYPES = (
    "MeTu1",
    "MeTu2a",
    "MeTu2b",
    "MeTu3a",
    "MeTu3b",
    "MeTu3c",
    "MeTu4_unclear",
    "MeTu4a",
    "MeTu4b",
    "MeTu4c",
    "MeTu4d",
    "MeTu4e",
    "MeTu4f",
)
TUBERCLE_BULB_TYPES = tuple(f"TuBu{index:02d}" for index in range(1, 11))
RING_NEURON_TYPES = (
    "ER1_a",
    "ER1_b",
    "ER1_c",
    "ER2_a",
    "ER2_b",
    "ER2_c",
    "ER2_d",
    "ER3a_a",
    "ER3a_b",
    "ER3a_c",
    "ER3a_d",
    "ER3d_a",
    "ER3d_b",
    "ER3d_c",
    "ER3d_d",
    "ER3d_e",
    "ER3m",
    "ER3p_a",
    "ER3p_b",
    "ER3w_a",
    "ER3w_b",
    "ER3w_c",
    "ER4d",
    "ER4m",
    "ER5",
    "ER6",
)
COMPASS_TYPES = ("EPG", "PEN_a(PEN1)", "PEN_b(PEN2)", "PEG", "Delta7")
STEERING_TYPES = ("PFL1", "PFL2", "PFL3")
CENTRAL_COMPLEX_COMPONENTS = (
    "anterior_visual_inputs",
    "tubercle_bulb",
    "ring_neurons",
    "compass",
    "steering",
)


def central_complex_component_specs() -> tuple[ComponentSpec, ...]:
    return (
        ComponentSpec(
            "anterior_visual_inputs",
            AnnotationEquals("cell_types", ANTERIOR_VISUAL_INPUT_TYPES),
            ComponentRole.PROCESSING,
            "MeTu visual-projection neurons entering the anterior visual pathway. They are a "
            "named subset of visual_projection and add no neurons when both are present.",
            BiologicalStatus.ANATOMICAL,
        ),
        ComponentSpec(
            "tubercle_bulb",
            AnnotationEquals("cell_types", TUBERCLE_BULB_TYPES),
            ComponentRole.PROCESSING,
            "TuBu neurons relaying the anterior optic tubercle to the bulb.",
            BiologicalStatus.ANATOMICAL,
        ),
        ComponentSpec(
            "ring_neurons",
            AnnotationEquals("cell_types", RING_NEURON_TYPES),
            ComponentRole.PROCESSING,
            "ER ring neurons supplying visual and landmark input to the compass.",
            BiologicalStatus.ANATOMICAL,
        ),
        ComponentSpec(
            "compass",
            AnnotationEquals("cell_types", COMPASS_TYPES),
            ComponentRole.MEMORY,
            "EPG, PEN, PEG and Delta7 heading circuit, a candidate persistent direction memory.",
            BiologicalStatus.ANATOMICAL,
        ),
        ComponentSpec(
            "steering",
            AnnotationEquals("cell_types", STEERING_TYPES),
            ComponentRole.OUTPUT,
            "PFL neurons comparing heading with goal direction to drive steering.",
            BiologicalStatus.ANATOMICAL,
        ),
    )


def central_complex_cell_types() -> tuple[str, ...]:
    """Central-brain cell types to load; MeTu inputs already load as visual projection."""

    return TUBERCLE_BULB_TYPES + RING_NEURON_TYPES + COMPASS_TYPES + STEERING_TYPES


# Fan-shaped body (Slice 9i step 2). PFN neurons carry heading copies shifted per hemisphere,
# gain-modulated by egocentric signals from the noduli; hDelta neurons combine them into
# allocentric vectors; FC2 neurons hold a goal direction that PFL3 compares with heading.
VECTOR_INPUT_TYPES = ("PFNa", "PFNd", "PFNm_a", "PFNm_b", "PFNv")
FAN_SHAPED_BODY_COLUMN_TYPES = tuple(f"hDelta{letter}" for letter in "ABCDEFGHIJKLM")
GOAL_MEMORY_TYPES = ("FC2A", "FC2B", "FC2C")
FAN_SHAPED_BODY_COMPONENTS = ("vector_inputs", "fan_shaped_body_columns", "goal_memory")


def fan_shaped_body_component_specs() -> tuple[ComponentSpec, ...]:
    return (
        ComponentSpec(
            "vector_inputs",
            AnnotationEquals("cell_types", VECTOR_INPUT_TYPES),
            ComponentRole.PROCESSING,
            "PFN neurons carrying hemisphere-shifted heading into the fan-shaped body, with gain "
            "set by egocentric input through the noduli.",
            BiologicalStatus.ANATOMICAL,
        ),
        ComponentSpec(
            "fan_shaped_body_columns",
            AnnotationEquals("cell_types", FAN_SHAPED_BODY_COLUMN_TYPES),
            ComponentRole.PROCESSING,
            "hDelta columnar neurons combining PFN vectors across fan-shaped-body columns.",
            BiologicalStatus.ANATOMICAL,
        ),
        ComponentSpec(
            "goal_memory",
            AnnotationEquals("cell_types", GOAL_MEMORY_TYPES),
            ComponentRole.MEMORY,
            "FC2 columnar neurons holding a goal direction for PFL3 to compare with heading.",
            BiologicalStatus.ANATOMICAL,
        ),
    )


def fan_shaped_body_cell_types() -> tuple[str, ...]:
    return VECTOR_INPUT_TYPES + FAN_SHAPED_BODY_COLUMN_TYPES + GOAL_MEMORY_TYPES
