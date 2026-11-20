# First connectome-constrained localisation model

Recorded 2026-11-20. This Slice-6 experiment compiles the reviewed MaleCNS visual network into
sparse JAX leaky-rate dynamics and evaluates it on the same Level-1 still-image split used by the
established fly-spatial and raw-RGB baselines.

## Circuit and parameter policy

| Property | Value |
|---|---:|
| Neurons | 41,829 |
| Directed edges | 313,471 |
| Represented anatomical synapses | 4,393,043 |
| Sparse topology storage | 7,857,936 bytes |
| Input neurons | 6,091 |
| Visual-projection readout neurons | 9,201 |
| Trainable parameters | 36,809 |
| End-to-end wall time | 61.74 seconds |
| Peak resident memory | 6,252,523,520 bytes |

Anatomical counts are normalized per target neuron and remain fixed. The first parameter policy
learns one global recurrent gain plus a four-output linear readout for presence and three-dimensional
bearing. Per-neuron biases are fixed at zero. Four leaky-rate settling steps use an integration
step of 1.0 and time constant of 2.0.

## Input mapping

`fly-vision` sensor IDs and MaleCNS body IDs are separate namespaces. A versioned
`ReceptorInputMap` explicitly maps all 1,726 sites × three receptor channels to MaleCNS
photoreceptors by eye, receptor class and stable rank. Where anatomy has fewer target neurons than
virtual receptor values, values are averaged only within that eye and receptor class. Where it has
more targets, a value fans out. All 5,178 source values participate through 6,829 assignments into
6,091 target neurons. The artifact preserves the source site IDs, channel names and target neuron
IDs; flattened indexes are only its compact assignment representation.

This is an approximation, not a claim of biological retinotopy. It does not globally pool either
compound eye. The complete source-to-target assignment is stored alongside the checkpoint and can
be replaced without changing the compiler.

## Level-1 result

| Model | Parameters | Validation balanced accuracy | Test balanced accuracy | Validation bearing error | Test bearing error |
|---|---:|---:|---:|---:|---:|
| Compiled MaleCNS visual circuit | 36,809 | 51.09% | 47.27% | 66.58° | 66.46° |
| Fly spatial model | 6,562 | 97.03% | 97.50% | 4.60° | 3.51° |
| Raw RGB CNN | 6,629 | 100.00% | 100.00% | 3.85° | 3.95° |

The connectome model does not compete with either baseline. Its validation loss continued to
improve through update 1,000, proving end-to-end gradients and learning, but the selected parameter
policy and approximate stable-rank mapping do not preserve enough useful spatial organization.
The recorded run used the JAX CPU backend; no GPU/VRAM was used.

This negative result sets the Slice-7 baseline. The next experiments should vary the receptor map,
input shortcuts, component set and parameter sharing independently. The stronger fly-spatial model
remains the retained working model.

Artifacts are stored under `/mnt/fastvault/minesophila/derived/brain-localization-v3/`:

- `connectome-level1-v1.input-map.json`
- `connectome-level1-v1.report.json`
- `connectome-level1-v1.checkpoint`

The experiment configuration is
`packages/fly-brain/configs/connectome-localization-v1.json`.
