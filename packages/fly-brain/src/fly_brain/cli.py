"""Local preprocessing and baseline experiment commands."""

import argparse
import hashlib
import json
import resource
import subprocess
import sys
import time
from dataclasses import asdict
from pathlib import Path
from typing import Any, cast

import jax
import numpy as np

from fly_brain.connectome import (
    BiologicalStatus,
    BrainCompiler,
    BrainModelSpec,
    BrainParameters,
    LeakyRateDynamics,
    MaleCNSLoader,
    OutputHead,
    ReceptorInputMap,
    SynapseCountPolicy,
    build_first_visual_network,
    build_receptor_input_map,
)
from fly_brain.connectome.visual import (
    LAMINA_RELAY_TYPES,
    MEDULLA_RELAY_TYPES,
    PHOTORECEPTOR_TYPES,
)
from fly_brain.contracts import BrainTargets, Eye, SensorLayout
from fly_brain.datasets import (
    CachedStillDataset,
    StillLabelAuditConfig,
    audit_still_labels,
    combine_batches,
    convert_honey_factory_dataset,
    load_image_localization_batches,
)
from fly_brain.experiments import save_checkpoint
from fly_brain.models import (
    CNNLocalizationModel,
    ConnectomeBatch,
    ConnectomeLocalizationModel,
    ConnectomeModelConfig,
    ConnectomeTrainingConfig,
    LinearLocalizationModel,
    LocalizationBatch,
    MLPLocalizationModel,
    MLPParameters,
    SpatialBatch,
    SpatialLocalizationModel,
    SpatialModelConfig,
    SpatialParameters,
    SpatialTopology,
    SpatialTrainingConfig,
    build_angular_topology,
    train_cnn,
    train_connectome,
    train_linear,
    train_mlp,
    train_spatial,
)
from fly_brain.tasks import evaluate_localization
from fly_vision.lattice import default_lattice


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="fly-brain")
    commands = parser.add_subparsers(dest="command", required=True)
    prepare = commands.add_parser("prepare-stills")
    prepare.add_argument("datasets", nargs="+", type=Path)
    prepare.add_argument("--out", required=True, type=Path)
    prepare.add_argument("--matrix-cache", required=True, type=Path)
    prepare.add_argument("--workers", type=int, default=1)
    audit = commands.add_parser("audit-stills")
    audit.add_argument("dataset", type=Path)
    audit.add_argument("cache", type=Path)
    audit.add_argument("--config", required=True, type=Path)
    audit.add_argument("--matrix-cache", required=True, type=Path)
    audit.add_argument("--report", required=True, type=Path)
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
    image = commands.add_parser("train-image-mlp")
    image.add_argument("dataset", type=Path)
    image.add_argument("labels", type=Path)
    image.add_argument("--config", required=True, type=Path)
    image.add_argument("--report", required=True, type=Path)
    image.add_argument("--checkpoint", required=True, type=Path)
    image_cnn = commands.add_parser("train-image-cnn")
    image_cnn.add_argument("dataset", type=Path)
    image_cnn.add_argument("labels", type=Path)
    image_cnn.add_argument("--config", required=True, type=Path)
    image_cnn.add_argument("--report", required=True, type=Path)
    image_cnn.add_argument("--checkpoint", required=True, type=Path)
    spatial = commands.add_parser("train-spatial")
    spatial.add_argument("caches", nargs="+", type=Path)
    spatial.add_argument("--config", required=True, type=Path)
    spatial.add_argument("--report", required=True, type=Path)
    spatial.add_argument("--checkpoint", required=True, type=Path)
    connectome = commands.add_parser("train-connectome")
    connectome.add_argument("caches", nargs="+", type=Path)
    connectome.add_argument("--connectome-root", required=True, type=Path)
    connectome.add_argument("--config", required=True, type=Path)
    connectome.add_argument("--input-map", required=True, type=Path)
    connectome.add_argument("--report", required=True, type=Path)
    connectome.add_argument("--checkpoint", required=True, type=Path)
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
            dataset,
            output,
            matrix_cache=args.matrix_cache,
            workers=args.workers,
            progress=progress,
        )
        print(f"  wrote {converted.rows} samples", flush=True)
    return 0


