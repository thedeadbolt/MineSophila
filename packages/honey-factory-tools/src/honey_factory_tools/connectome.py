# pyarrow's stubs leave much of its API untyped; fly-brain's MaleCNS loader does the same.
# pyright: reportArgumentType=false, reportCallIssue=false, reportUnknownArgumentType=false
# pyright: reportUnknownMemberType=false, reportUnknownVariableType=false
"""A compact connectome graph for the Brain workspace's network view (ADR 0029).

``connectome-graph`` reads the MaleCNS v1.0 feather files fly-brain uses, selects the same
components fly-brain compiles (the visual system, the anterior visual pathway, the
central complex and the visual Kenyon cells, by the same cell-type and class rules),
keeps neuron pairs joined by at least ``minimum_synapse_count`` synapses as fly-brain
does, and writes ``minesophila-connectome-graph-v1`` JSON:

- ``components``: name, neuron count and cell types, in the order signals flow;
- ``cell_types``: each type's component, neuron count, sides and consensus transmitter;
- ``type_edges``: ``[pre, post, synapses, neuron pairs]`` between cell types (indexes into
  ``cell_types``), the strongest ``max_type_edges``;
- ``component_edges``: synapses from component to component;
- ``neurons`` and ``neuron_edges``: every neuron of the central-complex components with
  its body ID, cell type, side and protocerebral-bridge angle, and the synapses among them.

The selection rules are copied from fly-brain (``connectome/visual.py`` and
``connectome/central_complex.py`` on its branch); keep them in step.
"""

import json
import re
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

import numpy as np
import pyarrow as pa
import pyarrow.compute as pc
import pyarrow.feather as feather
import pyarrow.ipc as ipc

type Record = dict[str, Any]

FORMAT = "minesophila-connectome-graph-v1"
RELEASE = "male-cns@1.0-minconf-0.5"
ANNOTATIONS = "body-annotations-male-cns-v1.0-minconf-0.5.feather"
TRANSMITTERS = "body-neurotransmitters-male-cns-v1.0.feather"
WEIGHTS = "connectome-weights-male-cns-v1.0-minconf-0.5.feather"

PHOTORECEPTOR_TYPES = (
    "HBeyelet", "R1-R6", "R7R8_unclear", "R7_unclear", "R7d", "R7p", "R7y",
    "R8_unclear", "R8d", "R8p", "R8y",
)  # fmt: skip
LAMINA_RELAY_TYPES = ("L1", "L2", "L3", "L4", "L5")
MEDULLA_RELAY_TYPES = ("Mi1", "Mi4", "Mi9", "Tm1", "Tm2", "Tm3", "Tm4", "Tm6", "Tm9", "Tm20")
ANTERIOR_VISUAL_INPUT_TYPES = (
    "MeTu1", "MeTu2a", "MeTu2b", "MeTu3a", "MeTu3b", "MeTu3c", "MeTu4_unclear",
    "MeTu4a", "MeTu4b", "MeTu4c", "MeTu4d", "MeTu4e", "MeTu4f",
)  # fmt: skip
TUBERCLE_BULB_TYPES = tuple(f"TuBu{index:02d}" for index in range(1, 11))
RING_NEURON_TYPES = (
    "ER1_a", "ER1_b", "ER1_c", "ER2_a", "ER2_b", "ER2_c", "ER2_d", "ER3a_a", "ER3a_b",
    "ER3a_c", "ER3a_d", "ER3d_a", "ER3d_b", "ER3d_c", "ER3d_d", "ER3d_e", "ER3m",
    "ER3p_a", "ER3p_b", "ER3w_a", "ER3w_b", "ER3w_c", "ER4d", "ER4m", "ER5", "ER6",
)  # fmt: skip
COMPASS_TYPES = ("EPG", "PEN_a(PEN1)", "PEN_b(PEN2)", "PEG", "Delta7")
STEERING_TYPES = ("PFL1", "PFL2", "PFL3")
# Components in the order signals flow. MeTu are visual projection neurons too; they are
# listed under the anterior visual pathway, as fly-brain does when both are present.
COMPONENT_ORDER = (
    "photoreceptors",
    "lamina_relays",
    "medulla_relays",
    "visual_projection",
    "collision_relays",
    "anterior_visual_inputs",
    "visual_kenyon",
    "tubercle_bulb",
    "ring_neurons",
    "compass",
    "vector_inputs",
    "goal_circuit",
    "steering",
    "steering_outputs",
    "steering_bridges",
    "touch_ascending",
    "descending_steering",
)
NEURON_COMPONENTS = ("tubercle_bulb", "ring_neurons", "compass", "steering")
_GLOMERULUS = re.compile(r"_([LR])(\d)$")
INHIBITORY = {"gaba", "glutamate"}


