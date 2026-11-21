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
