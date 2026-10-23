"""Command-line entry point: ``honey-factory-tools validate``."""

import argparse
import sys
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import cast

from honey_factory_tools import __version__
from honey_factory_tools.canonical import Json, loads
from honey_factory_tools.schemas import KINDS, Kind, Schemas, find_schema_directory


@dataclass(frozen=True)
class _Unreadable:
    reason: str


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="honey-factory-tools")
    parser.add_argument("--version", action="version", version=__version__)
    commands = parser.add_subparsers(dest="command", required=True)
    validate = commands.add_parser("validate", help="validate records against the JSON Schemas")
    validate.add_argument("--kind", choices=KINDS, required=True)
    validate.add_argument("--schemas", type=Path, help="schema directory for one contract version")
    validate.add_argument("files", nargs="+", type=Path, help=".json documents or .jsonl indexes")
    args = parser.parse_args(argv)

    try:
        schemas = Schemas(find_schema_directory(cast(Path | None, args.schemas)))
    except (FileNotFoundError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    kind = cast(Kind, args.kind)
    failures = 0
    for path in cast(list[Path], args.files):
        for location, record in _records(path):
            if isinstance(record, _Unreadable):
                print(f"{location} {record.reason}")
                failures += 1
                continue
            for error in schemas.errors(kind, record):
                print(f"{location}{error}")
                failures += 1
    print(f"{failures} error(s)", file=sys.stderr)
    return 1 if failures else 0


def _records(path: Path) -> list[tuple[str, Json | _Unreadable]]:
    """Return (location, record) pairs, with unreadable input marked as such."""
    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError) as error:
        return [(f"{path}:", _Unreadable(f"cannot read: {error}"))]
    if path.suffix != ".jsonl":
        return [(f"{path}:", _parse(text))]
    if text.startswith("\ufeff"):
        return [(f"{path}:", _Unreadable("must not start with a byte-order mark"))]
    if text and not text.endswith("\n"):
        return [(f"{path}:", _Unreadable("every record, including the last, must end with \\n"))]
    return [(f"{path}:{number}:", _parse(line)) for number, line in enumerate(text.splitlines(), 1)]


def _parse(text: str) -> Json | _Unreadable:
    try:
        return loads(text)
    except ValueError as error:
        return _Unreadable(f"invalid JSON: {error}")
