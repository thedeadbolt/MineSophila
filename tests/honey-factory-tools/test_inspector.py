import hashlib
import json
import shutil
import threading
import urllib.error
import urllib.request
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pytest

from honey_factory_tools import selection
from honey_factory_tools.catalog import Catalog, IntegrityError
from honey_factory_tools.cli import main
from honey_factory_tools.inspector import Inspector, make_server, url
from honey_factory_tools.reviews import ReviewError, ReviewStore
from honey_factory_tools.schemas import Schemas


@pytest.fixture
def copy(tmp_path: Path, contract_dir: Path) -> Path:
    """A writable copy of the shared mini dataset, images included."""
    target = tmp_path / "dataset"
    shutil.copytree(contract_dir / "dataset", target)
    return target


@pytest.fixture
def catalog(copy: Path, schemas: Schemas) -> Catalog:
    return Catalog(copy, schemas)


def tree(root: Path) -> dict[str, str]:
    """SHA-256 and modification time of every file under ``root``."""
    return {
        path.relative_to(root).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
        + f"@{path.stat().st_mtime_ns}"
        for path in sorted(root.rglob("*"))
        if path.is_file()
    }


def sample(
    sample_id: str,
    status: str,
    pixels: int = 0,
    box: list[int] | None = None,
    ratio: float | None = None,
    split: str = "train",
) -> dict[str, Any]:
    return {
        "sample_id": sample_id,
        "scene_id": sample_id[:7],
        "split": split,
        "viewpoint_index": int(sample_id[-2:]),
        "files": {"mask": {"width": 100, "height": 50}},
        "target": {
            "target_present": status != "absent",
            "target_status": status,
            "visible_pixels": pixels,
            "visible_bbox_xywh": box,
            "visibility_ratio": ratio,
        },
    }


PERSPECTIVE = {"capture": {"mode": "PERSPECTIVE_90", "width": 100, "height": 50}}


