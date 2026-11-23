# Connectome memory on occlusion-rich clips (Slice 9g, interim)

Recorded 2026-11-22, updated 2026-11-23. The first test of target-direction memory inside the
connectome, against the recurrent (GRU) control, on clips where the honey is hidden for long
stretches. Results are interim: the central-complex rows are single seeds. Extra central-complex
seeds and level 4 are still running.

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

Adding yaw rate to the PEN neurons (`self_motion_input: "pen_yaw"`, seed 0):

| Model | Test balanced accuracy | Visible bearing | Occluded bearing | Occluded recall |
|---|---:|---:|---:|---:|
| Compass readout + self-motion | 71.6% | 33.1° | 81.0° | 42.1% |
| Projection readout + self-motion | 64.1% | 25.9° | 49.1° | 67.6% |

## Level 3 (batch B, slice 1), test split

300 clips from 75 scenes; the test split has 1,229 occluded frames. Occlusions here are longer
and harder for every model.

| Model | Test balanced accuracy | Visible bearing | Occluded bearing | Occluded recall |
|---|---:|---:|---:|---:|
| GRU, vision only | 63.8 ± 3.2% | 29.6 ± 2.3° | 64.6 ± 1.9° | 90.4 ± 4.8% |
| GRU + egocentric motion | 74.8 ± 2.2% | **23.9 ± 1.5°** | 65.3 ± 0.6° | 84.2 ± 4.7% |
| Central complex, compass readout | **81.4%** | 52.0° | 78.1° | 73.6% |
| Central complex, projection readout | 71.3% | 37.3° | **51.1°** | 72.7% |

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
- **On the harder batch-B clips, the projection readout has the best occluded bearing:** 51.1°,
  against about 65° for both GRUs. Egocentric motion no longer helps the GRU there. Visible
  bearing is worse than the GRUs (37.3° against 23.9–29.6°), so the circuit trades visible
  precision for memory.
- **Self-motion as wired did not help.** It made the compass readout worse (81.0° against
  71.6°) and left the projection readout unchanged. Yaw rate averages 3.7°/tick, so at scale 0.1
  the PEN drive (about 0.37) is roughly 50 times the mean visual port value (about 0.008). The
  likely failure is that self-motion swamps the compass instead of rotating its bump. The
  per-type gains are bounded at 4, so training cannot scale it down far enough.
- **Next:** a much smaller self-motion scale (about 0.002, comparable with visual drive), seeds
  for the projection readout, and level 4.

## Artifacts

Under `/mnt/fastvault/minesophila/derived/brain-localization-v3/`:

- `temporal-level{2,3}-occ-a-{tick,ports}-v1.fly-brain.npz`
- `recurrent-level{2,3}-occ-a-{control,context}-s{0,1,2}.{report.json,checkpoint.npz}`
- `connectome-clips-level{2,3}-occ-a-central-complex-{compass,projection}-v1.{report.json,checkpoint.npz}`
- `connectome-clips-level3-occ-a-central-complex-{compass,projection}-self-motion-v1.*`
- `temporal-level3-occ-b-s1-{tick,ports}-v1.fly-brain.npz`, `recurrent-level3-occ-b-s1-*`,
  `connectome-clips-level3-occ-b-s1-central-complex-{compass,projection}-v1.*`

Visible and occluded subsets come from scoring each checkpoint on the test split's
`visible` and `occluded` intervals (occluded frames limited to those with honey present).