def _audit_stills(args: argparse.Namespace) -> int:
    raw_values: Any = json.loads(args.config.read_text())
    if not isinstance(raw_values, dict):
        raise ValueError("still-label audit config must be a JSON object")
    values = cast(dict[str, Any], raw_values)
    if values.pop("format", None) != ("fly-brain-still-label-audit-config-v1"):
        raise ValueError("unsupported still-label audit config format")
    try:
        config = StillLabelAuditConfig(**values)
    except TypeError as error:
        raise ValueError(f"invalid still-label audit setting: {error}") from error
    report = audit_still_labels(
        args.dataset,
        CachedStillDataset.load(args.cache),
        matrix_cache=args.matrix_cache,
        config=config,
    )
    payload = report.as_dict()
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n")
    print(json.dumps(payload, indent=2, sort_keys=True))
    return 0 if report.passed else 1


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


def _train_image(args: argparse.Namespace) -> int:
    values = cast(dict[str, Any], json.loads(args.config.read_text()))
    if values.pop("format", None) != "fly-brain-image-mlp-experiment-v1":
        raise ValueError("unsupported image MLP experiment config format")
    downsample_factor = int(values.pop("downsample_factor"))
    workers = int(values.pop("workers"))
    hidden_width = int(values.pop("hidden_width"))
    steps = int(values.pop("steps"))
    batch_size = int(values.pop("batch_size"))
    learning_rate = float(values.pop("learning_rate"))
    seed = int(values.pop("seed"))
    if values:
        raise ValueError(f"unknown image MLP settings: {sorted(values)}")
    labels = CachedStillDataset.load(args.labels)
    batches = load_image_localization_batches(
        args.dataset,
        labels,
        downsample_factor=downsample_factor,
        workers=workers,
    )
    train, validation, test = (batches[name] for name in ("train", "validation", "test"))
    model = MLPLocalizationModel(train.input_width, hidden_width=hidden_width)
    result = train_mlp(
        model,
        train,
        steps=steps,
        batch_size=batch_size,
        learning_rate=learning_rate,
        seed=seed,
    )
    validation_prediction = model.predict_features(result.parameters, validation.features)
    threshold_config = SpatialTrainingConfig()
    threshold = _select_presence_threshold(
        validation_prediction.presence_probability,
        validation.presence.astype(np.bool_),
        threshold_config,
    )
    report: dict[str, object] = {
        "format": "fly-brain-image-mlp-report-v1",
        "git_revision": _git_revision(),
        "dataset": labels.metadata,
        "model": {
            "kind": "raw_image_mlp",
            "input_width": model.input_width,
            "hidden_width": hidden_width,
            "downsample_factor": downsample_factor,
        },
        "training": {
            "steps": steps,
            "batch_size": batch_size,
            "learning_rate": learning_rate,
            "seed": seed,
            "samples": train.features.shape[0],
            "initial_loss": result.losses[0],
            "final_loss": result.losses[-1],
            "selected_presence_threshold": threshold,
        },
        "validation": asdict(
            evaluate_localization(
                validation_prediction, _targets(validation), presence_threshold=threshold
            )
        ),
        "test": asdict(
            evaluate_localization(
                model.predict_features(result.parameters, test.features),
                _targets(test),
                presence_threshold=threshold,
            )
        ),
    }
    report["checkpoint_manifest_sha256"] = save_checkpoint(
        args.checkpoint, result.parameters._asdict(), report
    )
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


