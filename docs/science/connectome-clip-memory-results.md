# Connectome memory on occlusion-rich clips (Slice 9g, interim)

Recorded 2026-11-22, updated 2026-11-23. The first test of target-direction memory inside the
connectome, against the recurrent (GRU) control, on clips where the honey is hidden for long
stretches. Results are interim: several central-complex rows are still single seeds.

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
| Central complex, projection readout (seeds 0–2) | 63.7 ± 1.7% | **25.0 ± 2.5°** | 51.6 ± 2.3° | 68.1 ± 1.8% |

Projection seeds 0, 1 and 2 scored 48.9°, 52.9° and 52.9° occluded bearing.

Adding yaw rate to the PEN neurons (`self_motion_input: "pen_yaw"`, seed 0):

| Model | Test balanced accuracy | Visible bearing | Occluded bearing | Occluded recall |
|---|---:|---:|---:|---:|
| Compass readout + self-motion (seeds 0–1) | 72.2% | 34.2° | 83.0° (81.0, 85.0) | 43.4% |
| Projection readout + self-motion (seeds 0–1) | 63.0% | 26.5° | 50.9° (49.1, 52.6) | 68.7% |
| Compass readout + weak self-motion (0.002) | 76.0% | 31.1° | 77.3° | 58.9% |
| Projection readout + weak self-motion (0.002) | 64.0% | 26.1° | 49.0° | 67.6% |

## Level 3 (batch B, slice 1), test split

300 clips from 75 scenes; the test split has 1,229 occluded frames. Occlusions here are longer
and harder for every model.

| Model | Test balanced accuracy | Visible bearing | Occluded bearing | Occluded recall |
|---|---:|---:|---:|---:|
| GRU, vision only | 63.8 ± 3.2% | 29.6 ± 2.3° | 64.6 ± 1.9° | 90.4 ± 4.8% |
| GRU + egocentric motion | 74.8 ± 2.2% | **23.9 ± 1.5°** | 65.3 ± 0.6° | 84.2 ± 4.7% |
| Central complex, compass readout | **81.4%** | 52.0° | 78.1° | 73.6% |
| Central complex, projection readout | 71.3% | 37.3° | **51.1°** | 72.7% |

## Ring-attractor compass (9h), level 3, test split

The compass starts at the ring-attractor operating point: a leading first-harmonic pair at 1.05,
every other mode at most 0.73, and tonic bias 0.02. It is then clip-trained as before, starting
from the level-1 checkpoint (seed 0). The compass readout is used throughout.

| Batch | Self-motion | Visible bearing | Occluded bearing | Bump strength | Phase-yaw corr | Phase-yaw gain |
|---|---|---:|---:|---:|---:|---:|
| A | none | 39.2° | 81.6° | 0.56 | 0.03 | 0.00 |
| A | PEN yaw, 0.05 | 35.8° | 74.7° | 0.35 | 0.37 | 0.66°/° |
| B | PEN yaw, 0.05 | 41.6° | 74.8° | 0.38 | 0.36 | 0.65°/° |

Training keeps the ring: the harmonic pair stays at 1.05 and the bump survives occlusion
(strength 0.35–0.38, unchanged phase tracking). Bearing does not improve on the silent compass
(71.6° and 78.1°). Ring neurons stay nearly silent (mean |a| about 0.005), so vision barely
anchors the bump.

**Dead-reckoning ceiling.** Egocentric honey bearing during occlusion is the last visible bearing
minus the turn since then. Scoring that estimate directly, on occluded frames that follow a
sighting, shows what a compass can offer:

| Batch | Frames | Hold last bearing | Minus true yaw | Minus bump phase (gain-corrected) |
|---|---:|---:|---:|---:|
| A | 174 | **44.1°** | 60.4° | 75.6° |
| B | 338 | 61.8° | **16.8°** | 72.2° |

On batch B, a perfect yaw integrator plus a stored onset bearing reaches 16.8°, against about
65° for the GRUs and 51° for the projection readout. On batch A, holding the bearing beats
integrating yaw, probably because translation past nearby honey dominates there.

Two things are missing:
1. A heading estimate good enough to integrate. The bump's phase follows yaw with corr of only
   0.36, which is worse than holding the bearing.
2. A place to keep the onset bearing. A linear readout of a heading bump yields heading, not
   honey relative to heading. In the fly, the fan-shaped body holds a goal and PFL3 compares it
   with heading. Our CX has no fan-shaped body.

## Compass integrator (9i), batch B level 3, test split

The bridge angle mapping used by 9h was mirrored on the left (fixed in 3e8ffc6), so the 9h
ring-compass runs above used a scrambled harmonic score. The compass is now fitted as an
angular integrator (ADR 0028):
- on the compass alone, by gradient;
- with recorded yaw;
- with PEN release behind a smooth threshold;
- with a target bump gain `k` (degrees of bump rotation per degree of yaw).

