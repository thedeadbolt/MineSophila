"""Single-record rules of contract 1.x that the JSON Schemas cannot express.

Each function takes a record that already passed its schema and returns every violation
as ``"<JSON pointer>: <message>"``. Cross-record rules live in :mod:`dataset`.
"""

import math
from datetime import datetime
from typing import Any, Literal, cast

from honey_factory_tools import canonical, ids

type Record = dict[str, Any]

CONFIGURATION_MEMBERS = ("difficulty", "target", "generator", "capture", "splits")
VECTOR_TOLERANCE = 1e-9


def sample_errors(sample: Record) -> list[str]:
    errors: list[str] = []
    scene_id: str = sample["scene_id"]
    split: ids.Split = sample["split"]
    base = ids.sample_id(scene_id, sample["viewpoint_index"])
    augmentation: Record | None = sample.get("augmentation")
    expected = base if augmentation is None else f"{base}-{augmentation['transform']}"
    if sample["sample_id"] != expected:
        errors.append(
            f"/sample_id: must be '{expected}' for this scene, viewpoint and augmentation"
        )
    if augmentation is not None and augmentation["source_sample_id"] != base:
        errors.append(f"/augmentation/source_sample_id: must be the base sample '{base}'")

    files: Record = sample["files"]
    roots: tuple[tuple[str, Literal["rgb", "masks"]], ...] = (("rgb", "rgb"), ("mask", "masks"))
    for key, root in roots:
        path = ids.image_path(root, split, sample["sample_id"])
        if files[key]["path"] != path:
            errors.append(f"/files/{key}/path: must be '{path}' for this split, shard and sample")
    rgb, mask = files["rgb"], files["mask"]
    if (rgb["width"], rgb["height"]) != (mask["width"], mask["height"]):
        errors.append("/files/mask: dimensions differ from the RGB image")

    errors += _camera_errors(sample["camera"])
    errors += _target_errors(sample["target"], mask["width"], mask["height"])
    return errors


def _camera_errors(camera: Record) -> list[str]:
    yaw = math.radians(camera["yaw_degrees"])
    pitch = math.radians(camera["pitch_degrees"])
    forward = [-math.sin(yaw) * math.cos(pitch), -math.sin(pitch), math.cos(yaw) * math.cos(pitch)]
    right = [-math.cos(yaw), 0.0, -math.sin(yaw)]
    up = [
        right[1] * forward[2] - right[2] * forward[1],
        right[2] * forward[0] - right[0] * forward[2],
        right[0] * forward[1] - right[1] * forward[0],
    ]
    player: list[float] = camera["player_position"]
    eye = [player[0], player[1] + camera["eye_height_blocks"], player[2]]
    errors: list[str] = []
    for name, expected in (
        ("eye_position", eye),
        ("forward", forward),
        ("right", right),
        ("up", up),
    ):
        actual: list[float] = camera[name]
        if any(abs(a - e) > VECTOR_TOLERANCE for a, e in zip(actual, expected, strict=True)):
            errors.append(
                f"/camera/{name}: is inconsistent with player position, eye height, yaw and pitch"
            )
    return errors


def target_status(present: bool, in_projection: bool, visible: int, unoccluded: int) -> str:
    """Derive the status from mask evidence; raises ``ValueError`` for impossible evidence."""
    if visible > unoccluded:
        raise ValueError("visible_pixels exceed unoccluded_pixels")
    if not present or not in_projection:
        if in_projection:
            raise ValueError("an absent target cannot be in the projection")
        if unoccluded > 0:
            raise ValueError("a target outside the projection cannot have mask pixels")
        return "out_of_view" if present else "absent"
    if unoccluded == 0:
        return "subpixel"
    if visible == 0:
        return "fully_occluded"
    return "fully_visible" if visible == unoccluded else "partially_visible"


