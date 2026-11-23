import numpy as np
import pytest

from fly_brain.connectome.goal import (
    GoalHoldConfig,
    _hold_batch,  # pyright: ignore[reportPrivateUsage]
    fit_goal_hold,
    goal_hold_error,
)
from fly_brain.connectome.subcircuit import SubCircuit


def goal_ring() -> tuple[SubCircuit, np.ndarray]:
    """Eight FC2-like headings with neighbour excitation and one inhibitory FB5A-like loop."""
    edges: list[tuple[int, int, float, str]] = []
    for i in range(8):
        for step in (-1, 1):
            edges.append((i, (i + step) % 8, 0.3, "FC2A>FC2A"))
        edges.append((i, 8, 0.125, "FC2A>FB5A"))
        edges.append((8, i, -0.3, "FB5A>FC2A"))
    pairs = ("FB5A>FC2A", "FC2A>FB5A", "FC2A>FC2A")
    circuit = SubCircuit(
        cell_types=np.array(["FC2A"] * 8 + ["FB5A"]),
        neuron_index=np.arange(9),
        source=np.array([e[0] for e in edges], dtype=np.int32),
        target=np.array([e[1] for e in edges], dtype=np.int32),
        group=np.array([pairs.index(e[3]) for e in edges], dtype=np.int32),
        weight=np.array([e[2] for e in edges], dtype=np.float32),
        group_pairs=pairs,
        global_groups=np.arange(3),
        gate=np.zeros(9, dtype=np.bool_),
        leak=0.5,
        maximum_gain=4.0,
    )
    angles = np.append(np.radians(np.arange(8) * 45.0), np.nan)
    return circuit, angles


def small_config(steps: int = 40) -> GoalHoldConfig:
    return GoalHoldConfig(
        steps=steps,
        learning_rate=0.05,
        frames=40,
        kick_frames=6,
        transitions_per_frame=2,
        batch_size=8,
    )


def test_goal_hold_fit_lowers_hold_error() -> None:
    circuit, angles = goal_ring()
    config = small_config()
    start = np.ones(len(circuit.group_pairs))  # too weak to hold: the bump fades
    validation = _hold_batch(circuit, angles, config, np.random.default_rng(99), 16)
    before = goal_hold_error(circuit, angles, start, None, config, validation)

    fit = fit_goal_hold(circuit, angles, start, config)

    after = goal_hold_error(circuit, angles, None, fit, config, validation)
    assert before == pytest.approx(90.0)
    assert after < before
    assert fit.hold_error_deg == pytest.approx(
        goal_hold_error(circuit, angles, None, fit, config, None), abs=1e-6
    )


def test_goal_fit_returns_every_internal_pair() -> None:
    circuit, angles = goal_ring()

    fit = fit_goal_hold(circuit, angles, np.ones(3), small_config(steps=3))

    assert set(fit.gains) == set(circuit.group_pairs)
    assert all(0 < value < 4.0 for value in fit.gains.values())
    assert set(fit.type_bias) == {"FC2A", "FB5A"}
    assert len(fit.history) >= 1


def test_goal_hold_config_rejects_bad_settings() -> None:
    with pytest.raises(ValueError, match="steps"):
        GoalHoldConfig(steps=0, learning_rate=0.1)
    with pytest.raises(ValueError, match="frames"):
        GoalHoldConfig(steps=1, learning_rate=0.1, frames=5, kick_frames=10)


def test_model_config_validates_the_fitted_goal() -> None:
    from fly_brain.models import ConnectomeModelConfig

    fb = ("vector_inputs", "fan_shaped_body_columns", "goal_memory", "goal_tangentials")
    base = {
        "edge_gain_mode": "cell_type_pair",
        "maximum_edge_gain": 4.0,
        "components": ("photoreceptors", "visual_projection", *fb),
    }
    fitted = ConnectomeModelConfig(
        **base,  # pyright: ignore[reportArgumentType]
        goal_operating_point="fitted",
        goal_gains={"FC2A>FB5A": 2.0},
        goal_type_bias={"FB5A": -0.1},
    )
    assert fitted.goal_gains == {"FC2A>FB5A": 2.0}
    with pytest.raises(ValueError, match="goal_gains"):
        ConnectomeModelConfig(**base, goal_operating_point="fitted")  # pyright: ignore[reportArgumentType]
    with pytest.raises(ValueError, match="goal_memory"):
        ConnectomeModelConfig(goal_operating_point="fitted", goal_gains={"FC2A>FB5A": 1.0})
    with pytest.raises(ValueError, match="need a fitted goal"):
        ConnectomeModelConfig(goal_gains={"FC2A>FB5A": 1.0})


def test_goal_heading_decoding_recovers_a_planted_offset() -> None:
    from fly_brain.connectome.goal import goal_heading_decoding

    rng = np.random.default_rng(0)
    offset = rng.uniform(-np.pi, np.pi, 200)
    planted = np.stack([np.cos(offset), np.sin(offset), 0.01 * rng.standard_normal(200)], axis=1)
    noise = rng.standard_normal((200, 3))

    good = goal_heading_decoding(planted, offset)
    bad = goal_heading_decoding(noise, offset)

    assert good["error_deg"] < 2
    assert bad["error_deg"] > 60
    assert good["trials"] == 200


