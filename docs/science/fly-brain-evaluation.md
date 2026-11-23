# fly-brain evaluation criteria

These criteria define the first task before model implementation. They apply equally to
conventional baselines and connectome-constrained models.

## First task

Given one `fly-vision` receptor frame, predict whether visible honey is present and, when present,
where it lies in head-centred fly space. Binary presence alone is not sufficient: a model could
exploit global colour or brightness without preserving useful spatial information.

The first model receives only receptor activations, validity and the fixed sensor layout. It does
not receive movement context, scene identity, global pose, honey coordinates, the source mask or
teacher state.

## Targets

- **Presence:** whether sampled honey coverage is non-zero, with any later visibility threshold
  fixed from dataset semantics rather than tuned on the test set.
- **Coverage:** the existing continuous honey coverage for every receptor site.
- **Direction:** the coverage-weighted head-centred unit direction, defined only for positive
  coverage. Bearing and elevation are derived views of the same direction.

## Split

- Split by scene for still datasets and by complete episode/clip for temporal datasets.
- Keep every frame, viewpoint and derived receptor representation from one scene or episode in one
  split.
- Preserve level, target-present/absent and visibility strata where dataset size permits.
- Select thresholds and checkpoints on validation data only; evaluate the test split once for the
  reported comparison.

## Metrics

Report metrics per level and overall, with scene-level bootstrap intervals when the dataset is
large enough.

| Output | Primary metrics | Conditions |
|---|---|---|
| Presence | average precision; F1 at a validation-selected threshold | All examples; also report class balance and AUROC |
| Direction | median and 90th-percentile angular error in degrees | Positive-coverage examples only |
| Coverage | soft intersection-over-union and receptor-site average precision | Valid receptor sites only |

Also report parameter count, peak host/GPU memory, examples or frames per second and inference
latency. A model result is incomplete without the dataset hash, sensor-layout hash, split manifest,
random seeds and software versions.

## Comparison ladder

1. Constant/class-frequency and centre-direction controls.
2. Direct receptor input with a linear head.
3. Direct receptor input with a small MLP.
4. The smallest selected connectome visual subgraph with the same task heads.
5. Input pooling and circuit expansions as controlled ablations.

Do not claim benefit from a connectome or biological transform without an identical split and task
comparison. Preserve useful negative results. Predictive improvement may justify a shortcut, but
the experiment manifest must label it as such.