The fitted values are then put into the full level-1 brain and scored on test clips. The
dead-reckoning error takes the last visible true bearing minus the bump's rotation since then,
corrected by the bump's measured gain. It measures the integrator alone: storing the onset
bearing is FC2's job (9i step 2).

| Compass | Transitions / frame | Target `k` | Measured gain | 16-frame corr | Dead reckoning |
|---|---:|---:|---:|---:|---:|
| Hold last bearing (no update) | – | – | – | – | 61.8° |
| 9h ring, silent-compass fix | 2 | – | 0.65 | 0.42 | 72.2° |
| Fitted | 2 | 1.0 | 1.44 | 0.69 | 63.4° |
| Fitted | 2 | 0.5 | 0.54 | 0.41 | 46.1° |
| Fitted | 2 | 0.3 | 0.35 | 0.38 | 34.3° |
| Fitted | 4 | 0.5 | 0.38 | 0.65 | **22.7°** |
| Fitted, trained longer | 4 | 0.5 | 0.31 | 0.74 | 34.2° |
| Fitted | 4 | 0.3 | 0.22 | 0.40 | 26.6° |
| True yaw (ceiling) | – | – | 1 | 1 | 16.8° |

- **Gain one is out of reach.** The compass rotates at most about 2–3°/frame at two transitions
  per frame. 41% of test frames turn faster than 4°/tick, up to 9°/tick. A lower target `k`
  keeps fast turns within range.
- **Four transitions per frame help.** τ ≈ 25 ms instead of 50 ms roughly doubles the maximum
  rotation per frame. The best fit is within 6° of the ceiling.
- **Spread between fits is large.** Fits at the same settings land between 23° and 34°, so the
  best single number is optimistic. The canonical run is `fit-compass-integrator` with
  `configs/compass-integrator-fit-v1.json`.
- Fitted PEN thresholds sit slightly below rest (θ ≈ −0.1). Seeded headings drift by 11–18°
  over 30 frames with no turning.

## Fan-shaped-body goal memory, stage 1 (9i step 2): no persistent goal mode

`fit-goal-memory` stage 1 kicks an FC2 bump and fits the FC2 + FB5A + hΔ type-pair gains so the
bump holds. From effective gain 1.0, 1,500 steps leave the hold error at 94° (chance): the bump
fades, and the phase loss stays near 1 throughout. The loaded circuit explains why:

| Pathway (MaleCNS, incoming-normalised weights summed) | Weight | Note |
|---|---:|---|
| hΔ → hΔ | +118.9 | strong excitation, but preserves a ring pattern with fidelity only 0.20 |
| hΔ → FC2 | +62.9 | passes the pattern on shifted 169° (hΔ arbors sit about 180° apart) |
| FB5A → FC2 | −24.3 | FB5A is GABAergic: inhibitory feedback, like Delta7 for the compass |
| FC2 → hΔ | +3.9 | almost none, so there is no FC2 → hΔ → FC2 loop |
| FC2 → FC2 | +1.1 | |

- At gain 1 the leading loop eigenvalue is 0.83 (decaying), and no leading mode is first
  harmonic (≤ 0.3 even when scored on FC2 alone).
