"""Compact temporal caches derived from Honey Factory clips."""

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np
from numpy.typing import NDArray

from fly_brain.contracts._arrays import readonly
from fly_brain.models import TemporalBatch


@dataclass(frozen=True)
class CachedClipDataset:
    """Concatenated clip features with offsets retaining variable sequence lengths."""

    sensory_features: NDArray[np.float32]  # (frames, F)
    motion_context: NDArray[np.float32]  # (frames, C)
    presence: NDArray[np.float32]  # (frames,)
    direction: NDArray[np.float32]  # (frames, 3)
    visible: NDArray[np.bool_]  # (frames,)
    teacher_actions: NDArray[np.float32]  # (frames, A)
    teacher_action_mask: NDArray[np.bool_]  # (frames,)
    offsets: NDArray[np.int64]  # (clips + 1,)
    clip_ids: tuple[str, ...]
    scene_ids: tuple[str, ...]
    splits: tuple[str, ...]
    clip_types: tuple[str, ...]
    metadata: dict[str, Any]

    def __post_init__(self) -> None:
        sensory = np.asarray(self.sensory_features, dtype=np.float32)
        context = np.asarray(self.motion_context, dtype=np.float32)
        presence = np.asarray(self.presence, dtype=np.float32)
        direction = np.asarray(self.direction, dtype=np.float32)
        visible = np.asarray(self.visible, dtype=np.bool_)
        actions = np.asarray(self.teacher_actions, dtype=np.float32)
        action_mask = np.asarray(self.teacher_action_mask, dtype=np.bool_)
        offsets = np.asarray(self.offsets, dtype=np.int64)
        if sensory.ndim != 2 or sensory.shape[0] == 0 or sensory.shape[1] == 0:
            raise ValueError("cached clip sensory features must have non-empty shape (frames, F)")
        frames = sensory.shape[0]
        if context.ndim != 2 or context.shape[0] != frames:
            raise ValueError("cached clip context must have shape (frames, C)")
        if presence.shape != (frames,) or direction.shape != (frames, 3):
            raise ValueError("cached clip localisation targets must name every frame")
        if visible.shape != (frames,) or action_mask.shape != (frames,):
            raise ValueError("cached clip masks must name every frame")
        if actions.ndim != 2 or actions.shape[0] != frames:
            raise ValueError("cached clip actions must have shape (frames, A)")
        numerical = (sensory, context, presence, direction, actions)
        if not all(np.isfinite(value).all() for value in numerical):
            raise ValueError("cached clip numerical arrays must be finite")
        if np.any((presence < 0) | (presence > 1)):
            raise ValueError("cached clip presence targets must lie in [0, 1]")
        clip_count = len(self.clip_ids)
        identifiers = (self.scene_ids, self.splits, self.clip_types)
        if any(len(values) != clip_count for values in identifiers):
            raise ValueError("cached clip identifiers must have equal lengths")
        if offsets.shape != (clip_count + 1,) or offsets[0] != 0 or offsets[-1] != frames:
            raise ValueError("cached clip offsets must bound every frame")
        if np.any(np.diff(offsets) <= 0):
            raise ValueError("cached clips must contain at least one frame")
        if any(split not in {"train", "validation", "test"} for split in self.splits):
            raise ValueError("cached clip splits must be train, validation or test")
        for name, value in (
            ("sensory_features", sensory),
            ("motion_context", context),
            ("presence", presence),
            ("direction", direction),
            ("visible", visible),
            ("teacher_actions", actions),
            ("teacher_action_mask", action_mask),
            ("offsets", offsets),
        ):
            object.__setattr__(self, name, readonly(value))

    @property
    def clip_count(self) -> int:
        return len(self.clip_ids)

    @property
    def frame_count(self) -> int:
        return self.sensory_features.shape[0]

    def batch(self, split: str, *, include_context: bool = False) -> TemporalBatch:
        selected = [index for index, value in enumerate(self.splits) if value == split]
        if not selected:
            raise ValueError(f"cached clip dataset has no {split!r} clips")
        lengths = [int(self.offsets[index + 1] - self.offsets[index]) for index in selected]
        maximum = max(lengths)
        width = self.sensory_features.shape[1]
        if include_context:
            width += self.motion_context.shape[1]
        features = np.zeros((len(selected), maximum, width), dtype=np.float32)
        mask = np.zeros((len(selected), maximum), dtype=np.bool_)
        presence = np.zeros((len(selected), maximum), dtype=np.float32)
        direction = np.zeros((len(selected), maximum, 3), dtype=np.float32)
        for row, (clip_index, length) in enumerate(zip(selected, lengths, strict=True)):
            start, stop = self.offsets[clip_index : clip_index + 2]
            sensory = self.sensory_features[start:stop]
            if include_context:
                features[row, :length] = np.concatenate(
                    [sensory, self.motion_context[start:stop]], axis=1
                )
            else:
                features[row, :length] = sensory
            mask[row, :length] = True
            presence[row, :length] = self.presence[start:stop]
            direction[row, :length] = self.direction[start:stop]
        return TemporalBatch(features, mask, presence, direction)

    def interval_mask(self, split: str, kind: str) -> NDArray[np.bool_]:
        """Return a padded visibility or occlusion mask aligned with ``batch(split)``."""
        if kind not in {"visible", "occluded"}:
            raise ValueError("interval kind must be 'visible' or 'occluded'")
        selected = [index for index, value in enumerate(self.splits) if value == split]
        if not selected:
            raise ValueError(f"cached clip dataset has no {split!r} clips")
        maximum = max(int(self.offsets[index + 1] - self.offsets[index]) for index in selected)
        result = np.zeros((len(selected), maximum), dtype=np.bool_)
        for row, clip_index in enumerate(selected):
            start, stop = self.offsets[clip_index : clip_index + 2]
            values = self.visible[start:stop]
            result[row, : values.size] = values if kind == "visible" else ~values
        return result

    def save(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        np.savez_compressed(
            path,
            sensory_features=self.sensory_features,
            motion_context=self.motion_context,
            presence=self.presence,
            direction=self.direction,
            visible=self.visible,
            teacher_actions=self.teacher_actions,
            teacher_action_mask=self.teacher_action_mask,
            offsets=self.offsets,
            clip_ids=np.asarray(self.clip_ids),
            scene_ids=np.asarray(self.scene_ids),
            splits=np.asarray(self.splits),
            clip_types=np.asarray(self.clip_types),
            metadata=np.asarray(json.dumps(self.metadata, sort_keys=True)),
        )

    @classmethod
    def load(cls, path: Path) -> "CachedClipDataset":
        with np.load(path, allow_pickle=False) as values:
            return cls(
                sensory_features=values["sensory_features"],
                motion_context=values["motion_context"],
                presence=values["presence"],
                direction=values["direction"],
                visible=values["visible"],
                teacher_actions=values["teacher_actions"],
                teacher_action_mask=values["teacher_action_mask"],
                offsets=values["offsets"],
                clip_ids=tuple(str(value) for value in values["clip_ids"]),
                scene_ids=tuple(str(value) for value in values["scene_ids"]),
                splits=tuple(str(value) for value in values["splits"]),
                clip_types=tuple(str(value) for value in values["clip_types"]),
                metadata=json.loads(str(values["metadata"])),
            )
