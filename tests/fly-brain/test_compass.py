import numpy as np
import pytest

from fly_brain.connectome.compass import (
    CompassOperatingPoint,
    bump_metrics,
    compass_modes,
    pb_glomerulus_angle,
    ring_attractor_gains,
)
from fly_brain.models import ConnectomeModelConfig


def ring_fixture():
    """Eight EPG-like neurons on a ring with neighbour excitation and one global inhibitor."""
    epg = 8
    sources: list[int] = []
    targets: list[int] = []
    weights: list[float] = []
    groups: list[int] = []
    for index in range(epg):
        for step in (-1, 1):
            sources.append(index)
            targets.append((index + step) % epg)
            weights.append(0.3)
            groups.append(0)
        sources.append(index)
        targets.append(epg)
        weights.append(0.1)
        groups.append(1)
        sources.append(epg)
        targets.append(index)
        weights.append(-0.1)
        groups.append(2)
    angles = np.full(epg + 1, np.nan)
    angles[:epg] = np.radians(np.arange(epg) * 45.0)
    return (
        np.array(sources),
        np.array(targets),
        np.array(weights, dtype=np.float32),
        np.array(groups),
        np.ones(epg + 1, dtype=np.bool_),
        angles,
    )


def test_pb_glomerulus_angle_reads_instance_names() -> None:
    # Heading runs along the bridge, L8..L1 then R1..R8, so Ln and R(9-n) share a heading
    # (MaleCNS Delta7 instances such as L4R5 connect them), and L9 wraps onto L1.
    assert pb_glomerulus_angle("EPG(PB08)_R1") == pytest.approx(0.0)
    assert pb_glomerulus_angle("EPG(PB08)_R4") == pytest.approx(np.radians(135.0))
    assert pb_glomerulus_angle("EPG(PB08)_L5") == pytest.approx(np.radians(135.0))
    assert pb_glomerulus_angle("EPG(PB08)_L8") == pytest.approx(0.0)
    assert pb_glomerulus_angle("PEN_a(PB06a)_L9") == pytest.approx(pb_glomerulus_angle("x_L1"))
    assert pb_glomerulus_angle("PEG(PB07)_R9") == pytest.approx(pb_glomerulus_angle("x_R1"))
    with pytest.raises(ValueError, match="glomerulus"):
        pb_glomerulus_angle("EPG(PB08)")


def test_compass_modes_separate_uniform_and_first_harmonic() -> None:
    source, target, weight, group, members, angles = ring_fixture()
    modes = compass_modes(source, target, weight * np.ones(3)[group], members, angles)
    uniform = [mode for mode in modes if mode.uniform > 0.9]
    harmonic = [mode for mode in modes if mode.harmonic > 0.9]
    # Neighbour excitation 0.3: uniform mode 0.6 minus inhibition; first harmonic 0.6 cos 45.
    assert harmonic
    assert harmonic[0].eigenvalue == pytest.approx(0.6 * np.cos(np.pi / 4), abs=1e-4)
    assert uniform


def test_ring_attractor_gains_make_the_first_harmonic_lead() -> None:
    source, target, weight, group, members, angles = ring_fixture()
    point = CompassOperatingPoint(iterations=3000)

    result = ring_attractor_gains(
        source, target, weight, group, np.ones(3), members, angles, maximum_gain=4.0, point=point
    )

    assert result.harmonic_eigenvalue == pytest.approx(point.harmonic_eigenvalue, abs=0.02)
    assert result.other_eigenvalue <= point.max_other_eigenvalue + 0.02
    assert np.all((result.gains > 0) & (result.gains < 4.0))
    again = ring_attractor_gains(
        source, target, weight, group, np.ones(3), members, angles, maximum_gain=4.0, point=point
    )
    np.testing.assert_array_equal(result.gains, again.gains)


def test_ring_attractor_gains_change_only_compass_groups() -> None:
    source, target, weight, group, members, angles = ring_fixture()
    source = np.append(source, 0)
    target = np.append(target, 9)
    weight = np.append(weight, 0.5).astype(np.float32)
    group = np.append(group, 3)
    members = np.append(members, False)
    angles = np.append(angles, np.nan)

    result = ring_attractor_gains(
        source,
        target,
        weight,
        group,
        np.array([1.0, 1.0, 1.0, 2.5]),
        members,
        angles,
        maximum_gain=4.0,
        point=CompassOperatingPoint(iterations=500),
    )

    assert result.gains[3] == 2.5
    assert set(result.changed_groups.tolist()) == {0, 1, 2}


def test_operating_point_rejects_bad_settings() -> None:
    with pytest.raises(ValueError, match="eigenvalue"):
        CompassOperatingPoint(harmonic_eigenvalue=0.5, max_other_eigenvalue=0.8)
    with pytest.raises(ValueError, match="tonic"):
        CompassOperatingPoint(tonic_bias=-0.1)


def test_bump_metrics_find_a_rotating_bump() -> None:
    angles = np.radians(np.arange(8) * 45.0)
    phase = np.radians(np.arange(20) * 10.0)
    activity = np.maximum(np.cos(angles[None, :] - phase[:, None]), 0)[None]
    yaw = np.full((1, 20), 10.0)

    metrics = bump_metrics(activity, angles, yaw, np.ones((1, 20), dtype=np.bool_))

    strength = metrics["bump_strength"]
    assert strength is not None
    assert strength > 0.5
    assert metrics["phase_yaw_corr"] is None or metrics["phase_yaw_gain"] == pytest.approx(1.0)
    flat = bump_metrics(np.ones_like(activity), angles, yaw, np.ones((1, 20), dtype=np.bool_))
    flat_strength = flat["bump_strength"]
    assert flat_strength is not None
    assert flat_strength < 0.05


def test_model_config_validates_the_compass_operating_point() -> None:
    assert ConnectomeModelConfig().compass_operating_point == "none"
    with pytest.raises(ValueError, match="compass component"):
        ConnectomeModelConfig(
            components=("photoreceptors",), compass_operating_point="ring_attractor"
        )
    with pytest.raises(ValueError, match="tonic"):
        ConnectomeModelConfig(compass_tonic_bias=-1.0)
