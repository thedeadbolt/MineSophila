from fly_vision import CHANNELS
from fly_vision.config import FlyVisionConfig, config_hash


def test_channels_order() -> None:
    assert CHANNELS == ("R1_6", "R7", "R8")


def test_config_defaults() -> None:
    c = FlyVisionConfig()
    assert c.acceptance_fwhm_deg == 5.0
    assert c.adaptation == "none"
    assert c.visible_coverage_threshold == 0.05


def test_config_hash_stable_and_sensitive() -> None:
    assert config_hash(FlyVisionConfig()) == config_hash(FlyVisionConfig())
    assert config_hash(FlyVisionConfig()) != config_hash(FlyVisionConfig(acceptance_fwhm_deg=4.0))
