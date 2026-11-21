"""Compact temporal caches derived from Honey Factory clips."""

import hashlib
import json
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Literal, cast

import numpy as np
from numpy.typing import NDArray

from fly_brain.adapters import EgocentricMotionContextAdapter, TeacherActionTargetAdapter
from fly_brain.contracts import BrainTargets
from fly_brain.contracts._arrays import readonly
from fly_brain.models import (
    ActionTemporalBatch,
    LocalizationBatch,
    SpatialBatch,
    SpatialLocalizationModel,
    SpatialParameters,
    SpatialTopology,
    TemporalBatch,
)
from fly_vision.config import FlyVisionConfig, config_hash
from fly_vision.eye import FlyEye
from fly_vision.lattice import default_lattice, lattice_hash
from fly_vision.projection import honey_factory_panorama, to_unit
from honey_factory_tools.clips import iter_clips


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

    def sensory_feature_indexes(self, group: str) -> NDArray[np.int64]:
        raw_manifest = self.metadata.get("sensory_features")
        if not isinstance(raw_manifest, dict):
            raise ValueError("cached sensory features do not define named groups")
        manifest = cast(dict[str, Any], raw_manifest)
        if not isinstance(manifest.get("groups"), dict):
            raise ValueError("cached sensory features do not define named groups")
        groups = cast(dict[str, Any], manifest["groups"])
        if group not in groups or not isinstance(groups[group], list):
            raise ValueError(f"cached sensory features do not define group {group!r}")
        indexes = np.asarray(groups[group], dtype=np.int64)
        if (
            indexes.ndim != 1
            or indexes.size == 0
            or np.unique(indexes).size != indexes.size
            or np.any((indexes < 0) | (indexes >= self.sensory_features.shape[1]))
        ):
            raise ValueError(f"cached sensory feature group {group!r} is invalid")
        return readonly(indexes)

    def batch(
        self,
        split: str,
        *,
        include_context: bool = False,
        feature_group: str | None = None,
    ) -> TemporalBatch:
        selected = [index for index, value in enumerate(self.splits) if value == split]
        if not selected:
            raise ValueError(f"cached clip dataset has no {split!r} clips")
        lengths = [int(self.offsets[index + 1] - self.offsets[index]) for index in selected]
        maximum = max(lengths)
        feature_indexes = (
            None if feature_group is None else self.sensory_feature_indexes(feature_group)
        )
        width = self.sensory_features.shape[1] if feature_indexes is None else feature_indexes.size
        if include_context:
            width += self.motion_context.shape[1]
        features = np.zeros((len(selected), maximum, width), dtype=np.float32)
        mask = np.zeros((len(selected), maximum), dtype=np.bool_)
        presence = np.zeros((len(selected), maximum), dtype=np.float32)
        direction = np.zeros((len(selected), maximum, 3), dtype=np.float32)
        for row, (clip_index, length) in enumerate(zip(selected, lengths, strict=True)):
            start, stop = self.offsets[clip_index : clip_index + 2]
            sensory = self.sensory_features[start:stop]
            if feature_indexes is not None:
                sensory = sensory[:, feature_indexes]
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

    def frame_batch(self, split: str, *, feature_group: str | None = None) -> LocalizationBatch:
        """Flatten every frame of a split's clips into independent localisation samples."""
        selected = [index for index, value in enumerate(self.splits) if value == split]
        if not selected:
            raise ValueError(f"cached clip dataset has no {split!r} clips")
        spans: list[NDArray[np.int64]] = [
            np.arange(int(self.offsets[index]), int(self.offsets[index + 1]), dtype=np.int64)
            for index in selected
        ]
        rows = np.concatenate(spans)
        features = self.sensory_features[rows]
        if feature_group is not None:
            features = features[:, self.sensory_feature_indexes(feature_group)]
        return LocalizationBatch(
            features=features,
            time_mask=np.ones(rows.size, dtype=np.bool_),
            presence=self.presence[rows],
            direction=self.direction[rows],
            coverage=None,
            site_count=1,
        )

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

    def clip_type_mask(self, split: str, clip_type: str) -> NDArray[np.bool_]:
        """Return a padded mask selecting every frame of one held-out clip type."""
        selected = [index for index, value in enumerate(self.splits) if value == split]
        if not selected:
            raise ValueError(f"cached clip dataset has no {split!r} clips")
        if not any(self.clip_types[index] == clip_type for index in selected):
            raise ValueError(f"cached {split!r} split has no {clip_type!r} clips")
        maximum = max(int(self.offsets[index + 1] - self.offsets[index]) for index in selected)
        result = np.zeros((len(selected), maximum), dtype=np.bool_)
        for row, clip_index in enumerate(selected):
            if self.clip_types[clip_index] == clip_type:
                start, stop = self.offsets[clip_index : clip_index + 2]
                result[row, : stop - start] = True
        return result

    def action_batch(
        self,
        split: str,
        *,
        include_context: bool = True,
        feature_group: str | None = None,
    ) -> ActionTemporalBatch:
        """Batch clips with visible, teacher-labelled action targets."""
        candidates = [index for index, value in enumerate(self.splits) if value == split]
        selected = [
            index
            for index in candidates
            if np.any(
                self.teacher_action_mask[self.offsets[index] : self.offsets[index + 1]]
                & self.visible[self.offsets[index] : self.offsets[index + 1]]
            )
        ]
        if not selected:
            raise ValueError(f"cached clip dataset has no supervised {split!r} actions")
        lengths = [int(self.offsets[index + 1] - self.offsets[index]) for index in selected]
        maximum = max(lengths)
        feature_indexes = (
            None if feature_group is None else self.sensory_feature_indexes(feature_group)
        )
        width = self.sensory_features.shape[1] if feature_indexes is None else feature_indexes.size
        if include_context:
            width += self.motion_context.shape[1]
        features = np.zeros((len(selected), maximum, width), dtype=np.float32)
        sequence_mask = np.zeros((len(selected), maximum), dtype=np.bool_)
        action_mask = np.zeros((len(selected), maximum), dtype=np.bool_)
        actions = np.zeros(
            (len(selected), maximum, self.teacher_actions.shape[1]), dtype=np.float32
        )
        for row, (clip_index, length) in enumerate(zip(selected, lengths, strict=True)):
            start, stop = self.offsets[clip_index : clip_index + 2]
            sensory = self.sensory_features[start:stop]
            if feature_indexes is not None:
                sensory = sensory[:, feature_indexes]
            features[row, :length] = (
                np.concatenate([sensory, self.motion_context[start:stop]], axis=1)
                if include_context
                else sensory
            )
            sequence_mask[row, :length] = True
            action_mask[row, :length] = (
                self.teacher_action_mask[start:stop] & self.visible[start:stop]
            )
            actions[row, :length] = self.teacher_actions[start:stop]
        return ActionTemporalBatch(features, sequence_mask, action_mask, actions)

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


