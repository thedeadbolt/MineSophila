import json
from pathlib import Path

import pytest

from honey_factory_tools.cli import main
from honey_factory_tools.clip_report import report
from honey_factory_tools.schemas import Schemas


def test_report_counts_the_fixture(contract_dir: Path, schemas: Schemas) -> None:
    result = report([contract_dir / "clips"], schemas)
    dataset = result["datasets"][0]
    assert dataset["clips"] == 4
    assert dataset["frames"] == 52
    assert dataset["types"] == {"approach": 2, "no_honey": 2}
    assert dataset["fallbacks"] == {}
    assert dataset["approach"] == {
        "clips": 2,
        "reachable": 2,
        "reached": 0,
        "without_drift": 2,
        "reached_without_drift": 0,
    }
    assert dataset["realizability_violations"] == 0
    # The re-acquire clip remembers the honey for one tick; the no-honey clips search.
    assert dataset["ticks_by_mode"] == {"track": 7, "reacquire": 1, "search": 8}
    assert dataset["search_coverage"]["4"] == 0.4
    assert dataset["statuses"]["absent"] == 26
    assert dataset["video_bytes"] > 0
    assert dataset["raw_ratio"] > 1.0
    assert result["totals"]["clips"] == 4


def test_report_counts_a_realizability_violation(
    contract_dir: Path, schemas: Schemas, tmp_path: Path
) -> None:
    import shutil

    copy = tmp_path / "clips"
    shutil.copytree(contract_dir / "clips", copy)
    clip = json.loads((copy / "clips.jsonl").read_text(encoding="utf-8").splitlines()[2])
    frames = copy / clip["files"]["frames"]["path"]
    lines = frames.read_text(encoding="utf-8").splitlines()
    record = json.loads(lines[12])
    record["tick_labels"]["belief"]["source"] = "seen"
    lines[12] = json.dumps(record)
    frames.write_text("\n".join(lines) + "\n", encoding="utf-8")
    assert report([copy], schemas)["datasets"][0]["realizability_violations"] == 1


def test_cli_writes_the_report(
    contract_dir: Path, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    output = tmp_path / "clip-report.json"
    assert main(["clip-report", "--output", str(output), str(contract_dir / "clips")]) == 0
    written = json.loads(output.read_text(encoding="utf-8"))
    assert written["totals"]["clips"] == 4
    out = capsys.readouterr().out
    assert "approach reached" in out
    assert "reach approach 0/2; misses timeout 2" in out


def test_report_counts_ticks_by_driver(contract_dir: Path, schemas: Schemas) -> None:
    from honey_factory_tools import clip_report

    result = clip_report.report([contract_dir / "clips"], schemas)
    assert result["datasets"][0]["ticks_by_driver"] == {
        "teacher": 16,
        "teacher_drift": 0,
        "script": 0,
    }


def test_report_counts_guarded_ticks(contract_dir: Path, schemas: Schemas) -> None:
    from honey_factory_tools import clip_report

    result = clip_report.report([contract_dir / "clips"], schemas)
    assert result["datasets"][0]["guarded_ticks"] == 0
    assert result["totals"]["guarded_ticks"] == 0


def test_report_counts_reach_misses_and_falls(contract_dir: Path, schemas: Schemas) -> None:
    from honey_factory_tools import clip_report

    result = clip_report.report([contract_dir / "clips"], schemas)
    dataset = result["datasets"][0]
    assert dataset["reach"] == {"approach": {"clips": 2, "reachable": 2, "reached": 0}}
    assert dataset["miss_reasons"] == {"timeout": 2}
    assert dataset["falls"] == 0
    assert result["totals"]["teacher_failures"] == 0
    assert result["totals"]["falls"] == 0


def test_reach_leaves_out_clips_with_no_way_to_the_honey(
    contract_dir: Path, schemas: Schemas, tmp_path: Path
) -> None:
    import shutil

    copy = tmp_path / "clips"
    shutil.copytree(contract_dir / "clips", copy)
    index = copy / "clips.jsonl"
    clips = [json.loads(line) for line in index.read_text(encoding="utf-8").splitlines()]
    first = next(clip for clip in clips if clip["clip_type"] == "approach")
    first["summary"]["miss_reason"] = "unreachable"
    index.write_text("".join(json.dumps(clip) + "\n" for clip in clips), encoding="utf-8")
    dataset = report([copy], schemas)["datasets"][0]
    assert dataset["approach"]["reachable"] == 1
    assert dataset["reach"]["approach"] == {"clips": 2, "reachable": 1, "reached": 0}


def test_gate_reach_leaves_out_fallbacks(
    contract_dir: Path, schemas: Schemas, tmp_path: Path
) -> None:
    # A detour or search with no qualifying start falls back to approach from a draw that may
    # hide the honey, so the teacher searches first: not the planned approach the gate measures.
    import shutil

    copy = tmp_path / "clips"
    shutil.copytree(contract_dir / "clips", copy)
    index = copy / "clips.jsonl"
    clips = [json.loads(line) for line in index.read_text(encoding="utf-8").splitlines()]
    first = next(clip for clip in clips if clip["clip_type"] == "approach")
    first["requested_type"] = "detour"
    index.write_text("".join(json.dumps(clip) + "\n" for clip in clips), encoding="utf-8")
    dataset = report([copy], schemas)["datasets"][0]
    assert dataset["fallbacks"] == {"detour->approach": 1}
    assert dataset["approach"]["clips"] == 1
    assert dataset["reach"]["approach"]["clips"] == 2