def _train_image_cnn(args: argparse.Namespace) -> int:
    values = cast(dict[str, Any], json.loads(args.config.read_text()))
    if values.pop("format", None) != "fly-brain-image-cnn-experiment-v1":
        raise ValueError("unsupported image CNN experiment config format")
    downsample_factor = int(values.pop("downsample_factor"))
    workers = int(values.pop("workers"))
    channel_widths = tuple(int(value) for value in values.pop("channel_widths"))
    kernel_sizes = tuple(int(value) for value in values.pop("kernel_sizes"))
    strides = tuple(int(value) for value in values.pop("strides"))
    include_directions = bool(values.pop("include_directions", False))
    steps = int(values.pop("steps"))
    batch_size = int(values.pop("batch_size"))
    learning_rate = float(values.pop("learning_rate"))
    seed = int(values.pop("seed"))
    if values:
        raise ValueError(f"unknown image CNN settings: {sorted(values)}")
    labels = CachedStillDataset.load(args.labels)
    batches = load_image_localization_batches(
        args.dataset,
        labels,
        downsample_factor=downsample_factor,
        workers=workers,
    )
    manifest = cast(dict[str, Any], json.loads((args.dataset / "dataset.json").read_text()))
    capture = cast(dict[str, Any], manifest["capture"])
    model = CNNLocalizationModel(
        image_height=int(capture["height"]) // downsample_factor,
        image_width=int(capture["width"]) // downsample_factor,
        channel_widths=channel_widths,
        kernel_sizes=kernel_sizes,
        strides=strides,
        include_directions=include_directions,
    )
    train, validation, test = (batches[name] for name in ("train", "validation", "test"))
    result = train_cnn(
        model,
        train,
        steps=steps,
        batch_size=batch_size,
        learning_rate=learning_rate,
        seed=seed,
    )
    validation_prediction = model.predict_features(result.parameters, validation.features)
    threshold = _select_presence_threshold(
        validation_prediction.presence_probability,
        validation.presence.astype(np.bool_),
        SpatialTrainingConfig(),
    )
    report: dict[str, object] = {
        "format": "fly-brain-image-cnn-report-v1",
        "git_revision": _git_revision(),
        "dataset": labels.metadata,
        "model": {
            "kind": "raw_image_cnn",
            "input_shape": [model.image_height, model.image_width, 3],
            "channel_widths": list(channel_widths),
            "kernel_sizes": list(kernel_sizes),
            "strides": list(strides),
            "include_directions": include_directions,
            "trainable_parameters": model.trainable_parameter_count,
            "downsample_factor": downsample_factor,
        },
        "training": {
            "steps": steps,
            "batch_size": batch_size,
            "learning_rate": learning_rate,
            "seed": seed,
            "samples": train.features.shape[0],
            "initial_loss": result.losses[0],
            "final_loss": result.losses[-1],
            "selected_presence_threshold": threshold,
        },
        "validation": asdict(
            evaluate_localization(
                validation_prediction, _targets(validation), presence_threshold=threshold
            )
        ),
        "test": asdict(
            evaluate_localization(
                model.predict_features(result.parameters, test.features),
                _targets(test),
                presence_threshold=threshold,
            )
        ),
    }
    report["checkpoint_manifest_sha256"] = save_checkpoint(
        args.checkpoint, result.parameters.checkpoint_arrays(), report
    )
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