def _target_errors(target: Record, width: int, height: int) -> list[str]:
    mask_pixels = width * height
    present: bool = target["target_present"]
    visible: int = target["visible_pixels"]
    unoccluded: int = target["unoccluded_pixels"]
    if unoccluded > mask_pixels:
        return [f"/target/unoccluded_pixels: exceeds the mask's {mask_pixels} pixels"]
    try:
        derived = target_status(present, target["target_in_projection"], visible, unoccluded)
    except ValueError as error:
        return [f"/target/visible_pixels: {error}"]
    errors: list[str] = []
    if target["target_status"] != derived:
        errors.append(f"/target/target_status: must be {derived} for this evidence")
    ratio = None if unoccluded == 0 else visible / unoccluded
    if target["visibility_ratio"] != ratio:
        errors.append(f"/target/visibility_ratio: must be {ratio} (visible / unoccluded pixels)")

    bbox: list[int] | None = target["visible_bbox_xywh"]
    centroid: list[float] | None = target["visible_centroid_xy"]
    if (visible == 0) != (bbox is None) or (visible == 0) != (centroid is None):
        errors.append(
            "/target/visible_bbox_xywh: box and centroid are null exactly when nothing is visible"
        )
    elif bbox is not None and centroid is not None:
        x, y, box_width, box_height = bbox
        if (
            x < 0
            or y < 0
            or box_width < 1
            or box_height < 1
            or x + box_width > width
            or y + box_height > height
        ):
            errors.append(f"/target/visible_bbox_xywh: must lie inside the {width}x{height} mask")
        elif box_width * box_height < visible:
            errors.append(f"/target/visible_bbox_xywh: is too small to contain {visible} pixels")
        inside_x = x + 0.5 <= centroid[0] <= x + box_width - 0.5
        inside_y = y + 0.5 <= centroid[1] <= y + box_height - 0.5
        if not (inside_x and inside_y):
            errors.append("/target/visible_centroid_xy: must lie within the box's pixel centres")
    if present != ("distance_blocks" in target):
        errors.append(
            "/target/distance_blocks: "
            + ("is required for a present target" if present else "must be omitted when absent")
        )
    return errors


def scene_errors(scene: Record) -> list[str]:
    errors: list[str] = []
    generation: Record = scene["generation"]
    expected = ids.scene_id(generation["scene_index"])
    if scene["scene_id"] != expected:
        errors.append(
            f"/scene_id: must be '{expected}' for scene_index {generation['scene_index']}"
        )

    bounds: Record = scene["arena"]["bounds"]
    low: list[int] = bounds["min"]
    high: list[int] = bounds["max"]
    if any(a > b for a, b in zip(low, high, strict=True)):
        errors.append("/arena/bounds/min: must not exceed max on any axis")
    patch: Record | None = scene.get("source_patch")
    if patch is not None and any(
        a > b for a, b in zip(patch["bounds"]["min"], patch["bounds"]["max"], strict=True)
    ):
        errors.append("/source_patch/bounds/min: must not exceed max on any axis")

    target: Record = scene["target"]
    block_id: str = target["block_id"]
    if target["present"]:
        state: str = target["block_state"]
        if state != block_id and not state.startswith(block_id + "["):
            errors.append(f"/target/block_state: is not a state of {block_id}")
        if not _inside(target["position"], low, high):
            errors.append("/target/position: must lie inside the arena bounds")
    elif "block_state" in target or "position" in target:
        errors.append("/target: block_state and position must be omitted when absent")

    for rule, value in cast(Record, scene["environment"]["gamerules"]).items():
        if not isinstance(value, bool) and not (
            isinstance(value, int | float) and value == int(value)
        ):
            errors.append(f"/environment/gamerules/{rule}: must be a boolean or an integer")

    parameters: Record | None = generation.get("parameters")
    if parameters is not None:
        if len(parameters["viewpoint_draws"]) != scene["accepted_sample_count"]:
            errors.append(
                "/generation/parameters/viewpoint_draws: needs one draw per accepted sample"
            )
        if not _inside(parameters["aim_block"], low, high):
            errors.append("/generation/parameters/aim_block: must lie inside the arena bounds")
    return errors


def _inside(position: list[int], low: list[int], high: list[int]) -> bool:
    return all(a <= p <= b for p, a, b in zip(position, low, high, strict=True))


def configuration(manifest: Record) -> Record:
    """The hashed configuration members, without the final split counts."""
    members: Record = {member: manifest[member] for member in CONFIGURATION_MEMBERS}
    members["splits"] = {k: v for k, v in manifest["splits"].items() if k != "counts"}
    return members


