# pyarrow's stubs leave much of its API untyped; fly-brain's MaleCNS loader does the same.
# pyright: reportArgumentType=false, reportCallIssue=false, reportUnknownArgumentType=false
# pyright: reportUnknownMemberType=false, reportUnknownVariableType=false
"""The connectome graph behind the Brain network view, built from tiny feather files."""

import json
from pathlib import Path
from typing import Any

import pyarrow as pa
import pyarrow.feather as feather
import pyarrow.ipc as ipc
import pytest

from honey_factory_tools import connectome
from honey_factory_tools.cli import main

NEURONS = [
    # body, type, instance, superclass, class, side
    (1, "R1-R6", "R1-R6_L", "ol_sensory", "visual", "L"),
    (2, "L1", "L1_L", "ol_intrinsic", None, "L"),
    (3, "Tm3", "Tm3_L", "ol_intrinsic", None, "L"),
    (4, "LC10a", "LC10a_L", "visual_projection", None, "L"),
    (5, "MeTu1", "MeTu1_L", "visual_projection", None, "L"),
    (6, "TuBu01", "TuBu01_L", "cb_intrinsic", "CX", "L"),
    (7, "ER4d", "ER4d_L", "cb_intrinsic", "CX", "L"),
    (8, "EPG", "EPG(PB08)_L4", "cb_intrinsic", "CX", "L"),
    (9, "EPG", "EPG(PB08)_R2", "cb_intrinsic", "CX", "R"),
    (10, "PFL3", "PFL3(PB12c)_L1_C3", "cb_intrinsic", "CX", "L"),
    (11, "KCg-d", "KCg-d_L", "cb_intrinsic", "Kenyon_Cell", "L"),
    (12, "KCab", "KCab_L", "cb_intrinsic", "Kenyon_Cell", "L"),
    (13, "DNp01", "DNp01_R", "descending_neuron", None, "R"),
    (14, "IN01", "IN01_L", "vnc_intrinsic", None, "L"),
    (15, "SMP001", "SMP001_L", "cb_intrinsic", None, "L"),
]
# Somas in voxels; photoreceptor 1 and lamina 2 have none, as in MaleCNS.
SOMAS = {
    3: [5000, 1000, 1000], 4: [6000, 1500, 1000], 5: [7000, 1500, 1000],
    6: [8000, 1000, 1000], 7: [9000, 1000, 1000], 8: [10000, 1000, 1000],
    9: [10500, 1200, 1000], 10: [11000, 1000, 1000], 11: [7500, 500, 1000],
    12: [7600, 500, 1000], 13: [9000, 2000, 1000], 14: [9000, 9000, 9000],
    15: [8500, 800, 1000],
}  # fmt: skip
EDGES = [
    (1, 2, 50), (2, 3, 40), (3, 4, 30), (3, 5, 20), (5, 6, 25), (6, 7, 22), (7, 8, 18),
    (8, 9, 12), (9, 8, 12), (8, 10, 9), (4, 11, 7), (4, 12, 3), (11, 4, 6),
    (1, 3, 4),  # below the synapse threshold
    (10, 13, 30),  # to a neuron outside every component
]  # fmt: skip


@pytest.fixture
def connectome_dir(tmp_path: Path) -> Path:
    columns = list(zip(*NEURONS, strict=True))
    feather.write_feather(
        pa.table(
            {
                "bodyId": pa.array(columns[0], pa.int64()),
                "type": columns[1],
                "instance": columns[2],
                "superclass": columns[3],
                "class": columns[4],
                "somaSide": columns[5],
                "somaLocation": pa.array([SOMAS.get(n[0]) for n in NEURONS], pa.list_(pa.int64())),
                "tosomaLocation": pa.array([None] * len(NEURONS), pa.list_(pa.int64())),
            }
        ),
        tmp_path / connectome.ANNOTATIONS,
    )
    feather.write_feather(
        pa.table(
            {
                "body": pa.array([n[0] for n in NEURONS], pa.int64()),
                "consensus_nt": ["acetylcholine"] * 6 + ["GABA"] + ["acetylcholine"] * 8,
                "celltype_predicted_nt": [None] * len(NEURONS),
            }
        ),
        tmp_path / connectome.TRANSMITTERS,
    )
    schema = pa.schema(
        [("body_pre", pa.int64()), ("body_post", pa.int64()), ("weight", pa.int64())]
    )
    with ipc.new_file(str(tmp_path / connectome.WEIGHTS), schema) as writer:
        for chunk in (EDGES[:7], EDGES[7:]):  # two record batches
            pre, post, weight = zip(*chunk, strict=True)
            writer.write_batch(
                pa.record_batch([pa.array(pre), pa.array(post), pa.array(weight)], schema=schema)
            )
    return tmp_path