_ALIGNED_CLIP_FIELDS = (
    "motion_context",
    "presence",
    "direction",
    "visible",
    "teacher_actions",
    "teacher_action_mask",
    "offsets",
    "clip_ids",
    "scene_ids",
    "splits",
    "clip_types",
)


def combine_clip_features(
    parts: Sequence[tuple[str, CachedClipDataset]],
    composite_groups: Mapping[str, Sequence[str]],
) -> CachedClipDataset:
    """Concatenate sensory features from caches of the same clips into named groups.

    Every part becomes a group of its own name, and named groups inside a part are kept with
    shifted column indexes. Composite groups concatenate existing groups in the given order.
    Targets, context, offsets and clip identities must match exactly.
    """
    if not parts:
        raise ValueError("combine_clip_features requires at least one part")
    first = parts[0][1]
    for name, part in parts[1:]:
        for field in _ALIGNED_CLIP_FIELDS:
            if not np.array_equal(
                np.asarray(getattr(first, field)), np.asarray(getattr(part, field))
            ):
                raise ValueError(f"clip cache part {name!r} differs in {field}")
    groups: dict[str, list[int]] = {}
    sources: dict[str, Any] = {}
    start = 0
    for name, part in parts:
        width = part.sensory_features.shape[1]
        own: dict[str, list[int]] = {name: list(range(start, start + width))}
        manifest = part.metadata.get("sensory_features")
        part_groups = (
            cast(dict[str, Any], manifest).get("groups") if isinstance(manifest, dict) else None
        )
        if isinstance(part_groups, dict):
            for group in cast(dict[str, Any], part_groups):
                indexes = part.sensory_feature_indexes(group)
                own[group] = (indexes + start).tolist()
        for group, indexes in own.items():
            if group in groups:
                raise ValueError(f"sensory feature group {group!r} is defined twice")
            groups[group] = indexes
        sources[name] = part.metadata
        start += width
    for name, members in composite_groups.items():
        if name in groups:
            raise ValueError(f"sensory feature group {name!r} is defined twice")
        missing = [member for member in members if member not in groups]
        if missing or not members:
            raise ValueError(f"composite group {name!r} references unknown groups {missing}")
        indexes = [index for member in members for index in groups[member]]
        if len(set(indexes)) != len(indexes):
            raise ValueError(f"composite group {name!r} repeats feature columns")
        groups[name] = indexes
    metadata = {key: value for key, value in first.metadata.items() if key != "sensory_features"}
    metadata["sensory_features"] = {
        "kind": "combined_clip_features",
        "parts": [name for name, _ in parts],
        "composites": {name: list(members) for name, members in composite_groups.items()},
        "groups": groups,
        "width": start,
        "sources": sources,
    }
    return CachedClipDataset(
        sensory_features=np.concatenate([part.sensory_features for _, part in parts], axis=1),
        motion_context=first.motion_context,
        presence=first.presence,
        direction=first.direction,
        visible=first.visible,
        teacher_actions=first.teacher_actions,
        teacher_action_mask=first.teacher_action_mask,
        offsets=first.offsets,
        clip_ids=first.clip_ids,
        scene_ids=first.scene_ids,
        splits=first.splits,
        clip_types=first.clip_types,
        metadata=metadata,
    )


