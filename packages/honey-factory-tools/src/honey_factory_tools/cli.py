"""Command-line entry point: ``validate``, ``validate-dataset``, ``inspect``, ``select``,
``reviews``."""

import argparse
import json
import sys
import webbrowser
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import cast

from honey_factory_tools import __version__, dataset, inspector, selection
from honey_factory_tools.canonical import Json, loads
from honey_factory_tools.catalog import Catalog
from honey_factory_tools.reviews import ReviewError, ReviewStore
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
    whole = commands.add_parser(
        "validate-dataset", help="validate a dataset directory and its images"
    )
    whole.add_argument("--schemas", type=Path, help="schema directory for one contract version")
    whole.add_argument(
        "--allow-generating",
        action="store_true",
        help="accept a dataset whose manifest is still generating",
    )
    whole.add_argument("directory", type=Path, help="the dataset directory holding dataset.json")
    inspect = commands.add_parser("inspect", help="browse a dataset in a local web page")
    inspect.add_argument("--schemas", type=Path, help="schema directory for one contract version")
    inspect.add_argument(
        "--reviews", type=Path, help="sidecar review root (reviews are off without it)"
    )
    inspect.add_argument("--reviewer", help="name recorded with each review")
    inspect.add_argument("--port", type=int, default=0, help="port on 127.0.0.1 (0: any free one)")
    inspect.add_argument("--no-browser", action="store_true", help="do not open a browser")
    inspect.add_argument("directory", type=Path, help="the dataset directory holding dataset.json")
    choose = commands.add_parser("select", help="print a reproducible selection of samples")
    choose.add_argument("--schemas", type=Path, help="schema directory for one contract version")
    choose.add_argument("--mode", choices=selection.MODES, default="random")
    choose.add_argument("--seed", type=int, default=0)
    choose.add_argument("--count", type=int)
    choose.add_argument("--low-pixels", type=int, default=selection.DEFAULT_LOW_PIXELS)
    for name in ("split", "status", "scene-id", "sample-id"):
        choose.add_argument(f"--{name}")
    choose.add_argument("--present", choices=("true", "false"))
    choose.add_argument("--viewpoint", type=int)
    for name in ("ratio", "distance"):
        choose.add_argument(f"--min-{name}", type=float)
        choose.add_argument(f"--max-{name}", type=float)
    choose.add_argument("--min-pixels", type=int)
    choose.add_argument("--max-pixels", type=int)
    choose.add_argument("--json", action="store_true", help="print selection and samples as JSON")
    choose.add_argument("directory", type=Path, help="the dataset directory holding dataset.json")
    report = commands.add_parser("reviews", help="summarize the sidecar reviews of a dataset")
    report.add_argument("--schemas", type=Path, help="schema directory for one contract version")
    report.add_argument("--reviews", type=Path, required=True, help="sidecar review root")
    report.add_argument("--json", action="store_true", help="print the summary as JSON")
    report.add_argument("directory", type=Path, help="the dataset directory holding dataset.json")
    args = parser.parse_args(argv)

    try:
        schemas = Schemas(find_schema_directory(cast(Path | None, args.schemas)))
    except (FileNotFoundError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    if args.command == "validate-dataset":
        return _validate_dataset(
            schemas, cast(Path, args.directory), cast(bool, args.allow_generating)
        )
    if args.command in ("inspect", "select", "reviews"):
        try:
            catalog = Catalog(cast(Path, args.directory), schemas)
            if args.command == "inspect":
                return _inspect(catalog, args)
            if args.command == "select":
                return _select(catalog, args)
            return _reviews(catalog, args)
        except (ValueError, ReviewError) as error:
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


def _validate_dataset(schemas: Schemas, directory: Path, allow_generating: bool) -> int:
    report = dataset.validate(directory, schemas, allow_generating=allow_generating)
    for error in report.errors:
        print(error)
    print(
        f"{len(report.errors)} error(s); lifecycle {report.lifecycle}, "
        f"{len(report.scenes)} scenes, {len(report.samples)} samples",
        file=sys.stderr,
    )
    return 0 if report.passed else 1


def _inspect(catalog: Catalog, args: argparse.Namespace) -> int:
    root = cast(Path | None, args.reviews)
    reviews = None if root is None else ReviewStore(root, catalog, cast(str | None, args.reviewer))
    app = inspector.Inspector(catalog, reviews)
    catalog.start_validation()
    server = inspector.make_server(app, cast(int, args.port))
    address = inspector.url(server)
    print(f"Inspecting {catalog.dataset_id} at {address} (Ctrl-C to stop)", flush=True)
    if reviews is not None:
        print(f"Reviews are appended to {reviews.path}", flush=True)
    if not args.no_browser:
        webbrowser.open(address)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


def _select(catalog: Catalog, args: argparse.Namespace) -> int:
    present = cast(str | None, args.present)
    chosen = selection.Selection(
        mode=cast(str, args.mode),
        seed=cast(int, args.seed),
        count=cast(int | None, args.count),
        low_pixels=cast(int, args.low_pixels),
        filters=selection.Filters(
            split=args.split,
            status=args.status,
            present=None if present is None else present == "true",
            scene_id=args.scene_id,
            sample_id=args.sample_id,
            viewpoint=args.viewpoint,
            min_ratio=args.min_ratio,
            max_ratio=args.max_ratio,
            min_pixels=args.min_pixels,
            max_pixels=args.max_pixels,
            min_distance=args.min_distance,
            max_distance=args.max_distance,
        ),
    )
    samples = selection.select(catalog.manifest, catalog.samples, chosen)
    seams = selection.seam_columns(catalog.manifest)
    rows = [
        {
            "sample_id": sample["sample_id"],
            "scene_id": sample["scene_id"],
            "split": sample["split"],
            "target_status": sample["target"]["target_status"],
            "stratum": selection.stratum(sample, seams, chosen.low_pixels),
        }
        for sample in samples
    ]
    if args.json:
        print(json.dumps({"selection": chosen.to_json(), "samples": rows}, indent=2))
    else:
        for row in rows:
            print(f"{row['sample_id']}\t{row['split']}\t{row['target_status']}\t{row['stratum']}")
        print(f"{len(rows)} samples; selection {chosen.to_json()['sha256']}", file=sys.stderr)
    return 0


def _reviews(catalog: Catalog, args: argparse.Namespace) -> int:
    store = ReviewStore(cast(Path, args.reviews), catalog)
    summary = store.summary()
    if args.json:
        print(json.dumps({"path": str(store.path), **summary.__dict__}, indent=2))
        return 0
    print(f"{summary.reviews} reviews of {catalog.dataset_id} in {store.path}")
    for kind, counts in summary.latest.items():
        listed = ", ".join(f"{outcome} {count}" for outcome, count in counts.items()) or "none"
        print(f"latest by {kind}: {listed}")
    for subject in summary.failed:
        print(f"fail: {subject}")
    for subject in summary.uncertain:
        print(f"uncertain: {subject}")
    return 0


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
