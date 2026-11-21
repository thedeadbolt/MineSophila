# Pathway information ladder (Slice 9b)

Recorded 2026-11-21. The question is where presence and bearing information is lost between the
frozen spatial frontend and the visual Kenyon cells. Each stage is measured with the same decoder.
A loss at a stage therefore means the information is missing from that stage's activity, not that
a particular readout failed to use it.

## Method

- **Data:** the level-1 tick-aligned clips (`m12-2-pilot-l1-efccab07e309`, 200 clips, 8,460
  frames) with their source-defined scene splits. Frames are decoded independently.
- **Activity:** the static `connectome-level1-visual-kenyon-v1` checkpoint (legacy unsigned ReLU
  policy, frozen spatial frontend). Each frame settles for four steps from rest, as in training
  (`prepare-connectome-clips --state-mode reset`).
- **Decoder:** `fly-brain probe-clip-features`: a two-hidden-layer MLP of width 64 on z-scored
  features, trained for 3,000 Adam steps (batch 128, learning rate 10⁻³) for seeds 0–2. The
  presence threshold is selected on validation balanced accuracy, and results are reported on test.
- **Ceiling:** the same decoder trained on the frontend's own four outputs.

## Legacy unsigned ReLU model

| Stage | Neurons | Test balanced accuracy | Test bearing error |
|---|---:|---:|---:|
| Frontend output (ceiling) | 4 | 96.05 ± 0.34% | 2.26 ± 0.16° |
| Photoreceptors | 6,098 | 97.32 ± 0.16% | 3.46 ± 0.28° |
| Lamina relays | 8,884 | 98.93 ± 0.35% | 3.04 ± 0.48° |
| Medulla relays | 17,646 | 99.34 ± 0.52% | 2.86 ± 0.38° |
| Visual projection | 9,201 | 96.23 ± 0.71% | 2.92 ± 0.35° |
| Visual Kenyon inputs | 103 | 95.08 ± 0.31% | 7.26 ± 0.15° |
| Visual Kenyon cells | 284 | 92.72 ± 0.21% | 12.18 ± 0.76° |

Values are means ± standard deviation over three decoder seeds.

## Interpretation

**The optic-lobe pathway retains the task information.** Through the visual-projection neurons,
bearing stays within about 0.7° of the ceiling, even under the crude unsigned ReLU policy.
Presence decoding from the lamina and medulla is slightly above the ceiling: they keep evidence
the frontend's four outputs have already compressed away.

**The static models' ceiling was their readout.** The same checkpoint's trained linear readout of
visual-projection and Kenyon activity reaches 83.2% on stills. The MLP decoder reaches 96% from
visual-projection activity alone. The earlier apparent loss at injection (87.7% for the
photoreceptor shortcut) was also a linear-readout limit; photoreceptor activity decodes at 97.3%.

**The loss is in the Kenyon branch, and it is anatomical.** Only 103 of the 9,201
visual-projection neurons pass the Kenyon-input threshold. These carry much less direction
information (7.3°), and the Kenyon expansion loses more (12.2°). This agrees with the temporal
readouts in `temporal-learning-results.md` and with the mushroom body's known visual role, which
is mostly visual identity (colour and brightness) rather than precise retinotopic position.

## Consequences for Slice 9

- **Kenyon cells are a poor candidate for target-direction memory.** Memory of where the target
  was belongs to heading and position circuits, and the central complex is the anatomical
  candidate. The mushroom body remains a candidate for remembering what was seen, such as
  presence or identity.
- **Readouts must not be linear on raw activity.** Experiments comparing circuits should use a
  normalised readout, at least z-scoring, so that results measure the circuit rather than the
  scale of its activity.
- **The graded signed policy (ADR 0027) gets the same ladder.** It is judged by whether it keeps
  information at least as well as the legacy policy while staying stable over clips.

## Artifacts

Stored under `/mnt/fastvault/minesophila/derived/brain-localization-v3/`:

- `temporal-level1-pathway-stages-framewise-v1.fly-brain.npz` (42,113-neuron activity)
- `temporal-level1-frontend-pathway-stages-framewise-v1.fly-brain.npz` (combined with the
  frontend output)
- `probe-level1-pathway-stages-legacy-v1.report.json`
