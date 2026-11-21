# fly-brain

`fly-brain` learns honey localisation and, later, movement from `fly-vision` receptor signals.
Biological structure is an experimental prior rather than a restriction: anatomical,
approximate, learned and shortcut variants are compared on the same held-out tasks.

The current foundation defines framework-independent contracts for:

- a fixed two-compound-eye sensor layout;
- named, model-visible context features;
- still images and videos as one time-major observation type;
- targets, privileged state and provenance kept outside model input;
- complete examples joining those separate values for datasets and evaluation.

Cached `fly-vision` frames can now be assembled into still or temporal examples. Optional Honey
Factory adapters add causal egocentric movement context and masked teacher-action targets without
placing world pose or teacher state in model-visible observations.

The connectome layer represents a pinned release as canonical immutable neuron and directed-synapse
tables. A sparse JAX compiler now adds explicit receptor input ports, leaky-rate dynamics,
resettable state, parameter policies and learned output heads without mixing those concerns into
the structural graph.

## Connectome-constrained experiment

The first executable circuit uses the reviewed MaleCNS visual components and saves the complete
approximate virtual-receptor-to-neuron map beside its report and checkpoint.

```bash
uv run fly-brain train-connectome /path/to/*.fly-brain.npz \
  --connectome-root /path/to/male-cns-v1.0 \
  --config packages/fly-brain/configs/connectome-localization-v1.json \
  --input-map /path/to/connectome-input-map.json \
  --report /path/to/connectome-report.json \
  --checkpoint /path/to/connectome-checkpoint.npz
```

Parameter policies can learn global, cell-type-pair or per-edge gains, optionally apply
neurotransmitter signs and select named circuit/readout components. See the
[first result](../../docs/science/first-connectome-model.md), the
[input and component ablations](../../docs/science/connectome-ablation-results.md), and the
[foundation plan](../../docs/superpowers/plans/2026-11-19-fly-brain-foundation.md).

## Spatial coverage experiment

The topology-aware baseline predicts honey coverage at every ommatidium, then derives presence
and direction from that spatial prediction. Model, topology, loss, optimizer, early-stopping and
threshold-selection settings live in a versioned JSON experiment config rather than the command.

```bash
uv run fly-brain train-spatial /path/to/*.fly-brain.npz \
  --config packages/fly-brain/configs/spatial-coverage-v1.json \
  --report /path/to/spatial-report.json \
  --checkpoint /path/to/spatial-checkpoint.npz
```

Copy the supplied config to create a tuned run while retaining the complete settings in its
checkpoint manifest and report.

Before training, audit each raw-dataset/cache pair. This rechecks source hashes and split
isolation, reproduces configured random rows from their RGB images and masks, and compares mask
visibility, cached coverage and coverage-weighted direction.

```bash
uv run fly-brain audit-stills /path/to/dataset /path/to/dataset.fly-brain.npz \
  --config packages/fly-brain/configs/still-label-audit-v1.json \
  --matrix-cache /path/to/fly-vision-cache \
  --report /path/to/label-audit.json
```

## Temporal learning

Clip preprocessing streams Honey Factory videos through `fly-vision` and the selected frozen
spatial checkpoint. The compact cache contains only static sensory predictions and causal
egocentric context as model inputs. True bearing, visibility and teacher commands remain separate
targets. Tick-aligned conversion is the default; use `--frame-selection all` for every rendered
subframe.

```bash
uv run fly-brain prepare-clips /path/to/clip-dataset \
  --out /path/to/clips.fly-brain.npz \
  --matrix-cache /path/to/fly-vision-cache \
  --frontend-checkpoint /path/to/spatial-checkpoint.npz
```

The two localisation configs differ only in whether they include the legal movement context.
Training carries explicit recurrent state through each clip and detaches it only at configured
truncation boundaries.

```bash
uv run fly-brain train-recurrent /path/to/clips.fly-brain.npz \
  --config packages/fly-brain/configs/recurrent-localization-level1-v1.json \
  --report /path/to/recurrent-report.json \
  --checkpoint /path/to/recurrent-checkpoint.npz

uv run fly-brain train-recurrent-actions /path/to/clips.fly-brain.npz \
  --config packages/fly-brain/configs/recurrent-actions-level1-v1.json \
  --report /path/to/action-report.json \
  --checkpoint /path/to/action-checkpoint.npz
```

Action imitation updates recurrent state on every real frame but applies loss only where a teacher
command exists and honey is visible. Teacher commands and world-truth bearing are never model
features.
