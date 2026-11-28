"""Pinned source identity for a connectome artifact."""

import re
from dataclasses import dataclass

_SHA256 = re.compile(r"[0-9a-f]{64}")


@dataclass(frozen=True)
class ConnectomeRelease:
    """Identify one immutable upstream connectome artifact."""

    source: str
    version: str
    artifact_sha256: str
    source_uri: str
    license_name: str

    def __post_init__(self) -> None:
        for name in ("source", "version", "source_uri", "license_name"):
            if not getattr(self, name).strip():
                raise ValueError(f"{name} must not be empty")
        checksum = self.artifact_sha256.lower()
        if _SHA256.fullmatch(checksum) is None:
            raise ValueError("artifact_sha256 must be a 64-character hexadecimal SHA-256")
        object.__setattr__(self, "artifact_sha256", checksum)

    @property
    def release_id(self) -> str:
        return f"{self.source}@{self.version}"
