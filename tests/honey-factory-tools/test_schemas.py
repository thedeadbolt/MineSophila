import json
from collections.abc import Callable
from pathlib import Path
from typing import Any

import pytest

from honey_factory_tools import canonical
from honey_factory_tools.patch import Operation, apply
from honey_factory_tools.schemas import Kind, Schemas, find_schema_directory

CONFIGURATION_MEMBERS = ("difficulty", "target", "generator", "capture", "splits")


def _kind(path: Path) -> Kind:
    kind = path.name.split("-")[0]
    assert kind in ("dataset", "scene", "sample")
    return kind


def test_valid_fixtures_pass(
    schemas: Schemas, contract_dir: Path, load_json: Callable[[Path], Any]
) -> None:
    for path in sorted((contract_dir / "valid").glob("*.json")):
        assert schemas.errors(_kind(path), load_json(path)) == [], path.name


def test_shared_dataset_records_pass(
    schemas: Schemas, contract_dir: Path, load_json: Callable[[Path], Any]
) -> None:
    dataset = contract_dir / "dataset"
    assert schemas.errors("dataset", load_json(dataset / "dataset.json")) == []
    indexes: tuple[tuple[str, Kind], ...] = (("scenes.jsonl", "scene"), ("samples.jsonl", "sample"))
    for name, kind in indexes:
        lines = (dataset / name).read_text(encoding="utf-8").splitlines()
        for line in lines:
            assert schemas.errors(kind, canonical.loads(line)) == [], line[:80]


# Parametrization happens at collection, before fixtures exist.
FIXTURE_DIR = Path(__file__).resolve().parents[1] / "integration/fixtures/honey-factory/1.0"
CASES: list[dict[str, Any]] = json.loads((FIXTURE_DIR / "record-cases.json").read_text())["cases"]


@pytest.mark.parametrize("case", CASES, ids=lambda case: case["name"])
def test_shared_record_cases(
    schemas: Schemas, contract_dir: Path, case: dict[str, Any], load_json: Callable[[Path], Any]
) -> None:
    operations: list[Operation] = case["patch"]
    record = apply(load_json(contract_dir / case["base"]), operations)
    errors = schemas.errors(case["kind"], record)
    # Semantic cases need cross-member arithmetic the schemas deliberately leave to validators.
    if case["expect"] == "schema":
        assert errors, "the schema accepted an invalid record"
    else:
        assert errors == [], [str(error) for error in errors]


@pytest.mark.parametrize("name", ["dataset-generating.json", "dataset-complete.json"])
def test_python_recomputes_the_java_configuration_hash(
    contract_dir: Path, name: str, load_json: Callable[[Path], Any]
) -> None:
    manifest = load_json(contract_dir / "valid" / name)
    configuration = {member: manifest[member] for member in CONFIGURATION_MEMBERS}
    configuration["splits"] = {k: v for k, v in manifest["splits"].items() if k != "counts"}
    digest = canonical.sha256(configuration)
    assert digest == manifest["resolved_config_sha256"]
    assert manifest["dataset_id"] == f"{manifest['generator']['dataset_name']}-{digest[:12]}"


def test_finds_schemas_upwards(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("HONEY_FACTORY_SCHEMAS", raising=False)
    target = tmp_path / "schemas" / "honey-factory" / "1.0"
    target.mkdir(parents=True)
    nested = tmp_path / "a" / "b"
    nested.mkdir(parents=True)
    assert find_schema_directory(start=nested) == target
    with pytest.raises(FileNotFoundError, match="HONEY_FACTORY_SCHEMAS"):
        find_schema_directory(start=Path(tmp_path.anchor))
