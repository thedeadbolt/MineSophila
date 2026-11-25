"""The Eye and Brain workspaces (ADR 0029): fly-vision sampling, run reports and traces."""

import json
import math
import shutil
import threading
import urllib.error
import urllib.request
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import numpy as np
import pytest
from PIL import Image

from honey_factory_tools.brain import BrainError, Runs, Traces, family, load_trace, summary
from honey_factory_tools.catalog import Catalog
from honey_factory_tools.eye import NO_OMMATIDIUM, EyeError, EyeModel, check_capture, scaled
from honey_factory_tools.inspector import Inspector, make_server, url
from honey_factory_tools.schemas import Schemas


@pytest.fixture(scope="module")
def eye_model() -> EyeModel:
    return EyeModel()


class TestEye:
    def test_layout_describes_every_ommatidium_and_both_pigment_axes(
        self, eye_model: EyeModel
    ) -> None:
        layout = eye_model.layout()
        count = layout["count"]
        assert count > 1000
        for key in ("eye", "subtype", "hex_uv", "azimuth", "elevation"):
            assert len(layout[key]) == count
        assert set(layout["eye"]) == {"L", "R"}
        assert layout["channels"] == ["R1_6", "R7", "R8"]
        spectra = layout["spectra"]
        assert set(spectra["modes"]) == {"physical", "remapped"}
        wavelengths = spectra["wavelengths"]
        for curves in spectra["modes"].values():
            assert set(curves) == {"Rh1", "Rh3", "Rh4", "Rh5", "Rh6"}
            assert all(len(values) == len(wavelengths) for values in curves.values())
        # The remap moves Rh1's peak to longer wavelengths (ADR 0021).
        physical = wavelengths[int(np.argmax(spectra["modes"]["physical"]["Rh1"]))]
        remapped = wavelengths[int(np.argmax(spectra["modes"]["remapped"]["Rh1"]))]
        assert remapped > physical

    def test_a_uniform_frame_gives_uniform_receptors_and_full_honey_coverage(
        self, eye_model: EyeModel
    ) -> None:
        capture = (310, 160)
        rgb = Image.new("RGB", capture, (128, 128, 128))
        mask = Image.new("L", capture, 255)
        result = eye_model.sample(rgb, mask, capture)
        assert result["size"] == list(scaled(*capture))
        activations = np.array(result["activations"])
        valid = np.array(result["valid"], dtype=bool)
        assert activations.shape == (3, eye_model.layout()["count"])
        assert valid.any()
        r16 = activations[0, valid]
        assert r16.max() - r16.min() < 1e-3
        assert r16.min() > 0
        coverage = np.array(result["coverage"])[valid]
        assert coverage.min() > 0.99

    def test_a_dark_frame_with_no_mask_has_no_coverage(self, eye_model: EyeModel) -> None:
        capture = (310, 160)
        result = eye_model.sample(Image.new("RGB", capture), None, capture)
        assert result["coverage"] is None
        assert max(result["maxima"]) == 0

    def test_the_decode_map_points_every_covered_pixel_at_an_ommatidium(
        self, eye_model: EyeModel
    ) -> None:
        data = eye_model.decode_map(62, 32)
        index = np.frombuffer(data, dtype="<u2")
        assert index.size == 62 * 32
        covered = index[index != NO_OMMATIDIUM]
        assert covered.size > 0.9 * index.size
        assert covered.max() < eye_model.layout()["count"]
        assert eye_model.decode_map(62, 32) is data
        with pytest.raises(EyeError):
            eye_model.decode_map(4, 4)

    def test_only_panoramas_can_be_seen_through_the_eye(self) -> None:
        check_capture({"mode": "PANORAMA_310"})
        with pytest.raises(EyeError, match="PERSPECTIVE_90"):
            check_capture({"mode": "PERSPECTIVE_90"})


def report(bearing: float, occluded: float | None = None) -> dict[str, Any]:
    return {
        "format": "fly-brain-connectome-clip-report-v1",
        "git_revision": "abc123",
        "checkpoint_manifest_sha256": "f" * 64,
        "dataset": {"source_dataset_id": "occ-a-l3"},
        "model": {"compilation": {"neuron_count": 42}},
        "training": {"steps": 10, "loss_curve": [2.0, 1.0]},
        "test": {
            "mean_bearing_error_deg": bearing,
            "presence_balanced_accuracy": 0.8,
            "sample_count": 5,
        },
        "test_occluded": {"mean_bearing_error_deg": occluded, "sample_count": 2},
    }