class TestBuild:
    def test_selects_fly_brain_components_and_visual_kenyon_cells(
        self, connectome_dir: Path
    ) -> None:
        graph = connectome.build(connectome_dir)
        counts = {c["name"]: c["neurons"] for c in graph["components"]}
        assert counts == {
            "photoreceptors": 1, "lamina_relays": 1, "medulla_relays": 1, "visual_projection": 1,
            "anterior_visual_inputs": 1, "visual_kenyon": 1, "tubercle_bulb": 1, "ring_neurons": 1,
            "compass": 2, "steering": 1,
        }  # fmt: skip
        # KCab gets only 3 synapses from vision, below the threshold; DNp01 is in no component.
        names = [t["name"] for t in graph["cell_types"]]
        assert "KCab" not in names
        assert "DNp01" not in names
        assert names.index("R1-R6") < names.index("Tm3") < names.index("EPG") < names.index("PFL3")

    def test_extra_components_from_a_model_add_their_cell_types(self, connectome_dir: Path) -> None:
        # A pathfinding model's descending neurons (DNp01 here); EPG stays in the compass.
        extra = {"descending_steering": ["DNp01", "EPG"], "collision_relays": ["SMP001"]}
        graph = connectome.build(connectome_dir, extra_components=extra)
        counts = {c["name"]: c["neurons"] for c in graph["components"]}
        assert counts["descending_steering"] == 1
        assert counts["collision_relays"] == 1
        assert counts["compass"] == 2
        names = [t["name"] for t in graph["cell_types"]]
        assert names.index("PFL3") < names.index("DNp01")
        edges = {(names[a], names[b]): w for a, b, w, _ in graph["type_edges"]}
        assert edges[("PFL3", "DNp01")] == 30
        assert graph["component_edges"]["steering"]["descending_steering"] == 30

    def test_extra_components_come_from_a_fly_brain_report(self, tmp_path: Path) -> None:
        report = tmp_path / "report.json"
        report.write_text(json.dumps({"connectome": {"component_cell_types": {
            "descending_steering": {"DNp01": 1}, "compass": {"EPG": 2}}}}))  # fmt: skip
        assert connectome.report_components(report) == {
            "descending_steering": ["DNp01"],
            "compass": ["EPG"],
        }

    def test_counts_synapses_by_cell_type_and_component(self, connectome_dir: Path) -> None:
        graph = connectome.build(connectome_dir)
        names = [t["name"] for t in graph["cell_types"]]
        edges = {(names[a], names[b]): (w, n) for a, b, w, n in graph["type_edges"]}
        assert edges[("R1-R6", "L1")] == (50, 1)
        assert ("R1-R6", "Tm3") not in edges
        assert edges[("EPG", "EPG")] == (24, 2)
        assert edges[("KCg-d", "LC10a")] == (6, 1)
        assert graph["component_edges"]["compass"] == {"compass": 24, "steering": 9}
        assert graph["edge_count"] == 12
        assert graph["synapse_count"] == sum(w for _, _, w in EDGES if w >= 5) - 30

    def test_keeps_central_complex_neurons_with_bridge_angles(self, connectome_dir: Path) -> None:
        graph = connectome.build(connectome_dir)
        neurons = {n["instance"]: n for n in graph["neurons"]}
        assert set(neurons) == {
            "TuBu01_L",
            "ER4d_L",
            "EPG(PB08)_L4",
            "EPG(PB08)_R2",
            "PFL3(PB12c)_L1_C3",
        }
        assert neurons["EPG(PB08)_L4"]["pb_angle"] == 180.0
        assert neurons["EPG(PB08)_R2"]["pb_angle"] == 45.0
        assert neurons["PFL3(PB12c)_L1_C3"]["pb_angle"] is None
        assert len(graph["neuron_edges"]) == 5
        ring = next(t for t in graph["cell_types"] if t["name"] == "ER4d")
        assert ring["transmitter"] == "gaba"

    def test_places_neurons_at_their_somas_for_the_3d_brain(self, connectome_dir: Path) -> None:
        atlas = connectome.build(connectome_dir)["atlas"]
        scale = atlas["scale"]
        index = {body: i for i, body in enumerate(atlas["body_id"])}
        position = atlas["position"]
        assert position[3 * index[3] : 3 * index[3] + 3] == [
            5000 / scale,
            1000 / scale,
            1000 / scale,
        ]
        # R1-R6 and L1 have no soma, so they are placed among their partners.
        assert atlas["placed_from_partners"] == 2
        assert all(position[3 * index[b]] >= 0 for b in (1, 2))
        assert abs(position[3 * index[2]] * scale - 5000) < 3000
        # The outline holds other brain neurons only: no model neurons, no nerve cord.
        background = atlas["background"]
        # KCab (too little visual input), DNp01 and SMP001; IN01 is in the nerve cord.
        assert [background[k : k + 3] for k in range(0, len(background), 3)] == [
            [152, 10, 20],
            [180, 40, 20],
            [170, 16, 20],
        ]
        assert len(atlas["edges"]) == 12
        assert atlas["edges"][0][2] == 50

    def test_missing_files_are_reported(self, tmp_path: Path) -> None:
        with pytest.raises(connectome.ConnectomeError, match="feather"):
            connectome.build(tmp_path)


def test_pb_angle_follows_fly_brain() -> None:
    assert connectome.pb_angle("EPG(PB08)_R1") == 0.0
    assert connectome.pb_angle("EPG(PB08)_L1") == -45.0
    assert connectome.pb_angle("EPG(PB08)_L8") == 0.0
    # Delta7 instances span several glomeruli (L1L9R8), so they get no single angle.
    assert connectome.pb_angle("Delta7(PB15)_L1L9R8_R") is None
    assert connectome.pb_angle(None) is None


def test_cli_writes_a_graph_the_inspector_can_load(connectome_dir: Path, tmp_path: Path) -> None:
    out = tmp_path / "graph.json"
    assert main(["connectome-graph", str(connectome_dir), "--out", str(out)]) == 0
    graph: Any = connectome.load(out)
    assert graph["format"] == connectome.FORMAT
    bad = tmp_path / "bad.json"
    bad.write_text(json.dumps({"format": "other"}))
    with pytest.raises(connectome.ConnectomeError):
        connectome.load(bad)
