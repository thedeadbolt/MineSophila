"""Local preprocessing and baseline experiment commands."""

import argparse
import hashlib
import json
import resource
import subprocess
import sys
import time
from dataclasses import asdict, replace
from pathlib import Path
from typing import Any, Literal, cast

import jax
import jax.numpy as jnp
import numpy as np
from numpy.typing import NDArray

from fly_brain.connectome import (
    CENTRAL_COMPLEX_COMPONENTS,
    BiologicalStatus,
    BrainCompiler,
    BrainModelSpec,
    BrainNetwork,
    BrainParameters,
    CompiledBrain,
    ConnectomeGraph,
    InjectionRule,
    InputPort,
    LeakyRateDynamics,
    MaleCNSLoader,
    NetworkComponent,
    OutputHead,
    ReceptorInputMap,
    SynapseCountPolicy,
    build_first_visual_network,
    build_receptor_input_map,
    central_complex_cell_types,
)
from fly_brain.connectome.central_complex import (
    ANTERIOR_VISUAL_INPUT_TYPES,
    COMPASS_TYPES,
    RING_NEURON_TYPES,
    STEERING_TYPES,
    TUBERCLE_BULB_TYPES,
)
from fly_brain.connectome.compass import (
    CompassOperatingPoint,
    bump_metrics,
    compass_modes,
    pb_glomerulus_angle,
    ring_attractor_gains,
    type_pair_raw_gains,
)
from fly_brain.connectome.integrator import (
    CompassCircuit,
    IntegratorFitConfig,
    IntegratorStage,
    constant_turn_response,
    fit_compass_integrator,
)
from fly_brain.connectome.visual import (
    LAMINA_RELAY_TYPES,
    MEDULLA_RELAY_TYPES,
    PHOTORECEPTOR_TYPES,
    VISUAL_KENYON_COMPONENT,
    VISUAL_KENYON_INPUT_COMPONENT,
)
from fly_brain.contracts import BrainTargets, Eye, SensorLayout
from fly_brain.datasets import (
    CachedClipDataset,
    CachedStillDataset,
    StillLabelAuditConfig,
    audit_still_labels,
    combine_batches,
    combine_clip_features,
    convert_honey_factory_clips,
    convert_honey_factory_dataset,
    load_image_localization_batches,
)
from fly_brain.experiments import load_checkpoint, save_checkpoint
from fly_brain.models import (
    ActionPrediction,
    ActionTemporalBatch,
    CNNLocalizationModel,
    ConnectomeActivityEncoder,
    ConnectomeBatch,
    ConnectomeClipActivityTransform,
    ConnectomeClipModel,
    ConnectomeClipTrainingConfig,
    ConnectomeInputTransform,
    ConnectomeLocalizationModel,
    ConnectomeModelConfig,
    ConnectomeTrainingConfig,
    LinearLocalizationModel,
    LocalizationBatch,
    MLPLocalizationModel,
    MLPParameters,
    ReceptorPoolingInputTransform,
    RecurrentActionModel,
    RecurrentLocalizationModel,
    RecurrentTrainingConfig,
    SpatialBatch,
    SpatialCoverageInputTransform,
    SpatialLocalizationModel,
    SpatialModelConfig,
    SpatialParameters,
    SpatialTopology,
    SpatialTrainingConfig,
    TemporalBatch,
    TemporalPrediction,
    build_angular_topology,
    self_motion_port_values,
    train_cnn,
    train_connectome,
    train_connectome_clips,
    train_linear,
    train_mlp,
    train_recurrent,
    train_recurrent_actions,
    train_spatial,
)
from fly_brain.tasks import HoneyLocalizationPrediction, evaluate_localization
from fly_vision.lattice import default_lattice
from fly_vision.projection import to_unit


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="fly-brain")
    commands = parser.add_subparsers(dest="command", required=True)
    prepare = commands.add_parser("prepare-stills")
    prepare.add_argument("datasets", nargs="+", type=Path)
    prepare.add_argument("--out", required=True, type=Path)
    prepare.add_argument("--matrix-cache", required=True, type=Path)
    prepare.add_argument("--workers", type=int, default=1)
    prepare_clips = commands.add_parser("prepare-clips")
    prepare_clips.add_argument("dataset", type=Path)
    prepare_clips.add_argument("--out", required=True, type=Path)
    prepare_clips.add_argument("--matrix-cache", required=True, type=Path)
    prepare_clips.add_argument("--frontend-checkpoint", required=True, type=Path)
    prepare_clips.add_argument("--prediction-batch-size", type=int, default=64)
    prepare_clips.add_argument("--frame-selection", choices=("all", "tick"), default="tick")
    prepare_connectome_clips = commands.add_parser("prepare-connectome-clips")
    prepare_connectome_clips.add_argument("dataset", type=Path)
    prepare_connectome_clips.add_argument("--out", required=True, type=Path)
    prepare_connectome_clips.add_argument("--matrix-cache", required=True, type=Path)
    prepare_connectome_clips.add_argument("--layout-cache", required=True, type=Path)
    prepare_connectome_clips.add_argument("--connectome-root", required=True, type=Path)
    prepare_connectome_clips.add_argument("--config", required=True, type=Path)
    prepare_connectome_clips.add_argument("--connectome-checkpoint", required=True, type=Path)
    prepare_connectome_clips.add_argument("--frontend-checkpoint", required=True, type=Path)
    prepare_connectome_clips.add_argument(
        "--activity-components", nargs="+", default=[VISUAL_KENYON_COMPONENT]
    )
    prepare_connectome_clips.add_argument("--transitions-per-frame", type=int, default=1)
    prepare_connectome_clips.add_argument(
        "--state-mode",
        choices=("carry", "reset"),
        default="carry",
        help="carry connectome state across frames or settle each frame from rest",
    )
    prepare_connectome_clips.add_argument("--prediction-batch-size", type=int, default=64)
    prepare_connectome_clips.add_argument(
        "--frame-selection", choices=("all", "tick"), default="tick"
    )
    combine_clips = commands.add_parser("combine-clip-features")
    combine_clips.add_argument(
        "--part", action="append", required=True, metavar="NAME=CACHE", help="named clip cache"
    )
    combine_clips.add_argument(
        "--group",
        action="append",
        default=[],
        metavar="NAME=GROUP[,GROUP...]",
        help="composite feature group concatenating existing groups",
    )
    combine_clips.add_argument("--out", required=True, type=Path)
    probe = commands.add_parser(
        "probe-clip-features",
        help="decode presence and bearing from each feature group with the same framewise MLP",
    )
    probe.add_argument("cache", type=Path)
    probe.add_argument("--groups", nargs="+", required=True)
    probe.add_argument("--steps", type=int, default=3_000)
    probe.add_argument("--batch-size", type=int, default=128)
    probe.add_argument("--hidden-width", type=int, default=64)
    probe.add_argument("--learning-rate", type=float, default=1e-3)
    probe.add_argument("--seeds", type=int, nargs="+", default=[0])
    probe.add_argument("--evaluation-split", default="test")
    probe.add_argument("--report", required=True, type=Path)
    port_clips = commands.add_parser(
        "prepare-port-clips", help="cache each clip frame's connectome input port values"
    )
    port_clips.add_argument("dataset", type=Path)
    port_clips.add_argument("--out", required=True, type=Path)
    port_clips.add_argument("--matrix-cache", required=True, type=Path)
    port_clips.add_argument("--layout-cache", required=True, type=Path)
    port_clips.add_argument("--connectome-root", required=True, type=Path)
    port_clips.add_argument("--config", required=True, type=Path)
    port_clips.add_argument("--frontend-checkpoint", required=True, type=Path)
    port_clips.add_argument("--prediction-batch-size", type=int, default=64)
    port_clips.add_argument("--frame-selection", choices=("all", "tick"), default="tick")
    clip_connectome = commands.add_parser(
        "train-connectome-clips", help="train a connectome on clips with state carried over frames"
    )
    clip_connectome.add_argument("cache", type=Path)
    clip_connectome.add_argument("--layout-cache", required=True, type=Path)
    clip_connectome.add_argument("--connectome-root", required=True, type=Path)
    clip_connectome.add_argument("--config", required=True, type=Path)
    clip_connectome.add_argument("--initial-checkpoint", type=Path)
    clip_connectome.add_argument("--report", required=True, type=Path)
    clip_connectome.add_argument("--checkpoint", required=True, type=Path)
    compass_probe = commands.add_parser(
        "probe-compass",
        help="measure compass activity, eigenmodes and EPG bump tracking of a clip checkpoint",
    )
    compass_probe.add_argument("cache", type=Path)
    compass_probe.add_argument("--layout-cache", required=True, type=Path)
    compass_probe.add_argument("--connectome-root", required=True, type=Path)
    compass_probe.add_argument("--config", required=True, type=Path)
    compass_probe.add_argument("--checkpoint", required=True, type=Path)
    compass_probe.add_argument("--split", default="test")
    compass_probe.add_argument("--report", required=True, type=Path)
    integrator = commands.add_parser(
        "fit-compass-integrator",
        help="fit the compass as an angular integrator on recorded yaw (ADR 0028)",
    )
    integrator.add_argument("yaw_cache", type=Path, help="clip cache whose train split gives yaw")
    integrator.add_argument("--layout-cache", required=True, type=Path)
    integrator.add_argument("--connectome-root", required=True, type=Path)
    integrator.add_argument("--config", required=True, type=Path, help="connectome clip config")
    integrator.add_argument("--initial-checkpoint", required=True, type=Path)
    integrator.add_argument("--fit-config", required=True, type=Path)
    integrator.add_argument("--out", required=True, type=Path)
    temporal = commands.add_parser("train-recurrent")
    temporal.add_argument("cache", type=Path)
    temporal.add_argument("--config", required=True, type=Path)
    temporal.add_argument("--report", required=True, type=Path)
    temporal.add_argument("--checkpoint", required=True, type=Path)
    actions = commands.add_parser("train-recurrent-actions")
    actions.add_argument("cache", type=Path)
    actions.add_argument("--config", required=True, type=Path)
    actions.add_argument("--report", required=True, type=Path)
    actions.add_argument("--checkpoint", required=True, type=Path)
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
    connectome.add_argument("--frontend-checkpoint", type=Path)
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