- FC2 reads the fan-shaped body but has no excitatory loop of its own inside this circuit.
- At type-pair resolution, the modelled anatomy provides no ring attractor that could hold a goal.
- In the fly, goal maintenance may rely on parts we leave out: the other 61% of FC2 input
  (mostly tangentials), vΔ and FC1/FC3, or synaptic plasticity (#29).

The stage-2 (PFL3 comparison) and stage-3 (PFN write) fits are implemented but not yet run on
MaleCNS.

## Level 4 (batch B), test split

600 clips from 150 scenes; the test split has 6,340 occluded frames with honey present. Occluded
bearing is close to visible bearing for the GRUs, so these occlusions are easier to bridge than
level 3's.
Here the GRUs lead on every measure. This fits the level-3 pattern: the circuit's advantage
appears only where bridging the occlusion matters more than visible precision.

| Model | Test balanced accuracy | Visible bearing | Occluded bearing | Occluded recall |
|---|---:|---:|---:|---:|
| GRU, vision only | 73.2 ± 1.6% | 23.1 ± 0.4° | 23.0 ± 0.5° | 69.2 ± 7.0% |
| GRU + egocentric motion | **81.6 ± 2.4%** | **17.2 ± 0.6°** | **20.1 ± 0.5°** | 73.3 ± 2.7% |
| Central complex, compass readout | 75.2% | 43.9° | 39.3° | 72.5% |
| Central complex, projection readout | 67.9% | 30.6° | 27.5° | 71.8% |

## Level 2 (batch A)

| Model | Test balanced accuracy | Test bearing | Validation occluded bearing |
|---|---:|---:|---:|
| GRU, vision only | 68.7 ± 2.0% | 17.5 ± 1.6° | 65.0 ± 8.4° |
| GRU + egocentric motion | **87.2 ± 1.2%** | **9.3 ± 1.2°** | **52.6 ± 6.0°** |
| Central complex, compass readout | 73.5% | 25.2° | 97.8° |
| Central complex, projection readout | 74.3% | 22.0° | 56.6° |

## Interpretation so far

- **The stateful visual circuit retains direction.** Read at its visual-projection output, the
  clip-trained connectome beats the vision-only GRU on batch-A level-3 bearing over three seeds.
  Visible bearing is 25.0° against 30.7°. Occluded bearing is 51.6° against 54.9°, a modest margin
  that is about one standard deviation of either model. It does so without any self-motion input.
- **The compass readout is best at presence but has no usable direction memory yet:** 71.6° on
  level 3 and chance on level-2 validation.
- **On the harder batch-B clips, the projection readout has the best occluded bearing:** 51.1°,
  against about 65° for both GRUs. Egocentric motion no longer helps the GRU there. Visible
  bearing is worse than the GRUs (37.3° against 23.9–29.6°), so the circuit trades visible
  precision for memory.
- **Self-motion through PEN does not help at either scale.** At scale 0.1, yaw drive (mean 3.7°/tick
  × 0.1 ≈ 0.37) is about 50 times the mean visual port value (about 0.008), and the compass readout
  got worse (83.0° over two seeds, against 71.6°). At scale 0.002, matched to visual drive, it is
  77.3°: still no better than no self-motion. The projection readout is unchanged at either scale.
  The drive's strength is therefore not the problem. Either the compass carries no usable heading
  bump under these trained gains, or the readout cannot decode one. That should be checked
  directly from EPG activity before any further self-motion runs.
- **The compass is silent, which explains both results above.** Probing the trained compass
  checkpoint on level-3 test clips (mean |activity| per stage):

  | MeTu | TuBu | ER | EPG | PEN | Delta7 | PFL |
  |---:|---:|---:|---:|---:|---:|---:|
  | 0.0037 | 0.0040 | 0.00013 | 0.00008 | 0.00003 | 0.00002 | 0.00001 |

  EPG phase (angle of its top two principal components) does not follow yaw: corr −0.02, and
  −0.06 with self-motion at 0.1. With self-motion at 0.1, PEN saturates at ±0.5 while EPG still
  reaches only 0.003.
  - TuBu supplies about 6% of each ring neuron's input weight.
  - Ring-neuron input to the compass is inhibitory, with no tonic activity to shape.
  - The linearised compass loop has spectral radius 0.54, so it cannot sustain activity.
- **The ring-attractor operating point gives a bump that survives training and integrates yaw**
  (gain 0.65°/°), but it does not yet help localisation; see the 9h section above. Dead reckoning
  with true yaw sets a 16.8° ceiling on batch B, so the route is worth pursuing. It needs a
  cleaner integrator and a goal memory that stores the onset bearing.

## Artifacts

Under `/mnt/fastvault/minesophila/derived/brain-localization-v3/`:

- `temporal-level{2,3}-occ-a-{tick,ports}-v1.fly-brain.npz`
- `recurrent-level{2,3}-occ-a-{control,context}-s{0,1,2}.{report.json,checkpoint.npz}`
- `connectome-clips-level{2,3}-occ-a-central-complex-{compass,projection}-v1.{report.json,checkpoint.npz}`
- `connectome-clips-level3-occ-a-central-complex-{compass,projection}-self-motion-v1.*`
- `connectome-clips-level3-occ-a-central-complex-{compass,projection}-self-motion-weak-v1.*`
- `temporal-level4-occ-b-{tick,ports}-v1.fly-brain.npz`, `recurrent-level4-occ-b-{control,context}-s{0,1,2}.*`,
  `connectome-clips-level4-occ-b-central-complex-{compass,projection}-v1.*`
- `temporal-level3-occ-b-s1-{tick,ports}-v1.fly-brain.npz`, `recurrent-level3-occ-b-s1-*`,
  `connectome-clips-level3-occ-b-s1-central-complex-{compass,projection}-v1.*`
- `connectome-clips-level3-occ-a-central-complex-compass-ring{,-self-motion}-v1.*`,
  `connectome-clips-level3-occ-b-s1-central-complex-compass-ring-self-motion-v1.*`

Visible and occluded subsets come from scoring each checkpoint on the test split's
`visible` and `occluded` intervals (occluded frames limited to those with honey present).
