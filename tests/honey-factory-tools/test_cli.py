import json
from pathlib import Path

import pytest

from honey_factory_tools.cli import main


def test_accepts_valid_files(
    contract_dir: Path, schema_dir: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    samples = str(contract_dir / "dataset" / "samples.jsonl")
    assert main(["validate", "--schemas", str(schema_dir), "--kind", "sample", samples]) == 0
    assert "0 error(s)" in capsys.readouterr().err


def test_reports_each_invalid_record(
    tmp_path: Path, contract_dir: Path, schema_dir: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    record = json.loads((contract_dir / "valid" / "scene-present.json").read_text())
    record["difficulty"] = 9
    bad = tmp_path / "scenes.jsonl"
    bad.write_text(json.dumps(record) + "\n" + "not json\n")
    assert main(["validate", "--schemas", str(schema_dir), "--kind", "scene", str(bad)]) == 1
    output = capsys.readouterr().out
    assert f"{bad}:1:/difficulty" in output
    assert f"{bad}:2: invalid JSON" in output


def test_rejects_unterminated_jsonl(
    tmp_path: Path, schema_dir: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    bad = tmp_path / "samples.jsonl"
    bad.write_text("{}")
    assert main(["validate", "--schemas", str(schema_dir), "--kind", "sample", str(bad)]) == 1
    assert "must end with" in capsys.readouterr().out


def test_missing_schemas_exit_with_usage_error(tmp_path: Path) -> None:
    missing = str(tmp_path / "none")
    assert main(["validate", "--schemas", missing, "--kind", "sample", "x.json"]) == 2
