"""Causal movement context and teacher targets from Honey Factory frame records."""

from collections.abc import Sequence
from dataclasses import dataclass, replace
from typing import Any, Protocol, cast

import numpy as np

from fly_brain.contracts import (
    Availability,
    BrainTargets,
    FeatureBlock,
    FeatureSchema,
    FeatureSpec,
)

type FrameRecord = dict[str, Any]


class ContextAdapter(Protocol):
    def convert(self, frame_records: Sequence[FrameRecord]) -> FeatureBlock | None: ...


@dataclass(frozen=True)
class NoContextAdapter:
    def convert(self, frame_records: Sequence[FrameRecord]) -> None:
        if not frame_records:
            raise ValueError("at least one frame record is required")
        return None


_CONTEXT_FIELDS: tuple[tuple[str, str, str], ...] = (
    ("forward_speed", "blocks/tick", "Measured egocentric forward speed"),
    ("strafe_speed", "blocks/tick", "Measured egocentric strafe speed"),
    ("vertical_speed", "blocks/tick", "Measured vertical speed"),
    ("yaw_rate", "degrees/tick", "Measured yaw rate"),
    ("pitch_rate", "degrees/tick", "Measured pitch rate"),
    ("pitch", "degrees", "Current head pitch"),
    ("on_ground", "1", "Whether the body is on the ground"),
    ("bumped", "1", "Whether the body collided this tick"),
    ("in_water", "1", "Whether the body is in water"),
    ("last_forward", "1", "Previous command forward value"),
    ("last_strafe", "1", "Previous command strafe value"),
    ("last_turn_rate", "degrees/tick", "Previous command turn rate"),
    ("last_pitch_rate", "degrees/tick", "Previous command pitch rate"),
    ("last_jump", "1", "Previous command jump value"),
)

EGOCENTRIC_MOTION_SCHEMA = FeatureSchema(
    tuple(
        FeatureSpec(name, units, description, "honey-factory senses", Availability.AGENT)
        for name, units, description in _CONTEXT_FIELDS
    )
)


def _number(record: FrameRecord, key: str) -> float:
    value = record.get(key)
    if not isinstance(value, int | float | bool):
        raise ValueError(f"Honey Factory record field {key!r} is not numeric")
    return float(value)


def _context_row(senses: FrameRecord) -> list[float]:
    command_value = senses.get("last_command")
    if not isinstance(command_value, dict):
        raise ValueError("Honey Factory senses have no last_command object")
    command = cast(FrameRecord, command_value)
    return [
        *[
            _number(senses, name)
            for name in (
                "forward_speed",
                "strafe_speed",
                "vertical_speed",
                "yaw_rate",
                "pitch_rate",
                "pitch",
                "on_ground",
                "bumped",
                "in_water",
            )
        ],
        *[
            _number(command, name)
            for name in ("forward", "strafe", "turn_rate", "pitch_rate", "jump")
        ],
    ]


@dataclass(frozen=True)
class EgocentricMotionContextAdapter:
    """Use the latest senses available at or before each captured frame."""

    def convert(self, frame_records: Sequence[FrameRecord]) -> FeatureBlock:
        if not frame_records:
            raise ValueError("at least one frame record is required")
        rows: list[list[float]] = []
        latest: FrameRecord | None = None
        for index, frame in enumerate(frame_records):
            labels = frame.get("tick_labels")
            if labels is not None:
                if not isinstance(labels, dict):
                    raise ValueError(f"frame record {index} has invalid tick_labels.senses")
                label_record = cast(FrameRecord, labels)
                senses = label_record.get("senses")
                if not isinstance(senses, dict):
                    raise ValueError(f"frame record {index} has invalid tick_labels.senses")
                latest = cast(FrameRecord, senses)
            if latest is None:
                raise ValueError("the first frame record must provide tick_labels.senses")
            rows.append(_context_row(latest))
        return FeatureBlock(np.asarray(rows, dtype=np.float32), EGOCENTRIC_MOTION_SCHEMA)


_ACTION_FIELDS: tuple[tuple[str, str], ...] = (
    ("forward", "1"),
    ("strafe", "1"),
    ("turn_rate", "degrees/tick"),
    ("pitch_rate", "degrees/tick"),
    ("jump", "1"),
    ("delay_ticks", "ticks"),
)

TEACHER_ACTION_SCHEMA = FeatureSchema(
    tuple(
        FeatureSpec(
            name,
            units,
            "Teacher command target",
            "honey-factory tick_labels.teacher",
            Availability.TEACHER,
        )
        for name, units in _ACTION_FIELDS
    )
)


@dataclass(frozen=True)
class TeacherActionTargetAdapter:
    """Add teacher commands only where a frame record actually carries one."""

    def add_to(self, targets: BrainTargets, frame_records: Sequence[FrameRecord]) -> BrainTargets:
        if len(frame_records) != targets.time_steps:
            raise ValueError("frame records and targets must have equal time lengths")
        values = np.zeros((targets.time_steps, TEACHER_ACTION_SCHEMA.width), dtype=np.float32)
        mask = np.zeros(targets.time_steps, dtype=np.bool_)
        for index, frame in enumerate(frame_records):
            labels = frame.get("tick_labels")
            label_record = cast(FrameRecord, labels) if isinstance(labels, dict) else None
            teacher = None if label_record is None else label_record.get("teacher")
            if teacher is None:
                continue
            if not isinstance(teacher, dict):
                raise ValueError(f"frame record {index} has an invalid teacher command")
            command = cast(FrameRecord, teacher)
            values[index] = [_number(command, name) for name, _ in _ACTION_FIELDS]
            mask[index] = True
        return replace(
            targets,
            teacher_actions=FeatureBlock(values, TEACHER_ACTION_SCHEMA),
            teacher_action_mask=mask,
        )
