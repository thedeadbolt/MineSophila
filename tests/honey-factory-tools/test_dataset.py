import hashlib
import io
import json
import shutil
from collections.abc import Callable
from pathlib import Path
from typing import Any

import pytest
from PIL import Image

from honey_factory_tools import dataset, images, records
from honey_factory_tools.cli import main
from honey_factory_tools.patch import Operation, apply
from honey_factory_tools.schemas import Schemas

FIXTURE_DIR = Path(__file__).resolve().parents[1] / "integration/fixtures/honey-factory/1.0"
DATASET_CASES: list[dict[str, Any]] = json.loads((FIXTURE_DIR / "dataset-cases.json").read_text())[
    "cases"
]
RECORD_CASES: list[dict[str, Any]] = json.loads((FIXTURE_DIR / "record-cases.json").read_text())[
    "cases"
]


@pytest.fixture
def copy(tmp_path: Path, contract_dir: Path) -> Path:
    """A writable copy of the shared mini dataset, images included."""
    target = tmp_path / "dataset"
    shutil.copytree(contract_dir / "dataset", target)
    return target


def _lines(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]


def _write_lines(path: Path, lines: list[dict[str, Any]]) -> None:
    path.write_text("".join(json.dumps(line) + "\n" for line in lines), encoding="utf-8")


def _png(mode: str, size: tuple[int, int], data: bytes, **options: Any) -> bytes:
    buffer = io.BytesIO()
    Image.frombytes(mode, size, data).save(buffer, format="PNG", **options)
    return buffer.getvalue()


def test_shared_dataset_passes(schemas: Schemas, contract_dir: Path) -> None:
    report = dataset.validate(contract_dir / "dataset", schemas)
    assert report.errors == []
    assert (report.lifecycle, len(report.scenes), len(report.samples)) == ("complete", 4, 8)


@pytest.mark.parametrize("case", DATASET_CASES, ids=lambda case: case["name"])
def test_shared_dataset_cases(schemas: Schemas, copy: Path, case: dict[str, Any]) -> None:
    files = {name: _lines(copy / name) for name in ("scenes.jsonl", "samples.jsonl")}
    for edit in case["edits"]:
        lines = files[edit["file"]]
        if "append_copy_of" in edit:
            lines.append(json.loads(json.dumps(lines[edit["append_copy_of"]])))
        elif "remove_line" in edit:
            del lines[edit["remove_line"]]
        else:
            operations: list[Operation] = edit["patch"]
            lines[edit["line"]] = apply(lines[edit["line"]], operations)  # type: ignore[assignment]
    for name, lines in files.items():
        _write_lines(copy / name, lines)
    errors = dataset.validate(copy, schemas).errors
    assert any(case["expect_error"] in error for error in errors), errors
    assert any("integrity.indexes" in error for error in errors), "edited indexes no longer match"


@pytest.mark.parametrize(
    "case", [c for c in RECORD_CASES if c["expect"] != "schema"], ids=lambda case: case["name"]
)
def test_shared_semantic_record_cases(
    contract_dir: Path, case: dict[str, Any], load_json: Callable[[Path], Any]
) -> None:
    operations: list[Operation] = case["patch"]
    record = apply(load_json(contract_dir / case["base"]), operations)
    rules = {
        "dataset": records.manifest_errors,
        "scene": records.scene_errors,
        "sample": records.sample_errors,
    }
    errors = rules[case["kind"]](record)  # type: ignore[arg-type]
    if case["expect"] == "valid":
        assert errors == []
    else:
        assert errors, "the outside validator accepted a semantically invalid record"


def test_reports_damaged_missing_and_unreferenced_files(schemas: Schemas, copy: Path) -> None:
    samples = _lines(copy / "samples.jsonl")
    first, second = samples[0], samples[1]
    mask = copy / first["files"]["mask"]["path"]
    data = bytearray(mask.read_bytes())
    data[-20] ^= 0x01
    mask.write_bytes(bytes(data))
    (copy / second["files"]["rgb"]["path"]).unlink()
    (copy / "rgb" / "train" / "stray.png").write_bytes(b"not an image")
    (copy / "masks" / "train" / ".s000002-v00.png.tmp").write_bytes(b"partial")

    errors = dataset.validate(copy, schemas).errors
    assert any(first["sample_id"] in e and "SHA-256" in e for e in errors), errors
    assert any(second["sample_id"] in e and "missing" in e for e in errors), errors
    assert "rgb/train/stray.png: no sample references this file" in errors
    assert any(".s000002-v00.png.tmp" in e for e in errors), "leftover temporary files are reported"