class TestSelection:
    def test_random_order_follows_the_documented_key_and_ignores_file_order(
        self, catalog: Catalog
    ) -> None:
        chosen = selection.Selection(seed=7)
        forward = selection.select(catalog.manifest, catalog.samples, chosen)
        backward = selection.select(catalog.manifest, list(reversed(catalog.samples)), chosen)
        assert [s["sample_id"] for s in forward] == [s["sample_id"] for s in backward]
        expected = sorted(
            catalog.by_sample,
            key=lambda i: hashlib.sha256(
                f"honey-factory/review-selection-v1/7/{i}".encode()
            ).hexdigest(),
        )
        assert [s["sample_id"] for s in forward] == expected
        other = selection.select(catalog.manifest, catalog.samples, selection.Selection(seed=8))
        assert [s["sample_id"] for s in other] != expected
        three = selection.Selection(seed=7, count=3)
        assert selection.select(catalog.manifest, catalog.samples, three) == forward[:3]

    def test_filters_are_exact_and_ranges_inclusive(self, catalog: Catalog) -> None:
        def ids(**filters: Any) -> set[str]:
            chosen = selection.Selection(filters=selection.Filters(**filters))
            return {
                s["sample_id"] for s in selection.select(catalog.manifest, catalog.samples, chosen)
            }

        present = {i for i, s in catalog.by_sample.items() if s["target"]["target_present"]}
        assert ids(present=True) == present
        assert ids(status="absent") == set(catalog.by_sample) - present
        assert ids(min_ratio=1.0, max_ratio=1.0) == present, "absent targets have no ratio"
        assert ids(min_pixels=200) == {
            i for i, s in catalog.by_sample.items() if s["target"]["visible_pixels"] >= 200
        }
        assert ids(scene_id="s000001") == {"s000001-v00", "s000001-v01"}
        assert ids(sample_id="s000003-v01") == {"s000003-v01"}
        assert ids(split="validation", viewpoint=0) == {
            i
            for i, s in catalog.by_sample.items()
            if s["split"] == "validation" and s["viewpoint_index"] == 0
        }
        assert ids(min_distance=7.5) == {
            i for i, s in catalog.by_sample.items() if s["target"].get("distance_blocks", 0) >= 7.5
        }

    def test_strata_cover_the_review_cases(self) -> None:
        cases = [
            (sample("s000000-v00", "absent"), "absent"),
            (sample("s000000-v01", "fully_occluded"), "fully_occluded"),
            (sample("s000000-v02", "out_of_view"), "not_visible"),
            (sample("s000000-v03", "fully_visible", 400, [0, 10, 20, 20], 1.0), "edge_or_seam"),
            (sample("s000000-v04", "fully_visible", 400, [80, 30, 20, 20], 1.0), "edge_or_seam"),
            (
                sample("s000000-v05", "partially_visible", 30, [40, 20, 6, 5], 0.5),
                "low_pixel_count",
            ),
            (
                sample("s000000-v06", "partially_visible", 300, [40, 20, 20, 15], 0.5),
                "partially_visible",
            ),
            (sample("s000000-v07", "fully_visible", 300, [40, 20, 20, 15], 1.0), "fully_visible"),
        ]
        for record, expected in cases:
            assert selection.stratum(record, [], 64) == expected, record["sample_id"]
        assert selection.stratum(cases[6][0], [50.0], 64) == "edge_or_seam", "box crosses a seam"

    def test_panorama_seams_sit_at_the_side_face_boundaries(self) -> None:
        manifest = {
            "capture": {
                "mode": "PANORAMA_310",
                "width": 3100,
                "projection": {"longitude_min_degrees": -155.0, "longitude_max_degrees": 155.0},
            }
        }
        assert selection.seam_columns(manifest) == [200.0, 1100.0, 2000.0, 2900.0]
        assert selection.seam_columns(PERSPECTIVE) == []

    def test_stratified_selection_takes_each_stratum_in_turn(self) -> None:
        records = [sample(f"s00000{n}-v0{v}", "absent") for n in range(3) for v in range(3)]
        records += [
            sample(f"s00001{n}-v00", "fully_visible", 300, [40, 20, 20, 15], 1.0) for n in range(3)
        ]
        records.append(sample("s000020-v00", "partially_visible", 10, [40, 20, 2, 5], 0.2))
        chosen = selection.Selection(mode="stratified", seed=3, count=5)
        picked = [
            selection.stratum(r, [], 64) for r in selection.select(PERSPECTIVE, records, chosen)
        ]
        assert picked[:3] == ["absent", "low_pixel_count", "fully_visible"]
        assert picked[3:] == ["absent", "fully_visible"]
        again = selection.select(PERSPECTIVE, list(reversed(records)), chosen)
        assert [r["sample_id"] for r in again] == [
            r["sample_id"] for r in selection.select(PERSPECTIVE, records, chosen)
        ]

    def test_scene_order_follows_scene_and_viewpoint(self, catalog: Catalog) -> None:
        shuffled = selection.select(catalog.manifest, catalog.samples, selection.Selection(seed=9))
        for seed in (0, 9):
            chosen = selection.Selection(mode="scene", seed=seed)
            ordered = selection.select(catalog.manifest, shuffled, chosen)
            assert [s["sample_id"] for s in ordered] == sorted(catalog.by_sample)
        filtered = selection.Selection(
            mode="scene", count=3, filters=selection.Filters(present=True)
        )
        picked = selection.select(catalog.manifest, catalog.samples, filtered)
        assert [s["sample_id"] for s in picked] == sorted(
            i for i, s in catalog.by_sample.items() if s["target"]["target_present"]
        )[:3]

    def test_selection_identity_is_canonical(self) -> None:
        one = selection.Selection(
            mode="stratified", seed=1, count=4, filters=selection.Filters(split="test")
        )
        two = selection.Selection(
            mode="stratified", seed=1, count=4, filters=selection.Filters(split="test")
        )
        assert one.to_json() == two.to_json()
        assert one.to_json()["sha256"] != selection.Selection(seed=1, count=4).to_json()["sha256"]
        with pytest.raises(ValueError, match="mode must be one of"):
            selection.Selection(mode="first")


