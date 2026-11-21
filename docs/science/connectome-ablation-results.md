# Level-1 connectome ablations

Recorded 2026-11-21. These Slice-7 experiments use the same Level-1 scene splits and evaluator as
the established fly-spatial and raw-RGB baselines. Configurations were selected using validation
results; the test split is reported as a final measurement.

## Results

| Configuration | Neurons | Trainable | Frozen frontend | Validation balanced | Test balanced | Test bearing | Runtime |
|---|---:|---:|---:|---:|---:|---:|---:|
| Direct receptors, full circuit | 41,829 | 36,809 | 0 | 51.09% | 47.27% | 66.46° | 61.74 s |
| Signed cell-pair gains, full circuit | 41,829 | 42,090 | 0 | 50.00% | 50.00% | 75.53° | 81.55 s |
| Signed per-edge gains and biases | 41,829 | 392,108 | 0 | 50.00% | 50.00% | 75.29° | 81.34 s |
| Direct receptors, photoreceptor + projection readout | 41,829 | 61,201 | 0 | 53.75% | 53.83% | 64.69° | 168.07 s |
| Learned spatial frontend, photoreceptor shortcut | 15,299 | 24,397 | 6,562 | 85.47% | **87.66%** | **11.82°** | 48.76 s |
| Learned spatial frontend, lamina circuit | 24,183 | 36,809 | 6,562 | 81.95% | 81.88% | 18.15° | 64.59 s |
| Learned spatial frontend, medulla circuit | 32,945 | 36,809 | 6,562 | 82.34% | 82.27% | 15.31° | 94.38 s |
| Learned spatial frontend, full circuit only | 41,829 | 36,809 | 6,562 | 81.72% | 83.05% | 15.74° | 149.19 s |
| Learned spatial frontend, shortcut + full circuit | 41,829 | 61,201 | 6,562 | 84.77% | 85.39% | 13.17° | 212.45 s |
| Standalone fly-spatial model | — | 6,562 | 0 | 97.03% | 97.50% | 3.51° | not recorded |

The learned frontend is the frozen selected Level-1 fly-spatial model. It converts receptor signals
into predicted per-ommatidium coverage without reading labels or privileged state at inference.
That coverage is repeated across the three receptor classes and passed through the same explicit,
versioned receptor-to-neuron ID map as the direct-input experiments. It is marked `shortcut` in
the report and its checkpoint hash and 6,562 frozen parameters are recorded.

## Interpretation

The first failure was not primarily a shortage of trainable synaptic parameters. Moving from one
global gain to cell-type-pair or per-edge gains did not rescue the direct receptor mapping. The
signed ReLU variants are additionally a poor biological test: inhibitory histaminergic receptor
drive reaches a zero-baseline ReLU and loses activity. A serious signed model needs resting
potentials or another dynamics policy.

Preserving spatial meaning before the connectome is decisive. The learned sensory transform moves
the full circuit from 47.27% to 83.05% test balanced accuracy and clears the Level-1 80% target.
However, the photoreceptor shortcut is 4.61 percentage points more accurate, 3.92° better in
bearing and roughly three times faster than the full circuit-only model. Adding the full circuit
to the shortcut also reduces accuracy. The selected Slice-7 model is therefore the shortcut, not
the larger or more biological variant.

This pattern agrees with useful implementation evidence rather than with a claim that the projects
solve the same task. [FlyBridge](https://github.com/swotstudio/FlyBridge) uses a small trainable
image encoder before sensory neurons, bounded per-connection gains, learned leak and bias, and an
explicit motor controller. [train-your-fly](https://github.com/eudald-seeslab/train-your-fly)
uses reconstructed photoreceptor positions for its eye transform, learns one gain per connection
by default and reads from Kenyon cells. [FlyVis](https://turagalab.github.io/flyvis/) learns
cell-type-pair synaptic strengths and neuron dynamics on an optic-flow task. Our MaleCNS flat tables
do not currently provide verified retinal coordinates for these photoreceptor IDs, so stable rank
must remain labelled as an approximation.

## Next experiments

1. Add explicit per-eye and regional pooling controls; neither may replace the selected spatial
   shortcut unless it wins on held-out scenes.
2. Keep R1–R6 neural superposition as a named deferred transform until a defensible retinotopic
   correspondence is available. Do not manufacture it from body-ID order.
3. Replace signed zero-baseline ReLU dynamics with a resting-potential or signed-state policy before
   drawing conclusions about neurotransmitter signs.
4. Move the selected shortcut into temporal learning. Revisit Kenyon-cell and mushroom-body
   expansion only after video and movement context have a strong recurrent control.

All generated reports, checkpoints and input maps are stored under
`/mnt/fastvault/minesophila/derived/brain-localization-v3/`; no generated artifacts are committed.
