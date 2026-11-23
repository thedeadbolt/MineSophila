# pyright: reportUnknownMemberType=false, reportUnknownVariableType=false
# pyright: reportUnknownArgumentType=false
"""A slice of a compiled brain that can be simulated alone, for operating-point fits.

Fits such as the compass integrator (ADR 0028) and the fan-shaped-body goal memory run a few
hundred neurons instead of the whole brain: the edges with both ends inside chosen cell types,
their anatomical weights, and one bounded gain per cell-type pair. Dynamics match the compiled
brain (graded release, smooth thresholds on gated cell types, leak ``dt / tau``).
"""

from collections.abc import Sequence
from dataclasses import dataclass

import jax
import jax.numpy as jnp
import numpy as np
from numpy.typing import NDArray

from fly_brain.connectome.compiler import CompiledBrain
from fly_brain.connectome.dynamics import LeakyRateDynamics


@dataclass(frozen=True)
class SubCircuit:
    cell_types: NDArray[np.str_]
    neuron_index: NDArray[np.int64]  # indexes into the compiled brain
    source: NDArray[np.int32]  # local indexes
    target: NDArray[np.int32]
    group: NDArray[np.int32]  # index into ``group_pairs``
    weight: NDArray[np.float32]
    group_pairs: tuple[str, ...]  # "SOURCE>TARGET" per group
    global_groups: NDArray[np.int64]  # compiled-brain edge group per local group
    gate: NDArray[np.bool_]  # thresholded release
    leak: float
    maximum_gain: float

    def __post_init__(self) -> None:
        n = self.cell_types.size
        if self.neuron_index.size != n or self.gate.size != n:
            raise ValueError("per-neuron sub-circuit arrays must have the same length")
        if not (self.source.size == self.target.size == self.group.size == self.weight.size):
            raise ValueError("per-edge sub-circuit arrays must have the same length")
        if len(self.group_pairs) != self.global_groups.size:
            raise ValueError("every local group needs a name and a compiled group")
        if not 0 < self.leak <= 1 or not self.maximum_gain > 0:
            raise ValueError("sub-circuit leak and maximum gain are out of range")


def extract_subcircuit(
    brain: CompiledBrain,
    types: NDArray[np.str_],
    member_types: Sequence[str],
    gated_types: Sequence[str] = (),
) -> SubCircuit:
    """Neurons of ``member_types`` and the edges with both ends among them.

    ``types`` is the cell type per compiled neuron. Local groups are ordered by their
    compiled-brain group index.
    """
    maximum = brain.parameter_policy.maximum_edge_gain
    if maximum is None:
        raise ValueError("sub-circuit fits need bounded edge gains")
    members = np.isin(types, list(member_types))
    index = np.flatnonzero(members)
    if index.size == 0:
        raise ValueError("the sub-circuit selects no neurons")
    position = np.full(types.size, -1)
    position[index] = np.arange(index.size)
    inside = members[brain.source_indexes] & members[brain.target_indexes]
    global_groups, local = np.unique(brain.edge_group_indexes[inside], return_inverse=True)
    sources, targets = brain.source_indexes[inside], brain.target_indexes[inside]
    first = [int(np.flatnonzero(local == group)[0]) for group in range(global_groups.size)]
    return SubCircuit(
        cell_types=types[index],
        neuron_index=index.astype(np.int64),
        source=position[sources].astype(np.int32),
        target=position[targets].astype(np.int32),
        group=local.astype(np.int32),
        weight=brain.anatomical_weights[inside].astype(np.float32),
        group_pairs=tuple(f"{types[sources[e]]}>{types[targets[e]]}" for e in first),
        global_groups=global_groups.astype(np.int64),
        gate=np.isin(types[index], list(gated_types)),
        leak=brain.dt / brain.dynamics.time_constant,
        maximum_gain=float(maximum),
    )


def run_subcircuit(
    circuit: SubCircuit,
    raw_gains: jax.Array,
    bias: jax.Array,
    threshold: jax.Array,
    external: jax.Array,
    *,
    transitions: int,
    sharpness: float,
) -> jax.Array:
    """Simulate from rest with ``external`` drive (B, T, N); return the state after each frame.

    ``raw_gains`` are per local group (effective gain ``maximum * sigmoid(raw)``); ``bias`` and
    ``threshold`` are per neuron (``threshold`` is used where ``gate``).
    """
    dynamics = LeakyRateDynamics(activation="graded", release_sharpness=sharpness)
    gain = circuit.maximum_gain * jax.nn.sigmoid(raw_gains)[jnp.asarray(circuit.group)]
    weight = gain * jnp.asarray(circuit.weight)
    source, target = jnp.asarray(circuit.source), jnp.asarray(circuit.target)
    gate = jnp.asarray(circuit.gate)[None, :]

    def release(state: jax.Array) -> jax.Array:
        graded = dynamics.release(state)
        gated = dynamics.thresholded_release(state, threshold[None, :])
        return jnp.where(gate, gated, graded)

    def frame(state: jax.Array, push: jax.Array) -> tuple[jax.Array, jax.Array]:
        def step(_: int, inner: jax.Array) -> jax.Array:
            drive = jnp.zeros_like(inner).at[:, target].add(release(inner)[:, source] * weight)
            return inner + circuit.leak * (drive + bias[None, :] + push - inner)

        state = jax.lax.fori_loop(0, transitions, step, state)
        return state, state

    initial = jnp.zeros((external.shape[0], circuit.cell_types.size))
    _, states = jax.lax.scan(frame, initial, jnp.swapaxes(external, 0, 1))
    return jnp.swapaxes(states, 0, 1)
