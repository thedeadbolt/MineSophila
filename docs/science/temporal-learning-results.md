# Temporal localisation and action imitation

- **Date:** 2026-11-21
- **Slice:** 8
- **Dataset:** `m12-2-pilot-l1-efccab07e309`
- **Split:** 160 train / 20 validation / 20 test clips, fixed by scene
- **Input rate:** one tick-aligned frame per Minecraft control tick
- **Static frontend:** frozen level-1 spatial coverage model

## Data and controls

The source contains 200 clips and 8,460 selected frames. The compact temporal cache is 440 KB:
four frozen spatial predictions per frame, plus an optional 14-value causal egocentric context.
World-truth bearing, visibility and teacher commands are targets only. The cache contains 7,140
honey-present frames, 1,320 no-honey frames, 58 present-but-occluded frames and 6,922 visible
teacher-labelled frames.

All recurrent variants use a 64-unit GRU, explicit state and 32-frame truncated
backpropagation-through-time. Padding never updates state or contributes to loss. The static
control applies the frozen spatial prediction independently to every frame. Presence thresholds
are selected on validation clips only.

## Level-1 localisation results

| Model | Trainable parameters | Test balanced accuracy | Test bearing error | Occluded recall | Occluded bearing error | Visible motion stability error |
|---|---:|---:|---:|---:|---:|---:|
| Frozen spatial frontend | 0 | 96.33% | 5.04° | 66.67% | 64.66° | 1.07° |
| GRU, vision only | 13,516 | 95.45% | **2.26°** | 100% | 10.12° | **0.57°** |
| GRU + egocentric context | 16,232 | **99.24%** | 2.61° | 100% | **4.25°** | 0.70° |

The vision-only GRU improves overall bearing and temporal stability but does not beat the static
control's balanced presence accuracy. Adding causal movement context gives the best presence and
occlusion result while retaining much lower bearing error than the static control. This supports
keeping both recurrent variants as controls: vision-only for the sharpest bearing and context for
tracking through self-motion.

The test set contains only six present-but-occluded frames and one reappearance event. The
occlusion and recovery numbers therefore show that the path works, not a stable estimate of memory
quality. A level-2 or later clip set with explicit occlusion, detour and search clips is required
before making a stronger memory claim or adding Kenyon cells.

## Visible-frame teacher-action imitation

The action model uses the spatial features and causal egocentric context. Every real frame updates
recurrent state, but loss is applied only to visible frames carrying a teacher command. It trains
on 5,571 labelled frames from 128 clips and evaluates on 627 held-out frames.

| Teacher command | Units | Test MAE | Test RMSE |
|---|---|---:|---:|
| Forward | normalized | 0.039 | 0.070 |
| Strafe | normalized | 0.017 | 0.031 |
| Turn rate | degrees/tick | 0.609 | 1.020 |
| Pitch rate | degrees/tick | 0.176 | 0.289 |
| Jump | binary value | 0.015 | 0.024 |
| Delay | ticks | 0.013 | 0.023 |

These are offline imitation errors, not closed-loop success rates. The model has not yet driven
Honey Factory, and scripted or hidden-honey frames are deliberately outside this first action
loss. Closed-loop replay and DAgger remain Slice 10 work.

## Artifacts

Artifacts live outside Git under
`/mnt/fastvault/minesophila/derived/brain-localization-v3/`:

- `temporal-level1-tick-v1.fly-brain.npz`
- `recurrent-level1-v1.{report.json,checkpoint.npz}`
- `recurrent-level1-context-v1.{report.json,checkpoint.npz}`
- `recurrent-actions-level1-v1.{report.json,checkpoint.npz}`

The reports pin the source manifest and clip-index hashes, frozen frontend checkpoint hash, Git
revision, model/training configuration, selected threshold, runtime and held-out metrics.

## Level-2 validation

The same 64-unit models were rerun without architecture changes on 200 level-2 clips (8,592
frames). This set contains 164 present-but-occluded frames overall and nine in the held-out split.

| Model | Trainable parameters | Test balanced accuracy | Test bearing error | Occluded recall | Occluded bearing error | Visible motion stability error |
|---|---:|---:|---:|---:|---:|---:|
| Frozen spatial frontend | 0 | 78.03% | 28.80° | 0% | 124.47° | 4.50° |
| GRU, vision only | 13,516 | 94.92% | 20.11° | 100% | 114.19° | 3.20° |
| GRU + egocentric context | 16,232 | **95.50%** | **14.05°** | 100% | 163.97° | **2.40°** |

Recurrence substantially improves ordinary level-2 localisation, but neither recurrent model
retains a usable target direction through the held-out occlusions. The context model also varies
sharply by clip type: its approach bearing error is 4.57°, while pass-by and scan errors are
45.79° and 43.85°. These failures motivate testing a separately named visual Kenyon memory
component rather than treating the GRU result as evidence for robust target memory.