def manifest_errors(manifest: Record) -> list[str]:
    errors: list[str] = []
    digest = canonical.sha256(configuration(manifest))
    if manifest["resolved_config_sha256"] != digest:
        errors.append(
            f"/resolved_config_sha256: does not match the configuration (expected {digest})"
        )
    expected_id = ids.dataset_id(manifest["generator"]["dataset_name"], digest)
    if manifest["dataset_id"] != expected_id:
        errors.append(f"/dataset_id: must be '{expected_id}', the name plus the configuration hash")

    generator: Record = manifest["generator"]
    target: Record = manifest["target"]
    accepted_requested: int = generator["accepted_scenes"]
    if target["target_present_scenes"] + target["target_absent_scenes"] != accepted_requested:
        errors.append(
            "/target/target_present_scenes: present and absent quotas must sum to accepted_scenes"
        )
    natural = generator["scene"]["base"]["type"] == "natural_patch"
    grouping: str = manifest["splits"]["grouping"]
    if natural == (grouping == "scene"):
        errors.append(
            "/splits/grouping: a natural_patch base groups by source_region or source_world, "
            "a platform base by scene"
        )

    counts: Record = manifest["counts"]
    slice_: Record | None = manifest.get("slice")
    if slice_ is None and counts["requested_scenes"] != accepted_requested:
        errors.append("/counts/requested_scenes: must equal generator.accepted_scenes")
    if slice_ is not None:
        slots: list[int] = slice_["slots"]
        if counts["requested_scenes"] != len(slots):
            errors.append(f"/counts/requested_scenes: must equal the slice's {len(slots)} slots")
        if slots != sorted(set(slots)):
            errors.append("/slice/slots: must be scene indexes in increasing order")
        if slice_["index"] >= slice_["count"]:
            errors.append("/slice/index: must be less than count")
    if (
        counts["accepted_scenes"] + counts["rejected_scenes"] + counts["failed_scenes"]
        != counts["attempted_scenes"]
    ):
        errors.append("/counts/attempted_scenes: must equal accepted + rejected + failed scenes")
    if counts["accepted_scenes"] > counts["requested_scenes"]:
        errors.append("/counts/accepted_scenes: must not exceed requested_scenes")
    if sum(cast(dict[str, int], counts["rejection_reasons"]).values()) != counts["rejected_scenes"]:
        errors.append("/counts/rejection_reasons: must sum to rejected_scenes")

    return errors + _lifecycle_errors(manifest)


def _lifecycle_errors(manifest: Record) -> list[str]:
    lifecycle: str = manifest["lifecycle"]
    completed: str | None = manifest["completed_at_utc"]
    split_counts: Record | None = manifest["splits"].get("counts")
    if lifecycle == "generating":
        errors: list[str] = []
        if completed is not None:
            errors.append("/completed_at_utc: must be null while generating")
        if "integrity" in manifest:
            errors.append("/integrity: is populated only at completion")
        if split_counts is not None:
            errors.append("/splits/counts: appear only when complete")
        return errors
    if completed is None or _time(completed) < _time(manifest["created_at_utc"]):
        return [f"/completed_at_utc: must be no earlier than created_at_utc once {lifecycle}"]
    if lifecycle != "complete":
        return []

    errors = []
    integrity: Record | None = manifest.get("integrity")
    if (
        integrity is None
        or not integrity["validation"]["passed"]
        or integrity["validation"]["error_count"]
    ):
        errors.append("/integrity: a complete dataset needs a passed, error-free validation")
    counts: Record = manifest["counts"]
    if counts["accepted_scenes"] != counts["requested_scenes"]:
        errors.append("/counts/accepted_scenes: a complete dataset reaches the requested scenes")
    if split_counts is None:
        errors.append("/splits/counts: are required for a complete dataset")
    else:
        scenes = sum(count["scenes"] for count in split_counts.values())
        samples = sum(count["samples"] for count in split_counts.values())
        if (scenes, samples) != (counts["accepted_scenes"], counts["accepted_samples"]):
            errors.append("/splits/counts: must sum to the accepted scene and sample counts")
    return errors


def _time(value: str) -> datetime:
    return datetime.fromisoformat(value)
