# fly-brain

`fly-brain` learns honey localisation and, later, movement from `fly-vision` receptor signals.
Biological structure is an experimental prior rather than a restriction: anatomical,
approximate, learned and shortcut variants are compared on the same held-out tasks.

The current foundation defines framework-independent contracts for:

- a fixed two-compound-eye sensor layout;
- named, model-visible context features;
- still images and videos as one time-major observation type;
- targets, privileged state and provenance kept outside model input;
- complete examples joining those separate values for datasets and evaluation.

Cached `fly-vision` frames can now be assembled into still or temporal examples. Optional Honey
Factory adapters add causal egocentric movement context and masked teacher-action targets without
placing world pose or teacher state in model-visible observations.

The framework-independent connectome layer represents a pinned release as canonical immutable
neuron and directed-synapse tables. It validates endpoints and anatomical counts, creates stable
graph checksums and induced subgraphs, and reports annotation gaps and in-memory size.

Connectome loading, training frameworks and executable models follow in later slices of the
[foundation plan](../../docs/superpowers/plans/2026-11-19-fly-brain-foundation.md).