class TestCatalog:
    def test_indexes_scenes_and_their_viewpoints(self, catalog: Catalog) -> None:
        assert len(catalog.scenes) == 4
        assert len(catalog.samples) == 8
        for scene_id, samples in catalog.scene_samples.items():
            assert [s["scene_id"] for s in samples] == [scene_id] * len(samples)
            assert [s["viewpoint_index"] for s in samples] == sorted(
                s["viewpoint_index"] for s in samples
            )
        assert catalog.index_errors == []

    def test_reports_damaged_and_missing_images_instead_of_serving_them(
        self, copy: Path, schemas: Schemas
    ) -> None:
        catalog = Catalog(copy, schemas)
        first, second = catalog.samples[0], catalog.samples[1]
        mask = copy / first["files"]["mask"]["path"]
        mask.write_bytes(mask.read_bytes()[:-8])
        (copy / second["files"]["rgb"]["path"]).unlink()
        assert catalog.integrity(first["sample_id"]) == "unchecked"
        assert any("SHA-256" in e for e in catalog.sample_errors(first["sample_id"]))
        assert catalog.integrity(first["sample_id"]) == "error"
        with pytest.raises(IntegrityError, match="missing"):
            catalog.image(second["sample_id"], "mask")
        third = catalog.samples[2]["sample_id"]
        assert catalog.image(third, "mask").startswith(b"\x89PNG")
        assert catalog.integrity(third) == "ok"

    def test_background_validation_attributes_errors(self, copy: Path, schemas: Schemas) -> None:
        catalog = Catalog(copy, schemas)
        victim = catalog.samples[3]
        (copy / victim["files"]["rgb"]["path"]).write_bytes(b"not a png")
        (copy / "rgb" / "train" / "stray.png").write_bytes(b"stray")
        done = threading.Event()
        catalog.start_validation(done.set)
        assert done.wait(30)
        assert catalog.validation.state == "done"
        assert victim["sample_id"] in catalog.validation.subject_errors
        assert any("stray.png" in e for e in catalog.validation.dataset_errors)
        assert catalog.integrity(catalog.samples[4]["sample_id"]) == "ok"
        assert catalog.integrity(victim["sample_id"]) == "error"

    def test_refuses_a_directory_without_a_manifest(self, tmp_path: Path, schemas: Schemas) -> None:
        with pytest.raises(ValueError, match="no readable manifest"):
            Catalog(tmp_path, schemas)


class TestReviews:
    def test_records_beside_the_dataset_and_changes_nothing_in_it(
        self, copy: Path, catalog: Catalog, tmp_path: Path
    ) -> None:
        before = tree(copy)
        store = ReviewStore(tmp_path / "reviews", catalog, reviewer="auditor")
        first = store.record("sample", "s000001-v00", "fail", "mask_misaligned", "shifted left")
        store.record("scene", "s000002", "uncertain", "target_ambiguous")
        later = store.record("sample", "s000001-v00", "pass", "ok", selection={"sha256": "x"})
        assert tree(copy) == before
        assert (
            store.path
            == (
                tmp_path / "reviews" / catalog.dataset_id / f"{catalog.manifest_sha256[:16]}.jsonl"
            ).resolve()
        )
        manifest = (copy / "dataset.json").read_bytes()
        assert first["manifest_sha256"] == hashlib.sha256(manifest).hexdigest()
        assert first["subject"] == {"kind": "sample", "id": "s000001-v00", "scene_id": "s000001"}
        assert first["reviewer"] == "auditor"
        assert first["format"] == "honey-factory-review-v1"
        lines = store.path.read_text(encoding="utf-8").splitlines()
        assert [json.loads(line)["outcome"] for line in lines] == ["fail", "uncertain", "pass"]
        assert store.latest()[("sample", "s000001-v00")] == later
        summary = store.summary()
        assert summary.reviews == 3
        assert summary.latest == {"sample": {"pass": 1}, "scene": {"uncertain": 1}}
        assert summary.uncertain == ["scene s000002"]

    def test_rejects_invalid_reviews(self, catalog: Catalog, tmp_path: Path) -> None:
        store = ReviewStore(tmp_path / "reviews", catalog)
        for args in (
            ("sample", "s000001-v00", "maybe", "ok"),
            ("sample", "s000001-v00", "pass", "looks fine"),
            ("sample", "s000001-v00", "fail", "ok"),
            ("sample", "s000099-v00", "pass", "ok"),
            ("scene", "s000099", "pass", "ok"),
            ("viewpoint", "s000001-v00", "pass", "ok"),
        ):
            with pytest.raises(ReviewError):
                store.record(*args)
        assert not store.path.exists()

    def test_refuses_a_root_inside_the_dataset(self, copy: Path, catalog: Catalog) -> None:
        for root in (copy, copy / "reviews"):
            with pytest.raises(ReviewError, match="inside the dataset"):
                ReviewStore(root, catalog)

    def test_refuses_once_the_manifest_changes(
        self, copy: Path, catalog: Catalog, tmp_path: Path
    ) -> None:
        store = ReviewStore(tmp_path / "reviews", catalog)
        manifest = copy / "dataset.json"
        manifest.write_bytes(manifest.read_bytes() + b"\n")
        with pytest.raises(ReviewError, match="changed"):
            store.record("sample", "s000001-v00", "pass", "ok")