def _train_connectome(args: argparse.Namespace) -> int:
    started = time.perf_counter()
    model_config, training_config = _load_connectome_config(args.config)
    datasets = [CachedStillDataset.load(path) for path in args.caches]
    layout = _cached_sensor_layout(datasets)
    cell_types = PHOTORECEPTOR_TYPES + LAMINA_RELAY_TYPES + MEDULLA_RELAY_TYPES
    selector_loader = MaleCNSLoader(
        args.connectome_root,
        minimum_synapse_count=model_config.minimum_synapse_count,
    )
    selected_ids = selector_loader.annotation_ids(
        cell_types=cell_types,
        superclasses=("visual_projection",),
    )
    graph = MaleCNSLoader(
        args.connectome_root,
        minimum_synapse_count=model_config.minimum_synapse_count,
        neuron_ids=selected_ids,
    ).load()
    network = build_first_visual_network(
        graph,
        minimum_synapse_count=model_config.minimum_synapse_count,
        component_names=model_config.components,
    )
    components = {component.spec.name: component for component in network.components}
    input_map = build_receptor_input_map(layout, graph, components["photoreceptors"])
    readout_neuron_ids = np.unique(
        np.concatenate([components[name].neuron_ids for name in model_config.readout_components])
    )
    input_manifest = input_map.to_manifest()
    input_payload = json.dumps(input_manifest, indent=2, sort_keys=True) + "\n"
    args.input_map.parent.mkdir(parents=True, exist_ok=True)
    args.input_map.write_text(input_payload)
    input_sha256 = hashlib.sha256(input_payload.encode()).hexdigest()
    spec = BrainModelSpec(
        network,
        (input_map.input_port(),),
        OutputHead(
            "honey_localisation",
            readout_neuron_ids,
            4,
            BiologicalStatus.LEARNED,
        ),
        LeakyRateDynamics(model_config.time_constant, model_config.activation),
        SynapseCountPolicy(
            model_config.initial_edge_gain,
            model_config.train_neuron_bias,
            model_config.edge_gain_mode,
            model_config.maximum_edge_gain,
            model_config.use_neurotransmitter_signs,
        ),
        model_config.dt,
    )
    compiler = BrainCompiler(spec)
    plan = compiler.plan()
    model = ConnectomeLocalizationModel(compiler.compile(), model_config.settling_steps)
    train = _connectome_batch(datasets, "train", input_map)
    validation = _connectome_batch(datasets, "validation", input_map)
    test = _connectome_batch(datasets, "test", input_map)
    result = train_connectome(model, train, validation, training_config)
    validation_prediction = model.predict(
        result.parameters, validation, batch_size=training_config.batch_size
    )
    threshold = _select_presence_threshold(
        validation_prediction.presence_probability,
        validation.presence.astype(np.bool_),
        training_config,
    )
    description = network.describe()
    report: dict[str, object] = {
        "format": "fly-brain-connectome-report-v1",
        "git_revision": _git_revision(),
        "datasets": [dataset.metadata for dataset in datasets],
        "connectome": {
            "release_id": graph.release.release_id,
            "graph_sha256": graph.graph_sha256,
            "components": dict(description.component_sizes),
            "anatomical_synapse_count": description.anatomical_synapse_count,
        },
        "input_map": {
            "path": str(args.input_map),
            "sha256": input_sha256,
            "method": input_map.method,
            "biological_status": input_map.biological_status.value,
            "source_width": input_map.source_width,
            "target_neuron_count": input_map.target_neuron_ids.size,
            "assignment_count": input_map.assignment_source_indices.size,
        },
        "model": {**asdict(model_config), "compilation": asdict(plan)},
        "training": {
            **asdict(training_config),
            "samples": train.rows,
            "completed_steps": len(result.losses),
            "best_step": result.best_step,
            "initial_loss": result.losses[0],
            "final_loss": result.losses[-1],
            "validation_losses": [list(item) for item in result.validation_losses],
            "selected_presence_threshold": threshold,
        },
        "validation": asdict(
            evaluate_localization(
                validation_prediction,
                _connectome_targets(validation),
                presence_threshold=threshold,
            )
        ),
        "test": _evaluate_connectome(
            model, result.parameters, test, training_config.batch_size, threshold
        ),
        "per_dataset": {
            str(dataset.metadata["source_dataset_id"]): {
                split: _evaluate_connectome(
                    model,
                    result.parameters,
                    _connectome_batch([dataset], split, input_map),
                    training_config.batch_size,
                    threshold,
                )
                for split in ("validation", "test")
            }
            for dataset in datasets
        },
    }
    report["runtime"] = {
        "backend": jax.default_backend(),
        "devices": [str(device) for device in cast(list[Any], cast(Any, jax).devices())],
        "wall_seconds": time.perf_counter() - started,
        "peak_rss_bytes": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss * 1024,
    }
    report["checkpoint_manifest_sha256"] = save_checkpoint(
        args.checkpoint, result.parameters._asdict(), report
    )
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


def _load_connectome_config(
    path: Path,
) -> tuple[ConnectomeModelConfig, ConnectomeTrainingConfig]:
    raw_values: Any = json.loads(path.read_text())
    if not isinstance(raw_values, dict):
        raise ValueError("connectome experiment config must be a JSON object")
    values = cast(dict[str, Any], raw_values)
    if values.get("format") != "fly-brain-connectome-experiment-v1":
        raise ValueError("unsupported connectome experiment config format")
    model_values = values.get("model")
    training_values = values.get("training")
    if not isinstance(model_values, dict) or not isinstance(training_values, dict):
        raise ValueError("connectome experiment config requires model and training objects")
    try:
        return ConnectomeModelConfig(
            **cast(dict[str, Any], model_values)
        ), ConnectomeTrainingConfig(**cast(dict[str, Any], training_values))
    except TypeError as error:
        raise ValueError(f"invalid connectome experiment setting: {error}") from error


