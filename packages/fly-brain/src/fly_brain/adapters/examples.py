"""Composition of sensor, context and target adapters into complete examples."""

from collections.abc import Sequence
from dataclasses import dataclass, field
from typing import Any, cast

from fly_brain.adapters.fly_vision import CachedFlyVisionAdapter, HoneyCoverageTargetAdapter
from fly_brain.adapters.honey_factory import ContextAdapter, FrameRecord, TeacherActionTargetAdapter
from fly_brain.contracts import BrainExample, Provenance
from fly_vision.frame import FlyFrame, FlyLabels


def _source(frame: FlyFrame, index: int) -> dict[str, Any]:
    source = frame.meta.get("source")
    if not isinstance(source, dict):
        raise ValueError(f"frame {index} has no fly-vision source metadata")
    return cast(dict[str, Any], source)


@dataclass(frozen=True)
class CachedExampleAdapter:
    """Build one example from already sampled receptor frames and aligned records."""

    vision: CachedFlyVisionAdapter
    context: ContextAdapter
    honey_targets: HoneyCoverageTargetAdapter = field(default_factory=HoneyCoverageTargetAdapter)
    teacher_targets: TeacherActionTargetAdapter | None = None

    def convert(
        self,
        frames: Sequence[FlyFrame],
        labels: Sequence[FlyLabels],
        frame_records: Sequence[FrameRecord],
        timestamps_s: Sequence[float],
        *,
        dataset_id: str,
        clip_id: str | None = None,
        capture_config_hash: str | None = None,
    ) -> BrainExample:
        if len(frame_records) != len(frames):
            raise ValueError("frame records and receptor frames must have equal length")
        context = self.context.convert(frame_records)
        observation = self.vision.convert(frames, timestamps_s, context)
        targets = self.honey_targets.convert(labels, frames, observation)
        if self.teacher_targets is not None:
            targets = self.teacher_targets.add_to(targets, frame_records)
        sources = [_source(frame, index) for index, frame in enumerate(frames)]
        scene_ids: set[str] = set()
        frame_ids_list: list[str] = []
        for source in sources:
            scene_id_value = source.get("scene_id")
            if not isinstance(scene_id_value, str) or not scene_id_value:
                raise ValueError("every frame must have a non-empty source scene_id")
            frame_id_value = source.get("frame_id")
            if not isinstance(frame_id_value, str) or not frame_id_value:
                raise ValueError("every frame must have a non-empty source frame_id")
            scene_ids.add(scene_id_value)
            frame_ids_list.append(frame_id_value)
        if len(scene_ids) != 1:
            raise ValueError("all frames must have the same source scene_id")
        scene_id = next(iter(scene_ids))
        frame_ids = tuple(frame_ids_list)
        return BrainExample(
            observation=observation,
            targets=targets,
            provenance=Provenance(
                dataset_id=dataset_id,
                scene_id=scene_id,
                frame_ids=frame_ids,
                sensor_config_hash=observation.layout.config_hash,
                clip_id=clip_id,
                capture_config_hash=capture_config_hash,
            ),
        )
