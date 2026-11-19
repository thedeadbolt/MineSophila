"""Provider-independent connectome loading contract."""

from typing import Protocol

from fly_brain.connectome.graph import ConnectomeGraph
from fly_brain.connectome.release import ConnectomeRelease


class ConnectomeLoader(Protocol):
    """Load and validate one exact connectome release."""

    def load(self, release: ConnectomeRelease) -> ConnectomeGraph: ...
