# 0028: Thresholded release per cell type and a fitted compass integrator

- **Status:** Proposed
- **Date:** 2026-11-23

## Context

Occluded honey bearing needs the central-complex compass to work as an angular integrator: an
EPG bump that holds its heading when the fly is still and rotates in proportion to yaw. The
dead-reckoning ceiling on batch-B level-3 test clips (last visible bearing minus summed true
yaw) is 16.8°, against about 65° for the GRUs and 51° for the best connectome readout.

Slice 9i measured why the compass did not integrate under ADR 0027:

1. **Linear release cannot rotate a bump.** Under graded signed release every neuron is linear
   at rest. An angular-velocity drive added to all left (or right) PEN neurons then produces a
   pattern that does not depend on where the bump is. It shifts the bump by a fixed offset and
   then stops. The anatomy can rotate the bump: weighted by MaleCNS synapse counts, PENs in the
   left protocerebral-bridge hemisphere project to EPGs 67° to one side and right-hemisphere PENs
   67° to the other. Rotation needs PEN output gated by bump × turn, which needs a threshold.
   In the fly, EPG and PEN are spiking neurons.
2. **Hand-set operating points do not integrate.** The 9h ring-attractor search sets the
   linearised compass spectrum. With the corrected bridge mapping, a harmonic target of 1.5 and
   no tonic bias, it gives a bump that holds a seeded heading within about 7° (strength 0.75).
   But it pushes nearly every compass gain to the cap. With a PEN threshold added, the bump
   rotates with the correct sign but far too slowly (±0.18°/frame at 8°/tick).
3. **The circuit has a speed ceiling.** Fitted by gradient on a synthetic integration task, the
   compass rotates at most about 2–3°/frame with 2 transitions per frame (τ = one 50 ms tick).
   41% of recorded frames turn faster than 4°/tick, and the teacher's turns reach 9°/tick. At a
   bump gain of 1 the fitted compass falls behind on fast turns. Dead reckoning from the bump is
   then no better than holding the last bearing (63° against 62°).

## Decision

- **Thresholded release per cell type.** Graded dynamics gain an optional per-cell-type smooth
  threshold:
  - release is `0.5·tanh(2·softplus_k(v − θ))` minus its value at rest, where
    `softplus_k(x) = softplus(k x)/k`, with sharpness `k` (default 20) and threshold `θ` per type;
  - other neurons keep graded release;
  - it is configured by `release_thresholds` (cell type to θ) and `release_threshold_sharpness`;
  - it starts with the PEN types. Status: approximation of spiking output.
- **Fitted compass integrator.** `fly-brain fit-compass-integrator` fits:
  - the compass cell-type-pair gains (EPG, PEN_a, PEN_b, PEG, Delta7);
  - one threshold per PEN type, the yaw drive scale and one bias per compass type.
  It runs on the compass alone, with anatomical weights from the compiled brain. Each trial seeds
  a bump by a brief EPG kick and then applies recorded clip yaw (with some synthetic turns). The
  loss asks the bump phase to follow `seed + k·Σ yaw`, with a floor on bump strength. The fit
  starts from the ring-attractor search. Its output is a reviewable JSON block of model
  overrides. Clip configs apply it through `compass_operating_point: "fitted"`, with
  `compass_gains` keyed `SOURCE>TARGET` and `compass_type_bias`. Status: learned.
- **A bump gain below one.** The compass rotates by `k` degrees per degree of yaw, with `k`
  about 0.3–0.5. Downstream circuits (FC2/hΔ goal memory, PFL3, readouts) are trained with the
  compass and work in bump units, so `k` needs no explicit inverse. Integration only needs `k` to
  be consistent across turn rates. Measured fly bump gains also vary around one.
- **Four transitions per frame for compass work.** τ = two transitions, so at four transitions
  per frame τ ≈ 25 ms, closer to fly neurons. This roughly doubles the compass's maximum
  rotation per frame, and clip training costs about twice as much.

## Consequences

- On batch-B test clips in the full brain, dead reckoning from the fitted bump (last visible
  bearing minus gain-corrected bump rotation) is 22.7° with `k = 0.5` and four transitions per
  frame, and 34.3° with `k = 0.3` and two. This compares with 61.8° for holding the last bearing,
  63° for the best fit at `k = 1`, and the 16.8° ceiling. These numbers still use the true last
  visible bearing. Storing that bearing is the job of the FC2/hΔ goal memory (9i step 2).
- The fitted thresholds came out slightly below rest (θ ≈ −0.1), so PENs are tonically active
  and the threshold acts as a smooth rectifier around rest, not as silence until bump × turn. The
  fit chose this. It is recorded rather than forced.
- Holding still costs some accuracy: seeded headings drift by about 11–18° over 30 frames.
  Vision through the ring neurons is meant to correct this, but ring-neuron drive is still weak
  (about 6% of ring-neuron input comes from TuBu).
- The compass-only fit ignores inputs from outside the compass. The full-brain evaluation is the
  acceptance test, and clip training may retune the gains. Thresholds stay fixed unless they
  become trainable parameters later.
- Unresolved: whether EPG and Delta7 also need thresholds; learning `k` jointly with the goal
  memory; and the right transition count for the whole brain against cost.