class Client:
    def __init__(self, base: str, token: str) -> None:
        self.base = base
        self.token = token

    def get(self, path: str, token: bool = True, host: str | None = None) -> tuple[int, Any]:
        headers = {"X-Inspector-Token": self.token} if token else {}
        if host:
            headers["Host"] = host
        return self._send(urllib.request.Request(self.base + path, headers=headers))

    def post(self, path: str, body: Any) -> tuple[int, Any]:
        data = json.dumps(body).encode()
        headers = {"X-Inspector-Token": self.token, "Content-Type": "application/json"}
        return self._send(urllib.request.Request(self.base + path, data, headers, method="POST"))

    def _send(self, request: urllib.request.Request) -> tuple[int, Any]:
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                status, kind, body = (
                    response.status,
                    response.headers["Content-Type"],
                    response.read(),
                )
        except urllib.error.HTTPError as error:
            status, kind, body = error.code, error.headers["Content-Type"], error.read()
        if kind == "application/json":
            return status, json.loads(body)
        return status, body


def serve(inspector: Inspector) -> Iterator[Client]:
    server = make_server(inspector)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield Client(url(server).rstrip("/"), inspector.token)
    finally:
        server.shutdown()
        server.server_close()


@pytest.fixture
def client(catalog: Catalog, tmp_path: Path) -> Iterator[Client]:
    yield from serve(Inspector(catalog, ReviewStore(tmp_path / "reviews", catalog, "tester")))


