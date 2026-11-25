"""Command-line entry point: ``validate``, ``validate-dataset``, ``inspect``, ``select``,
``reviews``, ``acceptance-report``, ``merge-slices``, ``generate``, ``clip-report`` and
``control-demo``."""

import argparse
import json
import shlex
import sys
import webbrowser
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import cast

from honey_factory_tools import (
    __version__,
    clip_report,
    connectome,
    control,
    dataset,
    inspector,
    merge,
    orchestrate,
    report,
    selection,
)
from honey_factory_tools.brain import Runs, Traces
from honey_factory_tools.canonical import Json, loads
from honey_factory_tools.catalog import Catalog
from honey_factory_tools.eye import EyeModel
from honey_factory_tools.live import LiveCatalog, LiveTraces
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
    whole.add_argument(
        "--quick",
        action="store_true",
        help="for a clip dataset, decode the masks of only 1 clip in 20",
    )
    whole.add_argument("directory", type=Path, help="the dataset directory holding dataset.json")
    inspect = commands.add_parser("inspect", help="browse a dataset in a local web page")
    inspect.add_argument("--schemas", type=Path, help="schema directory for one contract version")
    inspect.add_argument(
        "--reviews", type=Path, help="sidecar review root (reviews are off without it)"
    )
    inspect.add_argument("--reviewer", help="name recorded with each review")
    inspect.add_argument(
        "--runs", type=Path, help="directory of fly-brain *.report.json files (Brain workspace)"
    )
    inspect.add_argument(
        "--traces", type=Path, help="directory of fly-brain clip traces (Brain workspace)"
    )
    inspect.add_argument(
        "--no-eye", action="store_true", help="turn off the Eye workspace (fly-vision)"
    )
    inspect.add_argument(
        "--connectome",
        type=Path,
        help="connectome graph JSON from connectome-graph (Brain network view)",
    )
    graph = commands.add_parser(
        "connectome-graph", help="summarise the MaleCNS connectome for the Brain network view"
    )
    graph.add_argument("directory", type=Path, help="the male-cns-v1.0 feather directory")
    graph.add_argument("--out", type=Path, required=True, help="the graph JSON to write")
    graph.add_argument("--minimum-synapses", type=int, default=5, help="per neuron pair")
    inspect.add_argument("--port", type=int, default=0, help="port on 127.0.0.1 (0: any free one)")
    inspect.add_argument("--no-browser", action="store_true", help="do not open a browser")
    inspect.add_argument("directory", type=Path, help="the dataset directory holding dataset.json")
    live = commands.add_parser(
        "inspect-live",
        help="browse fly-brain live runs (run-live --out-dir): episodes, videos and brain traces",
    )
    live.add_argument(
        "run_dirs",
        nargs="+",
        type=Path,
        help="run-live output directories, or folders of them (new runs and episodes appear)",
    )
    live.add_argument(
        "--include-unfilmed",
        action="store_true",
        help="also list episodes recorded before videos (grey frames)",
    )
    live.add_argument(
        "--trace-command",
        help="command that traces an episode on demand, given RUN_DIR --episodes NAME (for"
        " example 'uv run --project ../MineSophila fly-brain trace-live'); off without it",
    )
    live.add_argument(
        "--runs", type=Path, help="directory of fly-brain *.report.json files (Brain workspace)"
    )
    live.add_argument("--no-eye", action="store_true", help="turn off the Eye workspace")
    live.add_argument("--connectome", type=Path, help="connectome graph JSON from connectome-graph")
    live.add_argument("--port", type=int, default=0, help="port on 127.0.0.1 (0: any free one)")
    live.add_argument("--no-browser", action="store_true", help="do not open a browser")
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
    acceptance = commands.add_parser(
        "acceptance-report", help="report on an acceptance folder of runs and datasets"
    )
    acceptance.add_argument(
        "--schemas", type=Path, help="schema directory for one contract version"
    )
    acceptance.add_argument("--reviews", type=Path, help="sidecar review root for the audit")
    acceptance.add_argument("--audit-seed", type=int, help="seed of the stratified audit selection")
    acceptance.add_argument("--audit-count", type=int, default=200)
    acceptance.add_argument(
        "--output", type=Path, help="write <output>.md and <output>.json instead of printing"
    )
    acceptance.add_argument("directory", type=Path, help="the acceptance folder")
    joined = commands.add_parser(
        "merge-slices", help="merge the slices of one dataset into the dataset a single run writes"
    )
    joined.add_argument("--schemas", type=Path, help="schema directory for one contract version")
    joined.add_argument("output", type=Path, help="folder the merged dataset is written into")
    joined.add_argument("slices", nargs="+", type=Path, help="the slice dataset directories")
    clip_metrics = commands.add_parser(
        "clip-report", help="report the metrics of clip datasets (M12-2 pilot)"
    )
    clip_metrics.add_argument(
        "--schemas", type=Path, help="schema directory for one contract version"
    )
    clip_metrics.add_argument("--output", type=Path, help="also write the report as JSON here")
    clip_metrics.add_argument("datasets", nargs="+", type=Path, help="clip dataset directories")
    run = commands.add_parser(
        "generate", help="generate a dataset on several game instances and merge their slices"
    )
    run.add_argument("--schemas", type=Path, help="schema directory for one contract version")
    run.add_argument("--instances", type=int, default=1, help="game instances to run at once")
    run.add_argument("--root", type=Path, required=True, help="output folder for the dataset")
    run.add_argument("--world-seed", type=int, help="seed of every instance's world")
    run.add_argument(
        "--project", type=Path, help="the honey-factory mod project (found from here by default)"
    )
    run.add_argument("config", type=Path, help="the dataset configuration JSON")
    demo = commands.add_parser(
        "control-demo", help="drive the player through a running control server and log every step"
    )
    demo.add_argument(
        "--connection", type=Path, required=True, help="the server's honey-factory-control.json"
    )
    demo.add_argument("--steps", type=int, default=1000)
    demo.add_argument("--seed", type=int, default=0, help="scene seed and command schedule seed")
    demo.add_argument("--log", type=Path, required=True, help="JSONL log to write")
    demo.add_argument("--preset", default="level1", help="scene preset")
    demo.add_argument("--width", type=int, default=128)
    demo.add_argument("--height", type=int, default=128)
    demo.add_argument(
        "--client-delay-ms",
        type=int,
        default=0,
        help="sleep before each step; frames must not change",
    )
    args = parser.parse_args(argv)
    if args.command == "control-demo":
        return _control_demo(args)
    if args.command == "inspect-live":
        command = cast(str | None, args.trace_command)
        catalog = LiveCatalog(
            cast(list[Path], args.run_dirs),
            shlex.split(command) if command else None,
            include_unfilmed=cast(bool, args.include_unfilmed),
        )
        args.reviews = None
        args.traces = None
        return _inspect(cast(Catalog, catalog), args, LiveTraces(catalog))
    if args.command == "connectome-graph":
        try:
            built = connectome.build(cast(Path, args.directory), cast(int, args.minimum_synapses))
        except connectome.ConnectomeError as error:
            print(f"error: {error}", file=sys.stderr)
            return 2
        connectome.write(built, cast(Path, args.out))
        print(
            f"{built['neuron_count']} neurons, {len(built['cell_types'])} cell types, "
            f"{built['edge_count']} edges written to {args.out}"
        )
        return 0

    try:
        schemas = Schemas(find_schema_directory(cast(Path | None, args.schemas)))
    except (FileNotFoundError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    if args.command == "validate-dataset":
        return _validate_dataset(
            schemas,
            cast(Path, args.directory),
            cast(bool, args.allow_generating),
            cast(bool, args.quick),
        )
    if args.command == "acceptance-report":
        return _acceptance(schemas, args)
    if args.command == "generate":
        project = cast(Path | None, args.project) or _mod_project()
        if project is None:
            print("error: no honey-factory mod project found; use --project", file=sys.stderr)
            return 2
        try:
            made = orchestrate.generate(
                cast(Path, args.config),
                cast(int, args.instances),
                cast(Path, args.root),
                orchestrate.GradleLauncher(project),
                schemas,
                cast(int | None, args.world_seed),
            )
        except orchestrate.GenerateError as error:
            print(f"error: {error}", file=sys.stderr)
            return 1
        print(made)
        return 0
    if args.command == "clip-report":
        result = clip_report.report(cast(list[Path], args.datasets), schemas)
        if args.output is not None:
            output = cast(Path, args.output)
            output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        print(clip_report.table(result))
        return 0
    if args.command == "merge-slices":
        try:
            merged = merge.merge(cast(list[Path], args.slices), cast(Path, args.output), schemas)
        except merge.MergeError as error:
            print(f"error: {error}", file=sys.stderr)
            return 1
        print(merged)
        return 0
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


def _mod_project() -> Path | None:
    """The mod project above the working directory: ``packages/honey-factory``."""
    for folder in (Path.cwd(), *Path.cwd().parents):
        candidate = folder / "packages" / "honey-factory"
        if (candidate / "gradlew").is_file():
            return candidate
    return None


def _validate_dataset(
    schemas: Schemas, directory: Path, allow_generating: bool, quick: bool = False
) -> int:
    report = dataset.validate(directory, schemas, allow_generating=allow_generating, quick=quick)
    for error in report.errors:
        print(error)
    print(
        f"{len(report.errors)} error(s); lifecycle {report.lifecycle}, "
        f"{len(report.scenes)} scenes, "
        + (f"{len(report.clips)} clips" if report.clips else f"{len(report.samples)} samples"),
        file=sys.stderr,
    )
    return 0 if report.passed else 1


def _inspect(catalog: Catalog, args: argparse.Namespace, traces: Traces | None = None) -> int:
    root = cast(Path | None, args.reviews)
    reviews = None if root is None else ReviewStore(root, catalog, cast(str | None, args.reviewer))
    runs_root = cast(Path | None, args.runs)
    traces_root = cast(Path | None, args.traces)
    app = inspector.Inspector(
        catalog,
        reviews,
        eye=None if args.no_eye else EyeModel(),
        runs=None if runs_root is None else Runs(runs_root),
        traces=traces
        if traces is not None
        else None
        if traces_root is None
        else Traces(traces_root),
        network=None if args.connectome is None else connectome.load(cast(Path, args.connectome)),
    )
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


def _acceptance(schemas: Schemas, args: argparse.Namespace) -> int:
    reports = report.build(
        cast(Path, args.directory),
        schemas,
        cast(Path | None, args.reviews),
        cast(int | None, args.audit_seed),
        cast(int, args.audit_count),
    )
    output = cast(Path | None, args.output)
    if output is None:
        print(report.to_markdown(reports), end="")
    else:
        report.write(reports, output)
        print(f"Wrote {output.with_suffix('.md')} and {output.with_suffix('.json')}")
    return 0 if reports and all(r.passed for r in reports) else 1


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


def _control_demo(args: argparse.Namespace) -> int:
    try:
        control.run_demo(
            cast(Path, args.connection),
            steps=cast(int, args.steps),
            seed=cast(int, args.seed),
            log=cast(Path, args.log),
            capture={
                "mode": "PERSPECTIVE_90",
                "width": cast(int, args.width),
                "height": cast(int, args.height),
            },
            scene={"preset": cast(str, args.preset)},
            client_delay_ms=cast(int, args.client_delay_ms),
        )
    except (OSError, control.ControlError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0
