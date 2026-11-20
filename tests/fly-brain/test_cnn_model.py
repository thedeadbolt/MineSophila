import numpy as np

from fly_brain.models import CNNLocalizationModel, LocalizationBatch, train_cnn


def test_cnn_has_expected_parameter_count_and_learns_bright_target() -> None:
    images = np.zeros((16, 8, 8, 3), dtype=np.float32)
    presence = np.array([0.0, 1.0] * 8, dtype=np.float32)
    images[presence > 0, 3:5, 3:5] = 1.0
    direction = np.zeros((16, 3), dtype=np.float32)
    direction[presence > 0, 0] = 1.0
    batch = LocalizationBatch(
        features=images.reshape(16, -1),
        time_mask=np.ones(16, dtype=np.bool_),
        presence=presence,
        direction=direction,
        coverage=None,
        site_count=1,
    )
    model = CNNLocalizationModel(
        image_height=8,
        image_width=8,
        channel_widths=(2, 4),
        kernel_sizes=(3, 3),
        strides=(2, 2),
    )

    result = train_cnn(model, batch, steps=100, batch_size=16, learning_rate=0.01, seed=0)
    prediction = model.predict_features(result.parameters, batch.features)

    assert model.trainable_parameter_count == 168
    assert result.losses[-1] < result.losses[0] * 0.2
    np.testing.assert_array_equal(prediction.presence_probability >= 0.5, presence > 0)
    assert np.all(prediction.direction[presence > 0, 0] > 0.99)
