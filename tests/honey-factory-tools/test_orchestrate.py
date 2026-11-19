import json
import shutil
from collections.abc import Callable
from pathlib import Path
from typing import Any

import pytest

from honey_factory_tools import dataset
from honey_factory_tools.orchestrate import GenerateError, Job, generate
from honey_factory_tools.schemas import Schemas


class _Done:
    def __init__(self, code: int) -> None:
        self.code = code

    def wait(self) -> int:
        return self.code


class FakeLauncher:
    """Plays the game instances: each job produces its slice of the mini dataset, or fails."""

    def __init__(self, source: Path, make_slice: Callable[..., Path], fail: set[int]) -> None:
        self.source = source
        self.make_slice = make_slice
        self.fail = fail
        self.jobs: list[Job] = []

    def start(self, job: Job) -> _Done:
        self.jobs.append(job)
        config: dict[str, Any] = json.loads(job.config.read_text(encoding="utf-8"))
        part: dict[str, Any] | None = config["output"].get("slice")
        index = 0 if part is None else part["index"]
        if index in self.fail:
            return _Done(1)
        root = Path(config["output"]["root"])
        if part is None:
            # One instance writes the whole dataset, as a single run does.
            dataset_id = json.loads((self.source / "dataset.json").read_text(encoding="utf-8"))
            shutil.copytree(self.source, root / dataset_id["dataset_id"])
            return _Done(0)
        self.make_slice(
            self.source, root, index, part["count"], 1 if index == 0 else 0, part["world"]
        )
        return _Done(0)


@pytest.fixture
def config(tmp_path: Path) -> Path:
    path = tmp_path / "config.json"
    path.write_text(
        json.dumps(
            {
                "dataset": {"name": "honey-l1-fixture", "seed": 7, "difficulty": 1},
                "quotas": {"accepted_scenes": 4},
                "output": {"root": "/unused"},
            }
        ),
        encoding="utf-8",
    )
    return path


def test_instances_generate_slices_that_merge_into_one_valid_dataset(
    config: Path,
    tmp_path: Path,
    contract_dir: Path,
    make_slice: Callable[..., Path],
    schemas: Schemas,
) -> None:
    launcher = FakeLauncher(contract_dir / "dataset", make_slice, set())
    merged = generate(config, 2, tmp_path / "out", launcher, schemas)
    assert dataset.validate(merged, schemas).errors == []
    assert [job.world for job in launcher.jobs] == ["honey-l1-fixture-i0", "honey-l1-fixture-i1"]
    assert len({job.run_dir for job in launcher.jobs}) == 2, "every instance has its own run folder"
    for job in launcher.jobs:
        sliced = json.loads(job.config.read_text(encoding="utf-8"))["output"]["slice"]
        assert sliced["world"] == job.world
    manifest = json.loads((merged / "dataset.json").read_text(encoding="utf-8"))
    assert [world["world"] for world in manifest["worlds"]] == [
        "honey-l1-fixture-i0",
        "honey-l1-fixture-i1",
    ]


def test_a_failed_instance_fails_the_job_naming_its_slice_and_keeps_the_others(
    config: Path,
    tmp_path: Path,
    contract_dir: Path,
    make_slice: Callable[..., Path],
    schemas: Schemas,
) -> None:
    launcher = FakeLauncher(contract_dir / "dataset", make_slice, {1})
    with pytest.raises(GenerateError, match="slice 1"):
        generate(config, 2, tmp_path / "out", launcher, schemas)
    kept = list((tmp_path / "out" / "slices").glob("*.slice-0-of-2"))
    assert len(kept) == 1, "the finished slice stays on disk"
    assert not list((tmp_path / "out").glob("honey-l1-fixture-*[0-9a-f]")), "no merged dataset"


def test_one_instance_writes_the_dataset_without_merging(
    config: Path,
    tmp_path: Path,
    contract_dir: Path,
    make_slice: Callable[..., Path],
    schemas: Schemas,
) -> None:
    launcher = FakeLauncher(contract_dir / "dataset", make_slice, set())
    result = generate(config, 1, tmp_path / "out", launcher, schemas)
    assert result.parent == tmp_path / "out"
    assert "slice" not in json.loads(launcher.jobs[0].config.read_text(encoding="utf-8"))["output"]
    assert dataset.validate(result, schemas).errors == []
