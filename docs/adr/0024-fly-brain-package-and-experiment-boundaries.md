# 0024: fly-brain package and experiment boundaries

- **Status:** Accepted
- **Date:** 2026-11-19

## Context

`fly-vision` produces spatially indexed receptor activations from still images and video frames.
A separate package must learn honey localisation and eventually movement from those observations.
The work needs connectome structure without making biological fidelity a condition for useful
predictions. It must also prevent teacher labels and world truth from leaking into student input.

The same contracts must serve single images, recorded clips and live lock-step control. Network
structure must grow from small visual circuits into optional memory and output circuits without a
single mutable class taking responsibility for data, anatomy, dynamics, training and deployment.

## Decision

- Add a Python workspace package named `fly-brain`. Keep learned models and connectome processing
  out of the fixed `fly-vision` sensor package.
- Use one time-major observation contract: a still image has one time step and a video has several.
  Preserve receptor site, compound-eye identity, receptor channel and validity information.
- Separate model-visible agent context, training targets, privileged state and provenance in the
  type system. The first static localisation model receives no movement context.
- Use MaleCNS v1.0 as the first structural source, pinned by release checksum. Begin at an external
  receptor/visual-column input boundary instead of claiming the current three-channel eye model
  represents every individual R1-R8 photoreceptor neuron.
- Represent anatomy as an immutable sparse graph and compose an immutable `BrainNetwork` from
  named, resolved neuron components. Save exact neuron IDs with the source release checksum.
- Label components and transforms as anatomical, approximate, learned or shortcuts. Retain
  non-biological features when controlled held-out comparisons show better results.
- Keep recurrent state explicit and use pure initialization, step and sequence-run operations in
  executable models. Keep framework-specific compilation separate from structural contracts.
- Establish direct-receptor linear and small conventional neural baselines before evaluating a
  connectome-constrained model.
- Use a learned readout as the first model output. More anatomical output and motor pathways are
  later measured expansions, not prerequisites for honey localisation.

## Consequences

Image and video learning share one interface, and package boundaries make accidental privileged
input easier to detect. Connectome selections and experimental shortcuts remain inspectable and
reproducible. A new input representation, neuron dynamic or output pathway can be compared without
rewriting dataset contracts.

The first implementation does not reproduce R1-R6 neural superposition. Preserve spatial `R1_6`,
`R7` and `R8` signals and defer anatomically mapped convergence to a named input transform. Compare
it with direct input, per-eye summaries, regional pooling and learned pooling before retaining it.
Ocelli, polarisation and exact photoreceptor-to-column mapping remain separate future inputs.

MaleCNS anatomy specifies possible connections, not their functional strength or dynamics. Those
remain explicit experimental assumptions. The initial model may therefore localise honey without
being a faithful simulation of a fly brain.
