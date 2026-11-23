from pathlib import Path

import numpy as np
import pyarrow as pa
import pyarrow.feather as feather

from fly_brain.connectome import MALE_CNS_V1_RELEASE, MaleCNSLoader


def write_release(root: Path) -> None:
    feather.write_feather(
        pa.table(
            {
                "bodyId": [10, 20, 30],
                "type": ["photoreceptor", "lamina", "other"],
                "class": [None, "visual", None],
                "superclass": ["sensory", "visual_projection", "other"],
                "somaSide": ["L", "L", None],
                "rootSide": [None, None, "R"],
                "somaNeuromere": ["ME", "LO", None],
                "instance": ["R1_L", "EPG(PB08)_L3", None],
            }
        ),
        root / "body-annotations-male-cns-v1.0-minconf-0.5.feather",
    )
    feather.write_feather(
        pa.table({"body": [10, 20, 30], "consensus_nt": ["histamine", "gaba", None]}),
        root / "body-neurotransmitters-male-cns-v1.0.feather",
    )
    feather.write_feather(
        pa.table(
            {
                "body_pre": [10, 20, 30],
                "body_post": [20, 10, 20],
                "weight": [8, 3, 20],
            }
        ),
        root / "connectome-weights-male-cns-v1.0-minconf-0.5.feather",
    )


def test_male_cns_loader_joins_annotations_and_restricts_graph(tmp_path: Path) -> None:
    write_release(tmp_path)

    graph = MaleCNSLoader(
        tmp_path,
        minimum_synapse_count=5,
        neuron_ids=(10, 20),
        verify_checksums=False,
    ).load()

    assert graph.release == MALE_CNS_V1_RELEASE
    assert graph.neurons.neuron_ids.tolist() == [10, 20]
    assert graph.neurons.classes.tolist() == ["sensory", "visual"]
    assert graph.neurons.neurotransmitters.tolist() == ["histamine", "gaba"]
    assert graph.synapses.source_ids.tolist() == [10]
    assert graph.synapses.target_ids.tolist() == [20]
    assert graph.synapses.counts.tolist() == [8]


def test_male_cns_annotation_selection_precedes_edge_materialization(tmp_path: Path) -> None:
    write_release(tmp_path)
    loader = MaleCNSLoader(tmp_path, verify_checksums=False)

    assert loader.annotation_ids(cell_types=("photoreceptor",)) == (10,)
    assert loader.annotation_ids(classes=("visual",)) == (20,)
    assert loader.annotation_ids(superclasses=("visual_projection",)) == (20,)


def test_male_cns_instances_name_requested_neurons(tmp_path: Path) -> None:
    write_release(tmp_path)
    loader = MaleCNSLoader(tmp_path, verify_checksums=False)

    assert loader.instances(np.array([20, 10])) == {10: "R1_L", 20: "EPG(PB08)_L3"}


def test_male_cns_loader_counts_full_input_including_unloaded_neurons(tmp_path: Path) -> None:
    write_release(tmp_path)

    graph = MaleCNSLoader(
        tmp_path,
        minimum_synapse_count=5,
        neuron_ids=(10, 20),
        verify_checksums=False,
        input_totals=True,
    ).load()

    # Neuron 20 hears 8 synapses from loaded neuron 10 and 20 from unloaded neuron 30;
    # neuron 10's only input (3 from 20) is below the edge threshold.
    assert graph.neurons.input_synapse_counts is not None
    assert graph.neurons.input_synapse_counts.tolist() == [0, 28]
    plain = MaleCNSLoader(
        tmp_path, minimum_synapse_count=5, neuron_ids=(10, 20), verify_checksums=False
    ).load()
    assert plain.neurons.input_synapse_counts is None
    assert plain.graph_sha256 == graph.graph_sha256