class ConnectomeError(ValueError):
    """The connectome files are missing or unreadable."""


def pb_angle(instance: str | None) -> float | None:
    """Heading angle in degrees of a protocerebral-bridge glomerulus, as fly-brain's
    ``pb_glomerulus_angle``: L8..L1 then R1..R8, 45° apart; None when the instance names
    no glomerulus."""
    match = _GLOMERULUS.search(instance or "")
    if match is None:
        return None
    side, number = match.group(1), int(match.group(2))
    step = number - 1 if side == "R" else 8 - number
    angle = (step * 45.0) % 360.0
    return angle - 360.0 if angle > 180.0 else angle


def component_of(cell_type: str | None, superclass: str | None) -> str | None:
    if cell_type in ANTERIOR_VISUAL_INPUT_TYPES:
        return "anterior_visual_inputs"
    for name, types in (
        ("photoreceptors", PHOTORECEPTOR_TYPES),
        ("lamina_relays", LAMINA_RELAY_TYPES),
        ("medulla_relays", MEDULLA_RELAY_TYPES),
        ("tubercle_bulb", TUBERCLE_BULB_TYPES),
        ("ring_neurons", RING_NEURON_TYPES),
        ("compass", COMPASS_TYPES),
        ("steering", STEERING_TYPES),
    ):
        if cell_type in types:
            return name
    if superclass == "visual_projection":
        return "visual_projection"
    return None


def _ranks(body: dict[int, Record]) -> dict[str, int]:
    """Each component's place in the flow: ``COMPONENT_ORDER``, then any others by name."""
    extra = sorted({str(r["component"]) for r in body.values()} - set(COMPONENT_ORDER))
    return {name: rank for rank, name in enumerate((*COMPONENT_ORDER, *extra))}


def report_components(report: Path) -> dict[str, list[str]]:
    """A fly-brain run report's components as ``{component: [cell types]}``, from its
    ``connectome.component_cell_types``."""
    value: Any = json.loads(report.read_text())
    types: Any = ((value.get("connectome") or {}).get("component_cell_types")) or {}
    if not isinstance(types, dict) or not types:
        raise ConnectomeError(f"{report} lists no connectome.component_cell_types")
    return {str(name): sorted(str(t) for t in members) for name, members in types.items()}


