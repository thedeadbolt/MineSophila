"""Functional neuron dynamics with explicit recurrent state."""

from dataclasses import dataclass
from typing import Literal

import jax
import jax.numpy as jnp
import numpy as np

ActivationName = Literal["relu", "tanh"]


@dataclass(frozen=True)
class LeakyRateDynamics:
    time_constant: float = 2.0
    activation: ActivationName = "tanh"

    def __post_init__(self) -> None:
        if not np.isfinite(self.time_constant) or self.time_constant <= 0:
            raise ValueError("time_constant must be positive")
        if self.activation not in {"relu", "tanh"}:
            raise ValueError(f"unsupported dynamics activation {self.activation!r}")

    def transition(self, state: jax.Array, drive: jax.Array, dt: float) -> jax.Array:
        if not 0 < dt <= self.time_constant:
            raise ValueError("dt must satisfy 0 < dt <= time_constant")
        target = jax.nn.relu(drive) if self.activation == "relu" else jnp.tanh(drive)
        leak = dt / self.time_constant
        return state + leak * (target - state)