def _cached_sensor_layout(datasets: list[CachedStillDataset]) -> SensorLayout:
    if not datasets:
        raise ValueError("at least one cached dataset is required")
    first = datasets[0]
    lattice = default_lattice()
    if first.activations.shape[1] != lattice.ommatidium_id.size:
        raise ValueError("cached sensor sites do not match the default fly-vision lattice")
    return SensorLayout(
        str(first.metadata["layout_id"]),
        lattice.ommatidium_id.astype(np.int64),
        tuple(Eye.LEFT if value == "L" else Eye.RIGHT for value in lattice.eye),
        first.directions,
        tuple(str(value) for value in lattice.subtype),
        ("R1_6", "R7", "R8"),
        "fly-vision-cache-v1",
        str(first.metadata["layout_id"]),
        str(first.metadata["sensor_config_hash"]),
    )


def _connectome_batch(
    datasets: list[CachedStillDataset], split: str, input_map: ReceptorInputMap
) -> ConnectomeBatch:
    spatial = _spatial_batch(datasets, split)
    weighted = spatial.coverage @ spatial.directions
    norms = np.linalg.norm(weighted, axis=1)
    direction = np.zeros_like(weighted, dtype=np.float32)
    nonzero = norms > 0
    direction[nonzero] = weighted[nonzero] / norms[nonzero, None]
    return ConnectomeBatch(
        input_map.transform(spatial.signals, valid=spatial.valid),
        spatial.presence,
        direction,
    )


def _connectome_targets(batch: ConnectomeBatch) -> BrainTargets:
    return BrainTargets(
        time_mask=np.ones(batch.rows, dtype=np.bool_),
        honey_present=batch.presence.astype(np.bool_),
        honey_direction=batch.direction,
    )


def _evaluate_connectome(
    model: ConnectomeLocalizationModel,
    parameters: BrainParameters,
    batch: ConnectomeBatch,
    batch_size: int,
    threshold: float,
) -> dict[str, object]:
    return asdict(
        evaluate_localization(
            model.predict(parameters, batch, batch_size=batch_size),
            _connectome_targets(batch),
            presence_threshold=threshold,
        )
    )


