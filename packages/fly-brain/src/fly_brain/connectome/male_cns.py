# pyright: reportArgumentType=false, reportCallIssue=false, reportMissingTypeArgument=false
# pyright: reportUnknownArgumentType=false, reportUnknownMemberType=false
# pyright: reportUnknownParameterType=false
"""Pinned loader for the official MaleCNS v1.0 flat connectome."""

import hashlib
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pyarrow as pa
import pyarrow.compute as pc
import pyarrow.feather as feather
from numpy.typing import NDArray

from fly_brain.connectome.graph import ConnectomeGraph
from fly_brain.connectome.release import ConnectomeRelease
from fly_brain.connectome.tables import NeuronTable, SynapseTable

MALE_CNS_V1_RELEASE = ConnectomeRelease(
    source="male-cns",
    version="1.0-minconf-0.5",
    artifact_sha256="97649f72a1dfcd47d1fabac78f3c97314acd95987f2fe2ac88e8281c410a916f",
    source_uri="https://male-cns.janelia.org/download/",
    license_name="CC BY 4.0",
)

_FILES = {
    "annotations": (
        "body-annotations-male-cns-v1.0-minconf-0.5.feather",
        "2177e246113e4cfbf1e7772ec37c6da1955ff22e8063d0b1f833101f99a9a3b2",
    ),
    "neurotransmitters": (
        "body-neurotransmitters-male-cns-v1.0.feather",
        "95c9289220663abeb3409f3ad9e5a7f8a53f8093f5139d15502cd08da8879621",
    ),
    "weights": (
        "connectome-weights-male-cns-v1.0-minconf-0.5.feather",
        "e35da783d1c686b2b58b3b87cd6a403ae43bfcfba8bff28e08ef752c1a56afc1",
    ),
}


@dataclass(frozen=True)
class MaleCNSLoader:
    root: Path
    minimum_synapse_count: int = 50
    neuron_ids: tuple[int, ...] | None = None
    verify_checksums: bool = True

    def __post_init__(self) -> None:
        if self.minimum_synapse_count < 1:
            raise ValueError("minimum_synapse_count must be positive")

    def annotation_ids(
        self,
        *,
        cell_types: tuple[str, ...] = (),
        classes: tuple[str, ...] = (),
        superclasses: tuple[str, ...] = (),
    ) -> tuple[int, ...]:
        path = self.root / _FILES["annotations"][0]
        annotations = feather.read_table(
            path, columns=["bodyId", "type", "class", "superclass"], memory_map=True
        )
        mask = np.zeros(annotations.num_rows, dtype=np.bool_)
        if cell_types:
            mask |= np.isin(_strings(annotations["type"]), cell_types)
        if classes:
            mask |= np.isin(_strings(annotations["class"]), classes)
        if superclasses:
            mask |= np.isin(_strings(annotations["superclass"]), superclasses)
        if not mask.any():
            raise ValueError("MaleCNS annotation selection resolved to no neurons")
        return tuple(np.asarray(annotations["bodyId"], dtype=np.int64)[mask].tolist())

    def instances(self, neuron_ids: NDArray[np.int64]) -> dict[int, str]:
        """Instance names (for example ``EPG(PB08)_L3``) of the requested neurons."""
        annotations = feather.read_table(
            self.root / _FILES["annotations"][0], columns=["bodyId", "instance"], memory_map=True
        )
        annotations = annotations.filter(
            pc.is_in(annotations["bodyId"], value_set=pa.array(np.asarray(neuron_ids, np.int64)))
        )
        return dict(
            zip(
                np.asarray(annotations["bodyId"], dtype=np.int64).tolist(),
                _strings(annotations["instance"]).tolist(),
                strict=True,
            )
        )

    def load(self, release: ConnectomeRelease = MALE_CNS_V1_RELEASE) -> ConnectomeGraph:
        if release != MALE_CNS_V1_RELEASE:
            raise ValueError("MaleCNSLoader only supports the pinned MaleCNS v1.0 release")
        paths = {name: self.root / value[0] for name, value in _FILES.items()}
        for name, path in paths.items():
            if not path.is_file():
                raise FileNotFoundError(path)
            if self.verify_checksums:
                _verify(path, _FILES[name][1])

        annotations = feather.read_table(
            paths["annotations"],
            columns=[
                "bodyId",
                "type",
                "class",
                "superclass",
                "somaSide",
                "rootSide",
                "somaNeuromere",
            ],
            memory_map=True,
        )
        selected = _selected_ids(annotations["bodyId"], self.neuron_ids)
        annotation_mask = pc.is_in(annotations["bodyId"], value_set=pa.array(selected))
        annotations = annotations.filter(annotation_mask)
        neurotransmitters = feather.read_table(
            paths["neurotransmitters"], columns=["body", "consensus_nt"], memory_map=True
        )
        neurotransmitters = neurotransmitters.filter(
            pc.is_in(neurotransmitters["body"], value_set=pa.array(selected))
        )
        nt_by_id = dict(
            zip(
                neurotransmitters["body"].to_pylist(),
                _strings(neurotransmitters["consensus_nt"]).tolist(),
                strict=True,
            )
        )
        ids = np.asarray(annotations["bodyId"], dtype=np.int64)
        soma_side = _strings(annotations["somaSide"])
        root_side = _strings(annotations["rootSide"])
        neurons = NeuronTable(
            neuron_ids=ids,
            cell_types=_strings(annotations["type"]),
            classes=_coalesce(_strings(annotations["class"]), _strings(annotations["superclass"])),
            neuropils=_strings(annotations["somaNeuromere"]),
            neurotransmitters=np.asarray([nt_by_id.get(int(value), "") for value in ids]),
            sides=_coalesce(soma_side, root_side),
        )

        weights = feather.read_table(paths["weights"], memory_map=True)
        known = pa.array(neurons.neuron_ids)
        edge_mask = pc.and_(
            pc.greater_equal(weights["weight"], self.minimum_synapse_count),
            pc.and_(
                pc.is_in(weights["body_pre"], value_set=known),
                pc.is_in(weights["body_post"], value_set=known),
            ),
        )
        weights = weights.filter(edge_mask)
        return ConnectomeGraph(
            release=release,
            neurons=neurons,
            synapses=SynapseTable(
                source_ids=np.asarray(weights["body_pre"], dtype=np.int64),
                target_ids=np.asarray(weights["body_post"], dtype=np.int64),
                counts=np.asarray(weights["weight"], dtype=np.int64),
            ),
        )


def _selected_ids(column: pa.ChunkedArray, requested: tuple[int, ...] | None) -> NDArray[np.int64]:
    available = np.asarray(column, dtype=np.int64)
    if requested is None:
        return available
    selected = np.unique(np.asarray(requested, dtype=np.int64))
    missing = selected[~np.isin(selected, available)]
    if missing.size:
        raise ValueError(f"MaleCNS neuron IDs lack curated annotations: {missing.tolist()}")
    return selected


def _strings(column: pa.ChunkedArray) -> NDArray[np.str_]:
    return np.asarray(pc.fill_null(column, "").to_pylist(), dtype=np.str_)


def _coalesce(primary: NDArray[np.str_], fallback: NDArray[np.str_]) -> NDArray[np.str_]:
    return np.where(primary != "", primary, fallback)


def _verify(path: Path, expected: str) -> None:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(8 * 1024 * 1024):
            digest.update(block)
    if digest.hexdigest() != expected:
        raise ValueError(f"MaleCNS artifact checksum mismatch: {path.name}")
