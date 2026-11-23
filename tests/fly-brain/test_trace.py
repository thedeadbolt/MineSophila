import numpy as np
import pytest

from fly_brain.connectome.trace import (
    bump_phase_strength,
    component_flow,
    primary_components,
    readout_decoding,
)


def test_bump_phase_and_strength_per_frame() -> None:
    angles = np.radians(np.arange(8) * 45.0)
    peak = np.maximum(np.cos(angles - np.radians(90.0)), 0.0)
    activity = np.stack([peak, np.ones(8)])  # frame 0: bump at 90 deg; frame 1: flat

    phase, strength = bump_phase_strength(activity, angles)

    assert phase[0] == pytest.approx(90.0, abs=1e-4)
    assert strength[0] > 0.5
    assert strength[1] < 1e-6


def test_primary_component_is_the_last_listed_containing_the_neuron() -> None:
    members = {
        "visual_projection": np.array([1, 2, 3]),
        "anterior_visual_inputs": np.array([2]),
        "compass": np.array([4]),
    }
    ids = np.array([1, 2, 3, 4, 5])

    names, index = primary_components(
        ids, members, ("visual_projection", "anterior_visual_inputs", "compass")
    )

    assert names == ("visual_projection", "anterior_visual_inputs", "compass")
    assert index.tolist() == [0, 1, 0, 2, -1]


def test_component_flow_sums_signed_release_per_component_pair() -> None:
    # Neurons 0,1 in component 0; neuron 2 in component 1; neuron 3 unassigned.
    release = np.array([[0.5, -0.5, 0.25, 1.0], [0.0, 0.5, 0.0, 1.0]], dtype=np.float32)
    source = np.array([0, 1, 2, 3, 2])
    target = np.array([2, 2, 0, 2, 2])
    weight = np.array([2.0, 1.0, -4.0, 1.0, 1.0], dtype=np.float32)
    component = np.array([0, 0, 1, -1])

    flow = component_flow(release, source, target, weight, component, 2)

    assert flow.shape == (2, 2, 2)
    # frame 0: 0->1 gets 0.5*2 + (-0.5)*1 = 0.5; 1->0 gets 0.25*-4 = -1; 1->1 self 0.25
    np.testing.assert_allclose(flow[0], [[0.0, 0.5], [-1.0, 0.25]])
    np.testing.assert_allclose(flow[1], [[0.0, 0.5], [0.0, 0.0]])


def test_readout_decoding_matches_the_training_convention() -> None:
    raw = np.array([[0.0, 1.0, 1.0, 0.0], [10.0, 0.0, -2.0, 2.0]], dtype=np.float32)

    probability, azimuth, elevation = readout_decoding(raw)

    np.testing.assert_allclose(probability, [0.5, 1 / (1 + np.exp(-10))], rtol=1e-6)
    np.testing.assert_allclose(azimuth, [45.0, -90.0], atol=1e-5)
    np.testing.assert_allclose(elevation, [0.0, 45.0], atol=1e-5)
