"""Versioned, non-pickle experiment checkpoints."""

import hashlib
import json
from collections.abc import Mapping
from pathlib import Path
from typing import Any

import numpy as np
from numpy.typing import NDArray


def save_checkpoint(path: Path, parameters: Mapping[str, Any], manifest: Mapping[str, Any]) -> str:
    manifest_json = json.dumps(manifest, separators=(",", ":"), sort_keys=True)
    manifest_sha256 = hashlib.sha256(manifest_json.encode()).hexdigest()
    arrays = {f"parameter.{name}": np.asarray(value) for name, value in parameters.items()}
    path.parent.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(
        path,
        **arrays,  # pyright: ignore[reportArgumentType]
        format=np.asarray("fly-brain-checkpoint-v1"),
        manifest=np.asarray(manifest_json),
        manifest_sha256=np.asarray(manifest_sha256),
    )
    return manifest_sha256


def load_checkpoint(path: Path) -> tuple[dict[str, NDArray[np.generic]], dict[str, Any]]:
    with np.load(path, allow_pickle=False) as values:
        if str(values["format"]) != "fly-brain-checkpoint-v1":
            raise ValueError("unsupported fly-brain checkpoint format")
        manifest_json = str(values["manifest"])
        expected = str(values["manifest_sha256"])
        actual = hashlib.sha256(manifest_json.encode()).hexdigest()
        if actual != expected:
            raise ValueError("checkpoint manifest hash does not match its contents")
        parameters = {
            name.removeprefix("parameter."): values[name]
            for name in values.files
            if name.startswith("parameter.")
        }
    return parameters, json.loads(manifest_json)
