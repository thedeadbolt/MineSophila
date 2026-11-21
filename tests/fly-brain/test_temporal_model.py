from dataclasses import replace

import numpy as np

from fly_brain.contracts import (
    Availability,
    BrainExample,
    BrainTargets,
    FeatureBlock,
    FeatureSchema,
    FeatureSpec,
    ObservationSequence,
    Provenance,
    SensorLayout,
)
from fly_brain.models import (
    ActionTemporalBatch,
    RecurrentActionModel,
    RecurrentLocalizationModel,
    RecurrentTrainingConfig,
    prepare_temporal_batch,
    train_recurrent,
    train_recurrent_actions,
)


def sequence(
    layout: SensorLayout,
    values: list[float],
    present: list[bool],
    scene: str,
    *,
    with_context: bool = False,
) -> BrainExample:
    steps = len(values)
    context = None
    if with_context:
        schema = FeatureSchema(
            (FeatureSpec("yaw_rate", "degrees/tick", "Turn rate", "fixture", Availability.AGENT),)
        )
        context = FeatureBlock(np.arange(steps, dtype=np.float32)[:, None], schema)
    observation = ObservationSequence(
        layout,
        np.repeat(
            np.asarray(values, dtype=np.float32)[:, None, None],
            layout.site_count,
            axis=1,
        ).repeat(layout.channel_count, axis=2),
        np.ones((steps, layout.site_count), dtype=np.bool_),
        np.arange(steps, dtype=np.float64),
        context,
    )
    direction = np.zeros((steps, 3), dtype=np.float32)
    direction[np.asarray(present)] = [1.0, 0.0, 0.0]
    return BrainExample(
        observation,
        BrainTargets(
            np.ones(steps, dtype=np.bool_),
            np.asarray(present),
            honey_direction=direction,
        ),
        Provenance(
            "fixture",
            scene,
            tuple(f"{scene}-{index}" for index in range(steps)),
            layout.config_hash,
            clip_id=scene,
        ),
    )


def test_temporal_batch_pads_sequences_and_keeps_context_optional(
    sensor_layout: SensorLayout,
) -> None:
    short = sequence(sensor_layout, [-1.0, 1.0], [False, True], "short", with_context=True)
    long = sequence(
        sensor_layout,
        [-1.0, -1.0, 1.0],
        [False, False, True],
        "long",
        with_context=True,
    )

    vision_only = prepare_temporal_batch([short, long])
    with_context = prepare_temporal_batch([short, long], include_context=True)

    assert vision_only.features.shape[:2] == (2, 3)
    assert vision_only.time_mask.tolist() == [[True, True, False], [True, True, True]]
    assert with_context.input_width == vision_only.input_width + 1
    assert with_context.features[0, 2].tolist() == [0.0] * with_context.input_width


def test_recurrent_state_can_continue_a_sequence(sensor_layout: SensorLayout) -> None:
    item = sequence(sensor_layout, [-1.0, 0.0, 1.0], [False, False, True], "one")
    batch = prepare_temporal_batch([item])
    model = RecurrentLocalizationModel(batch.input_width, hidden_width=4)
    parameters = model.initialize(
        3,
        np.zeros(batch.input_width, dtype=np.float32),
        np.ones(batch.input_width, dtype=np.float32),
    )

    whole, whole_state = model.run(parameters, batch.features, batch.time_mask)
    first, first_state = model.run(parameters, batch.features[:, :2], batch.time_mask[:, :2])
    second, second_state = model.run(
        parameters,
        batch.features[:, 2:],
        batch.time_mask[:, 2:],
        first_state,
    )

    np.testing.assert_allclose(
        whole.presence_probability,
        np.concatenate([first.presence_probability, second.presence_probability], axis=1),
        rtol=1e-5,
    )
    np.testing.assert_allclose(whole_state.hidden, second_state.hidden, rtol=1e-5)


def test_recurrent_baseline_learns_masked_sequences(sensor_layout: SensorLayout) -> None:
    items = [
        sequence(sensor_layout, [-1.0, -0.8, 0.8, 1.0], [False, False, True, True], f"s{i}")
        for i in range(8)
    ]
    items.append(sequence(sensor_layout, [-1.0, 1.0], [False, True], "short"))
    batch = prepare_temporal_batch(items)
    model = RecurrentLocalizationModel(batch.input_width, hidden_width=8)

    result = train_recurrent(
        model,
        batch,
        RecurrentTrainingConfig(
            steps=150,
            batch_size=4,
            learning_rate=0.02,
            truncation_steps=2,
            seed=4,
        ),
    )
    prediction, _ = model.run(result.parameters, batch.features, batch.time_mask)

    assert result.losses[-1] < result.losses[0] * 0.4
    np.testing.assert_array_equal(
        prediction.presence_probability[batch.time_mask] >= 0.5,
        batch.presence[batch.time_mask] > 0.5,
    )


def test_padding_does_not_change_recurrent_state(sensor_layout: SensorLayout) -> None:
    item = sequence(sensor_layout, [-1.0, 1.0], [False, True], "clip")
    padded = prepare_temporal_batch([item])
    padded = replace(
        padded,
        features=np.pad(padded.features, ((0, 0), (0, 2), (0, 0))),
        time_mask=np.pad(padded.time_mask, ((0, 0), (0, 2))),
        presence=np.pad(padded.presence, ((0, 0), (0, 2))),
        direction=np.pad(padded.direction, ((0, 0), (0, 2), (0, 0))),
    )
    model = RecurrentLocalizationModel(padded.input_width, hidden_width=4)
    parameters = model.initialize(
        0,
        np.zeros(padded.input_width, dtype=np.float32),
        np.ones(padded.input_width, dtype=np.float32),
    )

    _, full_state = model.run(parameters, padded.features, padded.time_mask)
    _, valid_state = model.run(parameters, padded.features[:, :2], padded.time_mask[:, :2])

    np.testing.assert_allclose(full_state.hidden, valid_state.hidden, rtol=1e-6)


def test_recurrent_action_head_learns_masked_teacher_commands() -> None:
    features = np.tile(
        np.array([[[-1.0], [-0.5], [0.5], [1.0]]], dtype=np.float32),
        (8, 1, 1),
    )
    actions = np.concatenate([features, -features], axis=2)
    mask = np.ones((8, 4), dtype=np.bool_)
    mask[:, 0] = False
    batch = ActionTemporalBatch(features, np.ones_like(mask), mask, actions)
    model = RecurrentActionModel(1, (1.0, 1.0), hidden_width=8)

    result = train_recurrent_actions(
        model,
        batch,
        RecurrentTrainingConfig(
            steps=200,
            batch_size=4,
            learning_rate=0.02,
            truncation_steps=2,
            seed=2,
        ),
    )
    prediction, _ = model.run(result.parameters, features, np.ones_like(mask))

    assert result.losses[-1] < result.losses[0] * 0.2
    np.testing.assert_allclose(prediction.values[mask], actions[mask], atol=0.15)
