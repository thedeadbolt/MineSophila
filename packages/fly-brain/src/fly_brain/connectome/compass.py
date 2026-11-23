"""Compass operating point: initialise the central-complex compass as a ring attractor (9h).

With every cell-type-pair gain at one, the MaleCNS compass loop (EPG, PEN, PEG, Delta7) has a
uniform leading mode with linearised eigenvalue about 0.5: activity decays, and when driven it
rises everywhere at once instead of forming a bump. A ring attractor needs the opposite: a
first-harmonic pair over the protocerebral-bridge glomeruli near one, so a bump can persist and
rotate, with the uniform mode held below it. ``ring_attractor_gains`` searches the compass
type-pair gains (inside the gain cap) for that spectrum; a small tonic bias then gives ring-neuron
inhibition something to sculpt. Release has unit slope at rest (ADR 0027), so the linearised loop
is the anatomical weight times the effective gain.
"""

import re
from dataclasses import dataclass

import numpy as np
from numpy.typing import NDArray

_GLOMERULUS = re.compile(r"_([LR])(\d)(?:_C\d+\w*)?$")
_HARMONIC_MODE = 0.6


@dataclass(frozen=True)
class CompassOperatingPoint:
    harmonic_eigenvalue: float = 1.05
    max_other_eigenvalue: float = 0.8
    tonic_bias: float = 0.02
    iterations: int = 6_000
    seed: int = 0

    def __post_init__(self) -> None:
        if not 0 < self.max_other_eigenvalue < self.harmonic_eigenvalue:
            raise ValueError("need 0 < max_other_eigenvalue < harmonic_eigenvalue")
        if not np.isfinite(self.tonic_bias) or self.tonic_bias < 0:
            raise ValueError("tonic_bias must be non-negative")
        if self.iterations < 1:
            raise ValueError("iterations must be positive")


@dataclass(frozen=True)
class CompassMode:
    eigenvalue: float
    uniform: float
    harmonic: float


@dataclass(frozen=True)
class RingSearchResult:
    gains: NDArray[np.float64]
    changed_groups: NDArray[np.int64]
    harmonic_eigenvalue: float
    other_eigenvalue: float
    modes: tuple[CompassMode, ...]


def pb_glomerulus_angle(instance: str) -> float:
    """Heading angle of a protocerebral-bridge glomerulus from an instance name.

    Heading runs along the bridge, L8..L1 then R1..R8, 45 degrees per glomerulus, so Ln and
    R(9-n) share a heading (MaleCNS Delta7 instances such as ``L4R5`` join them) and the
    outermost glomeruli L9 and R9 wrap onto L1 and R1.
    """
    match = _GLOMERULUS.search(instance)
    if match is None:
        raise ValueError(f"instance {instance!r} names no protocerebral-bridge glomerulus")
    side, number = match.group(1), int(match.group(2))
    step = number - 1 if side == "R" else 8 - number
    return float(np.radians((step * 45.0) % 360.0))


def compass_modes(
    source: NDArray[np.integer],
    target: NDArray[np.integer],
    edge_weight: NDArray[np.floating],
    members: NDArray[np.bool_],
    angles: NDArray[np.floating],
    *,
    count: int = 12,
) -> tuple[CompassMode, ...]:
    """Leading eigenmodes of the loop restricted to ``members``, scored over neurons with angles.

    ``edge_weight`` is the effective weight per edge. ``angles`` is per neuron (NaN where the
    neuron has no ring position). ``uniform`` and ``harmonic`` are the share of each mode's
    angled part that is constant or first harmonic (1 for a pure mode).
    """
    index = np.flatnonzero(members)
    position = np.full(members.size, -1)
    position[index] = np.arange(index.size)
    inside = (position[source] >= 0) & (position[target] >= 0)
    matrix = np.zeros((index.size, index.size))
    np.add.at(matrix, (position[target[inside]], position[source[inside]]), edge_weight[inside])
    angled = np.isfinite(angles[index])
    if not angled.any():
        raise ValueError("compass modes need neurons with ring angles")
    theta = angles[index][angled]
    values, vectors = np.linalg.eig(matrix)
    modes: list[CompassMode] = []
    for k in np.argsort(-values.real)[:count]:
        part = vectors[angled, k]
        part = part / max(float(np.linalg.norm(part)), 1e-12)
        uniform = abs(part.sum()) / np.sqrt(part.size)
        harmonic = max(
            abs((part * np.exp(1j * theta)).sum()), abs((part * np.exp(-1j * theta)).sum())
        ) / np.sqrt(part.size / 2)
        modes.append(CompassMode(float(values[k].real), float(uniform), float(min(harmonic, 1.0))))
    return tuple(modes)


