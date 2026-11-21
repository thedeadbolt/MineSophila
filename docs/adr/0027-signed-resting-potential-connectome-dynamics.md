# 0027: Signed, resting-potential connectome dynamics

- **Status:** Proposed
- **Date:** 2026-11-21

## Context

The connectome models selected so far use the first dynamics policy from the fly-brain plan:

- all edges excitatory (neurotransmitter signs off);
- incoming anatomical weights normalised so each neuron's inputs sum to one;
- one learned global edge gain with no upper bound;
- zero-baseline ReLU rate neurons with leak `dt / tau = 0.5`;
- four settling steps from rest for each independent frame.

Three measured results show this policy is not good enough for Slice 9:

1. **It is unstable over time.** The level-1 visual Kenyon checkpoint learned a global gain of
   1.59. With incoming-normalised weights, every recurrent loop then amplifies by about 1.59 per
   pass. Carried across a clip, activity grew from about 0 to about 10⁴, and up to 4 × 10⁵. This is
   harmless for four settling steps but rules out memory in the circuit.
2. **It cannot use inhibition.** With signs on, fly photoreceptors are histaminergic and inhibit
   the lamina. A zero-baseline ReLU clips negative drive to zero, so the visual signal dies at the
   first synapse. The signed cell-pair and per-edge ablations both collapsed to 50% balanced
   accuracy. Real lamina monopolar cells respond by hyperpolarising, and most optic-lobe neurons
   signal with graded potentials in both directions around rest.
3. **It loses information.** Readout accuracy falls from 97.5% (frontend output) to 87.7% at the
   photoreceptors and about 83% after the circuit. Over clips, GRUs reading visual Kenyon activity
   decode bearing at 11.4°, against 7.1° from the Kenyon cells' own inputs and 2.7° from the
   frontend output. Slice 9b measures the loss per stage under the current policy.

## Decision

Add a second, named dynamics policy and keep the current one as the legacy control:

- **State relative to rest.** Each neuron has a graded potential `v` that may go above or below
  a learned resting value. Leak relaxes `v` toward rest, not toward zero.
- **Bounded, smooth release relative to rest.** A neuron drives its targets with release
  measured relative to its tonic release at rest, `0.5 * tanh(2 v)`: zero at rest, unit slope
  at rest and bounded to ±0.5. Hyperpolarisation therefore reduces downstream drive instead of
  being lost, and drive and state are bounded for bounded gains and inputs. The constant tonic
  release itself is absorbed into each neuron's learned rest. A first version used absolute
  logistic release; its slope of 0.25 at rest attenuated small signals about fourfold per
  synapse, its tonic offset accumulated with depth, and the model stayed at chance.
- **Neurotransmitter signs on.** Sources whose `consensus_nt` is GABA, glutamate or histamine are
  inhibitory; acetylcholine and other labelled transmitters are excitatory. Glutamate is treated
  as inhibitory as an approximation; it is excitatory at some fly synapses. Unlabelled neurons
  default to excitatory, and their count is reported with every compiled model.
- **Bounded gains.** Cell-type-pair gains use the existing `maximum_edge_gain` sigmoid bound.
  No unbounded global gain is allowed under this policy.
- **Measured stability before training.** A compiled model must keep activity bounded and
  non-saturated over the longest available clip without resets, with initial and trained
  parameters. The report records the activity range and the fraction of saturated neurons.
- **Information retention as an objective.** Training may add an auxiliary loss that requires
  selected stages (at least the visual Kenyon cells) to decode the frozen frontend output. The
  Slice 9b per-stage decoder is the acceptance measure: the policy should close the gap to the
  frontend ceiling, or the remaining gap must be documented.
- **Status labels.** Wiring and transmitter identity are anatomical. Signs inferred from
  predicted transmitters and the release curve are approximate. Rest, threshold, leak and gains
  are learned.

## Consequences

- Existing results stay comparable: the legacy policy remains selectable, and every new result is
  reported against it under the same splits and evaluator.
- Connectome models must be retrained under the new policy before any memory experiment. Static
  accuracy must at least match the legacy model's 83% before clip training starts.
- Clip training backpropagates through about 42,000 neurons and 318,000 edges per step. Truncation
  length and memory use need a measured budget.
- The input itself varies little between frames (coverage std about 0.02), and the linear
  readout reads unnormalised activity of order 10⁻⁴. Readout normalisation is a separate,
  policy-independent experiment and must be applied to both policies when compared.
- Unresolved: per-cell-type versus per-neuron rest; how
  to treat the few transmitters with mixed or unknown sign; and whether glutamate needs a
  receptor-specific exception for known excitatory synapses.
