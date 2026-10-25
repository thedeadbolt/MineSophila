import json
import shutil
from pathlib import Path

import pytest

from honey_factory_tools import report
from honey_factory_tools.catalog import Catalog
from honey_factory_tools.cli import main
from honey_factory_tools.reviews import ReviewStore
from honey_factory_tools.schemas import Schemas


@pytest.fixture
def acceptance(tmp_path: Path, contract_dir: Path) -> Path:
    """An acceptance folder with one pilot run holding the shared dataset and its metrics."""
    root = tmp_path / "acceptance"
    run = root / "pilot"
    shutil.copytree(contract_dir / "dataset", run / "dataset")
    manifest = json.loads((run / "dataset" / "dataset.json").read_text(encoding="utf-8"))
    total = {
        "count": 8,
        "seconds": 4.0,
        "mean_seconds": 0.5,
        "p50_seconds": 0.4,
        "p95_seconds": 0.9,
        "max_seconds": 1.0,
    }
    metrics = {
        "dataset_id": manifest["dataset_id"],
        "seconds": 60.0,
        "samples_per_hour": 480.0,
        "rejected_scenes": 1,
        "rejection_reasons": {"visibility": 1},
        "phases": {"reset": total},
        "captures": {"pose_wall": total},
        "memory": {"peak_rss_bytes": 3e9, "peak_device_vram_used_bytes": 2e9},
    }
    (run / "metrics").mkdir()
    (run / "metrics" / "dataset.json").write_text(json.dumps(metrics), encoding="utf-8")
    return root


def test_reports_validation_identity_and_capacity(acceptance: Path, schemas: Schemas) -> None:
    [only] = report.build(acceptance, schemas)
    assert only.passed
    assert only.validation_errors == []
    assert only.facts["quotas_met"]
    assert only.facts["samples"] == 8
    assert only.facts["rgb_png_bytes"]["count"] == 8
    assert only.facts["disk_bytes"] > 0
    assert only.metrics is not None
    text = report.to_markdown([only])
    assert "Overall: **passed** over 1 datasets." in text
    assert "480" in text
    assert only.facts["resolved_config_sha256"] in text
    assert "Rejections in" in text


def test_damaged_datasets_fail_the_report(acceptance: Path, schemas: Schemas) -> None:
    samples = acceptance / "pilot" / "dataset" / "samples.jsonl"
    first = json.loads(samples.read_text(encoding="utf-8").splitlines()[0])
    (acceptance / "pilot" / "dataset" / first["files"]["rgb"]["path"]).unlink()
    [only] = report.build(acceptance, schemas)
    assert not only.passed
    assert any("missing" in error for error in only.validation_errors)


def test_audit_needs_every_selected_sample_to_pass(
    acceptance: Path, schemas: Schemas, tmp_path: Path
) -> None:
    reviews = tmp_path / "reviews"
    [unreviewed] = report.build(acceptance, schemas, reviews, audit_seed=4, audit_count=6)
    assert unreviewed.audit is not None
    assert len(unreviewed.audit.samples) == 6
    assert len(unreviewed.audit.unreviewed) == 6
    assert not unreviewed.passed
    assert unreviewed.audit.coverage["statuses"]["selected"] == ["absent", "fully_visible"]

    store = ReviewStore(reviews, Catalog(acceptance / "pilot" / "dataset", schemas))
    for sample_id in unreviewed.audit.samples:
        store.record("sample", sample_id, "pass", "ok")
    [reviewed] = report.build(acceptance, schemas, reviews, audit_seed=4, audit_count=6)
    assert reviewed.audit is not None
    assert reviewed.audit.gaps == []
    assert reviewed.passed

    failed = reviewed.audit.samples[0]
    store.record("scene", failed[:7], "fail", "scene_invalid")
    [rejected] = report.build(acceptance, schemas, reviews, audit_seed=4, audit_count=6)
    assert rejected.audit is not None
    assert failed in rejected.audit.not_passed
    assert not rejected.passed


def test_distribution_uses_nearest_rank() -> None:
    assert report.distribution([5, 1, 3, 2, 4]) == {
        "count": 5,
        "mean": 3.0,
        "p50": 3,
        "p95": 5,
        "min": 1,
        "max": 5,
    }
    assert report.distribution([]) == {"count": 0}


def test_cli_writes_markdown_and_json(acceptance: Path, schema_dir: Path, tmp_path: Path) -> None:
    out = tmp_path / "report"
    assert (
        main(
            [
                "acceptance-report",
                "--schemas",
                str(schema_dir),
                "--output",
                str(out),
                str(acceptance),
            ]
        )
        == 0
    )
    assert (tmp_path / "report.md").read_text(encoding="utf-8").startswith("# Acceptance report")
    assert json.loads((tmp_path / "report.json").read_text(encoding="utf-8"))["passed"] is True
