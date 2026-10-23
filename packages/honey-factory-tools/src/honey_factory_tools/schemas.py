"""Load the language-neutral honey-factory JSON Schemas and validate records."""

import json
import os
from collections.abc import Iterable
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Literal, cast

from jsonschema import Draft202012Validator, ValidationError
from referencing import Registry, Resource

from honey_factory_tools.canonical import Json

type Kind = Literal["dataset", "scene", "sample"]
KINDS: tuple[Kind, ...] = ("dataset", "scene", "sample")
CONTRACT_VERSION = "1.0"
SCHEMAS_ENV = "HONEY_FACTORY_SCHEMAS"


@dataclass(frozen=True)
class SchemaError:
    """One schema violation at a JSON pointer."""

    pointer: str
    message: str

    def __str__(self) -> str:
        return f"{self.pointer or '/'}: {self.message}"


class Schemas:
    """Validators for one contract version, resolving cross-file references offline."""

    def __init__(self, directory: Path) -> None:
        resources: list[tuple[str, Resource[Any]]] = []
        documents: dict[str, dict[str, Any]] = {}
        for path in sorted(directory.glob("*.schema.json")):
            document = cast(dict[str, Any], json.loads(path.read_text(encoding="utf-8")))
            resources.append((str(document["$id"]), Resource.from_contents(document)))
            documents[path.name.removesuffix(".schema.json")] = document
        missing = [kind for kind in KINDS if kind not in documents]
        if missing:
            raise FileNotFoundError(f"{directory} lacks schemas for {', '.join(missing)}")
        registry = Registry[Any]().with_resources(resources)
        # jsonschema's validator classes are only partially typed.
        self._validators: dict[Kind, Any] = {
            kind: Draft202012Validator(documents[kind], registry=registry) for kind in KINDS
        }

    def errors(self, kind: Kind, record: Json) -> list[SchemaError]:
        """Return every violation, ordered by location."""
        found = cast(Iterable[ValidationError], self._validators[kind].iter_errors(record))
        errors = [SchemaError(_pointer(error.absolute_path), error.message) for error in found]
        return sorted(errors, key=lambda error: error.pointer)


def _pointer(path: Iterable[str | int]) -> str:
    """RFC 6901 JSON Pointer, matching the Java contract errors."""
    return "".join("/" + str(token).replace("~", "~0").replace("/", "~1") for token in path)


def find_schema_directory(explicit: Path | None = None, start: Path | None = None) -> Path:
    """Locate ``schemas/honey-factory/<version>`` from an argument, environment or ancestors."""
    if explicit is not None:
        return explicit
    configured = os.environ.get(SCHEMAS_ENV)
    if configured:
        return Path(configured)
    current = (start or Path.cwd()).resolve()
    for directory in (current, *current.parents):
        candidate = directory / "schemas" / "honey-factory" / CONTRACT_VERSION
        if candidate.is_dir():
            return candidate
    raise FileNotFoundError(
        f"cannot find schemas/honey-factory/{CONTRACT_VERSION}; pass --schemas or set {SCHEMAS_ENV}"
    )
