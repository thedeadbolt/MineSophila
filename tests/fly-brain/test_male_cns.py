from pathlib import Path

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
