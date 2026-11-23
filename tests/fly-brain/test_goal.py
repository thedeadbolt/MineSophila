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
