"""Run configuration and its hash, recorded as provenance on every output."""

import hashlib
import json
from dataclasses import asdict, dataclass
from typing import Literal

from fly_vision import __version__


@dataclass(frozen=True)
class FlyVisionConfig:
    """Parameters that change the output; see docs/assumptions.md."""

    acceptance_fwhm_deg: float = 5.0
    cutoff_sigmas: float = 3.0
    valid_coverage_min: float = 0.5
    visible_coverage_threshold: float = 0.05
    low_bit_depth_levels: int = 32
    adaptation: Literal["none", "frame_mean"] = "none"
    seed: int = 0


def config_hash(cfg: FlyVisionConfig) -> str:
    """SHA-256 hex of the config fields (sorted-key JSON) and the package version."""
    payload = {"config": asdict(cfg), "version": __version__}
    text = json.dumps(payload, sort_keys=True)
    return hashlib.sha256(text.encode()).hexdigest()
