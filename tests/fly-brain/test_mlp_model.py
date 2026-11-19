import numpy as np

from fly_brain.models import LocalizationBatch, MLPLocalizationModel, train_mlp


def test_mlp_learns_balanced_nonlinear_localization() -> None:
    features = np.array(
        [
            [-1.0, -1.0],
            [-1.0, 1.0],
            [1.0, -1.0],
            [1.0, 1.0],
        ]
        * 4,
        dtype=np.float32,
    )
    presence = np.array([0.0, 1.0, 1.0, 0.0] * 4, dtype=np.float32)
    direction = np.zeros((16, 3), dtype=np.float32)
    direction[presence > 0] = np.array([1.0, 0.0, 0.0], dtype=np.float32)
    batch = LocalizationBatch(
        features=features,
        time_mask=np.ones(16, dtype=np.bool_),
        presence=presence,
        direction=direction,
        coverage=None,
        site_count=1,
    )
    model = MLPLocalizationModel(input_width=2, hidden_width=16)

    result = train_mlp(
        model,
        batch,
        steps=300,
        batch_size=16,
        learning_rate=0.01,
        seed=2,
    )
    prediction = model.predict_features(result.parameters, features)

    assert result.losses[-1] < result.losses[0] * 0.1
    np.testing.assert_array_equal(prediction.presence_probability >= 0.5, presence > 0)
    assert np.all(prediction.direction[presence > 0, 0] > 0.99)
