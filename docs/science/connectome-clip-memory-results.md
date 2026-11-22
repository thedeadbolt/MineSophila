# Connectome memory on occlusion-rich clips (Slice 9g, interim)

Recorded 2026-11-22. The first test of target-direction memory inside the connectome, against the
recurrent (GRU) control, on clips where the honey is hidden for long stretches. Results are
interim: the central-complex rows are single seeds. The batch-B datasets and the self-motion
variant are still training.

## Data

Two daytime pilots generated occlusion-rich clips with the mix shifted to search (30%), occlusion
(25%) and detour (20%) clips:

- `fly-brain-occ-a`: levels 2 and 3, 50 scenes each, about 200 clips per level.
- `fly-brain-occ-b`: levels 3 and 4, 150 scenes each, splits 70/10/20.
  - Level 4 (600 clips) is complete.
  - Level 3 lost one of its two slices: a single mask frame with non-binary pixels failed the
    validation of instance 0. The valid slice (300 clips, 75 scenes) is used on its own.

Batch-A level 3 is the first split that can measure memory. Its test split has 20 clips, 713
occluded frames and honey-free frames. The level-2 test split happened to contain no occluded
frames, so level-2 memory is measured on validation, where occluded bearing does not depend on
the presence threshold.

## Models

- **GRU:** the established recurrent control on the frozen spatial frontend output, with or
  without egocentric motion context. Seeds 0–2.
- **Central complex:** the graded signed connectome (ADR 0027) with the anterior visual pathway
  and compass (`central_complex.py`). It starts from the static level-1 central-complex
  checkpoint and is trained on clips with its state carried across frames
  (`train-connectome-clips`). Settings: two transitions per frame, truncation at 16 frames, 1,500
  steps, normalised readout, rest frozen. There is no self-motion input. Two readouts:
  - compass: compass and steering neurons only;
  - projection: visual-projection neurons only.

## Level 3 (batch A), test split

| Model | Test balanced accuracy | Visible bearing | Occluded bearing | Occluded recall |
|---|---:|---:|---:|---:|
| GRU, vision only | 67.2 ± 2.1% | 30.7 ± 3.3° | 54.9 ± 1.7° | 69.6 ± 3.1% |
| GRU + egocentric motion | 73.9 ± 6.9% | 27.5 ± 5.3° | **42.6 ± 2.6°** | 76.3 ± 2.3% |
| Central complex, compass readout | **76.5%** | 31.2° | 71.6° | 58.2% |
| Central complex, projection readout | 64.0% | **25.4°** | 48.9° | 67.6% |

## Level 2 (batch A)

| Model | Test balanced accuracy | Test bearing | Validation occluded bearing |
|---|---:|---:|---:|
| GRU, vision only | 68.7 ± 2.0% | 17.5 ± 1.6° | 65.0 ± 8.4° |
| GRU + egocentric motion | **87.2 ± 1.2%** | **9.3 ± 1.2°** | **52.6 ± 6.0°** |
| Central complex, compass readout | 73.5% | 25.2° | 97.8° |
| Central complex, projection readout | 74.3% | 22.0° | 56.6° |

## Interpretation so far

- **The stateful visual circuit retains direction.** Read at its visual-projection output, the
  clip-trained connectome beats the vision-only GRU on level-3 bearing, both visible (25.4°
  against 30.7°) and occluded (48.9° against 54.9°). It does so without any self-motion input.
- **The compass readout is best at presence but has no usable direction memory yet:** 71.6° on
  level 3 and chance on level-2 validation.
- **The best memory belongs to the GRU given egocentric motion.** In the fly, the compass keeps
  heading by integrating angular velocity, which PEN neurons receive from the noduli. These runs
  give the circuit no such input.
- **Next:** the same runs with yaw rate injected into the PEN neurons (`self_motion_input:
  "pen_yaw"`), then seeds and the larger batch-B splits.

## Artifacts

Under `/mnt/fastvault/minesophila/derived/brain-localization-v3/`:

- `temporal-level{2,3}-occ-a-{tick,ports}-v1.fly-brain.npz`
- `recurrent-level{2,3}-occ-a-{control,context}-s{0,1,2}.{report.json,checkpoint.npz}`
- `connectome-clips-level{2,3}-occ-a-central-complex-{compass,projection}-v1.{report.json,checkpoint.npz}`
