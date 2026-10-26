import hashlib
import json
import shutil
from collections.abc import Callable
from pathlib import Path
from typing import Any

import pytest

from honey_factory_tools.schemas import Schemas

TESTS = Path(__file__).resolve().parents[1]
FIXTURES = TESTS / "integration" / "fixtures" / "honey-factory"
SCHEMA_DIR = TESTS.parent / "schemas" / "honey-factory" / "1.0"


def load(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


@pytest.fixture(scope="session")
def load_json() -> Callable[[Path], Any]:
    return load


@pytest.fixture(scope="session")
def schema_dir() -> Path:
    return SCHEMA_DIR


@pytest.fixture(scope="session")
def schemas() -> Schemas:
    return Schemas(SCHEMA_DIR)


@pytest.fixture(scope="session")
def contract_dir() -> Path:
    return FIXTURES / "1.0"


@pytest.fixture(scope="session")
def vectors_dir() -> Path:
    return FIXTURES / "vectors"


def _lines(path: Path) -> list[str]:
    return path.read_text(encoding="utf-8").splitlines(keepends=True)


def _digest(data: bytes) -> dict[str, Any]:
    return {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def _make_slice(
    source: Path, target: Path, index: int, count: int, rejected: int, world: str | None = None
) -> Path:
    """A slice of ``source``: its scenes with ``scene_index % count == index``, as one of
    ``count`` instances would have generated them, with ``rejected`` rejected attempts."""
    manifest: dict[str, Any] = json.loads((source / "dataset.json").read_text(encoding="utf-8"))
    root = target / f"{manifest['dataset_id']}.slice-{index}-of-{count}"
    root.mkdir(parents=True)
    scenes = [
        line
        for line in _lines(source / "scenes.jsonl")
        if json.loads(line)["generation"]["scene_index"] % count == index
    ]
    ids = {json.loads(line)["scene_id"] for line in scenes}
    samples = [
        line for line in _lines(source / "samples.jsonl") if json.loads(line)["scene_id"] in ids
    ]
    for line in samples:
        for file in json.loads(line)["files"].values():
            destination = root / file["path"]
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source / file["path"], destination)
    scene_bytes = "".join(scenes).encode()
    sample_bytes = "".join(samples).encode()
    (root / "scenes.jsonl").write_bytes(scene_bytes)
    (root / "samples.jsonl").write_bytes(sample_bytes)

    slots = sorted(json.loads(line)["generation"]["scene_index"] for line in scenes)
    split_counts = {split: {"scenes": 0, "samples": 0} for split in ("train", "validation", "test")}
    for line in scenes:
        scene = json.loads(line)
        split_counts[scene["split"]]["scenes"] += 1
        split_counts[scene["split"]]["samples"] += scene["accepted_sample_count"]
    accepted_samples = sum(count["samples"] for count in split_counts.values())
    manifest["splits"]["counts"] = split_counts
    manifest["counts"] = {
        "requested_scenes": len(slots),
        "attempted_scenes": len(slots) + rejected,
        "accepted_scenes": len(slots),
        "rejected_scenes": rejected,
        "failed_scenes": 0,
        "accepted_samples": accepted_samples,
        "rejection_reasons": {"visibility": rejected} if rejected else {},
    }
    manifest["integrity"]["indexes"] = {
        "scenes": _digest(scene_bytes),
        "samples": _digest(sample_bytes),
    }
    manifest["slice"] = {
        "index": index,
        "count": count,
        "world": world or f"world-{index}",
        "slots": slots,
    }
    (root / "dataset.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return root


@pytest.fixture(scope="session")
def make_slice() -> Callable[..., Path]:
    """Cuts a slice out of a complete dataset, as one of several instances would have
    generated it (M12-1)."""
    return _make_slice
