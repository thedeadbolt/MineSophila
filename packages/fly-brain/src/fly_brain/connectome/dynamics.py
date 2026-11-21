"""Functional neuron dynamics with explicit recurrent state."""

from dataclasses import dataclass
from typing import Literal

import jax
import jax.numpy as jnp
import numpy as np

ActivationName = Literal["relu", "tanh", "graded"]


@dataclass(frozen=True)
class LeakyRateDynamics:
    """Leaky neuron state with a policy-defined target and presynaptic release.

    ``relu`` and ``tanh`` are rate policies: state relaxes toward ``f(drive)`` and the state is
    itself the presynaptic signal. ``graded`` is a signed potential policy (ADR 0027): state
    relaxes toward the drive, may fall below rest, and drives targets through bounded logistic
    release with tonic release at rest, so hyperpolarisation reduces downstream drive.
    """

    time_constant: float = 2.0
    activation: ActivationName = "tanh"

    def __post_init__(self) -> None:
        if not np.isfinite(self.time_constant) or self.time_constant <= 0:
            raise ValueError("time_constant must be positive")
        if self.activation not in {"relu", "tanh", "graded"}:
            raise ValueError(f"unsupported dynamics activation {self.activation!r}")

    def transition(self, state: jax.Array, drive: jax.Array, dt: float) -> jax.Array:
        if not 0 < dt <= self.time_constant:
            raise ValueError("dt must satisfy 0 < dt <= time_constant")
        if self.activation == "relu":
            target = jax.nn.relu(drive)
        elif self.activation == "tanh":
            target = jnp.tanh(drive)
        else:
            target = drive
        leak = dt / self.time_constant
        return state + leak * (target - state)

    def release(self, state: jax.Array) -> jax.Array:
        """Return the presynaptic signal that a neuron's state sends along its edges."""
        if self.activation == "graded":
            return jax.nn.sigmoid(state)
        return state