def build(
    directory: Path,
    minimum_synapse_count: int = 5,
    max_type_edges: int = 6000,
    extra_components: dict[str, list[str]] | None = None,
) -> Record:
    """The graph described in the module docstring, from a MaleCNS v1.0 directory.

    ``extra_components`` (``{component: [cell types]}``, for example a pathfinding model's,
    see ``report_components``) adds every neuron of those types that the built-in rules leave
    out, under the first component listing it."""
    extra_type: dict[str, str] = {}
    for name, types in (extra_components or {}).items():
        for cell_type in types:
            extra_type.setdefault(cell_type, name)
    for name in (ANNOTATIONS, TRANSMITTERS, WEIGHTS):
        if not (directory / name).is_file():
            raise ConnectomeError(f"{directory} has no {name}")
    annotations = feather.read_table(
        directory / ANNOTATIONS,
        columns=[
            "bodyId", "type", "instance", "superclass", "class", "somaSide",
            "somaLocation", "tosomaLocation",
        ],
    ).to_pylist()  # fmt: skip
    body: dict[int, Record] = {}
    kenyon: set[int] = set()
    for row in annotations:
        component = component_of(row["type"], row["superclass"]) or extra_type.get(row["type"])
        if component is not None:
            body[int(row["bodyId"])] = {**row, "component": component}
        elif row["class"] == "Kenyon_Cell":
            kenyon.add(int(row["bodyId"]))

    # One pass over the weights: edges among selected neurons, and visual input to Kenyon cells.
    selected = pa.array(sorted(body), type=pa.int64())
    kenyon_array = pa.array(sorted(kenyon), type=pa.int64())
    visual = pa.array(
        sorted(b for b, r in body.items() if r["superclass"] == "visual_projection"),
        type=pa.int64(),
    )
    pre_parts: list[Any] = []
    post_parts: list[Any] = []
    weight_parts: list[Any] = []
    kenyon_input: set[int] = set()
    with pa.memory_map(str(directory / WEIGHTS)) as source:
        reader = ipc.open_file(source)
        for index in range(reader.num_record_batches):
            batch = reader.get_batch(index)
            pre, post, weight = batch.column(0), batch.column(1), batch.column(2)
            strong = pc.greater_equal(weight, minimum_synapse_count)
            to_kenyon = pc.and_(
                strong, pc.and_(pc.is_in(pre, visual), pc.is_in(post, kenyon_array))
            )
            kenyon_input.update(pc.filter(post, to_kenyon).to_pylist())
            inside = pc.and_(strong, pc.and_(pc.is_in(pre, selected), pc.is_in(post, selected)))
            pre_parts.append(pc.filter(pre, inside))
            post_parts.append(pc.filter(post, inside))
            weight_parts.append(pc.filter(weight, inside))
    kenyon_rows = {int(r["bodyId"]): r for r in annotations if int(r["bodyId"]) in kenyon_input}
    for body_id, row in kenyon_rows.items():
        body[body_id] = {**row, "component": "visual_kenyon"}
    # Edges into and out of the new Kenyon cells need a second, narrower pass.
    if kenyon_rows:
        everyone = pa.array(sorted(body), type=pa.int64())
        new = pa.array(sorted(kenyon_rows), type=pa.int64())
        with pa.memory_map(str(directory / WEIGHTS)) as source:
            reader = ipc.open_file(source)
            for index in range(reader.num_record_batches):
                batch = reader.get_batch(index)
                pre, post, weight = batch.column(0), batch.column(1), batch.column(2)
                touches = pc.or_(pc.is_in(pre, new), pc.is_in(post, new))
                keep = pc.and_(
                    pc.greater_equal(weight, minimum_synapse_count),
                    pc.and_(touches, pc.and_(pc.is_in(pre, everyone), pc.is_in(post, everyone))),
                )
                pre_parts.append(pc.filter(pre, keep))
                post_parts.append(pc.filter(post, keep))
                weight_parts.append(pc.filter(weight, keep))
    pre_ids = np.concatenate([np.asarray(p) for p in pre_parts]) if pre_parts else np.zeros(0, int)
    post_ids = (
        np.concatenate([np.asarray(p) for p in post_parts]) if post_parts else np.zeros(0, int)
    )
    weights = (
        np.concatenate([np.asarray(w) for w in weight_parts]) if weight_parts else np.zeros(0, int)
    )

    transmitters = _transmitters(directory, set(body))
    graph = _summarise(
        body, pre_ids, post_ids, weights, transmitters, minimum_synapse_count, max_type_edges
    )
    graph["atlas"] = _atlas(body, annotations, graph["cell_types"], pre_ids, post_ids, weights)
    return graph


