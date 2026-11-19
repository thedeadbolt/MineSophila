import numpy as np
import pytest

from fly_brain.contracts import BrainTargets
from fly_brain.tasks import HoneyLocalizationPrediction, evaluate_localization


def targets() -> BrainTargets:
    return BrainTargets(
        time_mask=np.array([True, True, True, False]),
        honey_present=np.array([True, True, False, True]),
        honey_direction=np.array([[1, 0, 0], [0, 1, 0], [0, 0, 0], [0, 0, 1]], dtype=np.float32),
        honey_coverage=np.array([[1, 0], [0, 1], [0, 0], [0.5, 0.5]], dtype=np.float32),
    )


def test_localisation_metrics_respect_time_mask_and_positive_targets() -> None:
    prediction = HoneyLocalizationPrediction(
        presence_probability=np.array([0.9, 0.8, 0.7, 0.0]),
        direction=np.array([[1, 0, 0], [1, 0, 0], [0, 0, 0], [1, 0, 0]], dtype=np.float32),
        coverage=np.array([[1, 0], [0.2, 0.8], [0, 0], [0, 0]], dtype=np.float32),
    )

    metrics = evaluate_localization(prediction, targets())

    assert metrics.sample_count == 3
    assert metrics.positive_count == 2
    assert metrics.presence_accuracy == pytest.approx(2 / 3)
    assert metrics.presence_balanced_accuracy == 0.5
    assert metrics.presence_precision == pytest.approx(2 / 3)
    assert metrics.presence_recall == 1.0
    assert metrics.presence_specificity == 0.0
    assert metrics.mean_bearing_error_deg == pytest.approx(45.0)
    assert metrics.coverage_mae == pytest.approx(0.4 / 6)


def test_bearing_error_is_absent_without_scorable_positive_direction() -> None:
    prediction = HoneyLocalizationPrediction(
        presence_probability=np.array([0.0]),
        direction=np.zeros((1, 3), dtype=np.float32),
    )
    negative = BrainTargets(
        time_mask=np.array([True]),
        honey_present=np.array([False]),
        honey_direction=np.zeros((1, 3), dtype=np.float32),
    )

    metrics = evaluate_localization(prediction, negative)

    assert metrics.mean_bearing_error_deg is None
    assert metrics.coverage_mae is None


def test_prediction_rejects_non_unit_directions_and_invalid_probabilities() -> None:
    with pytest.raises(ValueError, match="unit vectors or zero"):
        HoneyLocalizationPrediction(np.array([0.5]), np.array([[2.0, 0.0, 0.0]]))
    with pytest.raises(ValueError, match=r"\[0, 1\]"):
        HoneyLocalizationPrediction(np.array([1.1]), np.array([[1.0, 0.0, 0.0]]))