@pytest.fixture
def runs_dir(tmp_path: Path) -> Path:
    root = tmp_path / "runs"
    root.mkdir()
    (root / "a.report.json").write_text(json.dumps(report(40.0, 50.0)))
    # Python's json writes NaN, which strict JSON clients cannot read.
    (root / "b.report.json").write_text(json.dumps(report(math.nan)))
    (root / "broken.report.json").write_text("{")
    (root / "a.checkpoint.npz").write_bytes(b"not a report")
    return root


class TestRuns:
    def test_lists_reports_with_their_shared_fields_and_flags_unreadable_ones(
        self, runs_dir: Path
    ) -> None:
        rows = {row["name"]: row for row in Runs(runs_dir).summaries()}
        assert set(rows) == {"a", "b", "broken"}
        a = rows["a"]
        assert a["family"] == "connectome-clip"
        assert a["neurons"] == 42
        assert a["splits"]["test"]["mean_bearing_error_deg"] == 40.0
        assert a["splits"]["test_occluded"]["mean_bearing_error_deg"] == 50.0
        assert rows["b"]["splits"]["test"]["mean_bearing_error_deg"] is None
        assert "error" in rows["broken"]

    def test_reads_one_report_and_refuses_paths(self, runs_dir: Path) -> None:
        runs = Runs(runs_dir)
        assert runs.report("a")["training"]["loss_curve"] == [2.0, 1.0]
        for name in ("../a", "missing", ".hidden"):
            with pytest.raises(BrainError):
                runs.report(name)
        with pytest.raises(BrainError):
            Runs(runs_dir / "nowhere")

    def test_finds_run_folders_report_json_one_or_two_levels_down(self, runs_dir: Path) -> None:
        # fly-brain's newer runs keep RUN/report.json, often under a runs/ folder.
        for folder in (runs_dir / "pf-p1v", runs_dir / "runs" / "pf-p2"):
            folder.mkdir(parents=True)
            (folder / "report.json").write_text(json.dumps(report(36.0)))
        (runs_dir / "runs" / "live-l3-p1v").mkdir()
        (runs_dir / "runs" / "live-l3-p1v" / "summary.json").write_text("{}")
        runs = Runs(runs_dir)
        assert set(runs.names()) == {"a", "b", "broken", "pf-p1v", "pf-p2"}
        assert runs.report("pf-p2")["test"]["mean_bearing_error_deg"] == 36.0

    def test_family_names_drop_the_shared_prefix_and_suffix(self) -> None:
        assert family("fly-brain-recurrent-report-v1") == "recurrent"
        assert family("fly-brain-clip-feature-probe-v1") == "clip-feature-probe"
        assert summary("x", {})["family"] == "unknown"


def write_trace(path: Path, clip_id: str, steps: int = 6, **header: Any) -> None:
    types = np.array(["EPG"] * 4 + ["TuBu01"] * 2 + ["R7"] * 70)
    stages = np.array(["compass"] * 4 + ["TuBu"] * 2 + ["photoreceptors"] * 70)
    angles = np.array([-90.0, 0.0, 90.0, 180.0] + [math.nan] * 72, dtype=np.float32)
    activity = np.arange(steps * types.size, dtype=np.float32).reshape(steps, types.size) / 1000
    head = {
        "format": "fly-brain-clip-trace-v1",
        "dataset_id": "d1",
        "clip_id": clip_id,
        "frames": [3 * k for k in range(steps)],
        **header,
    }
    np.savez(
        path,
        header=np.array(json.dumps(head)),
        activity=activity.astype(np.float16),
        cell_type=types,
        stage=stages,
        side=np.array(["L", "R"] * 38),
        pb_angle=angles,
        presence=np.ones(steps, dtype=np.float32),
        readout=np.zeros((steps, 4), dtype=np.float32),
        bump_phase=np.linspace(0, 1, steps, dtype=np.float32),
    )