def ring_attractor_gains(
    source: NDArray[np.integer],
    target: NDArray[np.integer],
    anatomical: NDArray[np.floating],
    edge_group: NDArray[np.integer],
    gains: NDArray[np.floating],
    members: NDArray[np.bool_],
    angles: NDArray[np.floating],
    *,
    maximum_gain: float,
    point: CompassOperatingPoint,
) -> RingSearchResult:
    """Search effective gains of groups inside ``members`` for a leading first-harmonic mode.

    A deterministic, seeded multiplicative search over the groups whose edges stay inside the
    compass; other groups keep ``gains``. Returns effective gains per group.
    """
    inside = members[source] & members[target]
    changed = np.unique(edge_group[inside]).astype(np.int64)
    if not np.all(inside[np.isin(edge_group, changed)]):
        raise ValueError("compass gain groups must not also carry edges outside the compass")
    current = np.asarray(gains, dtype=np.float64).copy()
    upper = maximum_gain * 0.999

    def score(candidate: NDArray[np.float64]) -> tuple[float, float, float]:
        weight = anatomical * candidate[edge_group]
        modes = compass_modes(source, target, weight, members, angles)
        harmonic = max((m.eigenvalue for m in modes if m.harmonic > _HARMONIC_MODE), default=-1.0)
        other = max((m.eigenvalue for m in modes if m.harmonic <= _HARMONIC_MODE), default=0.0)
        loss = (
            (harmonic - point.harmonic_eigenvalue) ** 2
            + 4 * max(0.0, other - point.max_other_eigenvalue) ** 2
            + 4 * max(0.0, other - harmonic + 0.15) ** 2
        )
        return loss, harmonic, other

    rng = np.random.default_rng(point.seed)
    best = score(current)
    for _ in range(point.iterations):
        candidate = current.copy()
        group = changed[rng.integers(changed.size)]
        candidate[group] = np.clip(candidate[group] * np.exp(rng.normal(0, 0.5)), 0.01, upper)
        trial = score(candidate)
        if trial[0] < best[0]:
            current, best = candidate, trial
    modes = compass_modes(source, target, anatomical * current[edge_group], members, angles)
    return RingSearchResult(current, changed, best[1], best[2], modes)


def bump_metrics(
    activity: NDArray[np.floating],
    angles: NDArray[np.floating],
    yaw: NDArray[np.floating],
    mask: NDArray[np.bool_],
    *,
    turning_threshold: float = 0.5,
) -> dict[str, float | int | None]:
    """Bump strength and how the bump phase follows yaw, for EPG activity of shape (B, T, N).

    Strength is the normalised population-vector length of rectified, baseline-subtracted
    release (0 flat, 1 a single active glomerulus). The phase-yaw gain is degrees of bump
    rotation per degree of measured yaw on turning frames.
    """
    release = np.clip(0.5 * np.tanh(2.0 * np.asarray(activity)), 0.0, None)
    release = release - release.min(axis=2, keepdims=True)
    vector = (release * np.exp(1j * np.asarray(angles))).sum(axis=2)
    strength = np.abs(vector) / np.maximum(release.sum(axis=2), 1e-9)
    phase = np.degrees(np.angle(vector))
    step = (np.diff(phase, axis=1) + 180.0) % 360.0 - 180.0
    turning = mask[:, 1:] & mask[:, :-1] & (np.abs(yaw[:, 1:]) > turning_threshold)
    x, y = np.asarray(yaw)[:, 1:][turning], step[turning]
    corr = None
    if x.size > 2 and x.std() > 0 and y.std() > 0:
        corr = float(np.corrcoef(x, y)[0, 1])
    return {
        "bump_strength": float(strength[mask].mean()),
        "turning_frames": int(turning.sum()),
        "phase_yaw_corr": corr,
        "phase_yaw_gain": float(np.dot(x, y) / np.dot(x, x)) if x.size else None,
    }


def type_pair_raw_gains(
    raw_gains: NDArray[np.floating],
    edge_group: NDArray[np.integer],
    source_types: NDArray[np.str_],
    target_types: NDArray[np.str_],
    gains: dict[str, float],
    maximum_gain: float,
) -> NDArray[np.float32]:
    """Set bounded raw gains so named ``SOURCE>TARGET`` cell-type pairs take effective ``gains``.

    ``source_types`` and ``target_types`` are per edge. Each pair must name exactly one group.
    """
    updated = np.asarray(raw_gains, dtype=np.float32).copy()
    pairs = np.char.add(np.char.add(source_types.astype(str), ">"), target_types.astype(str))
    for key, value in gains.items():
        groups = np.unique(edge_group[pairs == key])
        if groups.size != 1:
            raise ValueError(f"compass gain {key!r} matches no edge group")
        updated[groups[0]] = np.log(value / (maximum_gain - value))
    return updated


def connectivity_angles(
    source: NDArray[np.integer],
    target: NDArray[np.integer],
    weight: NDArray[np.floating],
    angles: NDArray[np.floating],
    assign: NDArray[np.bool_],
    *,
    passes: int = 2,
) -> tuple[NDArray[np.float64], NDArray[np.float64]]:
    """Heading angles for neurons without one, from the weighted circular mean of angled inputs.

    Neurons in ``assign`` that lack an angle take the angle of the summed vector
    ``sum(|w| exp(i theta_source))`` over angled presynaptic partners. Each pass can reach neurons
    one synapse further. Returns angles and the concentration ``|sum| / sum |w|`` (NaN where
    unassigned). Fan-shaped-body columns are compressed at the edges relative to their labels, so
    connectivity defines their heading.
    """
    result = np.asarray(angles, dtype=np.float64).copy()
    concentration = np.full(result.size, np.nan)
    magnitude = np.abs(np.asarray(weight, dtype=np.float64))
    for _ in range(passes):
        known = np.isfinite(result)
        use = known[source] & assign[target] & ~known[target]
        if not use.any():
            break
        vector = np.zeros(result.size, dtype=np.complex128)
        total = np.zeros(result.size)
        np.add.at(vector, target[use], magnitude[use] * np.exp(1j * result[source[use]]))
        np.add.at(total, target[use], magnitude[use])
        reached = total > 0
        result[reached] = np.angle(vector[reached])
        concentration[reached] = np.abs(vector[reached]) / total[reached]
    return result, concentration