def _transmitters(directory: Path, bodies: set[int]) -> dict[int, str]:
    table = feather.read_table(
        directory / TRANSMITTERS, columns=["body", "consensus_nt", "celltype_predicted_nt"]
    )
    out: dict[int, str] = {}
    for row in table.to_pylist():
        body_id = int(row["body"])
        if body_id in bodies:
            value = row["consensus_nt"] or row["celltype_predicted_nt"]
            if value:
                out[body_id] = str(value).lower()
    return out


def _summarise(
    body: dict[int, Record],
    pre_ids: Any,
    post_ids: Any,
    weights: Any,
    transmitters: dict[int, str],
    minimum_synapse_count: int,
    max_type_edges: int,
) -> Record:
    order = _ranks(body)
    type_rows: dict[str, list[int]] = defaultdict(list)
    for body_id, row in body.items():
        type_rows[str(row["type"] or "untyped")].append(body_id)
    type_names = sorted(type_rows, key=lambda t: (order[body[type_rows[t][0]]["component"]], t))
    type_index = {name: i for i, name in enumerate(type_names)}
    cell_types: list[Record] = []
    for name in type_names:
        members = type_rows[name]
        nts = Counter(transmitters.get(m, "unknown") for m in members)
        sides = Counter(str(body[m]["somaSide"] or "?") for m in members)
        cell_types.append(
            {
                "name": name,
                "component": body[members[0]]["component"],
                "count": len(members),
                "transmitter": nts.most_common(1)[0][0],
                "sides": dict(sides),
            }
        )

    body_type = {b: type_index[str(r["type"] or "untyped")] for b, r in body.items()}
    pairs: dict[tuple[int, int], list[int]] = defaultdict(lambda: [0, 0])
    components: dict[str, Counter[str]] = defaultdict(Counter)
    for pre, post, weight in zip(
        pre_ids.tolist(), post_ids.tolist(), weights.tolist(), strict=True
    ):
        key = (body_type[pre], body_type[post])
        pairs[key][0] += weight
        pairs[key][1] += 1
        components[body[pre]["component"]][body[post]["component"]] += weight
    strongest = sorted(pairs.items(), key=lambda item: -item[1][0])[:max_type_edges]

    neuron_ids = sorted(
        (b for b, r in body.items() if r["component"] in NEURON_COMPONENTS),
        key=lambda b: (order[body[b]["component"]], body_type[b], str(body[b]["instance"])),
    )
    neuron_index = {b: i for i, b in enumerate(neuron_ids)}
    neuron_edges = [
        [neuron_index[pre], neuron_index[post], weight]
        for pre, post, weight in zip(
            pre_ids.tolist(), post_ids.tolist(), weights.tolist(), strict=True
        )
        if pre in neuron_index and post in neuron_index
    ]

    present = [
        name
        for name in sorted(_ranks(body), key=_ranks(body).get)
        if any(r["component"] == name for r in body.values())
    ]
    return {
        "format": FORMAT,
        "release": RELEASE,
        "minimum_synapse_count": minimum_synapse_count,
        "neuron_count": len(body),
        "edge_count": len(weights),
        "synapse_count": int(np.sum(weights)) if len(weights) else 0,
        "components": [
            {
                "name": name,
                "neurons": sum(1 for r in body.values() if r["component"] == name),
                "cell_types": sum(1 for t in cell_types if t["component"] == name),
            }
            for name in present
        ],
        "cell_types": cell_types,
        "type_edges": [
            [pre, post, synapses, count] for (pre, post), (synapses, count) in strongest
        ],
        "component_edges": {src: dict(targets) for src, targets in components.items()},
        "neurons": [
            {
                "body_id": b,
                "cell_type": body_type[b],
                "side": body[b]["somaSide"],
                "instance": body[b]["instance"],
                "pb_angle": pb_angle(body[b]["instance"]),
            }
            for b in neuron_ids
        ],
        "neuron_edges": neuron_edges,
        "inhibitory_transmitters": sorted(INHIBITORY),
    }