def _prepare_clips(args: argparse.Namespace) -> int:
    model, parameters, topology = _load_spatial_frontend(args.frontend_checkpoint)
    print(f"preparing clips from {args.dataset} -> {args.out}", flush=True)

    def progress(done: int, total: int) -> None:
        print(f"  {done}/{total} clips", flush=True)

    converted = convert_honey_factory_clips(
        args.dataset,
        args.out,
        matrix_cache=args.matrix_cache,
        spatial_model=model,
        spatial_parameters=parameters,
        spatial_topology=topology,
        frontend_checkpoint_sha256=_file_sha256(args.frontend_checkpoint),
        prediction_batch_size=args.prediction_batch_size,
        frame_selection=args.frame_selection,
        progress=progress,
    )
    print(f"  wrote {converted.clip_count} clips and {converted.frame_count} frames", flush=True)
    return 0


def _prepare_connectome_clips(args: argparse.Namespace) -> int:
    model_config, training_config = _load_connectome_config(args.config)
    layout_dataset = CachedStillDataset.load(args.layout_cache)
    layout = _cached_sensor_layout([layout_dataset])
    graph, _, components, input_map, compiler = _build_connectome(
        layout, args.connectome_root, model_config
    )
    unknown = set(args.activity_components) - set(components)
    if unknown:
        raise ValueError(f"unknown activity components: {sorted(unknown)}")
    parameter_values, checkpoint_manifest = load_checkpoint(args.connectome_checkpoint)
    checkpoint_connectome = checkpoint_manifest.get("connectome")
    if not isinstance(checkpoint_connectome, dict):
        raise ValueError("connectome checkpoint lacks its graph manifest")
    checkpoint_graph = cast(dict[str, Any], checkpoint_connectome).get("graph_sha256")
    if checkpoint_graph != graph.graph_sha256:
        raise ValueError("connectome checkpoint graph does not match the compiled graph")
    parameters = BrainParameters(**cast(Any, parameter_values))
    frontend, frontend_manifest = _connectome_frontend(
        model_config,
        args.frontend_checkpoint,
        [layout_dataset],
        input_map,
        training_config.batch_size,
    )
    if frontend is None:
        raise ValueError("connectome clip activity requires an explicit input transform")
    selected = tuple(args.activity_components)
    neuron_ids = np.unique(np.concatenate([components[name].neuron_ids for name in selected]))
    feature_groups = {
        name: np.searchsorted(neuron_ids, components[name].neuron_ids).tolist() for name in selected
    }
    encoder = ConnectomeActivityEncoder(
        compiler.compile(),
        neuron_ids,
        args.transitions_per_frame,
        reset_each_frame=args.state_mode == "reset",
    )
    transform = ConnectomeClipActivityTransform(frontend, encoder, parameters)
    spatial_model, spatial_parameters, spatial_topology = _load_spatial_frontend(
        args.frontend_checkpoint
    )
    print(f"preparing connectome clips from {args.dataset} -> {args.out}", flush=True)

    def progress(done: int, total: int) -> None:
        print(f"  {done}/{total} clips", flush=True)

    converted = convert_honey_factory_clips(
        args.dataset,
        args.out,
        matrix_cache=args.matrix_cache,
        spatial_model=spatial_model,
        spatial_parameters=spatial_parameters,
        spatial_topology=spatial_topology,
        frontend_checkpoint_sha256=_file_sha256(args.frontend_checkpoint),
        prediction_batch_size=args.prediction_batch_size,
        frame_selection=args.frame_selection,
        sensory_transform=transform.transform,
        sensory_feature_manifest={
            "kind": (
                "framewise_connectome_activity"
                if encoder.reset_each_frame
                else "stateful_connectome_activity"
            ),
            "components": {name: int(components[name].neuron_ids.size) for name in selected},
            "groups": feature_groups,
            "neuron_ids": neuron_ids.tolist(),
            "width": encoder.output_width,
            "transitions_per_frame": encoder.transitions_per_frame,
            "state_mode": "reset" if encoder.reset_each_frame else "carry",
            "connectome_checkpoint_sha256": _file_sha256(args.connectome_checkpoint),
            "graph_sha256": graph.graph_sha256,
            "frontend": frontend_manifest,
        },
        progress=progress,
    )
    print(f"  wrote {converted.clip_count} clips and {converted.frame_count} frames", flush=True)
    return 0


def _named_option(value: str, option: str) -> tuple[str, str]:
    name, separator, rest = value.partition("=")
    if not separator or not name or not rest:
        raise ValueError(f"{option} must have the form NAME=VALUE, got {value!r}")
    return name, rest


def _combine_clip_features(args: argparse.Namespace) -> int:
    parts: list[tuple[str, CachedClipDataset]] = []
    for value in args.part:
        name, path = _named_option(value, "--part")
        parts.append((name, CachedClipDataset.load(Path(path))))
    composites: dict[str, tuple[str, ...]] = {}
    for value in args.group:
        name, members = _named_option(value, "--group")
        composites[name] = tuple(member for member in members.split(",") if member)
    combined = combine_clip_features(parts, composites)
    combined.metadata["sensory_features"]["source_paths"] = {
        name: str(Path(path).resolve())
        for name, path in (_named_option(value, "--part") for value in args.part)
    }
    combined.save(args.out)
    groups = combined.metadata["sensory_features"]["groups"]
    print(
        f"wrote {combined.frame_count} frames x {combined.sensory_features.shape[1]} features "
        f"with groups {sorted(groups)} -> {args.out}",
        flush=True,
    )
    return 0


def _balanced_threshold(probabilities: np.ndarray, truth: np.ndarray) -> float:
    best_threshold, best_score = 0.5, -1.0
    for threshold in np.linspace(0.05, 0.95, 181):
        predicted = probabilities >= threshold
        recall = float(np.mean(predicted[truth])) if truth.any() else 0.0
        specificity = float(np.mean(~predicted[~truth])) if (~truth).any() else 0.0
        score = (recall + specificity) / 2.0
        if score > best_score:
            best_threshold, best_score = float(threshold), score
    return best_threshold