def test_probe_goal_heading_decodes_a_readout_that_sees_both_bumps() -> None:
    from fly_brain.connectome.goal import probe_goal_heading

    # Two 8-neuron rings ("EPG" heading, "FC2A" goal) that each hold a kicked bump, and an
    # 8-neuron "PFL3" layer driven by both: its pattern depends on goal minus heading.
    n = 8
    edges: list[tuple[int, int, float, str]] = []
    for base, name in ((0, "EPG"), (n, "FC2A")):
        for i in range(n):
            for j in range(n):
                near = (i - j) % n in (1, n - 1)
                if i != j:
                    weight = 0.3 if near else -0.12  # neighbour excitation, broad inhibition
                    edges.append((base + i, base + j, weight, f"{name}>{name}"))
    # Left and right PFL3 populations read the goal shifted in opposite directions, as in the fly.
    for side, shift in ((0, 2), (n, -2)):
        for i in range(n):
            edges.append((i, 2 * n + side + i, 0.5, "EPG>PFL3"))
            edges.append((n + (i + shift) % n, 2 * n + side + i, 0.5, "FC2A>PFL3"))
    pairs = tuple(sorted({e[3] for e in edges}))
    types = ["EPG"] * n + ["FC2A"] * n + ["PFL3"] * (2 * n)
    circuit = SubCircuit(
        cell_types=np.array(types),
        neuron_index=np.arange(4 * n),
        source=np.array([e[0] for e in edges], dtype=np.int32),
        target=np.array([e[1] for e in edges], dtype=np.int32),
        group=np.array([pairs.index(e[3]) for e in edges], dtype=np.int32),
        weight=np.array([e[2] for e in edges], dtype=np.float32),
        group_pairs=pairs,
        global_groups=np.arange(len(pairs)),
        gate=np.array(types) == "PFL3",  # PFL3 release is thresholded, as spiking output
        leak=0.5,
        maximum_gain=4.0,
    )
    ring = np.radians(np.arange(n) * 45.0)
    angles = np.concatenate([ring, ring, np.full(2 * n, np.nan)])
    raw = np.zeros(len(pairs))  # effective gain 2: both rings hold

    result = probe_goal_heading(
        circuit,
        angles,
        raw,
        np.zeros(4 * n),
        np.where(circuit.gate, 0.4, 0.0),
        heading=circuit.cell_types == "EPG",
        goal=circuit.cell_types == "FC2A",
        readout=circuit.cell_types == "PFL3",
        transitions=2,
        trials=128,
    )

    # Linear decoding of a product of two bumps is coarse; well above chance (90) is the bar.
    assert result["error_deg"] < 50


def test_goal_targets_carry_the_last_sighting() -> None:
    from fly_brain.connectome.goal import goal_targets

    visible = np.array([[False, True, True, False, False]])
    bearing = np.radians(np.array([[0.0, 10.0, 20.0, 30.0, 40.0]]))
    heading = np.zeros((1, 5))

    target, mask = goal_targets(heading, bearing, visible, bump_gain=0.5)

    assert mask.tolist() == [[False, True, True, True, True]]
    np.testing.assert_allclose(np.degrees(target[0, 1:]), [5.0, 10.0, 10.0, 10.0], atol=1e-9)


def test_goal_targets_mask_clips_never_visible() -> None:
    from fly_brain.connectome.goal import goal_targets, masked_phase_loss

    visible = np.zeros((2, 4), dtype=np.bool_)
    target, mask = goal_targets(np.zeros((2, 4)), np.zeros((2, 4)), visible, bump_gain=0.4)
    vector = np.zeros((2, 4, 2))

    assert not mask.any()
    assert float(masked_phase_loss(vector, target, mask)) == 0.0


def test_goal_write_fit_returns_learned_and_frozen_values() -> None:
    from fly_brain.connectome.goal import GoalWriteConfig, fit_goal_write

    circuit, angles = goal_ring()
    n = circuit.cell_types.size
    config = GoalWriteConfig(
        steps=3, learning_rate=0.01, bump_gain=0.5, frames=20, kick_frames=4,
        transitions_per_frame=2, batch_size=2,
    )
    rng = np.random.default_rng(0)
    clips = {
        "yaw": rng.normal(0, 2, (3, 30)).astype(np.float32),
        "bearing": rng.uniform(-1, 1, (3, 30)),
        "visible": rng.random((3, 30)) < 0.5,
        "mask": np.ones((3, 30), dtype=np.bool_),
    }
    frozen = np.array([True, False, False])

    fit = fit_goal_write(
        circuit,
        angles,
        offsets=np.zeros(n),
        vector_targets=np.zeros(n, dtype=np.bool_),
        heading=circuit.cell_types == "FC2A",  # fixture: heading decoded from the same ring
        goal=circuit.cell_types == "FC2A",
        yaw_drive=np.zeros(n),
        initial_gains=np.ones(3),
        frozen=frozen,
        base_bias=np.zeros(n),
        base_threshold=np.zeros(n),
        clips=clips,
        config=config,
    )

    assert fit.gains[circuit.group_pairs[0]] == pytest.approx(1.0, abs=1e-6)
    assert set(fit.gains) == set(circuit.group_pairs)
    assert fit.egocentric_scale > 0
    assert np.isfinite(fit.write_error_deg)