def test_checks_mask_values_and_statistics(schemas: Schemas, copy: Path) -> None:
    samples = _lines(copy / "samples.jsonl")
    present = next(s for s in samples if s["target"]["target_present"])
    absent = next(s for s in samples if not s["target"]["target_present"])
    width, height = present["files"]["mask"]["width"], present["files"]["mask"]["height"]

    grey = _png("L", (width, height), bytes([128]) * (width * height))
    shifted = bytearray(width * height)
    shifted[0] = 255
    for sample, png in ((absent, grey), (present, _png("L", (width, height), bytes(shifted)))):
        path = copy / sample["files"]["mask"]["path"]
        path.write_bytes(png)
        sample["files"]["mask"]["bytes"] = len(png)
        sample["files"]["mask"]["sha256"] = hashlib.sha256(png).hexdigest()
    _write_lines(copy / "samples.jsonl", samples)

    errors = dataset.validate(copy, schemas).errors
    assert any(absent["sample_id"] in e and "not 0 or 255" in e for e in errors), errors
    assert any(present["sample_id"] in e and "differ from the saved mask" in e for e in errors), (
        errors
    )


def test_a_generating_dataset_is_reported_unless_allowed(
    schemas: Schemas, copy: Path, contract_dir: Path
) -> None:
    shutil.copy(contract_dir / "valid" / "dataset-generating.json", copy / "dataset.json")
    errors = dataset.validate(copy, schemas).errors
    assert any("lifecycle is generating" in error for error in errors)
    allowed = dataset.validate(copy, schemas, allow_generating=True).errors
    assert not any("lifecycle is generating" in error for error in allowed)


def test_decode_accepts_only_8_bit_grey_and_rgb() -> None:
    rgb = images.decode(_png("RGB", (3, 2), bytes(range(18))))
    assert (rgb.width, rgb.height, rgb.channels, rgb.data) == (3, 2, 3, bytes(range(18)))
    grey = images.decode(_png("L", (2, 2), bytes([0, 255, 7, 9])))
    assert (grey.channels, grey.data) == (1, bytes([0, 255, 7, 9]))
    with pytest.raises(images.ImageError, match="bit depth 16"):
        images.decode(_png("I;16", (2, 2), bytes(8)))
    with pytest.raises(images.ImageError, match="colour type 3"):
        images.decode(_png("P", (2, 2), bytes(4)))
    with pytest.raises(images.ImageError, match="not a PNG"):
        images.decode(b"GIF89a")


def test_mask_statistics_match_the_contract() -> None:
    mask = bytearray(6 * 4)
    for x, y in ((1, 1), (2, 1), (4, 2)):
        mask[y * 6 + x] = 255
    statistics = images.mask_statistics(images.Image(6, 4, 1, bytes(mask)))
    assert statistics.positive_pixels == 3
    assert statistics.bbox_xywh == [1, 1, 4, 2]
    assert statistics.centroid_xy == [7 / 3 + 0.5, 4 / 3 + 0.5]
    assert images.mask_statistics(images.Image(2, 2, 1, bytes(4))).bbox_xywh is None


def test_cli_validates_a_dataset_directory(
    schema_dir: Path, copy: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    assert main(["validate-dataset", "--schemas", str(schema_dir), str(copy)]) == 0
    assert "0 error(s); lifecycle complete, 4 scenes, 8 samples" in capsys.readouterr().err
    (copy / "rgb" / "stray.png").write_bytes(b"")
    assert main(["validate-dataset", "--schemas", str(schema_dir), str(copy)]) == 1
    assert "rgb/stray.png" in capsys.readouterr().out
