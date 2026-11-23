# 0030: Fan-shaped-body goal memory with a learned egocentric vector input

- **Status:** Proposed
- **Date:** 2026-11-23

## Context

The fitted compass integrator (ADR 0028) tracks heading through occlusion. Dead reckoning from
its bump reaches 23–34° on batch-B level-3 clips, against a 16.8° ceiling. That figure still
uses the true last-seen bearing. To remember the honey, the brain must also store that bearing
and compare it with heading.

In MaleCNS the steering neurons PFL3 (in the model, 24 neurons) receive their heading on the
bridge side, through Delta7 and EPG. Their goal input comes from the fan-shaped body:
- FB tangential neurons 38%;
- hΔ 20%;
- FC2 13%.

The model held only about 11.5% of PFL3's real input. FC2 holds goal directions in flies, and
PFL3 compares goal with heading (Mussells Pires et al. 2024).

FC2's own input (edges of at least 5 synapses) is:
- 61% FB tangentials, the largest single type FB5A, which in turn takes most of its own input
  from FC2;
- 28% hΔ;
- 4.5% vΔ;
- 4% PFN.

No visual-projection neuron synapses onto FC2 or hΔ. Two-hop visual routes pass through broad,
non-columnar tangentials. The best-supported mechanism for building an allocentric direction is
vector addition (Lyu, Abbott & Maimon 2022; Lu et al. 2022):
- PFN neurons carry heading copies shifted per hemisphere;
- egocentric signals arriving through the noduli set their gain;
- hΔ sums them into a rotated vector.

### Literature check (2026-11-23)

- FC2 encodes the goal direction, and PFL3 compares it with heading in the bridge, where about
  80% of PFL3's heading input comes from Δ7 ([Mussells Pires et al. 2024](https://www.nature.com/articles/s41586-023-07006-3);
  [Westeinde et al. 2024](https://www.nature.com/articles/s41586-024-07039-2)). This matches MaleCNS.
- Olfactory memories specify the goal direction in FC2 during navigation
  ([Nature 2026](https://www.nature.com/articles/s41586-026-10827-7)). The goal is set by learned
  value, not only by a seen object.
- A preprint ([Nanni & Lee 2026, arXiv:2607.18969](https://arxiv.org/abs/2607.18969);
  FlyWire, neuron level, not peer reviewed) makes these claims:
  - There is no within-FC2 ring attractor. FC2 "normalizes an externally set goal rather than
    selecting it".
  - FB5A is a uniform global inhibitor of FC2 (divisive normalization). Its GABA identity is a
    low-confidence prediction.
  - The goal is held upstream, in an hΔ recurrent network led by hΔC.
  - That network is gated by value through MBON → FB5AB → {hΔC, FC2}.
  - The hΔ loop latches only partially, at gains of about 6 or more.
- No specific plasticity rule for writing FC2 goals has been reported.

This agrees with the stage-1 finding in this repository. In the modelled circuit FC2 has no
excitatory loop of its own, and no leading mode is first harmonic. So the goal store, if it is
held in activity, is the hΔ network, read and normalized by FC2.

## Decision

- **Components (opt-in):**
  - `vector_inputs`: PFNa, PFNd, PFNm_a, PFNm_b, PFNv (165);
  - `fan_shaped_body_columns`: hΔA–M (189);
  - `goal_memory`: FC2A–C (92);
  - `goal_tangentials`: FB5A (4).
  Configs without them are unchanged.
- **Heading angles from connectivity.** Bridge glomeruli give PB angles. FC2, hΔ and FB5A take
  the weighted circular mean of their angled inputs, because FB column labels are compressed
  relative to heading (about 36° per column, not 45°).
- **A learned egocentric vector into PFN** (`egocentric_input: "pfn_vector"`), with status
  approximation. A learned linear map reads a two-component vector `(x, y)` from
  visual-projection release. Each PFN receives `x cos(o) + y sin(o)`, where `o` is its measured
  FB heading shift: the circular mean of (target angle − own angle) over its edges onto angled
  hΔ and FC2. This stands in for the unmodelled noduli route. It is the only non-connectome
  element, and it carries no ground truth: it reads the model's own visual activity.
- **Staged fits before clip training,** as for the compass:
  1. FC2 + FB5A + hΔ hold a seeded goal.
  2. PFL3 decodes goal − heading.
  3. The PFN → hΔ → FC2 path writes `EPG phase + k · bearing` while honey is visible. Here `k` is
     the compass bump gain. In this synthetic fit only, the true egocentric vector is injected.
  Clip training then learns everything end to end, including the visual-to-egocentric map.
- **Within a clip, only activity holds the goal.** Plasticity, and the mushroom body's influence
  through MBON→FB pathways, are deferred to #29.

## Consequences

- The model grows by about 450 neurons (42,465 to 42,915) and 9,201 × 2 learned egocentric
  weights.
- Measured PFN heading shifts:
  - PFNa: +30° (left), −35° (right), concentration about 0.92;
  - PFNm_a/b: +50–56° / −32°, about 0.97;
  - PFNd: +10° / −38°, 0.27;
  - PFNv: −18° / +32°, 0.12–0.23.
  PFNa and PFNm match the ±45° from the literature. PFNd and PFNv are diffuse. They project onto
  hΔ neurons whose two arbors are about 180° apart, so a single shift per neuron is a poor
  summary for them. The learned map can partly compensate. A per-arbor treatment is future
  work.
- Old checkpoints load: the new parameter defaults to an empty (0, 2) array.
- Unresolved:
  - when the goal should be overwritten when honey reappears elsewhere (writing is currently
    gated only by the egocentric vector going to zero without honey);
  - whether more tangentials or vΔ are needed;
  - how `k` should be shared between compass and goal fits.
