"""Per-frame trace helpers for viewing a model run on one clip (fly-brain-clip-trace-v1)."""

from collections.abc import Mapping, Sequence

import numpy as np
from numpy.typing import NDArray


def bump_phase_strength(
    activity: NDArray[np.floating], angles: NDArray[np.floating]
) -> tuple[NDArray[np.float64], NDArray[np.float64]]:
    """Bump phase (degrees) and strength per frame from activity (T, N) at ``angles`` (radians).

    Uses rectified, baseline-subtracted release, as ``bump_metrics`` does: strength 0 is flat and
    1 is a single active angle.
    """
    release = np.clip(0.5 * np.tanh(2.0 * np.asarray(activity, dtype=np.float64)), 0.0, None)
    release = release - release.min(axis=1, keepdims=True)
    vector = (release * np.exp(1j * np.asarray(angles))).sum(axis=1)
    strength = np.abs(vector) / np.maximum(release.sum(axis=1), 1e-9)
    return np.degrees(np.angle(vector)), strength


def goal_phase_strength(
    activity: NDArray[np.floating], angles: NDArray[np.floating]
) -> tuple[NDArray[np.float64], NDArray[np.float64]]:
    """Like ``bump_phase_strength`` over the neurons that have an angle (others are ignored)."""
    angled = np.isfinite(np.asarray(angles))
    return bump_phase_strength(np.asarray(activity)[:, angled], np.asarray(angles)[angled])


def primary_components(
    neuron_ids: NDArray[np.integer],
    members: Mapping[str, NDArray[np.integer]],
    order: Sequence[str],
) -> tuple[tuple[str, ...], NDArray[np.int64]]:
    """One component index per neuron: the last component in ``order`` containing it, or -1.

    Components can overlap (``anterior_visual_inputs`` is a named subset of
    ``visual_projection``); later, more specific components win.
    """
    names = tuple(order)
    index = np.full(np.asarray(neuron_ids).size, -1, dtype=np.int64)
    for position, name in enumerate(names):
        index[np.isin(neuron_ids, members[name])] = position
    return names, index


def component_flow(
    release: NDArray[np.floating],
    source: NDArray[np.integer],
    target: NDArray[np.integer],
    weight: NDArray[np.floating],
    component: NDArray[np.integer],
    count: int,
    *,
    chunk: int = 32,
) -> NDArray[np.float32]:
    """Signed signal per frame between components, shape (T, count, count) as [source, target].

    ``release`` is (T, N) presynaptic release; ``weight`` is the effective signed edge weight
    (anatomical weight times learned gain). Edges touching unassigned neurons are skipped.
    """
    keep = (component[source] >= 0) & (component[target] >= 0)
    pair = component[source[keep]] * count + component[target[keep]]
    sources, weights = source[keep], np.asarray(weight, dtype=np.float32)[keep]
    frames = release.shape[0]
    out = np.zeros((frames, count * count), dtype=np.float32)
    for start in range(0, frames, chunk):
        signal = np.asarray(release[start : start + chunk], dtype=np.float32)[:, sources] * weights
        for row, values in enumerate(signal):
            out[start + row] = np.bincount(pair, weights=values, minlength=count * count)
    return out.reshape(frames, count, count)


def readout_decoding(
    raw: NDArray[np.floating],
) -> tuple[NDArray[np.float64], NDArray[np.float64], NDArray[np.float64]]:
    """Presence probability and bearing (degrees) from readout rows [logit, fwd, right, up]."""
    values = np.asarray(raw, dtype=np.float64)
    probability = 1.0 / (1.0 + np.exp(-values[:, 0]))
    direction = values[:, 1:4]
    norm = np.maximum(np.linalg.norm(direction, axis=1), 1e-9)
    azimuth = np.degrees(np.arctan2(direction[:, 1], direction[:, 0]))
    elevation = np.degrees(np.arcsin(np.clip(direction[:, 2] / norm, -1.0, 1.0)))
    return probability, azimuth, elevation
