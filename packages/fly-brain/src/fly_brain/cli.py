"""Local preprocessing and baseline experiment commands."""

import argparse
import json
import subprocess
import sys
from dataclasses import asdict
from pathlib import Path

import numpy as np

from fly_brain.contracts import BrainTargets
from fly_brain.datasets import CachedStillDataset, combine_batches, convert_honey_factory_dataset
from fly_brain.experiments import save_checkpoint
from fly_brain.models import (
    LinearLocalizationModel,
    LocalizationBatch,
    MLPLocalizationModel,
    MLPParameters,
    train_linear,
    train_mlp,
)
from fly_brain.tasks import evaluate_localization


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="fly-brain")
    commands = parser.add_subparsers(dest="command", required=True)
    prepare = commands.add_parser("prepare-stills")
    prepare.add_argument("datasets", nargs="+", type=Path)
    prepare.add_argument("--out", required=True, type=Path)
    prepare.add_argument("--matrix-cache", required=True, type=Path)
    train = commands.add_parser("train-linear")
    train.add_argument("caches", nargs="+", type=Path)
    train.add_argument("--steps", type=int, default=300)
    train.add_argument("--learning-rate", type=float, default=0.05)
    train.add_argument("--seed", type=int, default=0)
    train.add_argument("--report", required=True, type=Path)
    deep = commands.add_parser("train-mlp")
    deep.add_argument("caches", nargs="+", type=Path)
    deep.add_argument("--steps", type=int, default=1_000)
    deep.add_argument("--batch-size", type=int, default=128)
    deep.add_argument("--hidden-width", type=int, default=64)
    deep.add_argument("--learning-rate", type=float, default=1e-3)
    deep.add_argument("--seed", type=int, default=0)
    deep.add_argument("--report", required=True, type=Path)
    deep.add_argument("--checkpoint", required=True, type=Path)
    return parser


def _prepare(args: argparse.Namespace) -> int:
    args.out.mkdir(parents=True, exist_ok=True)
    for dataset in args.datasets:
        manifest = json.loads((dataset / "dataset.json").read_text())
        output = args.out / f"{manifest['dataset_id']}.fly-brain.npz"
        print(f"preparing {manifest['dataset_id']} -> {output}", flush=True)

        def progress(done: int, total: int) -> None:
            if done == total or done % 50 == 0:
                print(f"  {done}/{total}", flush=True)

        converted = convert_honey_factory_dataset(
            dataset, output, matrix_cache=args.matrix_cache, progress=progress
        )
        print(f"  wrote {converted.rows} samples", flush=True)
    return 0


def _targets(batch: LocalizationBatch) -> BrainTargets:
    return BrainTargets(
        time_mask=batch.time_mask,
        honey_present=batch.presence.astype(np.bool_),
        honey_direction=batch.direction,
    )


def _train(args: argparse.Namespace) -> int:
    datasets = [CachedStillDataset.load(path) for path in args.caches]
    train = combine_batches([dataset.batch("train") for dataset in datasets])
    validation = combine_batches([dataset.batch("validation") for dataset in datasets])
    test = combine_batches([dataset.batch("test") for dataset in datasets])
    model = LinearLocalizationModel(train.input_width, train.site_count, predict_coverage=False)
    result = train_linear(
        model,
        train,
        steps=args.steps,
        learning_rate=args.learning_rate,
        seed=args.seed,
    )
    report = {
        "format": "fly-brain-linear-report-v1",
        "datasets": [dataset.metadata for dataset in datasets],
        "model": {
            "kind": "linear_localisation",
            "input_width": model.input_width,
            "site_count": model.site_count,
            "predict_coverage": model.predict_coverage,
        },
        "training": {
            "steps": args.steps,
            "learning_rate": args.learning_rate,
            "seed": args.seed,
            "samples": train.features.shape[0],
            "initial_loss": result.losses[0],
            "final_loss": result.losses[-1],
        },
        "validation": asdict(
            evaluate_localization(
                model.predict_features(result.parameters, validation.features),
                _targets(validation),
            )
        ),
        "test": asdict(
            evaluate_localization(
                model.predict_features(result.parameters, test.features), _targets(test)
            )
        ),
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


def _train_deep(args: argparse.Namespace) -> int:
    datasets = [CachedStillDataset.load(path) for path in args.caches]
    train = combine_batches([dataset.batch("train") for dataset in datasets])
    validation = combine_batches([dataset.batch("validation") for dataset in datasets])
    test = combine_batches([dataset.batch("test") for dataset in datasets])
    model = MLPLocalizationModel(train.input_width, hidden_width=args.hidden_width)
    result = train_mlp(
        model,
        train,
        steps=args.steps,
        batch_size=args.batch_size,
        learning_rate=args.learning_rate,
        seed=args.seed,
    )
    report = {
        "format": "fly-brain-mlp-report-v1",
        "git_revision": _git_revision(),
        "datasets": [dataset.metadata for dataset in datasets],
        "model": {
            "kind": "mlp_localisation",
            "input_width": model.input_width,
            "hidden_width": model.hidden_width,
            "hidden_layers": 2,
        },
        "training": {
            "steps": args.steps,
            "batch_size": args.batch_size,
            "learning_rate": args.learning_rate,
            "seed": args.seed,
            "samples": train.features.shape[0],
            "initial_loss": result.losses[0],
            "final_loss": result.losses[-1],
        },
        "validation": asdict(
            evaluate_localization(
                model.predict_features(result.parameters, validation.features),
                _targets(validation),
            )
        ),
        "test": asdict(
            evaluate_localization(
                model.predict_features(result.parameters, test.features), _targets(test)
            )
        ),
        "per_dataset": {
            str(dataset.metadata["source_dataset_id"]): {
                split: _evaluate_mlp(model, result.parameters, dataset.batch(split))
                for split in ("validation", "test")
            }
            for dataset in datasets
        },
    }
    report["checkpoint_manifest_sha256"] = save_checkpoint(
        args.checkpoint, result.parameters._asdict(), report
    )
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


def _evaluate_mlp(
    model: MLPLocalizationModel, parameters: MLPParameters, batch: LocalizationBatch
) -> dict[str, object]:
    return asdict(
        evaluate_localization(
            model.predict_features(parameters, batch.features),
            _targets(batch),
        )
    )


def _git_revision() -> str:
    return subprocess.run(
        ["git", "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        if args.command == "prepare-stills":
            return _prepare(args)
        if args.command == "train-linear":
            return _train(args)
        if args.command == "train-mlp":
            return _train_deep(args)
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        print(f"fly-brain: error: {error}", file=sys.stderr)
        return 1
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