def convert_honey_factory_clips(
    dataset_dir: Path,
    output_path: Path,
    *,
    matrix_cache: Path,
    spatial_model: SpatialLocalizationModel,
    spatial_parameters: SpatialParameters,
    spatial_topology: SpatialTopology,
    frontend_checkpoint_sha256: str,
    prediction_batch_size: int = 64,
    frame_selection: Literal["all", "tick"] = "tick",
    sensory_transform: Callable[[SpatialBatch], NDArray[np.float32]] | None = None,
    sensory_feature_manifest: object | None = None,
    progress: Callable[[int, int], None] | None = None,
) -> CachedClipDataset:
    """Convert RGB clips through fly-vision and a frozen spatial frontend."""
    if prediction_batch_size < 1:
        raise ValueError("prediction_batch_size must be positive")
    if frame_selection not in {"all", "tick"}:
        raise ValueError("frame_selection must be 'all' or 'tick'")
    if sensory_transform is not None and sensory_feature_manifest is None:
        raise ValueError("a custom sensory transform requires a feature manifest")
    manifest_path = dataset_dir / "dataset.json"
    manifest = cast(dict[str, Any], json.loads(manifest_path.read_text()))
    if manifest.get("lifecycle") != "complete" or manifest.get("kind") != "clips":
        raise ValueError(f"{dataset_dir} is not a complete clip dataset")
    capture = cast(dict[str, Any], manifest["capture"])
    if capture.get("mode") != "PANORAMA_310":
        raise ValueError("clip conversion currently requires PANORAMA_310")
    width, height = int(capture["width"]), int(capture["height"])
    lattice = default_lattice()
    vision_config = FlyVisionConfig()
    eye = FlyEye(
        lattice,
        honey_factory_panorama(width, height),
        vision_config,
        cache_dir=matrix_cache,
    )
    directions = to_unit(lattice.direction_deg[:, 0], lattice.direction_deg[:, 1]).astype(
        np.float32
    )
    if spatial_model.site_count != lattice.ommatidium_id.size:
        raise ValueError("spatial frontend sites do not match the fly-vision lattice")
    clips = list(iter_clips(dataset_dir))
    if not clips:
        raise ValueError("dataset contains no clips")
    sensory_rows: list[NDArray[np.float32]] = []
    context_rows: list[NDArray[np.float32]] = []
    presence_rows: list[NDArray[np.float32]] = []
    direction_rows: list[NDArray[np.float32]] = []
    visible_rows: list[NDArray[np.bool_]] = []
    action_rows: list[NDArray[np.float32]] = []
    action_mask_rows: list[NDArray[np.bool_]] = []
    offsets = [0]
    clip_ids: list[str] = []
    scene_ids: list[str] = []
    splits: list[str] = []
    clip_types: list[str] = []
    context_adapter = EgocentricMotionContextAdapter()
    teacher_adapter = TeacherActionTargetAdapter()
    for clip_index, clip in enumerate(clips, start=1):
        records: list[dict[str, Any]] = []
        activations: list[NDArray[np.float32]] = []
        for clip_frame in clip.frames():
            if frame_selection == "tick" and "tick_labels" not in clip_frame.labels:
                continue
            rgb = np.asarray(clip_frame.rgb, dtype=np.uint8)
            mask = (np.asarray(clip_frame.visible_mask) > 0).astype(np.float64)
            receptors, _ = eye.sample_arrays(rgb, mask)
            activations.append(receptors)
            records.append(clip_frame.labels)
        signal_rows = np.stack(activations)
        spatial_batch = SpatialBatch(
            signal_rows,
            eye.sm.valid,
            np.zeros((signal_rows.shape[0], spatial_model.site_count), dtype=np.float32),
            directions,
        )
        if sensory_transform is None:
            prediction = spatial_model.predict(
                spatial_parameters,
                spatial_batch,
                spatial_topology,
                batch_size=prediction_batch_size,
            )
            sensory = np.concatenate(
                [prediction.presence_probability[:, None], prediction.direction], axis=1
            )
        else:
            sensory = np.asarray(sensory_transform(spatial_batch), dtype=np.float32)
            if sensory.ndim != 2 or sensory.shape[0] != len(records):
                raise ValueError("clip sensory transform must return shape (frames, features)")
        sensory_rows.append(sensory)
        context = context_adapter.convert(records)
        context_rows.append(context.values)
        presence, direction, visible = _clip_localisation_targets(records)
        presence_rows.append(presence)
        direction_rows.append(direction)
        visible_rows.append(visible)
        teacher = teacher_adapter.add_to(
            BrainTargets(time_mask=np.ones(len(records), dtype=np.bool_)), records
        )
        assert teacher.teacher_actions is not None
        assert teacher.teacher_action_mask is not None
        action_rows.append(teacher.teacher_actions.values)
        action_mask_rows.append(teacher.teacher_action_mask)
        offsets.append(offsets[-1] + len(records))
        clip_ids.append(str(clip.record["clip_id"]))
        scene_ids.append(str(clip.record["scene_id"]))
        splits.append(str(clip.record["split"]))
        clip_types.append(str(clip.record["clip_type"]))
        if progress is not None:
            progress(clip_index, len(clips))
    result = CachedClipDataset(
        sensory_features=np.concatenate(sensory_rows),
        motion_context=np.concatenate(context_rows),
        presence=np.concatenate(presence_rows),
        direction=np.concatenate(direction_rows),
        visible=np.concatenate(visible_rows),
        teacher_actions=np.concatenate(action_rows),
        teacher_action_mask=np.concatenate(action_mask_rows),
        offsets=np.asarray(offsets, dtype=np.int64),
        clip_ids=tuple(clip_ids),
        scene_ids=tuple(scene_ids),
        splits=tuple(splits),
        clip_types=tuple(clip_types),
        metadata={
            "format": "fly-brain-cached-clips-v1",
            "source_dataset_id": str(manifest["dataset_id"]),
            "source_manifest_sha256": hashlib.sha256(manifest_path.read_bytes()).hexdigest(),
            "source_clips_sha256": manifest["integrity"]["indexes"]["clips"]["sha256"],
            "lattice_hash": lattice_hash(lattice),
            "sensor_config_hash": config_hash(vision_config),
            "sensory_features": (
                ["presence_probability", "direction_x", "direction_y", "direction_z"]
                if sensory_transform is None
                else sensory_feature_manifest
            ),
            "frontend_checkpoint_sha256": frontend_checkpoint_sha256,
            "frame_selection": frame_selection,
        },
    )
    result.save(output_path)
    return result


def _clip_localisation_targets(
    records: list[dict[str, Any]],
) -> tuple[NDArray[np.float32], NDArray[np.float32], NDArray[np.bool_]]:
    presence = np.zeros(len(records), dtype=np.float32)
    direction = np.zeros((len(records), 3), dtype=np.float32)
    visible = np.zeros(len(records), dtype=np.bool_)
    for index, record in enumerate(records):
        target = cast(dict[str, Any], record["target"])
        presence[index] = float(bool(target["target_present"]))
        visible[index] = int(target["visible_pixels"]) > 0
        bearing = record.get("bearing")
        if presence[index] and isinstance(bearing, dict):
            bearing_values = cast(dict[str, Any], bearing)
            direction[index] = to_unit(
                np.asarray(float(bearing_values["azimuth_degrees"])),
                np.asarray(float(bearing_values["elevation_degrees"])),
            )
    return presence, direction, visible
