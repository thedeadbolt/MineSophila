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


def test_model_config_validates_release_thresholds_and_fitted_compass() -> None:
    fitted = ConnectomeModelConfig(
        activation="graded",
        edge_gain_mode="cell_type_pair",
        maximum_edge_gain=4.0,
        components=("photoreceptors", "visual_projection", "compass"),
        compass_operating_point="fitted",
        compass_gains={"EPG>Delta7": 3.9, "PEN_a(PEN1)>EPG": 2.0},
        compass_type_bias={"EPG": 0.1},
        release_thresholds={"PEN_a(PEN1)": -0.1},
    )
    assert fitted.compass_gains["EPG>Delta7"] == 3.9
    with pytest.raises(ValueError, match="graded"):
        ConnectomeModelConfig(release_thresholds={"PEN_a(PEN1)": 0.1})
    with pytest.raises(ValueError, match="compass_gains"):
        ConnectomeModelConfig(
            edge_gain_mode="cell_type_pair",
            maximum_edge_gain=4.0,
            components=("photoreceptors", "visual_projection", "compass"),
            compass_operating_point="fitted",
        )
    with pytest.raises(ValueError, match="SOURCE>TARGET"):
        ConnectomeModelConfig(
            edge_gain_mode="cell_type_pair",
            maximum_edge_gain=4.0,
            components=("photoreceptors", "visual_projection", "compass"),
            compass_operating_point="fitted",
            compass_gains={"EPG-Delta7": 1.0},
        )
    with pytest.raises(ValueError, match="maximum"):
        ConnectomeModelConfig(
            edge_gain_mode="cell_type_pair",
            maximum_edge_gain=4.0,
            components=("photoreceptors", "visual_projection", "compass"),
            compass_operating_point="fitted",
            compass_gains={"EPG>Delta7": 4.0},
        )


def test_type_pair_gains_set_the_named_groups_and_reject_unknown_pairs() -> None:
    from fly_brain.connectome.compass import type_pair_raw_gains

    source_types = np.array(["EPG", "EPG", "Delta7", "PEN"])
    target_types = np.array(["Delta7", "Delta7", "EPG", "EPG"])
    edge_group = np.array([0, 0, 1, 2])
    raw = np.zeros(3, dtype=np.float32)

    updated = type_pair_raw_gains(
        raw, edge_group, source_types, target_types, {"EPG>Delta7": 3.0, "PEN>EPG": 1.0}, 4.0
    )

    effective = 4.0 / (1.0 + np.exp(-updated))
    np.testing.assert_allclose(effective, [3.0, 2.0, 1.0], rtol=1e-5)
    with pytest.raises(ValueError, match="no edge group"):
        type_pair_raw_gains(raw, edge_group, source_types, target_types, {"PEG>EPG": 1.0}, 4.0)


def test_pb_glomerulus_angle_accepts_a_trailing_fan_shaped_body_column() -> None:
    assert pb_glomerulus_angle("PFL3(PB12c)_L1_C3") == pytest.approx(pb_glomerulus_angle("x_L1"))
    assert pb_glomerulus_angle("PFNd(PB09)_R4_C6") == pytest.approx(pb_glomerulus_angle("x_R4"))
    with pytest.raises(ValueError, match="glomerulus"):
        pb_glomerulus_angle("FC2A_C3_L")


def test_connectivity_angles_follow_weighted_angled_inputs() -> None:
    from fly_brain.connectome.compass import connectivity_angles

    # Neurons 0-3 have angles; 4 hears 0 and 1 equally; 5 hears 2 strongly and 0 (opposite) weakly;
    # 6 hears only 4, so it is reached in a second pass; 7 hears nothing angled.
    angles = np.array([0.0, np.pi / 2, np.pi, 0.0, np.nan, np.nan, np.nan, np.nan])
    source = np.array([0, 1, 2, 0, 4])
    target = np.array([4, 4, 5, 5, 6])
    weight = np.array([1.0, 1.0, 3.0, 1.0, 1.0])
    assign = np.array([False, False, False, False, True, True, True, True])

    result, concentration = connectivity_angles(source, target, weight, angles, assign, passes=2)

    assert result[4] == pytest.approx(np.pi / 4)
    assert abs(np.angle(np.exp(1j * (result[5] - np.pi)))) < 1e-6
    assert concentration[5] == pytest.approx(0.5)
    assert result[6] == pytest.approx(np.pi / 4)
    assert np.isnan(result[7])
    assert result[0] == 0.0


def test_pfn_vector_input_requires_its_components() -> None:
    fb = ("vector_inputs", "fan_shaped_body_columns", "goal_memory")
    with pytest.raises(ValueError, match="pfn_vector egocentric input reads visual_projection"):
        ConnectomeModelConfig(
            components=("photoreceptors", "lamina_relays", *fb),
            readout_components=("photoreceptors",),
            egocentric_input="pfn_vector",
        )
    with pytest.raises(ValueError, match="vector_inputs"):
        ConnectomeModelConfig(egocentric_input="pfn_vector")
    ok = ConnectomeModelConfig(
        components=("photoreceptors", "visual_projection", *fb), egocentric_input="pfn_vector"
    )
    assert ok.egocentric_input == "pfn_vector"


def test_projection_offsets_average_target_minus_source_angle() -> None:
    from fly_brain.connectome.compass import projection_offsets

    # Source 0 at 0 rad projects to targets at +45 deg (w 3) and +45 deg (w 1): offset +45.
    # Source 1 at 90 deg projects to a target at 45 deg: offset -45. Source 2 has no target.
    angles = np.radians(np.array([0.0, 90.0, 0.0, 45.0, 45.0]))
    source = np.array([0, 0, 1])
    target = np.array([3, 4, 3])
    weight = np.array([3.0, 1.0, 2.0])
    sources = np.array([True, True, True, False, False])
    targets = np.array([False, False, False, True, True])

    offsets = projection_offsets(source, target, weight, angles, sources, targets)

    assert offsets[0] == pytest.approx(np.radians(45.0))
    assert offsets[1] == pytest.approx(np.radians(-45.0))
    assert np.isnan(offsets[2])
    assert np.isnan(offsets[3])
