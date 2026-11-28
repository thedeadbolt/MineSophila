from dataclasses import replace

import numpy as np
import pytest

from fly_brain.contracts import (
    BrainExample,
    BrainTargets,
    ObservationSequence,
    Provenance,
    SensorLayout,
)
from fly_brain.experiments import SplitFractions, split_examples


def example(scene_id: str, template: BrainExample) -> BrainExample:
    return replace(
        template,
        provenance=Provenance(
            dataset_id="dataset",
            scene_id=scene_id,
            frame_ids=(f"{scene_id}-frame",),
            sensor_config_hash=template.observation.layout.config_hash,
        ),
    )


@pytest.fixture
def template(sensor_layout: SensorLayout) -> BrainExample:
    return BrainExample(
        observation=ObservationSequence.from_frame(
            sensor_layout,
            np.zeros((sensor_layout.site_count, sensor_layout.channel_count), dtype=np.float32),
            np.ones(sensor_layout.site_count, dtype=np.bool_),
            0.0,
        ),
        targets=BrainTargets(time_mask=np.array([True])),
        provenance=Provenance("dataset", "template", ("frame",), sensor_layout.config_hash),
    )


def test_split_keeps_all_examples_from_a_scene_together(template: BrainExample) -> None:
    examples = [
        example(scene_id, template)
        for scene_id in ("a", "a", "b", "c", "d", "e", "f", "g", "h", "i")
    ]

    split = split_examples(examples, seed=42)

    assert len(split.train_scenes) == 6
    assert len(split.validation_scenes) == 2
    assert len(split.test_scenes) == 1
    split_for_index = {
        index: name
        for name, indices in (
            ("train", split.train_indices),
            ("validation", split.validation_indices),
            ("test", split.test_indices),
        )
        for index in indices.tolist()
    }
    assert split_for_index[0] == split_for_index[1]


def test_split_is_reproducible_and_seeded(template: BrainExample) -> None:
    examples = [example(str(index), template) for index in range(20)]

    first = split_examples(examples, seed=7)
    repeated = split_examples(examples, seed=7)
    different = split_examples(examples, seed=8)

    assert first.train_scenes == repeated.train_scenes
    assert first.train_scenes != different.train_scenes


def test_split_requires_three_scenes_and_valid_fractions(template: BrainExample) -> None:
    with pytest.raises(ValueError, match="three distinct scenes"):
        split_examples([example("a", template), example("b", template)], seed=0)
    with pytest.raises(ValueError, match="sum to 1"):
        SplitFractions(0.8, 0.2, 0.2)