def _train_spatial(args: argparse.Namespace) -> int:
    model_config, training_config = _load_spatial_config(args.config)
    datasets = [CachedStillDataset.load(path) for path in args.caches]
    train = _spatial_batch(datasets, "train")
    validation = _spatial_batch(datasets, "validation")
    test = _spatial_batch(datasets, "test")
    lattice = default_lattice()
    if lattice.ommatidium_id.size != train.site_count:
        raise ValueError("cached sensor sites do not match the default fly-vision lattice")
    topology = build_angular_topology(
        train.directions,
        lattice.eye,
        neighbor_count=model_config.neighbor_count,
        radius_deg=model_config.neighbor_radius_deg,
    )
    model = SpatialLocalizationModel(train.channel_count, train.site_count, model_config)
    result = train_spatial(model, train, validation, topology, training_config)
    validation_prediction = model.predict(
        result.parameters, validation, topology, batch_size=training_config.batch_size
    )
    threshold = _select_presence_threshold(
        validation_prediction.presence_probability,
        validation.presence.astype(np.bool_),
        training_config,
    )
    report: dict[str, object] = {
        "format": "fly-brain-spatial-report-v1",
        "git_revision": _git_revision(),
        "datasets": [dataset.metadata for dataset in datasets],
        "model": {
            "kind": "spatial_coverage_localisation",
            "channel_count": model.channel_count,
            "site_count": model.site_count,
            **asdict(model_config),
        },
        "topology": {
            "valid_edges": int(np.count_nonzero(topology.neighbor_valid)),
            "possible_edges": int(topology.neighbor_valid.size),
        },
        "training": {
            **asdict(training_config),
            "samples": train.rows,
            "completed_steps": len(result.losses),
            "best_step": result.best_step,
            "initial_loss": result.losses[0],
            "final_loss": result.losses[-1],
            "validation_losses": [list(item) for item in result.validation_losses],
            "selected_presence_threshold": threshold,
        },
        "validation": asdict(
            evaluate_localization(
                validation_prediction,
                _spatial_targets(validation),
                presence_threshold=threshold,
            )
        ),
        "test": _evaluate_spatial(
            model, result.parameters, test, topology, training_config.batch_size, threshold
        ),
        "per_dataset": {
            str(dataset.metadata["source_dataset_id"]): {
                split: _evaluate_spatial(
                    model,
                    result.parameters,
                    _spatial_batch([dataset], split),
                    topology,
                    training_config.batch_size,
                    threshold,
                )
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


def _load_spatial_config(path: Path) -> tuple[SpatialModelConfig, SpatialTrainingConfig]:
    raw_values: Any = json.loads(path.read_text())
    if not isinstance(raw_values, dict):
        raise ValueError("spatial experiment config must be a JSON object")
    values = cast(dict[str, Any], raw_values)
    if values.get("format") != "fly-brain-spatial-experiment-v1":
        raise ValueError("unsupported spatial experiment config format")
    model_values = values.get("model")
    training_values = values.get("training")
    if not isinstance(model_values, dict) or not isinstance(training_values, dict):
        raise ValueError("spatial experiment config requires model and training objects")
    try:
        return SpatialModelConfig(**cast(dict[str, Any], model_values)), SpatialTrainingConfig(
            **cast(dict[str, Any], training_values)
        )
    except TypeError as error:
        raise ValueError(f"invalid spatial experiment setting: {error}") from error


def _spatial_batch(datasets: list[CachedStillDataset], split: str) -> SpatialBatch:
    if not datasets:
        raise ValueError("at least one cached dataset is required")
    first = datasets[0]
    if any(
        not np.array_equal(dataset.valid, first.valid)
        or not np.allclose(dataset.directions, first.directions)
        or dataset.activations.shape[1:] != first.activations.shape[1:]
        for dataset in datasets[1:]
    ):
        raise ValueError("spatial datasets must use the same sensor layout")
    selected = [np.asarray(dataset.splits) == split for dataset in datasets]
    if any(not mask.any() for mask in selected):
        raise ValueError(f"every spatial dataset must contain {split!r} samples")
    return SpatialBatch(
        signals=np.concatenate(
            [dataset.activations[mask] for dataset, mask in zip(datasets, selected, strict=True)]
        ),
        valid=first.valid,
        coverage=np.concatenate(
            [dataset.coverage[mask] for dataset, mask in zip(datasets, selected, strict=True)]
        ),
        directions=first.directions,
    )


def _spatial_targets(batch: SpatialBatch) -> BrainTargets:
    weighted = batch.coverage @ batch.directions
    norms = np.linalg.norm(weighted, axis=1)
    direction = np.zeros_like(weighted, dtype=np.float32)
    nonzero = norms > 0
    direction[nonzero] = weighted[nonzero] / norms[nonzero, None]
    return BrainTargets(
        time_mask=np.ones(batch.rows, dtype=np.bool_),
        honey_present=batch.presence.astype(np.bool_),
        honey_direction=direction,
        honey_coverage=batch.coverage,
    )


def _select_presence_threshold(
    probabilities: np.ndarray,
    truth: np.ndarray,
    config: SpatialTrainingConfig | ConnectomeTrainingConfig,
) -> float:
    thresholds = np.linspace(
        config.threshold_search_min,
        config.threshold_search_max,
        config.threshold_search_steps,
    )
    positive = truth
    negative = ~truth
    best_threshold = float(thresholds[0])
    best_score = -1.0
    for threshold in thresholds:
        predicted = probabilities >= threshold
        recall = float(np.mean(predicted[positive])) if positive.any() else 0.0
        specificity = float(np.mean(~predicted[negative])) if negative.any() else 0.0
        score = (recall + specificity) / 2.0
        if score > best_score:
            best_score = score
            best_threshold = float(threshold)
    return best_threshold


def _evaluate_spatial(
    model: SpatialLocalizationModel,
    parameters: SpatialParameters,
    batch: SpatialBatch,
    topology: SpatialTopology,
    batch_size: int,
    threshold: float,
) -> dict[str, object]:
    return asdict(
        evaluate_localization(
            model.predict(parameters, batch, topology, batch_size=batch_size),
            _spatial_targets(batch),
            presence_threshold=threshold,
        )
    )


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
        if args.command == "audit-stills":
            return _audit_stills(args)
        if args.command == "train-linear":
            return _train(args)
        if args.command == "train-mlp":
            return _train_deep(args)
        if args.command == "train-image-mlp":
            return _train_image(args)
        if args.command == "train-image-cnn":
            return _train_image_cnn(args)
        if args.command == "train-spatial":
            return _train_spatial(args)
        if args.command == "train-connectome":
            return _train_connectome(args)
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        print(f"fly-brain: error: {error}", file=sys.stderr)
        return 1
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