The level-2 action model evaluates on 719 teacher-labelled frames. Its test MAEs are 0.091 forward,
0.020 strafe, 1.148°/tick turn, 0.238°/tick pitch, 0.016 jump and 0.013 ticks delay.

Additional artifacts in the same external directory are:

- `temporal-level2-tick-v1.fly-brain.npz`
- `recurrent-level2-v1.{report.json,checkpoint.npz}`
- `recurrent-level2-context-v1.{report.json,checkpoint.npz}`
- `recurrent-actions-level2-v1.{report.json,checkpoint.npz}`

## Visual Kenyon temporal readouts (Slice 9a)

Recorded 2026-11-21 on the level-1 clips above, same splits, evaluator and GRU training settings.
Values are means ± standard deviation over seeds 0–2. Each Kenyon row reads connectome activity
from the static `connectome-level1-visual-kenyon-v1` checkpoint (signs off, frozen spatial
frontend). Each frame settles for four steps from rest, exactly as that model was trained
(`--state-mode reset`); the memory is therefore in the GRU, not the connectome. The two
"inputs" rows use the 103 visual-projection neurons that feed the Kenyon cells, with hidden width
106 so that their parameter counts match the Kenyon rows.

| GRU input | Trainable parameters | Test balanced accuracy | Test bearing error | Occluded bearing error | Visible motion stability error |
|---|---:|---:|---:|---:|---:|
| Frontend output (control) | 13,516 | 94.07 ± 4.89% | **2.69 ± 0.30°** | 15.41 ± 3.91° | **0.61°** |
| Frontend output + egocentric context | 16,232 | **99.12 ± 0.18%** | 2.61 ± 0.09° | 7.96 ± 3.29° | 0.67° |
| Visual Kenyon cells (284) | 67,836 | 97.07 ± 0.41% | 11.36 ± 0.30° | 35.80 ± 6.21° | 2.77° |
| Kenyon inputs (103) | 67,414 | 98.55 ± 0.65% | 7.08 ± 0.74° | 65.48 ± 22.19° | 1.63° |
| Frontend output + Kenyon cells | 68,612 | 95.56 ± 5.75% | 7.38 ± 1.85° | 29.14 ± 10.74° | 2.26° |
| Frontend output + Kenyon inputs | 68,694 | 97.22 ± 1.70% | 3.21 ± 0.18° | 13.51 ± 2.01° | 1.01° |

Recurrence lifts presence decoding from connectome activity well above the static readout
(83.2% test balanced accuracy for the same checkpoint), so the connectome pathway retains enough
evidence for presence. It loses direction. The Kenyon expansion is worse than its own inputs
for bearing (11.4° against 7.1°, consistent across seeds), and adding either connectome group to
the frontend output makes bearing worse rather than better. On this task the visual Kenyon
expansion carries less spatial information than the visual-projection neurons that feed it, and
it adds nothing to the frontend output.

The occlusion columns are not evidence either way: the held-out split contains six occluded
frames and one recovery event. The Kenyon row's lower occluded bearing error than its input row is
within the seed spread of the input row.

The same readouts on the graded signed model (ADR 0027), seeds 0–2:

| GRU input | Per-frame reset bearing | Carried state bearing |
|---|---:|---:|
| Visual Kenyon cells | 11.04 ± 0.43° | 12.58 ± 0.35° |
| Kenyon inputs (103) | 7.31 ± 0.85° | 9.30 ± 0.62° |

Per-frame graded activity matches the legacy rows above. Carrying graded state across frames is
stable but slightly worse, as expected: the connectome was trained only on independent frames, so
its carried state blurs the current frame without having learned to remember anything.

An earlier cache carried connectome state across frames without resets. Unsigned, all-excitatory
ReLU dynamics with an uncapped gain are unstable over long runs, and activity grew from about 0 to
about 10⁴ (up to 4 × 10⁵) within a clip. Recurrent readouts of that cache reached about 61%
balanced accuracy with a final training loss of 0.75. Those runs measure the instability, not the
circuit, and are excluded; see the Slice 9 plan for the dynamics change that addresses them.

Artifacts in the same external directory:

- `temporal-level1-visual-kenyon-framewise-v1.fly-brain.npz` (per-frame reset activity, 387 features)
- `temporal-level1-frontend-visual-kenyon-framewise-v1.fly-brain.npz` (combined with the frontend
  output through `fly-brain combine-clip-features`)
- `recurrent-level1-*-framewise-v1.{report.json,checkpoint.npz}` (seed 0) and
  `recurrent-level1-seeds-*-s{1,2}.{report.json,checkpoint.npz}` (seeds 1–2, including reruns of
  the two frontend controls)
