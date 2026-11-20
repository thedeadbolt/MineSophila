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

The current parameter policy fixes normalized anatomical weights, learns one global recurrent
gain and learns a localisation readout. See the
[first result](../../docs/science/first-connectome-model.md) and the
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