def _probe_clip_features(args: argparse.Namespace) -> int:
    dataset = CachedClipDataset.load(args.cache)
    entries: list[dict[str, Any]] = []
    for group in args.groups:
        train = dataset.frame_batch("train", feature_group=group)
        validation = dataset.frame_batch("validation", feature_group=group)
        evaluation = dataset.frame_batch(args.evaluation_split, feature_group=group)
        model = MLPLocalizationModel(train.input_width, hidden_width=args.hidden_width)
        seeds: list[dict[str, Any]] = []
        for seed in args.seeds:
            started = time.perf_counter()
            result = train_mlp(
                model,
                train,
                steps=args.steps,
                batch_size=args.batch_size,
                learning_rate=args.learning_rate,
                seed=seed,
            )
            validation_prediction = model.predict_features(result.parameters, validation.features)
            threshold = _balanced_threshold(
                validation_prediction.presence_probability, validation.presence > 0.5
            )
            metrics = evaluate_localization(
                model.predict_features(result.parameters, evaluation.features),
                _targets(evaluation),
                presence_threshold=threshold,
            )
            seeds.append(
                {
                    "seed": seed,
                    "selected_presence_threshold": threshold,
                    "initial_loss": result.losses[0],
                    "final_loss": result.losses[-1],
                    "wall_seconds": time.perf_counter() - started,
                    args.evaluation_split: asdict(metrics),
                }
            )
        balanced = [entry[args.evaluation_split]["presence_balanced_accuracy"] for entry in seeds]
        bearing = [entry[args.evaluation_split]["mean_bearing_error_deg"] for entry in seeds]
        entries.append(
            {
                "group": group,
                "input_width": train.input_width,
                "train_frames": train.features.shape[0],
                "evaluation_frames": evaluation.features.shape[0],
                "mean_presence_balanced_accuracy": float(np.mean(balanced)),
                "std_presence_balanced_accuracy": float(np.std(balanced)),
                "mean_bearing_error_deg": float(np.mean(bearing)),
                "std_bearing_error_deg": float(np.std(bearing)),
                "seeds": seeds,
            }
        )
        print(
            f"{group}: width {train.input_width}, balanced "
            f"{100 * np.mean(balanced):.2f}%, bearing {np.mean(bearing):.2f} deg",
            flush=True,
        )
    report = {
        "format": "fly-brain-clip-feature-probe-v1",
        "git_revision": _git_revision(),
        "dataset": dataset.metadata,
        "cache_sha256": _file_sha256(args.cache),
        "decoder": {
            "kind": "framewise_mlp_localisation",
            "hidden_width": args.hidden_width,
            "hidden_layers": 2,
            "steps": args.steps,
            "batch_size": args.batch_size,
            "learning_rate": args.learning_rate,
            "threshold": "validation balanced accuracy",
        },
        "evaluation_split": args.evaluation_split,
        "groups": entries,
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    return 0


def _prepare_port_clips(args: argparse.Namespace) -> int:
    model_config, training_config = _load_connectome_config(args.config)
    layout_dataset = CachedStillDataset.load(args.layout_cache)
    layout = _cached_sensor_layout([layout_dataset])
    graph, _, _, input_map, _ = _build_connectome(layout, args.connectome_root, model_config)
    frontend, frontend_manifest = _connectome_frontend(
        model_config,
        args.frontend_checkpoint,
        [layout_dataset],
        input_map,
        training_config.batch_size,
    )
    if frontend is None:
        raise ValueError("port clips require an explicit input transform")
    spatial_model, spatial_parameters, spatial_topology = _load_spatial_frontend(
        args.frontend_checkpoint
    )
    print(f"preparing port clips from {args.dataset} -> {args.out}", flush=True)

    def progress(done: int, total: int) -> None:
        print(f"  {done}/{total} clips", flush=True)

    converted = convert_honey_factory_clips(
        args.dataset,
        args.out,
        matrix_cache=args.matrix_cache,
        spatial_model=spatial_model,
        spatial_parameters=spatial_parameters,
        spatial_topology=spatial_topology,
        frontend_checkpoint_sha256=_file_sha256(args.frontend_checkpoint),
        prediction_batch_size=args.prediction_batch_size,
        frame_selection=args.frame_selection,
        sensory_transform=frontend.transform,
        sensory_feature_manifest={
            "kind": "connectome_port_values",
            "width": int(input_map.target_neuron_ids.size),
            "input_map_sha256": _input_map_sha256(input_map),
            "photoreceptor_release": graph.release.release_id,
            "frontend": frontend_manifest,
        },
        progress=progress,
    )
    print(f"  wrote {converted.clip_count} clips and {converted.frame_count} frames", flush=True)
    return 0


def _input_map_sha256(input_map: ReceptorInputMap) -> str:
    """Hash the receptor-to-port assignment, independent of the rest of the graph."""
    manifest = {
        key: value for key, value in input_map.to_manifest().items() if key != "graph_sha256"
    }
    payload = json.dumps(manifest, indent=2, sort_keys=True) + "\n"
    return hashlib.sha256(payload.encode()).hexdigest()


def _load_connectome_clip_config(
    path: Path,
) -> tuple[ConnectomeModelConfig, int, ConnectomeClipTrainingConfig]:
    raw_values: Any = json.loads(path.read_text())
    if not isinstance(raw_values, dict):
        raise ValueError("connectome clip config must be a JSON object")
    values = cast(dict[str, Any], raw_values)
    if values.get("format") != "fly-brain-connectome-clip-experiment-v1":
        raise ValueError("unsupported connectome clip config format")
    model_values = values.get("model")
    training_values = values.get("training")
    if not isinstance(model_values, dict) or not isinstance(training_values, dict):
        raise ValueError("connectome clip config requires model and training objects")
    model = dict(cast(dict[str, Any], model_values))
    transitions = int(model.pop("transitions_per_frame", 2))
    try:
        return (
            ConnectomeModelConfig(**model),
            transitions,
            ConnectomeClipTrainingConfig(**cast(dict[str, Any], training_values)),
        )
    except TypeError as error:
        raise ValueError(f"invalid connectome clip setting: {error}") from error


def _train_connectome_clips(args: argparse.Namespace) -> int:
    started = time.perf_counter()
    model_config, transitions, training_config = _load_connectome_clip_config(args.config)
    layout_dataset = CachedStillDataset.load(args.layout_cache)
    layout = _cached_sensor_layout([layout_dataset])
    graph, network, _, input_map, compiler = _build_connectome(
        layout, args.connectome_root, model_config
    )
    dataset = CachedClipDataset.load(args.cache)
    raw_manifest = dataset.metadata.get("sensory_features")
    manifest = cast(dict[str, Any], raw_manifest) if isinstance(raw_manifest, dict) else {}
    if manifest.get("kind") != "connectome_port_values":
        raise ValueError("connectome clip training needs a port-value clip cache")
    if manifest.get("input_map_sha256") != _input_map_sha256(input_map):
        raise ValueError("port-value cache was built with a different receptor input map")
    model = ConnectomeClipModel(compiler.compile(), transitions)
    initial: BrainParameters | None = None
    initial_manifest: dict[str, object] | None = None
    if args.initial_checkpoint is not None:
        values, checkpoint_manifest = load_checkpoint(args.initial_checkpoint)
        checkpoint_connectome = checkpoint_manifest.get("connectome")
        graph_sha = (
            cast(dict[str, Any], checkpoint_connectome).get("graph_sha256")
            if isinstance(checkpoint_connectome, dict)
            else None
        )
        if graph_sha != graph.graph_sha256:
            raise ValueError("initial checkpoint graph does not match the compiled graph")
        loaded = BrainParameters(**cast(Any, values))
        fresh = model.initialize(training_config.seed)
        if loaded.edge_gain.shape != fresh.edge_gain.shape:
            raise ValueError("initial checkpoint gains do not match the parameter policy")
        # Keep the circuit; the readout is re-learned because the readout neurons may differ.
        initial = fresh._replace(edge_gain=loaded.edge_gain, neuron_bias=loaded.neuron_bias)
        initial_manifest = {
            "path": str(args.initial_checkpoint),
            "sha256": _file_sha256(args.initial_checkpoint),
            "reused": ["edge_gain", "neuron_bias"],
        }
    operating_point: dict[str, object] | None = None
    if model_config.compass_operating_point == "fitted":
        initial, operating_point = _fitted_compass(
            initial if initial is not None else model.initialize(training_config.seed),
            model.brain,
            graph,
            model_config,
        )
    if model_config.compass_operating_point == "ring_attractor":
        initial, operating_point = _compass_operating_point(
            initial if initial is not None else model.initialize(training_config.seed),
            model.brain,
            graph,
            args.connectome_root,
            model_config,
        )
    train = _connectome_clip_batch(dataset, "train", model_config, graph)
    validation = _connectome_clip_batch(dataset, "validation", model_config, graph)
    test = _connectome_clip_batch(dataset, "test", model_config, graph)
    readout_statistics: dict[str, object] = {"mode": model_config.readout_normalization}
    if model_config.readout_normalization == "initial":
        rows = np.random.default_rng(training_config.seed).permutation(train.sequences)[:32]
        model = model.with_readout_normalization(
            initial if initial is not None else model.initialize(training_config.seed),
            train.features[np.sort(rows)],
            train.time_mask[np.sort(rows)],
        )
        assert model.readout_scale is not None
        readout_statistics.update(
            {
                "clips": int(rows.size),
                "scale_min": float(np.min(model.readout_scale)),
                "scale_median": float(np.median(model.readout_scale)),
                "scale_max": float(np.max(model.readout_scale)),
            }
        )
    print(
        f"training connectome on {train.sequences} clips: {model.brain.neuron_ids.size} neurons, "
        f"{transitions} transitions per frame",
        flush=True,
    )
    clip_started = time.perf_counter()

    def progress(step: int, loss: float) -> None:
        elapsed = time.perf_counter() - clip_started
        remaining = elapsed / step * (training_config.steps - step)
        print(
            f"  step {step}/{training_config.steps} loss {loss:.4f} "
            f"({elapsed / step:.1f} s/step, about {remaining / 60:.0f} min left)",
            flush=True,
        )

    result = train_connectome_clips(model, train, training_config, initial, progress=progress)
    parameters = model.fold_readout_normalization(result.parameters)
    model = replace(model, readout_mean=None, readout_scale=None)
    validation_prediction = model.run(parameters, validation.features, validation.time_mask)
    threshold = _select_temporal_threshold(validation_prediction, validation)
    test_prediction = model.run(parameters, test.features, test.time_mask)
    visible = dataset.interval_mask("test", "visible") & test.time_mask
    occluded = dataset.interval_mask("test", "occluded") & test.time_mask & (test.presence > 0.5)
    test_clip_types = sorted(
        {
            value
            for value, split in zip(dataset.clip_types, dataset.splits, strict=True)
            if split == "test"
        }
    )
    description = network.describe()
    report: dict[str, object] = {
        "format": "fly-brain-connectome-clip-report-v1",
        "git_revision": _git_revision(),
        "dataset": dataset.metadata,
        "connectome": {
            "release_id": graph.release.release_id,
            "graph_sha256": graph.graph_sha256,
            "components": dict(description.component_sizes),
            **network.component_summary(),
        },
        "model": {
            **asdict(model_config),
            "transitions_per_frame": transitions,
            "compilation": asdict(compiler.plan()),
            "readout_statistics": readout_statistics,
            "initial_checkpoint": initial_manifest,
            "compass_operating_point": operating_point,
            "self_motion": (
                None
                if model_config.self_motion_input == "none"
                else {
                    "port": SELF_MOTION_PORT,
                    "cell_types": list(PEN_TYPES),
                    "neurons": int(_self_motion_neurons(graph).size),
                    "context_feature": "yaw_rate",
                    "scale": model_config.self_motion_scale,
                    "sign": "+1 left, -1 right",
                    "biological_status": "approximation",
                }
            ),
        },
        "training": {
            **asdict(training_config),
            "clips": train.sequences,
            "frames": int(np.count_nonzero(train.time_mask)),
            "initial_loss": result.losses[0],
            "final_loss": result.losses[-1],
            "loss_curve": [
                float(np.mean(result.losses[start : start + 50]))
                for start in range(0, len(result.losses), 50)
            ],
            "selected_presence_threshold": threshold,
        },
        "validation": _evaluate_temporal(validation_prediction, validation, threshold),
        "test": _evaluate_temporal(test_prediction, test, threshold),
        "test_visible": _evaluate_temporal(test_prediction, test, threshold, visible),
        "test_occluded": _evaluate_temporal(test_prediction, test, threshold, occluded),
        "test_by_clip_type": {
            clip_type: _evaluate_temporal(
                test_prediction, test, threshold, dataset.clip_type_mask("test", clip_type)
            )
            for clip_type in test_clip_types
        },
        "temporal_diagnostics": _temporal_diagnostics(test_prediction, test, visible),
        "runtime": {
            "backend": jax.default_backend(),
            "wall_seconds": time.perf_counter() - started,
            "peak_rss_bytes": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss * 1024,
        },
    }
    report["checkpoint_manifest_sha256"] = save_checkpoint(
        args.checkpoint, parameters._asdict(), report
    )
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    print(
        f"test balanced {report['test']}\nvisible {report['test_visible']}\n"
        f"occluded {report['test_occluded']}",
        flush=True,
    )
    return 0


def _load_spatial_frontend(
    checkpoint: Path,
) -> tuple[SpatialLocalizationModel, SpatialParameters, SpatialTopology]:
    values, manifest = load_checkpoint(checkpoint)
    raw_model = manifest.get("model")
    if not isinstance(raw_model, dict):
        raise ValueError("frontend checkpoint is not a spatial coverage model")
    model_values = cast(dict[str, Any], raw_model)
    if model_values.get("kind") != "spatial_coverage_localisation":
        raise ValueError("frontend checkpoint is not a spatial coverage model")
    config_fields = SpatialModelConfig.__dataclass_fields__
    config = SpatialModelConfig(
        **{name: value for name, value in model_values.items() if name in config_fields}
    )
    model = SpatialLocalizationModel(
        int(model_values["channel_count"]), int(model_values["site_count"]), config
    )
    parameters = SpatialParameters(**cast(Any, values))
    lattice = default_lattice()
    directions = to_unit(lattice.direction_deg[:, 0], lattice.direction_deg[:, 1]).astype(
        np.float32
    )
    topology = build_angular_topology(
        directions,
        lattice.eye,
        neighbor_count=config.neighbor_count,
        radius_deg=config.neighbor_radius_deg,
    )
    return model, parameters, topology


def _train_recurrent(args: argparse.Namespace) -> int:
    started = time.perf_counter()
    include_context, feature_group, hidden_width, training_config = _load_recurrent_config(
        args.config
    )
    dataset = CachedClipDataset.load(args.cache)
    train = dataset.batch("train", include_context=include_context, feature_group=feature_group)
    validation = dataset.batch(
        "validation", include_context=include_context, feature_group=feature_group
    )
    test = dataset.batch("test", include_context=include_context, feature_group=feature_group)
    model = RecurrentLocalizationModel(train.input_width, hidden_width)
    result = train_recurrent(model, train, training_config)
    validation_prediction, _ = model.run(
        result.parameters, validation.features, validation.time_mask
    )
    threshold = _select_temporal_threshold(validation_prediction, validation)
    test_prediction, _ = model.run(result.parameters, test.features, test.time_mask)
    visible = dataset.interval_mask("test", "visible") & test.time_mask
    occluded = dataset.interval_mask("test", "occluded") & test.time_mask & (test.presence > 0.5)
    test_clip_types = sorted(
        {
            value
            for value, split in zip(dataset.clip_types, dataset.splits, strict=True)
            if split == "test"
        }
    )
    static_report: dict[str, object] | None = None
    if feature_group is None:
        static_validation = _static_temporal_prediction(validation)
        static_threshold = _select_temporal_threshold(static_validation, validation)
        static_test = _static_temporal_prediction(test)
        static_report = {
            "validation": _evaluate_temporal(static_validation, validation, static_threshold),
            "test": _evaluate_temporal(static_test, test, static_threshold),
            "test_visible": _evaluate_temporal(static_test, test, static_threshold, visible),
            "test_occluded": _evaluate_temporal(static_test, test, static_threshold, occluded),
            "test_by_clip_type": {
                clip_type: _evaluate_temporal(
                    static_test,
                    test,
                    static_threshold,
                    dataset.clip_type_mask("test", clip_type),
                )
                for clip_type in test_clip_types
            },
            "temporal_diagnostics": _temporal_diagnostics(static_test, test, visible),
            "selected_presence_threshold": static_threshold,
        }
    report: dict[str, object] = {
        "format": "fly-brain-recurrent-report-v1",
        "git_revision": _git_revision(),
        "dataset": dataset.metadata,
        "model": {
            "kind": "gru_localisation",
            "input_width": model.input_width,
            "hidden_width": model.hidden_width,
            "include_context": include_context,
            "feature_group": feature_group,
            "parameter_count": sum(int(value.size) for value in result.parameters),
        },
        "training": {
            **asdict(training_config),
            "clips": train.sequences,
            "frames": int(np.count_nonzero(train.time_mask)),
            "initial_loss": result.losses[0],
            "final_loss": result.losses[-1],
            "selected_presence_threshold": threshold,
        },
        "validation": _evaluate_temporal(validation_prediction, validation, threshold),
        "test": _evaluate_temporal(test_prediction, test, threshold),
        "test_visible": _evaluate_temporal(test_prediction, test, threshold, visible),
        "test_occluded": _evaluate_temporal(test_prediction, test, threshold, occluded),
        "test_by_clip_type": {
            clip_type: _evaluate_temporal(
                test_prediction,
                test,
                threshold,
                dataset.clip_type_mask("test", clip_type),
            )
            for clip_type in test_clip_types
        },
        "temporal_diagnostics": _temporal_diagnostics(test_prediction, test, visible),
        "static_frontend": static_report,
        "runtime": {
            "backend": jax.default_backend(),
            "wall_seconds": time.perf_counter() - started,
            "peak_rss_bytes": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss * 1024,
        },
    }
    report["checkpoint_manifest_sha256"] = save_checkpoint(
        args.checkpoint, result.parameters._asdict(), report
    )
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


def _load_recurrent_config(
    path: Path,
) -> tuple[bool, str | None, int, RecurrentTrainingConfig]:
    raw_values: Any = json.loads(path.read_text())
    if not isinstance(raw_values, dict):
        raise ValueError("recurrent experiment config must be a JSON object")
    values = cast(dict[str, Any], raw_values)
    if values.get("format") != "fly-brain-recurrent-experiment-v1":
        raise ValueError("unsupported recurrent experiment config format")
    model_values = values.get("model")
    training_values = values.get("training")
    if not isinstance(model_values, dict) or not isinstance(training_values, dict):
        raise ValueError("recurrent experiment config requires model and training objects")
    model_config = cast(dict[str, Any], model_values)
    try:
        include_context = bool(model_config["include_context"])
        raw_feature_group = model_config.get("feature_group")
        feature_group = None if raw_feature_group is None else str(raw_feature_group)
        hidden_width = int(model_config["hidden_width"])
        training = RecurrentTrainingConfig(**cast(dict[str, Any], training_values))
    except (KeyError, TypeError, ValueError) as error:
        raise ValueError(f"invalid recurrent experiment setting: {error}") from error
    if hidden_width < 1:
        raise ValueError("recurrent hidden_width must be positive")
    return include_context, feature_group, hidden_width, training


def _train_recurrent_actions(args: argparse.Namespace) -> int:
    started = time.perf_counter()
    include_context, hidden_width, action_scales, training_config = _load_action_config(args.config)
    dataset = CachedClipDataset.load(args.cache)
    train = dataset.action_batch("train", include_context=include_context)
    validation = dataset.action_batch("validation", include_context=include_context)
    test = dataset.action_batch("test", include_context=include_context)
    model = RecurrentActionModel(train.input_width, action_scales, hidden_width)
    result = train_recurrent_actions(model, train, training_config)
    validation_prediction, _ = model.run(
        result.parameters, validation.features, validation.sequence_mask
    )
    test_prediction, _ = model.run(result.parameters, test.features, test.sequence_mask)
    report: dict[str, object] = {
        "format": "fly-brain-recurrent-action-report-v1",
        "git_revision": _git_revision(),
        "dataset": dataset.metadata,
        "model": {
            "kind": "gru_teacher_action_imitation",
            "input_width": model.input_width,
            "hidden_width": model.hidden_width,
            "action_scales": action_scales,
            "include_context": include_context,
            "parameter_count": sum(int(value.size) for value in result.parameters),
        },
        "training": {
            **asdict(training_config),
            "clips": train.sequences,
            "supervised_frames": int(np.count_nonzero(train.action_mask)),
            "initial_loss": result.losses[0],
            "final_loss": result.losses[-1],
        },
        "validation": _evaluate_actions(validation_prediction, validation),
        "test": _evaluate_actions(test_prediction, test),
        "runtime": {
            "backend": jax.default_backend(),
            "wall_seconds": time.perf_counter() - started,
            "peak_rss_bytes": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss * 1024,
        },
    }
    report["checkpoint_manifest_sha256"] = save_checkpoint(
        args.checkpoint, result.parameters._asdict(), report
    )
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


def _load_action_config(
    path: Path,
) -> tuple[bool, int, tuple[float, ...], RecurrentTrainingConfig]:
    raw_values: Any = json.loads(path.read_text())
    if not isinstance(raw_values, dict):
        raise ValueError("recurrent action config must be a JSON object")
    values = cast(dict[str, Any], raw_values)
    if values.get("format") != "fly-brain-recurrent-action-experiment-v1":
        raise ValueError("unsupported recurrent action config format")
    model_values = values.get("model")
    training_values = values.get("training")
    if not isinstance(model_values, dict) or not isinstance(training_values, dict):
        raise ValueError("recurrent action config requires model and training objects")
    model_config = cast(dict[str, Any], model_values)
    try:
        include_context = bool(model_config["include_context"])
        hidden_width = int(model_config["hidden_width"])
        action_scales = tuple(float(value) for value in model_config["action_scales"])
        training = RecurrentTrainingConfig(**cast(dict[str, Any], training_values))
    except (KeyError, TypeError, ValueError) as error:
        raise ValueError(f"invalid recurrent action setting: {error}") from error
    if hidden_width < 1 or len(action_scales) != 6:
        raise ValueError("action model requires positive hidden width and six action scales")
    return include_context, hidden_width, action_scales, training


def _evaluate_actions(
    prediction: ActionPrediction, batch: ActionTemporalBatch
) -> dict[str, object]:
    predicted = prediction.values[batch.action_mask]
    expected = batch.actions[batch.action_mask]
    error = predicted - expected
    return {
        "sample_count": int(predicted.shape[0]),
        "mean_absolute_error": np.mean(np.abs(error), axis=0).tolist(),
        "root_mean_square_error": np.sqrt(np.mean(np.square(error), axis=0)).tolist(),
    }


def _static_temporal_prediction(batch: TemporalBatch) -> TemporalPrediction:
    if batch.input_width < 4:
        raise ValueError("temporal cache lacks the four spatial frontend features")
    directions = batch.features[:, :, 1:4]
    norms = np.linalg.norm(directions, axis=2, keepdims=True)
    directions = np.divide(
        directions,
        np.maximum(norms, 1e-6),
        out=np.zeros_like(directions),
    )
    return TemporalPrediction(batch.features[:, :, 0], directions, batch.time_mask)


def _select_temporal_threshold(prediction: TemporalPrediction, batch: TemporalBatch) -> float:
    mask = batch.time_mask
    probabilities = prediction.presence_probability[mask]
    truth = batch.presence[mask] > 0.5
    best_threshold = 0.5
    best_score = -1.0
    for threshold in np.linspace(0.05, 0.95, 181):
        predicted = probabilities >= threshold
        positive = truth
        negative = ~truth
        recall = float(np.mean(predicted[positive])) if positive.any() else 0.0
        specificity = float(np.mean(~predicted[negative])) if negative.any() else 0.0
        score = (recall + specificity) / 2.0
        if score > best_score:
            best_score = score
            best_threshold = float(threshold)
    return best_threshold


def _evaluate_temporal(
    prediction: TemporalPrediction,
    batch: TemporalBatch,
    threshold: float,
    interval_mask: np.ndarray | None = None,
) -> dict[str, object] | None:
    mask = batch.time_mask if interval_mask is None else batch.time_mask & interval_mask
    if not mask.any():
        return None
    flat_prediction = HoneyLocalizationPrediction(
        prediction.presence_probability.reshape(-1),
        prediction.direction.reshape(-1, 3),
    )
    targets = BrainTargets(
        time_mask=mask.reshape(-1),
        honey_present=(batch.presence > 0.5).reshape(-1),
        honey_direction=batch.direction.reshape(-1, 3),
    )
    return asdict(evaluate_localization(flat_prediction, targets, presence_threshold=threshold))


def _temporal_diagnostics(
    prediction: TemporalPrediction,
    batch: TemporalBatch,
    visible: np.ndarray,
) -> dict[str, object]:
    consecutive = (
        batch.time_mask[:, 1:] & batch.time_mask[:, :-1] & visible[:, 1:] & visible[:, :-1]
    )
    predicted_change = _angular_change(prediction.direction[:, :-1], prediction.direction[:, 1:])
    target_change = _angular_change(batch.direction[:, :-1], batch.direction[:, 1:])
    stability_error = (
        float(np.mean(np.abs(predicted_change[consecutive] - target_change[consecutive])))
        if consecutive.any()
        else None
    )
    recovery_errors: list[list[float]] = [[] for _ in range(5)]
    recovery_count = 0
    for row in range(batch.sequences):
        transitions = (
            np.flatnonzero(
                batch.time_mask[row, 1:]
                & visible[row, 1:]
                & ~visible[row, :-1]
                & (batch.presence[row, 1:] > 0.5)
            )
            + 1
        )
        recovery_count += int(transitions.size)
        for start in transitions:
            for horizon in range(5):
                index = start + horizon
                if index >= batch.time_steps or not batch.time_mask[row, index]:
                    break
                error = _angular_change(
                    prediction.direction[row, index][None, :],
                    batch.direction[row, index][None, :],
                )[0]
                if np.isfinite(error):
                    recovery_errors[horizon].append(float(error))
    return {
        "visible_motion_stability_error_deg": stability_error,
        "recovery_event_count": recovery_count,
        "recovery_bearing_error_deg": [
            float(np.mean(values)) if values else None for values in recovery_errors
        ],
    }


def _angular_change(first: np.ndarray, second: np.ndarray) -> np.ndarray:
    first_norm = np.linalg.norm(first, axis=-1)
    second_norm = np.linalg.norm(second, axis=-1)
    usable = (first_norm > 1e-6) & (second_norm > 1e-6)
    result = np.full(first_norm.shape, np.nan, dtype=np.float32)
    cosine = np.sum(first * second, axis=-1) / np.maximum(first_norm * second_norm, 1e-12)
    result[usable] = np.degrees(np.arccos(np.clip(cosine[usable], -1.0, 1.0)))
    return result


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
    graph, network, _, input_map, compiler = _build_connectome(
        layout, args.connectome_root, model_config
    )
    input_manifest = input_map.to_manifest()
    input_payload = json.dumps(input_manifest, indent=2, sort_keys=True) + "\n"
    args.input_map.parent.mkdir(parents=True, exist_ok=True)
    args.input_map.write_text(input_payload)
    input_sha256 = hashlib.sha256(input_payload.encode()).hexdigest()
    plan = compiler.plan()
    model = ConnectomeLocalizationModel(compiler.compile(), model_config.settling_steps)
    frontend, frontend_manifest = _connectome_frontend(
        model_config, args.frontend_checkpoint, datasets, input_map, training_config.batch_size
    )
    train = _connectome_batch(datasets, "train", input_map, frontend)
    validation = _connectome_batch(datasets, "validation", input_map, frontend)
    test = _connectome_batch(datasets, "test", input_map, frontend)
    readout_statistics: dict[str, object] = {"mode": model_config.readout_normalization}
    if model_config.readout_normalization == "initial":
        sample = np.random.default_rng(training_config.seed).permutation(train.rows)[:2048]
        model = model.with_readout_normalization(
            model.initialize(training_config.seed),
            train.port_values[np.sort(sample)],
            batch_size=training_config.batch_size,
        )
        assert model.readout_scale is not None
        readout_statistics.update(
            {
                "samples": int(sample.size),
                "scale_min": float(np.min(model.readout_scale)),
                "scale_median": float(np.median(model.readout_scale)),
                "scale_max": float(np.max(model.readout_scale)),
            }
        )
    result = train_connectome(model, train, validation, training_config)
    # Fold any readout normalisation so the checkpoint holds plain parameters.
    result = result._replace(parameters=model.fold_readout_normalization(result.parameters))
    model = replace(model, readout_mean=None, readout_scale=None)
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
            **network.component_summary(),
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
        "frontend": frontend_manifest,
        "model": {
            **asdict(model_config),
            "compilation": asdict(plan),
            "readout_statistics": readout_statistics,
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
                    _connectome_batch([dataset], split, input_map, frontend),
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


def _build_connectome(
    layout: SensorLayout,
    connectome_root: Path,
    config: ConnectomeModelConfig,
) -> tuple[
    ConnectomeGraph,
    BrainNetwork,
    dict[str, NetworkComponent],
    ReceptorInputMap,
    BrainCompiler,
]:
    cell_types = PHOTORECEPTOR_TYPES + LAMINA_RELAY_TYPES + MEDULLA_RELAY_TYPES
    if set(CENTRAL_COMPLEX_COMPONENTS).intersection(config.components):
        cell_types += central_complex_cell_types()
    selector_loader = MaleCNSLoader(
        connectome_root,
        minimum_synapse_count=config.minimum_synapse_count,
    )
    selected_ids = selector_loader.annotation_ids(
        cell_types=cell_types,
        classes=(
            ("Kenyon_Cell",)
            if {VISUAL_KENYON_COMPONENT, VISUAL_KENYON_INPUT_COMPONENT}.intersection(
                config.components
            )
            else ()
        ),
        superclasses=("visual_projection",),
    )
    graph = MaleCNSLoader(
        connectome_root,
        minimum_synapse_count=config.minimum_synapse_count,
        neuron_ids=selected_ids,
    ).load()
    network = build_first_visual_network(
        graph,
        minimum_synapse_count=config.minimum_synapse_count,
        component_names=config.components,
    )
    components = {component.spec.name: component for component in network.components}
    input_map = build_receptor_input_map(layout, graph, components["photoreceptors"])
    readout_neuron_ids = np.unique(
        np.concatenate([components[name].neuron_ids for name in config.readout_components])
    )
    ports = [input_map.input_port()]
    if config.self_motion_input == "pen_yaw":
        ports.append(
            InputPort(
                SELF_MOTION_PORT,
                _self_motion_neurons(graph),
                InjectionRule.ADDITIVE,
                BiologicalStatus.APPROXIMATION,
            )
        )
    spec = BrainModelSpec(
        network,
        tuple(ports),
        OutputHead(
            "honey_localisation",
            readout_neuron_ids,
            4,
            BiologicalStatus.LEARNED,
        ),
        LeakyRateDynamics(
            config.time_constant,
            config.activation,
            tuple(sorted(config.release_thresholds.items())),
            config.release_threshold_sharpness,
        ),
        SynapseCountPolicy(
            config.initial_edge_gain,
            config.train_neuron_bias,
            config.edge_gain_mode,
            config.maximum_edge_gain,
            config.use_neurotransmitter_signs,
        ),
        config.dt,
    )
    return graph, network, components, input_map, BrainCompiler(spec)


SELF_MOTION_PORT = "angular_velocity"
PEN_TYPES = ("PEN_a(PEN1)", "PEN_b(PEN2)")
YAW_CONTEXT_INDEX = 3  # yaw_rate in EGOCENTRIC_MOTION_SCHEMA


def _compass_layout(
    brain: CompiledBrain, graph: ConnectomeGraph, connectome_root: Path
) -> tuple[NDArray[np.str_], NDArray[np.float64]]:
    """Cell type per compiled neuron, and the PB glomerulus angle of each EPG (NaN elsewhere)."""
    neurons = graph.neurons
    types = neurons.cell_types[np.searchsorted(neurons.neuron_ids, brain.neuron_ids)]
    epg = np.flatnonzero(types == "EPG")
    if epg.size == 0:
        raise ValueError("the compiled brain has no EPG neurons")
    instances = MaleCNSLoader(connectome_root, verify_checksums=False).instances(
        brain.neuron_ids[epg]
    )
    angles = np.full(types.size, np.nan)
    angles[epg] = [pb_glomerulus_angle(instances[int(brain.neuron_ids[i])]) for i in epg]
    return types, angles


def _probe_compass(args: argparse.Namespace) -> int:
    model_config, transitions, _ = _load_connectome_clip_config(args.config)
    layout = _cached_sensor_layout([CachedStillDataset.load(args.layout_cache)])
    graph, _, _, _, compiler = _build_connectome(layout, args.connectome_root, model_config)
    model = ConnectomeClipModel(compiler.compile(), transitions)
    brain = model.brain
    values, _ = load_checkpoint(args.checkpoint)
    parameters = BrainParameters(**cast(Any, values))
    types, angles = _compass_layout(brain, graph, args.connectome_root)
    dataset = CachedClipDataset.load(args.cache)
    batch = _connectome_clip_batch(dataset, args.split, model_config, graph)
    context = dataset.batch(args.split, include_context=True).features
    yaw = context[:, :, context.shape[2] - dataset.motion_context.shape[1] + YAW_CONTEXT_INDEX]
    stages = {
        "anterior_visual_inputs": ANTERIOR_VISUAL_INPUT_TYPES,
        "tubercle_bulb": TUBERCLE_BULB_TYPES,
        "ring_neurons": RING_NEURON_TYPES,
        **{cell_type: (cell_type,) for cell_type in COMPASS_TYPES},
        "steering": STEERING_TYPES,
    }
    groups = {name: np.flatnonzero(np.isin(types, members)) for name, members in stages.items()}
    watched = np.concatenate(list(groups.values()))
    activity = model.activity(parameters, batch.features, batch.time_mask, watched)
    mask = batch.time_mask
    stage_activity: dict[str, object] = {}
    start = 0
    for name, indexes in groups.items():
        part = activity[:, :, start : start + indexes.size][mask]
        start += indexes.size
        stage_activity[name] = {
            "neurons": int(indexes.size),
            "mean_abs": float(np.abs(part).mean()) if part.size else None,
        }
    epg = np.flatnonzero(types[watched] == "EPG")
    epg_activity = activity[:, :, epg]
    epg_angles = angles[watched[epg]]
    occluded = dataset.interval_mask(args.split, "occluded") & mask
    maximum = brain.parameter_policy.maximum_edge_gain
    raw = np.asarray(parameters.edge_gain, dtype=np.float64)
    gains = maximum / (1.0 + np.exp(-raw)) if maximum is not None else raw
    modes = compass_modes(
        brain.source_indexes,
        brain.target_indexes,
        brain.anatomical_weights * gains[brain.edge_group_indexes],
        np.isin(types, COMPASS_TYPES),
        angles,
        count=6,
    )
    report = {
        "format": "fly-brain-compass-probe-v1",
        "git_revision": _git_revision(),
        "checkpoint": str(args.checkpoint),
        "checkpoint_sha256": _file_sha256(args.checkpoint),
        "config": str(args.config),
        "split": args.split,
        "stage_activity": stage_activity,
        "compass_modes": [asdict(mode) for mode in modes],
        "bump": bump_metrics(epg_activity, epg_angles, yaw, mask),
        "bump_occluded": bump_metrics(epg_activity, epg_angles, yaw, occluded),
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    print(json.dumps({"bump": report["bump"], "bump_occluded": report["bump_occluded"]}))
    return 0


def _compass_operating_point(
    parameters: BrainParameters,
    brain: CompiledBrain,
    graph: ConnectomeGraph,
    connectome_root: Path,
    config: ConnectomeModelConfig,
) -> tuple[BrainParameters, dict[str, object]]:
    """Set compass type-pair gains to a ring attractor and add tonic bias to compass neurons."""
    maximum = brain.parameter_policy.maximum_edge_gain
    if maximum is None or brain.parameter_policy.edge_gain_mode != "cell_type_pair":
        raise ValueError("the compass operating point needs bounded cell-type-pair gains")
    types, angles = _compass_layout(brain, graph, connectome_root)
    members = np.isin(types, COMPASS_TYPES)
    raw = np.asarray(parameters.edge_gain, dtype=np.float64)
    point = CompassOperatingPoint(tonic_bias=config.compass_tonic_bias)
    result = ring_attractor_gains(
        brain.source_indexes,
        brain.target_indexes,
        brain.anatomical_weights,
        brain.edge_group_indexes,
        maximum / (1.0 + np.exp(-raw)),
        members,
        angles,
        maximum_gain=maximum,
        point=point,
    )
    gains = raw.copy()
    chosen = result.gains[result.changed_groups]
    gains[result.changed_groups] = np.log(chosen / (maximum - chosen))
    bias = np.asarray(parameters.neuron_bias, dtype=np.float32).copy()
    bias[members] += point.tonic_bias
    report: dict[str, object] = {
        "mode": "ring_attractor",
        "biological_status": "approximation",
        "groups": int(result.changed_groups.size),
        "harmonic_eigenvalue": result.harmonic_eigenvalue,
        "other_eigenvalue": result.other_eigenvalue,
        "tonic_bias": point.tonic_bias,
        "tonic_neurons": int(members.sum()),
        "search": {"iterations": point.iterations, "seed": point.seed},
    }
    return (
        parameters._replace(
            edge_gain=jnp.asarray(gains, dtype=jnp.float32),  # pyright: ignore[reportUnknownMemberType]
            neuron_bias=jnp.asarray(bias, dtype=jnp.float32),  # pyright: ignore[reportUnknownMemberType]
        ),
        report,
    )


def _load_integrator_fit_config(path: Path) -> tuple[IntegratorFitConfig, float]:
    raw: Any = json.loads(path.read_text())
    if not isinstance(raw, dict):
        raise ValueError("unsupported compass integrator fit config")
    values = dict(cast(dict[str, Any], raw))
    if values.get("format") != "fly-brain-compass-integrator-fit-v1":
        raise ValueError("unsupported compass integrator fit config")
    values.pop("format")
    harmonic = float(values.pop("initial_harmonic_eigenvalue", 1.5))
    stages = tuple(IntegratorStage(**stage) for stage in values.pop("stages"))
    try:
        return IntegratorFitConfig(stages=stages, **values), harmonic
    except TypeError as error:
        raise ValueError(f"invalid compass integrator setting: {error}") from error


def _compass_circuit(
    brain: CompiledBrain, graph: ConnectomeGraph, connectome_root: Path
) -> tuple[CompassCircuit, NDArray[np.int64], NDArray[np.bool_], NDArray[np.str_]]:
    """The compass sub-circuit of a compiled brain, its global group ids, members and types."""
    types, angles = _compass_layout(brain, graph, connectome_root)
    members = np.isin(types, COMPASS_TYPES)
    index = np.flatnonzero(members)
    position = np.full(types.size, -1)
    position[index] = np.arange(index.size)
    inside = members[brain.source_indexes] & members[brain.target_indexes]
    global_groups, local = np.unique(brain.edge_group_indexes[inside], return_inverse=True)
    sources, targets = brain.source_indexes[inside], brain.target_indexes[inside]
    pairs = tuple(
        f"{types[sources[first]]}>{types[targets[first]]}"
        for first in (np.flatnonzero(local == group)[0] for group in range(global_groups.size))
    )
    sides = graph.neurons.sides[np.searchsorted(graph.neurons.neuron_ids, brain.neuron_ids)]
    maximum = brain.parameter_policy.maximum_edge_gain
    if maximum is None:
        raise ValueError("the compass integrator needs bounded edge gains")
    circuit = CompassCircuit(
        cell_types=types[index],
        angles=angles[index],
        sides=sides[index],
        source=position[sources].astype(np.int32),
        target=position[targets].astype(np.int32),
        weight=brain.anatomical_weights[inside].astype(np.float32),
        group=local.astype(np.int32),
        group_pairs=pairs,
        compass_types=COMPASS_TYPES,
        leak=brain.dt / brain.dynamics.time_constant,
        maximum_gain=maximum,
    )
    return circuit, global_groups.astype(np.int64), members, types


def _fit_compass_integrator(args: argparse.Namespace) -> int:
    started = time.perf_counter()
    model_config, _, _ = _load_connectome_clip_config(args.config)
    fit_config, harmonic = _load_integrator_fit_config(args.fit_config)
    layout = _cached_sensor_layout([CachedStillDataset.load(args.layout_cache)])
    graph, _, _, _, compiler = _build_connectome(layout, args.connectome_root, model_config)
    brain = compiler.compile()
    circuit, groups, members, _ = _compass_circuit(brain, graph, args.connectome_root)
    values, _ = load_checkpoint(args.initial_checkpoint)
    maximum = circuit.maximum_gain
    start = maximum / (1.0 + np.exp(-np.asarray(values["edge_gain"], dtype=np.float64)))
    _, angles = _compass_layout(brain, graph, args.connectome_root)
    ring = ring_attractor_gains(
        brain.source_indexes,
        brain.target_indexes,
        brain.anatomical_weights,
        brain.edge_group_indexes,
        start,
        members,
        angles,
        maximum_gain=maximum,
        point=CompassOperatingPoint(harmonic_eigenvalue=harmonic),
    )
    dataset = CachedClipDataset.load(args.yaw_cache)
    context = dataset.batch("train", include_context=True)
    width = context.features.shape[2] - dataset.motion_context.shape[1]
    yaw = [
        context.features[row, : int(context.time_mask[row].sum()), width + YAW_CONTEXT_INDEX]
        for row in range(context.features.shape[0])
    ]
    fit = fit_compass_integrator(
        circuit,
        ring.gains[groups],
        yaw,
        fit_config,
        progress=lambda record: print(json.dumps(record), flush=True),
    )
    response = constant_turn_response(circuit, fit, fit_config)
    output = {
        "format": "fly-brain-compass-integrator-v1",
        "git_revision": _git_revision(),
        "initial_checkpoint": {
            "path": str(args.initial_checkpoint),
            "sha256": _file_sha256(args.initial_checkpoint),
        },
        "yaw_cache": str(args.yaw_cache),
        "fit_config": json.loads(args.fit_config.read_text()),
        "initial_ring": {
            "harmonic_eigenvalue": ring.harmonic_eigenvalue,
            "other_eigenvalue": ring.other_eigenvalue,
        },
        "model_overrides": {
            "compass_operating_point": "fitted",
            "compass_gains": fit.gains,
            "compass_type_bias": fit.type_bias,
            "release_thresholds": fit.release_thresholds,
            "release_threshold_sharpness": fit_config.release_sharpness,
            "self_motion_scale": fit.self_motion_scale,
            "transitions_per_frame": fit_config.transitions_per_frame,
        },
        "constant_turn": response,
        "history": list(fit.history),
        "runtime_seconds": time.perf_counter() - started,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(output, indent=2, sort_keys=True) + "\n")
    print(json.dumps({"constant_turn": response}))
    return 0


def _fitted_compass(
    parameters: BrainParameters,
    brain: CompiledBrain,
    graph: ConnectomeGraph,
    config: ConnectomeModelConfig,
) -> tuple[BrainParameters, dict[str, object]]:
    """Apply a fitted compass integrator: named type-pair gains and per-type biases (9i)."""
    maximum = brain.parameter_policy.maximum_edge_gain
    if maximum is None or brain.parameter_policy.edge_gain_mode != "cell_type_pair":
        raise ValueError("a fitted compass needs bounded cell-type-pair gains")
    neurons = graph.neurons
    types = neurons.cell_types[np.searchsorted(neurons.neuron_ids, brain.neuron_ids)]
    gains = type_pair_raw_gains(
        np.asarray(parameters.edge_gain),
        brain.edge_group_indexes,
        types[brain.source_indexes],
        types[brain.target_indexes],
        config.compass_gains,
        maximum,
    )
    bias = np.asarray(parameters.neuron_bias, dtype=np.float32).copy()
    for cell_type, value in config.compass_type_bias.items():
        selected = types == cell_type
        if not selected.any():
            raise ValueError(f"compass type bias names unknown cell type {cell_type!r}")
        bias[selected] += value
    report: dict[str, object] = {
        "mode": "fitted",
        "biological_status": "learned",
        "groups": len(config.compass_gains),
        "type_bias": dict(config.compass_type_bias),
        "release_thresholds": dict(config.release_thresholds),
    }
    return (
        parameters._replace(
            edge_gain=jnp.asarray(gains, dtype=jnp.float32),  # pyright: ignore[reportUnknownMemberType]
            neuron_bias=jnp.asarray(bias, dtype=jnp.float32),  # pyright: ignore[reportUnknownMemberType]
        ),
        report,
    )


def _self_motion_neurons(graph: ConnectomeGraph) -> NDArray[np.int64]:
    """PEN neurons, which carry angular velocity from the noduli into the compass."""
    neurons = graph.neurons
    ids = np.sort(neurons.neuron_ids[np.isin(neurons.cell_types, PEN_TYPES)]).astype(np.int64)
    if ids.size == 0:
        raise ValueError("the graph has no PEN neurons for the self-motion port")
    return ids


def _self_motion_signs(graph: ConnectomeGraph) -> NDArray[np.float32]:
    """+1 for left and -1 for right PEN neurons; turning drives the two sides oppositely."""
    ids = _self_motion_neurons(graph)
    sides = graph.neurons.sides[np.searchsorted(graph.neurons.neuron_ids, ids)]
    if not np.all(np.isin(sides, ("L", "R"))):
        raise ValueError("every PEN neuron needs a left or right side for the self-motion port")
    return np.where(sides == "L", 1.0, -1.0).astype(np.float32)


def _connectome_clip_batch(
    dataset: CachedClipDataset,
    split: str,
    config: ConnectomeModelConfig,
    graph: ConnectomeGraph,
) -> TemporalBatch:
    """Port values per frame, followed by self-motion port values when configured."""
    if config.self_motion_input == "none":
        return dataset.batch(split)
    batch = dataset.batch(split, include_context=True)
    width = batch.input_width - dataset.motion_context.shape[1]
    motion = self_motion_port_values(
        batch.features[:, :, width:],
        yaw_index=YAW_CONTEXT_INDEX,
        signs=_self_motion_signs(graph),
        scale=config.self_motion_scale,
    )
    features = np.concatenate([batch.features[:, :, :width], motion], axis=2)
    return TemporalBatch(features, batch.time_mask, batch.presence, batch.direction)


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
    datasets: list[CachedStillDataset],
    split: str,
    input_map: ReceptorInputMap,
    frontend: ConnectomeInputTransform | None = None,
) -> ConnectomeBatch:
    spatial = _spatial_batch(datasets, split)
    weighted = spatial.coverage @ spatial.directions
    norms = np.linalg.norm(weighted, axis=1)
    direction = np.zeros_like(weighted, dtype=np.float32)
    nonzero = norms > 0
    direction[nonzero] = weighted[nonzero] / norms[nonzero, None]
    return ConnectomeBatch(
        (
            input_map.transform(spatial.signals, valid=spatial.valid)
            if frontend is None
            else frontend.transform(spatial)
        ),
        spatial.presence,
        direction,
    )


def _connectome_frontend(
    config: ConnectomeModelConfig,
    checkpoint: Path | None,
    datasets: list[CachedStillDataset],
    input_map: ReceptorInputMap,
    batch_size: int,
) -> tuple[ConnectomeInputTransform | None, dict[str, object]]:
    if config.input_transform == "receptor":
        if checkpoint is not None:
            raise ValueError("frontend checkpoint requires a learned input transform")
        return None, {"kind": "direct_receptor", "biological_status": "approximation"}
    if config.input_transform in {"per_eye_mean", "regional_mean"}:
        if checkpoint is not None:
            raise ValueError("pooling input transforms do not use a frontend checkpoint")
        layout = _cached_sensor_layout(datasets)
        transform = ReceptorPoolingInputTransform(
            cast(Literal["per_eye_mean", "regional_mean"], config.input_transform),
            layout.eyes,
            input_map,
            config.regional_azimuth_bins,
            config.regional_elevation_bins,
        )
        return transform, {
            "kind": config.input_transform,
            "biological_status": transform.biological_status.value,
            "azimuth_bins": config.regional_azimuth_bins,
            "elevation_bins": config.regional_elevation_bins,
            "frozen_parameter_count": 0,
        }
    if checkpoint is None:
        raise ValueError("spatial_coverage input requires --frontend-checkpoint")
    values, manifest = load_checkpoint(checkpoint)
    raw_model_values = manifest.get("model")
    if not isinstance(raw_model_values, dict):
        raise ValueError("frontend checkpoint is not a spatial coverage model")
    model_values = cast(dict[str, Any], raw_model_values)
    if model_values.get("kind") != ("spatial_coverage_localisation"):
        raise ValueError("frontend checkpoint is not a spatial coverage model")
    spatial = _spatial_batch(datasets, "train")
    config_fields = SpatialModelConfig.__dataclass_fields__
    spatial_config = SpatialModelConfig(
        **{name: value for name, value in model_values.items() if name in config_fields}
    )
    spatial_model = SpatialLocalizationModel(
        int(model_values["channel_count"]),
        int(model_values["site_count"]),
        spatial_config,
    )
    parameters = SpatialParameters(**cast(Any, values))
    lattice = default_lattice()
    topology = build_angular_topology(
        spatial.directions,
        lattice.eye,
        neighbor_count=spatial_config.neighbor_count,
        radius_deg=spatial_config.neighbor_radius_deg,
    )
    transform = SpatialCoverageInputTransform(
        spatial_model, parameters, topology, input_map, batch_size
    )
    return transform, {
        "kind": "frozen_spatial_coverage",
        "biological_status": transform.biological_status.value,
        "checkpoint": str(checkpoint),
        "checkpoint_sha256": _file_sha256(checkpoint),
        "frozen_parameter_count": transform.parameter_count,
    }


def _file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


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
        if args.command == "prepare-clips":
            return _prepare_clips(args)
        if args.command == "prepare-connectome-clips":
            return _prepare_connectome_clips(args)
        if args.command == "combine-clip-features":
            return _combine_clip_features(args)
        if args.command == "probe-clip-features":
            return _probe_clip_features(args)
        if args.command == "prepare-port-clips":
            return _prepare_port_clips(args)
        if args.command == "train-connectome-clips":
            return _train_connectome_clips(args)
        if args.command == "probe-compass":
            return _probe_compass(args)
        if args.command == "fit-compass-integrator":
            return _fit_compass_integrator(args)
        if args.command == "train-recurrent":
            return _train_recurrent(args)
        if args.command == "train-recurrent-actions":
            return _train_recurrent_actions(args)
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
