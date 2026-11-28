from typing import Any

import numpy as np
import pytest

from fly_brain.adapters import (
    CachedExampleAdapter,
    CachedFlyVisionAdapter,
    EgocentricMotionContextAdapter,
    NoContextAdapter,
    TeacherActionTargetAdapter,
)
from fly_vision.frame import FlyFrame, FlyLabels
from fly_vision.lattice import Lattice, lattice_hash


def lattice() -> Lattice:
    return Lattice(
        ommatidium_id=np.arange(4, dtype=np.int32),
        eye=np.array(["L", "L", "R", "R"], dtype="<U1"),
        hex_uv=np.array([[0, 0], [0, 1], [0, 0], [0, 1]], dtype=np.int32),
        direction_deg=np.array(
            [[0.0, 0.0], [90.0, 0.0], [180.0, 0.0], [-90.0, 0.0]],
            dtype=np.float64,
        ),
        subtype=np.array(["pale", "yellow", "pale", "yellow"], dtype="<U13"),
        column_id=np.full(4, -1, dtype=np.int32),
        version="fixture-v1",
    )


def frame(index: int, eye: Lattice) -> FlyFrame:
    return FlyFrame(
        activations=np.full((4, 3), index + 1, dtype=np.float32),
        valid=np.array([True, True, True, False]),
        meta={
            "fly_vision_version": "0.0.1",
            "lattice_hash": lattice_hash(eye),
            "matrix_key": "matrix",
            "config_hash": "vision-config",
            "channels": ["R1_6", "R7", "R8"],
            "image_sha256": f"image-{index}",
            "source": {
                "scene_id": "s000001",
                "frame_id": f"f{index}",
                "image_path": None,
                "capture_meta": {},
            },
        },
    )


def label(index: int, eye: Lattice, coverage: list[float]) -> FlyLabels:
    return FlyLabels(
        honey_coverage=np.asarray(coverage, dtype=np.float32),
        meta={
            "lattice_hash": lattice_hash(eye),
            "matrix_key": "matrix",
            "image_sha256": f"image-{index}",
            "frame_id": f"f{index}",
        },
    )


def command(forward: float) -> dict[str, float | bool | int]:
    return {
        "forward": forward,
        "strafe": 0.0,
        "turn_rate": 4.5,
        "pitch_rate": 0.0,
        "jump": False,
        "delay_ticks": 0,
    }


def senses(forward_speed: float, last_forward: float) -> dict[str, Any]:
    return {
        "forward_speed": forward_speed,
        "strafe_speed": 0.0,
        "vertical_speed": 0.0,
        "yaw_rate": 0.0,
        "pitch_rate": 0.0,
        "pitch": 0.0,
        "on_ground": True,
        "bumped": False,
        "in_water": False,
        "last_command": command(last_forward),
    }


def reset_record() -> dict[str, Any]:
    return {
        "frame": 0,
        "tick_labels": {"senses": senses(0.0, 0.0), "teacher": None},
    }


def test_cached_still_frame_becomes_localisation_example() -> None:
    eye = lattice()
    cached = frame(0, eye)
    adapter = CachedExampleAdapter(
        vision=CachedFlyVisionAdapter.from_frame(eye, cached),
        context=NoContextAdapter(),
    )

    example = adapter.convert(
        [cached],
        [label(0, eye, [0.0, 0.75, 0.0, 0.0])],
        [reset_record()],
        [0.0],
        dataset_id="fixture-dataset",
    )

    assert example.observation.is_still
    assert example.observation.context is None
    assert example.targets.honey_present is not None
    assert example.targets.honey_present.tolist() == [True]
    assert example.targets.honey_direction is not None
    assert example.targets.honey_direction[0].tolist() == pytest.approx([0.0, 1.0, 0.0])
    assert example.provenance.frame_ids == ("f0",)


def test_clip_context_uses_only_latest_available_senses() -> None:
    eye = lattice()
    frames = [frame(index, eye) for index in range(4)]
    labels = [label(index, eye, [0.5, 0.0, 0.0, 0.0]) for index in range(4)]
    records = [
        reset_record(),
        {"frame": 1},
        {"frame": 2},
        {
            "frame": 3,
            "tick_labels": {
                "senses": senses(0.2, 1.0),
                "teacher": command(1.0),
            },
        },
    ]
    adapter = CachedExampleAdapter(
        vision=CachedFlyVisionAdapter.from_frame(eye, frames[0]),
        context=EgocentricMotionContextAdapter(),
        teacher_targets=TeacherActionTargetAdapter(),
    )

    example = adapter.convert(
        frames,
        labels,
        records,
        [0.0, 1 / 60, 2 / 60, 3 / 60],
        dataset_id="fixture-dataset",
        clip_id="clip-1",
    )

    context = example.observation.context
    assert context is not None
    assert context.values[:, 0].tolist() == pytest.approx([0.0, 0.0, 0.0, 0.2])
    assert context.values[:, 9].tolist() == pytest.approx([0.0, 0.0, 0.0, 1.0])
    assert example.targets.teacher_action_mask is not None
    assert example.targets.teacher_action_mask.tolist() == [False, False, False, True]
    assert example.targets.teacher_actions is not None
    assert example.targets.teacher_actions.values[3, 0] == pytest.approx(1.0)


def test_label_must_match_its_receptor_frame() -> None:
    eye = lattice()
    cached = frame(0, eye)
    mismatched = label(1, eye, [0.0, 0.0, 0.0, 0.0])
    adapter = CachedExampleAdapter(
        vision=CachedFlyVisionAdapter.from_frame(eye, cached),
        context=NoContextAdapter(),
    )

    with pytest.raises(ValueError, match="different source image"):
        adapter.convert(
            [cached],
            [mismatched],
            [reset_record()],
            [0.0],
            dataset_id="fixture-dataset",
        )
