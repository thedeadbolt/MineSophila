import dataclasses

import numpy as np
import pytest

from fly_brain.connectome.integrator import (
    CompassCircuit,
    IntegratorFitConfig,
    IntegratorStage,
    _batch,  # pyright: ignore[reportPrivateUsage]
    _loss,  # pyright: ignore[reportPrivateUsage]
    constant_turn_response,
    fit_compass_integrator,
    initial_parameters,
    parameters_from_fit,
    simulate,
)

COMPASS = ("EPG", "PEN_a(PEN1)", "PEN_b(PEN2)", "PEG", "Delta7")


def ring_circuit() -> CompassCircuit:
    """Eight EPG headings, a left and right PEN_a per heading shifted one step, one Delta7."""
    epg = list(range(8))
    left = list(range(8, 16))
    right = list(range(16, 24))
    delta7 = 24
    types = ["EPG"] * 8 + ["PEN_a(PEN1)"] * 16 + ["Delta7"]
    sides = ["L"] * 8 + ["L"] * 8 + ["R"] * 8 + ["L"]
    angles = np.full(25, np.nan)
    angles[:8] = np.radians(np.arange(8) * 45.0)
    edges: list[tuple[int, int, float, str]] = []
    for i in epg:
        for step in (-1, 1):
            edges.append((i, (i + step) % 8, 0.3, "EPG>EPG"))
        edges.append((i, left[i], 0.5, "EPG>PEN_a(PEN1)"))
        edges.append((i, right[i], 0.5, "EPG>PEN_a(PEN1)"))
        edges.append((left[i], (i + 1) % 8, 0.4, "PEN_a(PEN1)>EPG"))
        edges.append((right[i], (i - 1) % 8, 0.4, "PEN_a(PEN1)>EPG"))
        edges.append((i, delta7, 0.125, "EPG>Delta7"))
        edges.append((delta7, i, -0.3, "Delta7>EPG"))
    pairs = tuple(sorted({edge[3] for edge in edges}))
    return CompassCircuit(
        cell_types=np.array(types),
        angles=angles,
        sides=np.array(sides),
        source=np.array([e[0] for e in edges], dtype=np.int32),
        target=np.array([e[1] for e in edges], dtype=np.int32),
        weight=np.array([e[2] for e in edges], dtype=np.float32),
        group=np.array([pairs.index(e[3]) for e in edges], dtype=np.int32),
        group_pairs=pairs,
        compass_types=COMPASS,
        leak=0.5,
        maximum_gain=4.0,
    )


def small_config(steps: int = 40) -> IntegratorFitConfig:
    return IntegratorFitConfig(
        stages=(IntegratorStage(steps=steps, learning_rate=0.02, bump_gain=0.3),),
        transitions_per_frame=2,
        batch_size=8,
        frames=40,
        kick_frames=6,
    )


def test_simulate_returns_finite_epg_population_vectors() -> None:
    circuit = ring_circuit()
    config = small_config()
    params = initial_parameters(circuit, np.ones(len(circuit.group_pairs)), config)
    yaw, kick, _ = _batch(
        circuit, config, config.stages[0], np.random.default_rng(0), [], config.batch_size
    )

    out = np.asarray(simulate(circuit, params, yaw, kick, transitions=2, sharpness=20.0))

    assert out.shape == (8, 40, 3)
    assert np.isfinite(out).all()
    assert (out[..., 2] >= 0).all()


def test_fit_lowers_the_phase_loss_and_returns_named_values() -> None:
    circuit = ring_circuit()
    config = small_config()
    start = np.ones(len(circuit.group_pairs))
    validation = _batch(circuit, config, config.stages[0], np.random.default_rng(99), [], 16)
    before = float(
        _loss(circuit, config, initial_parameters(circuit, start, config), *validation)[1][0]
    )

    fit = fit_compass_integrator(circuit, start, [], config, progress_interval=10)

    after = float(_loss(circuit, config, parameters_from_fit(circuit, fit), *validation)[1][0])
    assert after < before
    assert set(fit.gains) == set(circuit.group_pairs)
    assert all(0 < value < 4.0 for value in fit.gains.values())
    assert set(fit.release_thresholds) == {"PEN_a(PEN1)", "PEN_b(PEN2)"}
    assert fit.self_motion_scale > 0
    assert len(fit.history) == 4
    response = constant_turn_response(circuit, fit, config)
    assert set(response) == {"hold_error_deg", "velocity_deg_per_frame"}


def test_real_yaw_sequences_feed_the_batch() -> None:
    circuit = ring_circuit()
    config = small_config()
    stage = IntegratorStage(steps=1, learning_rate=0.01, bump_gain=0.5, real_yaw_fraction=1.0)
    recorded = [np.full(50, 2.0, dtype=np.float32)]

    yaw, _, target = _batch(circuit, config, stage, np.random.default_rng(0), recorded, 4)

    assert np.allclose(np.asarray(yaw)[:, config.kick_frames :], 2.0)
    assert np.asarray(yaw)[:, : config.kick_frames].sum() == 0
    step = np.diff(np.degrees(np.asarray(target)), axis=1)[:, config.kick_frames]
    np.testing.assert_allclose(step, 1.0, rtol=1e-4)


def test_integrator_settings_are_validated() -> None:
    with pytest.raises(ValueError, match="stage"):
        IntegratorFitConfig(stages=())
    with pytest.raises(ValueError, match="bump_gain"):
        IntegratorStage(steps=1, learning_rate=0.1, bump_gain=0.0)
    circuit = ring_circuit()
    with pytest.raises(ValueError, match="heading angle"):
        dataclasses.replace(circuit, angles=np.full(circuit.cell_types.size, np.nan))