class TestTraces:
    def test_indexes_traces_by_dataset_and_clip_and_skips_other_files(self, tmp_path: Path) -> None:
        write_trace(tmp_path / "one.npz", "s000001-c00")
        write_trace(tmp_path / "two.npz", "s000002-c00", dataset_id="other")
        np.savez(tmp_path / "checkpoint.npz", weights=np.zeros(3))
        traces = Traces(tmp_path)
        assert traces.clip_ids("d1") == ["s000001-c00"]
        assert traces.clip_ids("other") == ["s000002-c00"]
        assert traces.trace("d1", "missing") is None

    def test_summarises_activity_by_cell_type_in_flow_order(self, tmp_path: Path) -> None:
        write_trace(tmp_path / "one.npz", "s000001-c00")
        trace = load_trace(tmp_path / "one.npz")
        assert trace["steps"] == 6
        assert trace["frames"] == [0, 3, 6, 9, 12, 15]
        assert [g["cell_type"] for g in trace["groups"]] == ["R7", "TuBu01", "EPG"]
        r7 = trace["groups"][0]
        assert r7["count"] == 70
        assert "rows" not in r7  # too many neurons to send each one
        assert len(trace["groups"][2]["rows"]) == 4
        assert trace["compass"]["angle"] == [-90.0, 0.0, 90.0, 180.0]
        assert len(trace["compass"]["activity"][0]) == 6
        assert set(trace["series"]) == {"presence", "readout", "bump_phase"}

    def test_decoded_readout_arrays_pass_through(self, tmp_path: Path) -> None:
        path = tmp_path / "one.npz"
        write_trace(path, "s000001-c00")
        with np.load(path) as data:
            arrays = {name: data[name] for name in data.files}
        arrays["presence_prob"] = np.full(6, 0.25, dtype=np.float32)
        arrays["bearing_azimuth_deg"] = np.array([10, 20, np.nan, 30, 40, 50], dtype=np.float32)
        np.savez(path, **arrays)
        series = load_trace(path)["series"]
        assert series["presence_prob"] == [0.25] * 6
        assert series["bearing_azimuth_deg"][2] is None

    def test_bad_frame_lists_fall_back_to_one_frame_per_step(self, tmp_path: Path) -> None:
        write_trace(tmp_path / "one.npz", "s000001-c00", frames=[1, 2])
        assert load_trace(tmp_path / "one.npz")["frames"] == list(range(6))


class Client:
    def __init__(self, base: str, token: str) -> None:
        self.base = base
        self.token = token

    def get(self, path: str) -> tuple[int, Any]:
        request = urllib.request.Request(
            self.base + path, headers={"X-Inspector-Token": self.token}
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return response.status, json.loads(response.read())
        except urllib.error.HTTPError as error:
            return error.code, json.loads(error.read())


@pytest.fixture
def client(
    tmp_path: Path, contract_dir: Path, schemas: Schemas, runs_dir: Path
) -> Iterator[Client]:
    dataset = tmp_path / "dataset"
    shutil.copytree(contract_dir / "dataset", dataset)
    catalog = Catalog(dataset, schemas)
    traces = tmp_path / "traces"
    traces.mkdir()
    write_trace(traces / "t.npz", "s000001-c00", dataset_id=catalog.dataset_id)
    inspector = Inspector(catalog, None, eye=EyeModel(), runs=Runs(runs_dir), traces=Traces(traces))
    server = make_server(inspector)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield Client(url(server).rstrip("/"), inspector.token)
    finally:
        server.shutdown()
        server.server_close()


class TestWorkspaceRoutes:
    def test_the_dataset_says_which_workspaces_can_run(self, client: Client) -> None:
        status, dataset = client.get("/api/dataset")
        assert status == 200
        workspaces = dataset["workspaces"]
        # The shared fixture is a perspective capture, which the eye cannot sample.
        assert workspaces["eye"]["available"] is False
        assert "PERSPECTIVE_90" in workspaces["eye"]["reason"]
        assert workspaces["brain"]["runs"] is True
        assert workspaces["brain"]["traced_clips"] == ["s000001-c00"]

    def test_eye_routes_refuse_a_perspective_dataset(self, client: Client) -> None:
        status, body = client.get("/api/eye/layout")
        assert status == 409
        assert "panorama" in body["error"]
        assert client.get("/api/eye/samples/s000000-v00")[0] == 409
        assert client.get("/api/eye/samples/nope")[0] == 404

    def test_brain_routes_serve_runs_and_traces(self, client: Client) -> None:
        status, body = client.get("/api/brain/runs")
        assert status == 200
        assert [run["name"] for run in body["runs"]] == ["a", "b", "broken"]
        status, run = client.get("/api/brain/runs/b")
        assert status == 200
        assert run["test"]["mean_bearing_error_deg"] is None
        assert client.get("/api/brain/runs/missing")[0] == 404
        status, trace = client.get("/api/brain/traces/s000001-c00")
        assert status == 200
        assert trace["steps"] == 6
        assert client.get("/api/brain/traces/s000009-c00")[0] == 404
        assert client.get("/api/brain/nothing")[0] == 404