class TestInspectorServer:
    def test_serves_the_page_with_its_token_and_guards_the_api(self, client: Client) -> None:
        status, page = client.get("/", token=False)
        assert status == 200
        assert client.token.encode() in page
        assert client.get("/static/app.js", token=False)[0] == 200
        assert client.get("/api/dataset", token=False)[0] == 403
        assert client.get("/api/dataset", host="evil.example:80")[0] == 403
        assert client.get("/api/nothing")[0] == 404

    def test_reports_the_dataset_and_its_validation(self, client: Client, catalog: Catalog) -> None:
        status, dataset = client.get("/api/dataset")
        assert status == 200
        assert dataset["dataset_id"] == catalog.dataset_id
        assert dataset["manifest_sha256"] == catalog.manifest_sha256
        assert (dataset["scene_count"], dataset["sample_count"]) == (4, 8)
        assert dataset["validation"]["state"] == "pending"
        assert "edge_or_seam" in dataset["options"]["strata"]

    def test_selections_are_reproducible_and_filterable(self, client: Client) -> None:
        _, first = client.get("/api/samples?mode=stratified&seed=5&count=4")
        _, again = client.get("/api/samples?mode=stratified&seed=5&count=4")
        assert first == again
        assert first["total"] == 4
        assert first["selection"]["mode"] == "stratified"
        _, split = client.get("/api/samples?split=validation&status=fully_visible")
        assert all(
            s["split"] == "validation" and s["target_status"] == "fully_visible"
            for s in split["samples"]
        )
        assert client.get("/api/samples?seed=x")[0] == 400

    def test_finds_a_sample_and_its_whole_scene(self, client: Client, catalog: Catalog) -> None:
        status, detail = client.get("/api/samples/s000002-v01")
        assert status == 200
        assert detail["sample"] == catalog.by_sample["s000002-v01"]
        assert detail["scene"]["scene_id"] == "s000002"
        assert detail["errors"] == []
        assert detail["replay_command"] == f"/honeyfactory_replay load s000002-v01 {catalog.root}"
        _, scene = client.get("/api/scenes/s000002")
        assert [s["sample_id"] for s in scene["samples"]] == ["s000002-v00", "s000002-v01"]
        assert scene["previous"] == "s000001"
        assert scene["next"] == "s000003"
        assert client.get("/api/samples/s000099-v00")[0] == 404

    def test_streams_only_intact_images(self, client: Client, copy: Path, catalog: Catalog) -> None:
        good = catalog.by_sample["s000000-v00"]
        status, body = client.get("/image/s000000-v00/mask")
        assert status == 200
        assert body == (copy / good["files"]["mask"]["path"]).read_bytes()
        bad = catalog.by_sample["s000001-v01"]
        (copy / bad["files"]["mask"]["path"]).write_bytes(b"\x89PNG broken")
        status, body = client.get("/image/s000001-v01/rgb")
        assert status == 409
        assert any("SHA-256" in e for e in body["errors"])
        _, detail = client.get("/api/samples/s000001-v01")
        assert detail["integrity"] == "error"
        assert detail["errors"]

    def test_records_reviews_without_touching_the_dataset(
        self, client: Client, copy: Path, tmp_path: Path
    ) -> None:
        before = tree(copy)
        status, review = client.post(
            "/api/reviews",
            {
                "kind": "scene",
                "id": "s000003",
                "outcome": "fail",
                "reason": "scene_invalid",
                "note": "x",
            },
        )
        assert status == 201
        assert review["reviewer"] == "tester"
        assert (
            client.post("/api/reviews", {"kind": "scene", "id": "s000003", "outcome": "no"})[0]
            == 400
        )
        _, scene = client.get("/api/scenes/s000003")
        assert [r["outcome"] for r in scene["reviews"]] == ["fail"]
        _, report = client.get("/api/reviews")
        assert report["summary"]["failed"] == ["scene s000003"]
        assert tree(copy) == before

    def test_refuses_reviews_when_disabled(self, catalog: Catalog) -> None:
        for read_only in serve(Inspector(catalog, None)):
            status, body = read_only.post(
                "/api/reviews",
                {"kind": "sample", "id": "s000000-v00", "outcome": "pass", "reason": "ok"},
            )
            assert status == 403
            assert "--reviews" in body["error"]
            assert read_only.get("/api/reviews")[1] == {"enabled": False}


class TestCli:
    def test_select_prints_a_reproducible_selection(
        self, copy: Path, schema_dir: Path, capsys: pytest.CaptureFixture[str]
    ) -> None:
        args = ["select", "--schemas", str(schema_dir), "--mode", "stratified", "--seed", "2"]
        assert main([*args, "--count", "3", "--json", str(copy)]) == 0
        first = json.loads(capsys.readouterr().out)
        assert main([*args, "--count", "3", "--json", str(copy)]) == 0
        assert json.loads(capsys.readouterr().out) == first
        assert len(first["samples"]) == 3
        assert first["selection"]["seed"] == 2
        assert main([*args, "--present", "false", str(copy)]) == 0
        lines = capsys.readouterr().out.splitlines()
        assert len(lines) == 4
        assert all("\tabsent\t" in line for line in lines)

    def test_reviews_summarizes_the_sidecar(
        self,
        copy: Path,
        schema_dir: Path,
        catalog: Catalog,
        tmp_path: Path,
        capsys: pytest.CaptureFixture[str],
    ) -> None:
        ReviewStore(tmp_path / "r", catalog).record(
            "sample", "s000000-v00", "fail", "render_artifact"
        )
        assert (
            main(
                [
                    "reviews",
                    "--schemas",
                    str(schema_dir),
                    "--reviews",
                    str(tmp_path / "r"),
                    str(copy),
                ]
            )
            == 0
        )
        out = capsys.readouterr().out
        assert "1 reviews" in out
        assert "fail: sample s000000-v00" in out

    def test_reports_unusable_directories(
        self, tmp_path: Path, schema_dir: Path, capsys: pytest.CaptureFixture[str]
    ) -> None:
        assert main(["inspect", "--schemas", str(schema_dir), "--no-browser", str(tmp_path)]) == 2
        assert "no readable manifest" in capsys.readouterr().err
