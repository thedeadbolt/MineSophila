import json
from collections.abc import Callable
from pathlib import Path

import pytest

from honey_factory_tools import dataset
from honey_factory_tools.cli import main
from honey_factory_tools.merge import MergeError, merge
from honey_factory_tools.schemas import Schemas


@pytest.fixture
def slices(
    contract_dir: Path, tmp_path: Path, make_slice: Callable[..., Path]
) -> tuple[Path, list[Path]]:
    source = contract_dir / "dataset"
    parts = [
        make_slice(source, tmp_path / "slices", 0, 2, 1),
        make_slice(source, tmp_path / "slices", 1, 2, 0),
    ]
    return source, parts


def test_each_slice_validates_on_its_own(slices: tuple[Path, list[Path]], schemas: Schemas) -> None:
    for part in slices[1]:
        assert dataset.validate(part, schemas).errors == []


def test_merging_both_slices_gives_the_original_dataset(
    slices: tuple[Path, list[Path]], schemas: Schemas, tmp_path: Path
) -> None:
    source, parts = slices
    merged = merge(list(reversed(parts)), tmp_path / "merged", schemas)
    dataset_id = json.loads((source / "dataset.json").read_text(encoding="utf-8"))["dataset_id"]
    assert merged == tmp_path / "merged" / dataset_id
    assert dataset.validate(merged, schemas).errors == []
    for index in ("scenes.jsonl", "samples.jsonl"):
        assert (merged / index).read_bytes() == (source / index).read_bytes()
    expected = json.loads((source / "dataset.json").read_text(encoding="utf-8"))
    actual = json.loads((merged / "dataset.json").read_text(encoding="utf-8"))
    worlds = actual.pop("worlds")
    assert actual == expected
    assert worlds == [
        {"world": "world-0", "slots": [0, 2]},
        {"world": "world-1", "slots": [1, 3]},
    ]
    for part in parts:
        assert (part / "dataset.json").exists(), "merging leaves the slices in place"


def test_a_missing_slice_is_refused(
    slices: tuple[Path, list[Path]], schemas: Schemas, tmp_path: Path
) -> None:
    with pytest.raises(MergeError, match="slice 1"):
        merge(slices[1][:1], tmp_path / "merged", schemas)
    assert not (tmp_path / "merged").exists() or not any((tmp_path / "merged").iterdir())


def test_a_duplicate_slice_is_refused(
    slices: tuple[Path, list[Path]], schemas: Schemas, tmp_path: Path
) -> None:
    with pytest.raises(MergeError, match="twice"):
        merge([slices[1][0], slices[1][0]], tmp_path / "merged", schemas)
    assert not (tmp_path / "merged").exists() or not any((tmp_path / "merged").iterdir())


def test_a_slice_of_another_configuration_is_refused(
    slices: tuple[Path, list[Path]], schemas: Schemas, tmp_path: Path
) -> None:
    other = slices[1][1]
    manifest = json.loads((other / "dataset.json").read_text(encoding="utf-8"))
    manifest["dataset_id"] = "another-000000000000"
    (other / "dataset.json").write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(MergeError, match="configuration"):
        merge(slices[1], tmp_path / "merged", schemas)
    assert not (tmp_path / "merged").exists() or not any((tmp_path / "merged").iterdir())


def test_the_command_merges_and_reports_refusals(
    slices: tuple[Path, list[Path]], tmp_path: Path, schema_dir: Path
) -> None:
    parts = [str(part) for part in slices[1]]
    assert main(["merge-slices", "--schemas", str(schema_dir), str(tmp_path / "out"), *parts]) == 0
    assert (
        main(["merge-slices", "--schemas", str(schema_dir), str(tmp_path / "out2"), parts[0]]) == 1
    )