ATLAS_SCALE = 50  # voxels per stored unit
ATLAS_BACKGROUND = 40000
ATLAS_EDGES = 12000


def _soma(row: Record) -> list[int] | None:
    location = row.get("somaLocation") or row.get("tosomaLocation")
    return [int(v) for v in location] if location else None


def _atlas(
    body: dict[int, Record],
    annotations: list[Record],
    cell_types: list[Record],
    pre_ids: Any,
    post_ids: Any,
    weights: Any,
) -> Record:
    """Every model neuron at its soma, so the network can be drawn in the shape of the brain.

    Neurons without a recorded soma (most photoreceptors and lamina cells, whose cell bodies
    lie outside the imaged volume) take the synapse-weighted mean position of their partners,
    repeated so positions spread outwards from the medulla, plus a small fixed offset per
    body so they do not stack. ``background`` is a fixed sample of the other brain neurons'
    somas (the nerve cord left out), which draws the outline of the brain."""
    order = _ranks(body)
    type_index = {t["name"]: i for i, t in enumerate(cell_types)}
    ids = sorted(body, key=lambda b: (order[body[b]["component"]], str(body[b]["type"] or ""), b))
    index = {b: i for i, b in enumerate(ids)}
    position = np.full((len(ids), 3), np.nan)
    for i, b in enumerate(ids):
        soma = _soma(body[b])
        if soma is not None:
            position[i] = soma
    pre = np.array([index[b] for b in pre_ids.tolist()], dtype=np.int64)
    post = np.array([index[b] for b in post_ids.tolist()], dtype=np.int64)
    weight = np.asarray(weights, dtype=float)
    for _ in range(4):
        missing = np.isnan(position[:, 0])
        if not missing.any():
            break
        known = ~np.isnan(position[:, 0])
        total = np.zeros((len(ids), 3))
        mass = np.zeros(len(ids))
        for a, b in ((pre, post), (post, pre)):
            use = missing[a] & known[b]
            np.add.at(total, a[use], position[b[use]] * weight[use, None])
            np.add.at(mass, a[use], weight[use])
        fill = missing & (mass > 0)
        position[fill] = total[fill] / mass[fill, None]
        rng = np.random.default_rng(11)
        position[fill] += rng.normal(0, 600, (int(fill.sum()), 3))
    stored = np.where(np.isnan(position), -1, np.round(position / ATLAS_SCALE)).astype(int)

    background: list[int] = []
    candidates = [
        soma
        for row in annotations
        if int(row["bodyId"]) not in body
        and not str(row["superclass"] or "").startswith("vnc")
        and (soma := _soma(row)) is not None
    ]
    step = max(1, len(candidates) // ATLAS_BACKGROUND)
    for soma in candidates[::step][:ATLAS_BACKGROUND]:
        background.extend(round(v / ATLAS_SCALE) for v in soma)

    strongest = np.argsort(-weight, kind="stable")[:ATLAS_EDGES]
    return {
        "scale": ATLAS_SCALE,
        "body_id": ids,
        "cell_type": [type_index[str(body[b]["type"] or "untyped")] for b in ids],
        "position": stored.reshape(-1).tolist(),
        "placed_from_partners": int(sum(1 for b in ids if _soma(body[b]) is None)),
        "background": background,
        "edges": [[int(pre[k]), int(post[k]), int(weight[k])] for k in strongest],
    }


def write(graph: Record, path: Path) -> None:
    path.write_text(json.dumps(graph, separators=(",", ":"), allow_nan=False) + "\n")


def load(path: Path) -> Record:
    try:
        value = json.loads(path.read_text())
    except (OSError, json.JSONDecodeError) as error:
        raise ConnectomeError(f"{path}: {error}") from error
    if not isinstance(value, dict) or value.get("format") != FORMAT:  # pyright: ignore[reportUnknownMemberType]
        raise ConnectomeError(f"{path} is not a {FORMAT} file")
    return value  # pyright: ignore[reportUnknownVariableType]
