# fly-brain foundation and learning plan

- **Date:** 2026-11-19
- **Status:** Proposed for review
- **Scope:** New Python workspace package `packages/fly-brain`
- **Related phases:** Phase 2 network design, Phase 3 honey identification, Phase 4 active search

## Goal

Build a modular learning package downstream of `fly-vision`. The first useful model identifies
and localises honey from receptor signals. Later models consume video, remember occluded honey,
imitate the teacher and drive Honey Factory through the lock-step interface.

Biological structure is a useful prior, not a requirement. Anatomical, approximate, learned and
deliberately non-biological components use the same interfaces. Held-out prediction and control
results decide which variants remain, while experiment records keep biological claims distinct
from engineering shortcuts.

## Success criteria

The foundation is successful when:

1. A still image and a video use the same validated, time-major observation contract.
2. Model-visible context, targets, privileged truth and provenance cannot be confused silently.
3. A pinned connectome release can be loaded into a validated sparse graph.
4. Named components resolve to exact reproducible neuron IDs and assemble into a network.
5. Conventional baselines and connectome-constrained models use the same task, trainer and
   evaluator interfaces.
6. The first honey-localisation baseline trains and evaluates on unseen scenes.
7. The smallest useful connectome model is compared with that baseline for accuracy, runtime and
   memory before the modeled circuit expands.

## Non-goals for the foundation

- Simulating the complete MaleCNS connectome.
- Exact reproduction of every photoreceptor, synapse or neural dynamic.
- Choosing spiking dynamics before a simpler rate model is measured.
- Adding ocelli, polarisation, smell, mining or flight.
- Coupling domain contracts to PyTorch, JAX or another training framework before a local
  compatibility benchmark.
- Treating teacher labels, global pose or the true honey position as student inputs.

## Package boundary

`fly-vision` remains a fixed sensor. It maps images into spatially indexed `R1_6`, `R7` and `R8`
signals. `fly-brain` owns input adapters, connectome selection, learned dynamics, task heads,
training, evaluation and live inference. Honey Factory owns rendered frames, labels, movement,
the teacher and the lock-step environment.

```text
honey-factory image or clip
            |
            v
       fly-vision
            |
            v
  ObservationSequence + permitted egocentric context
            |
            v
       fly-brain model
            |
       predictions/actions
            |
            v
    evaluation or live control
```

Raw image and video decoding stay at the source/adapter boundary. The mathematical brain model
consumes receptor sequences and does not know about PNG, MKV or Minecraft file layouts.

## Dependency direction

Start with one installable `fly-brain` distribution and strong internal subpackages:

```text
contracts
  ^
connectome
  ^
modeling
  ^
learning / runtime / baselines
```

- `contracts` knows nothing about connectomes or training.
- `connectome` owns structural data and selection, not tensor frameworks.
- `modeling` combines observations and structure into executable models.
- `learning` owns tasks, losses, training and evaluation.
- `runtime` owns stateful live inference and Honey Factory command mapping.
- `baselines` uses the same model/task interfaces without connectome internals.

Do not split these into separately released packages until a real consumer, dependency boundary,
installation cost or release cadence requires it. Plausible later extractions are
`fly-connectome`, `fly-brain-learning` and `fly-brain-runtime`.

## Proposed source layout

```text
packages/fly-brain/
  pyproject.toml
  README.md
  src/fly_brain/
    contracts/
      layouts.py
      features.py
      observations.py
      examples.py
      datasets.py
      sources.py
      adapters.py
    connectome/
      release.py
      graph.py
      loaders.py
      selectors.py
      components.py
      network.py
    modeling/
      transforms.py
      ports.py
      dynamics.py
      parameters.py
      model.py
      compiler.py
    learning/
      tasks.py
      losses.py
      training.py
      evaluation.py
      experiments.py
      checkpoints.py
    runtime/
      agent.py
      actions.py
      live.py
    baselines/
      linear.py
      mlp.py
      recurrent.py
tests/fly-brain/
```

This is a destination map. Create only the modules required by the current slice.

## Core data contracts

### `SensorLayout`

Static receptor-site geometry shared by many observations:

- stable layout ID and configuration hash;
- receptor-site IDs;
- left or right compound eye;
- head-centred viewing directions;
- ommatidium subtype;
- ordered receptor channel names;
- `fly-vision` model version and assumptions.

Observations reference a layout instead of repeating eye geometry per frame.

### `FeatureSpec`, `FeatureSchema` and `FeatureBlock`

Model-visible context is a named time-aligned feature matrix, never an arbitrary dictionary.
Every feature records its units, meaning, source and availability class. Initial candidates are
the previous requested and executed commands, measured egocentric velocity, yaw and pitch rate,
ground/contact state and frame interval.

Availability classes are:

- `agent`: legal model input;
- `teacher`: available to imitation labels or teacher logic;
- `privileged`: evaluation/debug truth only;
- `provenance`: identifiers and source facts, never a numerical feature.

### `ObservationSequence`

The only canonical model input:

```text
layout_id:         scalar
receptor_signals: [time, receptor_site, channel]
receptor_valid:   [time, receptor_site]
timestamps_s:     [time]
context:          FeatureBlock | absent
```

A still image has one time step; a video has several. Timestamps are strictly increasing, the
layout fixes site/channel dimensions, and context is aligned to time. Left and right compound
eyes remain spatially represented rather than being collapsed into one value per eye.

### `BrainTargets`

Task labels are stored separately from observations. Initial optional targets are honey presence,
per-receptor honey coverage, bearing/elevation and teacher commands. A sequence mask distinguishes
real time steps from batch padding.

### `Provenance` and `PrivilegedState`

Provenance records dataset, scene, clip and frame IDs, paths, capture hashes and sensor hashes.
Privileged state contains true pose, true honey position and teacher-only state. Neither is passed
to the model automatically.

### `BrainExample` and `BrainBatch`

`BrainExample` groups one observation, its targets, provenance and optional privileged state.
`BrainBatch` stacks examples and carries an explicit sequence mask. Begin with fixed-length
batches if convenient, but keep the mask in the contract so variable clips need no redesign.

## Source and adapter contracts

### `ObservationSource`

A protocol that yields source frames. Implementations may read one image, a recorded clip, cached
receptor frames or a live Minecraft connection.

### `ObservationAdapter`

Transforms a source into an `ObservationSequence`. Expected implementations are a `fly-vision`
adapter, a cached-frame adapter and eventually a live adapter.

### `ContextAdapter`

Constructs legal model context from Honey Factory frame records. Start with two explicit variants:

- no context, for a vision-only baseline;
- egocentric motion context, for previous commands and measured body movement.

### `TargetAdapter`

Constructs task targets independently. Presence, localisation, coverage and action-imitation
targets can be selected or combined without changing the observation contract.

## Connectome contracts

### `ConnectomeRelease`

Pins the source name, version, checksum, source location and licence. MaleCNS v1.0 is the current
primary candidate; a loader or experiment must never identify it only by an unversioned name.

### `NeuronTable`, `SynapseTable` and `ConnectomeGraph`

Use columnar arrays for neuron IDs, cell types, classes, neuropils, neurotransmitters and sides,
plus directed source/target IDs, anatomical synapse counts and optional confidence. The immutable
graph validates unique IDs and edge endpoints, builds stable indexes and creates induced subgraphs.
Anatomical counts stay separate from learned functional weights.

### `ConnectomeLoader`

Hides Feather, CSV, neuPrint or fixture formats behind one loading protocol. The rest of the
package consumes `ConnectomeGraph`, not provider-specific clients.

### `NeuronSelector`

A serializable expression tree, not an arbitrary callback. Begin with explicit neuron IDs, then
add selectors for cell type, neuropil, neurotransmitter and upstream/downstream hops. Support
union, intersection and exclusion composition.

### `ComponentSpec`, `ComponentResolver` and `NetworkComponent`

A component spec names a selection, role, purpose and biological status. The resolver applies it
to one pinned graph. The resolved component saves the exact neuron IDs, original selector and
connectome checksum. Empty or unresolved selections fail by default.

Roles are `sensory`, `processing`, `memory`, `output` and `experimental`. Biological status is
`anatomical`, `approximation`, `learned` or `shortcut`. Components may overlap and are views over
one graph rather than graph copies.

### `BrainNetwork`

An immutable structural assembly of a graph, named components and an `EdgePolicy`. Functional
`with_component()` composition returns a new network. The class validates membership, computes
stable unions, preserves cross-component connections, builds subgraphs and describes counts,
overlaps, disconnected selections and boundary edges. It owns no tensor framework, loss,
optimizer or recurrent state.

`EdgePolicy` makes minimum synapse count, internal/cross-component edges, recurrent edges and
optional boundary hops explicit.

## Model contracts

### `SignalTransform`

A pure observation-to-port transformation with a declared biological status and fixed or trainable
parameters. Candidate transforms are direct receptor input, per-eye pooling, regional pooling,
learned projection, egocentric context and deferred R1-R6 neural-superposition pooling.

### `InputPort`

Maps transformed values into explicit neuron IDs through an `InjectionRule`, such as additive
drive, clamped activity, learned affine projection or signal fan-out. Input injection is not
defined by connectome anatomy and must never be implicit.

### `NeuronDynamics`

A functional interface with explicit initialization and transition:

```text
initialize(topology, batch_size) -> BrainState
step(parameters, state, external_input, dt) -> new_state, activity
```

Start with simple leaky rate dynamics. Add recurrent-unit or spiking alternatives only as measured
experiments.

### `ParameterPolicy`

Defines how anatomy becomes trainable: per-edge weights, synapse counts times cell-type-pair gains,
fixed circuit plus learned readout, parameter sharing, neurotransmitter sign constraints, time
constants and biases. This remains separate from topology because it is a primary experimental
variable.

### `BrainState` and `BrainParameters`

All recurrent/adaptation state and trainable values are explicit immutable trees passed through
model functions. Training code must not depend on hidden mutable state inside a model object.

### `OutputHead`

Maps selected neural activity to presence, bearing, coverage or teacher-action predictions. A head
may be a direct neuron readout, fixed aggregation, linear layer or small non-biological MLP, with
its status recorded.

### `BrainModelSpec`, `BrainCompiler` and `CompiledBrain`

The model spec combines the structural network, ports, dynamics, parameter policy, output heads
and integration step. `BrainCompiler.plan()` resolves indexes and reports selected counts, memory,
parameter estimates and disconnected ports before allocation. `compile()` produces backend-ready
sparse operations.

The compiled model exposes pure initialization, `step()` and `run()` operations. `run()` is a scan
over `step()`, so still images and videos share exactly the same execution path.

## Learning and runtime contracts

### `BrainTask`

Defines targets, losses and metrics independently of network structure. Initial tasks are honey
presence, honey localisation, per-receptor coverage and later action imitation. A multitask object
composes them without modifying the network.

### Baselines

Linear, MLP and recurrent baselines implement the same compiled-model interface. A connectome
model earns complexity only by comparison with these baselines under the same split, task and
evaluator.

### `ExperimentSpec`, `Trainer` and `Evaluator`

An experiment spec serializes the model, task, data split, optimizer, training settings and seeds.
The trainer only orchestrates batches, updates, validation and checkpoints; it does not select
neurons, inputs or losses. The evaluator reports held-out results by scene, level, visibility,
occlusion, clip type and temporal horizon where applicable.

### `ExperimentManifest` and `Checkpoint`

The manifest records Git revision, data and layout hashes, connectome release/checksum, resolved
components, transforms and biological status, model/training configuration, seeds, software and
hardware. Checkpoints store numerical state and a manifest hash in a versioned non-pickle format.

### `BrainAgent`, `ActionMapper` and `LiveBrainClient`

`BrainAgent` is the small stateful shell permitted at live inference: immutable parameters plus
current explicit `BrainState`. `ActionMapper` converts outputs into bounded Honey Factory commands
without navigation logic. The live client owns the lock-step connection and runs frame ->
`fly-vision` -> observation -> brain -> action. Offline and live inference use the same compiled
model.

## Deferred input experiments

Do not implement exact R1-R6 neural superposition in the first model. Preserve spatial `R1_6`,
`R7` and `R8` signals initially. Keep neural superposition as a named `SignalTransform` and compare
it later with:

- direct per-ommatidium input;
- global left- and right-compound-eye summaries;
- regional pooling;
- learned pooling;
- combinations of spatial and pooled features.

Retain a shortcut if it improves held-out results, but record it as a shortcut or approximation.
Ocelli, polarisation and exact photoreceptor-to-column mapping remain separate future inputs.

## Implementation slices

Each slice must leave focused tests green and produce an inspectable result before the next begins.

### Slice 0 — Record the decisions

**Goal:** Freeze boundaries before code creates accidental APIs.

- Add an ADR for the `fly-brain` package boundary, performance-over-biological-purity rule,
  time-major input, metadata separation, immutable component assembly and deferred pooling.
- Confirm MaleCNS v1.0 as the first graph source or record the alternative.
- Define the first task and held-out metric before model implementation.

**Exit:** Accepted ADR and an agreed first localisation metric.

### Slice 1 — Scaffold the package and data contracts

**Goal:** Make image/video and metadata boundaries executable without an ML dependency.

- Add `fly-brain` to the uv workspace and isolated package tests.
- Implement `SensorLayout`, feature schema/block, `ObservationSequence`, targets, provenance,
  privileged state, example and batch validation.
- Test a one-frame image, multi-frame video, invalid shapes/timestamps, context alignment and
  separation of privileged information.

**Exit:** Both still and sequence fixtures produce the same canonical input type.

### Slice 2 — Add source adapters

**Goal:** Prove the boundary from existing MineSophila artifacts to brain examples.

- Implement cached `fly-vision` frame input first.
- Add still-image and Honey Factory clip adapters through `fly-vision` only where needed.
- Implement no-context and egocentric-motion context adapters.
- Implement honey presence, coverage and bearing targets.
- Pin source/layout hashes in provenance.

**Exit:** One still fixture and one clip fixture load as validated `BrainExample`s without label
leakage.

### Slice 3 — Represent and inspect a connectome

**Goal:** Establish a framework-independent sparse structural layer.

- Implement release, neuron table, synapse table, graph and loader protocol.
- Begin with a tiny synthetic fixture; add the chosen MaleCNS flat-data loader afterward.
- Validate checksums, IDs, endpoints, positive counts and deterministic indexing.
- Report neuron/edge counts, annotation gaps and memory estimates.

**Exit:** The selected real release loads reproducibly and a small induced graph can be inspected.

### Slice 4 — Resolve and assemble named components

**Goal:** Make circuit growth modular and reproducible.

- Implement explicit-ID selectors, component specs/resolution and immutable `BrainNetwork`.
- Add selector composition and annotation-based selectors only as required for the first circuit.
- Save resolved IDs and graph checksum.
- Implement `describe()` for roles, counts, overlaps, cross-component edges and disconnected
  selections.

**Exit:** A reviewed first visual subgraph is expressed entirely as named components and can be
rebuilt byte-for-byte from its manifest.

### Slice 5 — Train the simplest localisation baseline

**Goal:** Prove learning, splitting and evaluation before connectome dynamics.

- Choose the numerical framework only after a minimal CPU/GPU compatibility check on the local
  AMD system.
- Implement direct receptor input, one localisation task, linear baseline and evaluator.
- Add a small MLP only after the linear path works.
- Split by scene, not individual frame.
- Report presence accuracy, bearing error and per-receptor localisation metrics as agreed.

**Exit:** A reproducible model learns from receptor input and evaluates on unseen scenes with a
saved manifest and checkpoint.

### Slice 6 — Compile the first connectome-constrained model

**Goal:** Compare a small anatomical prior with the established baseline.

- Implement ports, leaky rate dynamics, explicit state, one parameter policy, compiler plan and
  compiled execution.
- Start with the smallest reviewed optic-lobe/visual-projection subgraph and a learned readout.
- Verify step/run equivalence, deterministic resets, gradients and sparse memory use.
- Compare against the baseline on identical data and metrics.

**Exit:** Accuracy, runtime, RAM/VRAM and parameter counts are recorded. Expansion is justified by
evidence, not assumed.

### Slice 7 — Run input and component ablations

**Goal:** Learn which biological and shortcut structures help.

- Compare direct spatial input, per-eye summaries, regional/learned pooling and combinations.
- Add deferred R1-R6 neural-superposition pooling as a named experiment.
- Add or remove selected visual components one at a time.
- Preserve the best-performing simple model even if a biological variant underperforms.

**Exit:** The chosen input transform and component set have an ablation table and explicit status.

### Slice 8 — Add temporal learning

**Goal:** Move from independent frames to video without changing the observation contract.

- Add sequence batching and truncated state handling.
- Establish a plain recurrent baseline.
- Compare no context with egocentric movement context.
- Train framewise localisation first, then teacher-action imitation while honey is visible.
- Evaluate temporal stability, occlusion intervals and recovery after turns.

**Exit:** A sequence model predicts localisation and/or teacher commands on unseen clips and beats
the agreed static or recurrent baseline where expected.

### Slice 9 — Expand memory and action circuits

**Goal:** Add active-search capabilities only after vision and temporal training work.

- Add visual Kenyon cells as a named expansion, then candidate mushroom-body or central-complex
  memory circuits.
- Add descending/output components and action heads.
- Keep a learned non-biological recurrent model as the control baseline.
- Evaluate memory after occlusion before enabling live control.

**Exit:** Offline predictions demonstrate useful memory and action imitation with documented
component ablations.

#### Slice 9 progress and revised order (2026-11-21)

**Done:** visual Kenyon component (284 cells) and its matched 103-neuron input group; static
ablation (neutral, as expected with per-frame resets); clip activity caching; component feature
groups for recurrent readouts.

**Finding that changes the order.** The first stateful Kenyon clip cache is invalid. The connectome
was trained on four settling steps from rest, with unsigned (all-excitatory) ReLU dynamics,
incoming-normalised weights and an uncapped global gain. Carried across a clip, activity grows from
≈0 to ≈10⁴ (some clips ≈4×10⁵), and the recurrent readouts trained on it reach 61% balanced
accuracy with training loss stuck at 0.75 (GRU control: 0.003). Those runs must not be reported as
a biological result.

Three further problems limit any memory claim:

- **Information loss through the pathway.** Readout accuracy falls from 97.5% (frontend output) to
  87.7% at the photoreceptors and ≈83% after the circuit. Readouts of Kenyon activity therefore
  start from a weaker representation than the GRU control, which reads the frontend output.
- **Inhibition is not modelled in the selected models.** Signs are off everywhere except two
  ablations, which collapse to chance because histaminergic photoreceptor drive is negative and a
  zero-baseline ReLU clips it to zero at the first synapse.
- **Too few occlusions.** Held-out splits contain 6 (level 1) and 9 (level 2) occluded frames.

**Goal for the rest of the slice:** a connectome pathway that retains the task-relevant information
(presence, bearing and, over time, target memory) rather than one that is bypassed by a shortcut.

Steps, each with its own exit:

- **9a. Framewise Kenyon baseline.** Cache activity with `--state-mode reset` (four settling
  steps from rest per frame, matching training). Compare GRUs on Kenyon activity, the matched
  103 inputs (equal parameter count), frontend output alone and frontend output + Kenyon.
  *Exit:* valid level-1 table in `docs/science/temporal-learning-results.md`, negative results
  included; the invalid carried-state runs are noted as invalid, not tabulated.
- **9b. Information ladder.** Train the same frozen-feature decoder (small MLP, same splits,
  seeds and evaluator) on activity from photoreceptors, lamina, medulla, visual projection,
  Kenyon inputs and Kenyon cells, with the frontend output as the ceiling. *Exit:* a per-stage
  table showing where presence and bearing information is lost under the current unsigned model.
  This is the baseline every later dynamics change is measured against.
- **9c. ADR 0027: signed, resting-potential, bounded dynamics.** Record: state relative to a
  resting potential so hyperpolarisation carries information; neurotransmitter signs on
  (GABA, glutamate, histamine inhibitory; glutamate as an approximation; unlabelled neurons
  default excitatory and are counted); bounded edge gains and a saturating or bounded
  activation so carried state stays bounded; a measured stability criterion (spectral radius
  or long-run activity bound) checked before training. Status labels: anatomical (wiring, signs),
  approximate (signs from predicted transmitters, dynamics), learned (gains, rest, leak).
- **9d. Stability check before retraining.** Run the new dynamics over full clips with initial
  and trained parameters; activity must stay bounded for the longest clip without resets.
  *Exit:* recorded activity bounds; no training until this passes.
- **9e. Retrain with a retention objective.** Retrain the static connectome under the new
  dynamics, adding an auxiliary loss requiring Kenyon (and optionally each stage) activity to
  decode the frozen frontend output alongside the task loss. *Exit:* rerun the 9b ladder; the
  Kenyon stage should approach the frontend ceiling, or the remaining gap is documented as
  approximation versus anatomical limit.
- **9f. Occlusion-rich clips.** Generate clips with the Honey Factory teacher-driven search clip
  and longer occlusions (implemented in M12, commit 961c09d). Requires a game run, so it needs
  explicit approval. *Exit:* held-out splits with enough occluded frames to measure memory.
- **9g. Stateful connectome memory.** Train the stable connectome across clips (truncated
  backpropagation through time) so state carries target memory in the circuit; compare with the
  GRU control, frontend + Kenyon GRU and the 9a baselines on occluded recall and bearing.
  *Exit:* a measured positive or documented negative memory result.

Only after 9g: candidate mushroom-body output or central-complex memory components, then
descending outputs and action heads, as originally planned.

#### Slice 9 status after 9a–9d (2026-11-21)

- **9a done.** Framewise Kenyon GRU readouts are in `docs/science/temporal-learning-results.md`.
  Kenyon cells decode bearing worse than their own inputs and add nothing to the frontend output.
- **9b done.** See `docs/science/pathway-information-ladder.md`. The optic-lobe pathway keeps the
  task information through the visual-projection neurons (2.8–2.9° bearing against a 2.3°
  ceiling). The static models' ~83% ceiling is their unnormalised linear readout. The loss is
  anatomical and specific to the Kenyon branch: 103 Kenyon-input neurons give 6.8–7.3°, and
  Kenyon cells give 11.7–12.2°.
- **9c done.** ADR 0027 accepted 2026-11-21, with centred graded release.
- **9d passed.** The graded signed policy keeps information as well as the legacy policy and
  stays bounded over the longest clip (|activity| ≤ 0.31, against about 4 × 10⁵ for legacy).

These results change the remaining steps:

- **9e (retention loss) is deprioritised.** The circuit already retains the information up to the
  visual-projection output. A retention loss at the Kenyon stage would fight the anatomy rather
  than fix an approximation. It is replaced by **normalised readouts** for compiled models: a
  fixed z-scoring of readout activity, applied to both policies, so that static comparisons
  measure circuits rather than activity scale.
- **9g changes circuit.** Target-direction memory is a heading and position problem, so the
  candidate is the central complex rather than the mushroom body. MaleCNS v1.0 annotates the
  anterior visual pathway and compass end to end:
  - MeTu: 13 types, 1,009 neurons
  - TuBu: 10 types, 156 neurons
  - ER ring neurons: 26 types, 282 neurons
  - EPG, PEN, PEG and Delta7: 148 neurons
  - hDelta and FB: about 790 neurons
  - PFL1–3: 50 neurons

  In the fly this circuit holds heading across darkness, and PFL3 steers toward a stored goal
  direction. It is about 2,500 neurons. Kenyon cells stay a candidate for remembering what was
  seen (presence or identity), not where.
- **9f is unchanged** and still needs explicit approval for a game run.

**Progress, 2026-11-21 late:**

- Normalised readouts are done: legacy 98.44%, graded 98.67% on stills, up from 82.66%.
- The central-complex components are added and reviewed: 352 neurons, a complete chain with no
  disconnected parts. The static graded central-complex model reaches 96.80%.
- The existing m12-2e level-3 clips give 1,005 occluded training frames, but their test split
  cannot settle memory.
- m12-2e level 4 is missing three `rgb.mkv` files.

Next is a level-1/2 pilot with search and long occlusion clips (daytime game run), then 9g.

Revised order: normalised readouts → central-complex components (named, reviewed as in Slice 4)
→ 9f occlusion-rich clips → 9g clip-trained graded central-complex memory against the GRU
control.

**Progress, 2026-11-23 (9f and 9g first pass):**

- 9f is done. Two daytime pilots produced occlusion-rich clips: batch A (levels 2–3) and batch B
  (levels 3–4).
- 9g first pass: the clip-trained graded central complex, read at its visual-projection neurons,
  holds occluded bearing at 51.6 ± 2.3° (batch A, three seeds) and 51.1° (batch B). The GRUs get
  54.9° and about 65°. Results are in `docs/science/connectome-clip-memory-results.md`.
- The compass readout does not hold direction. Yaw into PEN does not help at scale 0.1 or 0.002.
- **Diagnosis: the compass is silent.** Mean |activity| falls from about 0.004 at MeTu and TuBu
  to 0.0001 at ER and about 0.00005 at EPG, PEN and Delta7. EPG shows no bump, and its phase
  does not rotate with yaw (corr −0.02).
  - TuBu supplies only 6% of each ring neuron's input weight; recurrent ER inhibition dominates.
  - ER input to the compass is inhibitory (correct for the fly), but there is no tonic activity
    for it to sculpt, because bias and rest are frozen.
  - The linearised compass loop has spectral radius 0.54, so activity halves each transition.
    Trained gains stayed between 0.58 and 1.69, well under the cap of 4.

**9h: compass operating point (next).** Bring the compass into a regime where a bump can exist
before asking it to remember:

1. Initialise compass-to-compass gains so the linearised loop's spectral radius is about 0.95 (a
   factor of about 1.8, inside the gain cap).
2. Give compass neurons a tonic drive, either as a trained bias with its own step size or as a
   fixed resting input, so ring-neuron inhibition can carve a bump.
3. Re-scale self-motion relative to that operating level.
4. Acceptance before any localisation run: on test clips, EPG activity concentrates in two
   principal components, and its phase rotates with measured yaw (corr above 0.5). The probe
   lives in the scratchpad (`epg_bump.py`, `loop_gain.py`) and should become a CLI diagnostic.

Then rerun the compass and self-motion clip experiments against the same GRU controls.

**9h progress, 2026-11-23:**

- Eigenmodes explain the silence. With every gain at one, the compass loop's leading mode is
  uniform over EPG (eigenvalue 0.52, first-harmonic content 0.15), so raising gains uniformly
  only makes all of the compass active together.
- `compass_operating_point: "ring_attractor"` searches the 22 compass type-pair gains, inside the
  cap, for a leading first-harmonic pair at 1.05 with every other mode at most 0.8 (reached:
  0.73). It adds tonic bias of 0.02 to the 148 compass neurons. EPG ring angles come from
  MaleCNS instance names (PB glomeruli L/R 1–8). The search raises Delta7 inhibition and EPG
  output to the cap.
- Untrained, with self-motion at 0.05, EPG bump strength is 0.36 (0.2 before). Bump phase
  follows yaw with corr 0.38 and gain 0.66°/° (corr 0.02 before), and holds that through
  occlusion.
- `probe-compass` reports these measures for any clip checkpoint.
- Clip-trained ring compasses keep the ring (harmonic pair 1.05) and the bump through occlusion,
  but their compass-readout occluded bearing is 74.7–81.6°, no better than the silent compass.
  See the results doc.
- Dead reckoning sets the ceiling: last visible bearing minus true yaw gives 16.8° on batch B,
  and minus bump phase gives 72°. The gap comes from integrator noise (phase-yaw corr 0.36) and
  from having no store for the onset bearing.

**9i: goal memory and a cleaner integrator (next).**
1. Raise the bump's phase-yaw correlation above 0.8 before any further training. Candidates:
   - PEN input sign per hemisphere;
   - self-motion scale against the tonic level;
   - the strength of Delta7 inhibition.
   The acceptance test is `probe-compass` on the untrained operating point.
2. Add a goal memory, either the fan-shaped-body columnar types (hDeltas, FC) and PFL3 from
   MaleCNS, or a minimal stand-in. It latches the heading-relative honey bearing while honey is
   visible and holds it through occlusion; PFL3-style comparison with the bump then gives
   egocentric bearing.
3. Score each step against the dead-reckoning ceiling as well as the GRUs. Batch A is likely
   dominated by translation, so batch B is the primary test.

**9i progress, 2026-11-23 (step 1, integrator diagnosis):**

- Untrained ring compass, batch-B test clips, PEN yaw drive:
  - Zeroing visual input leaves EPG activity unchanged, so vision does not reach the bump.
  - Varying the self-motion scale (0.02–0.2), the PEN sign, or driving PEN_a or PEN_b alone
    leaves the per-frame phase-yaw corr at about 0.3–0.37.
- **The PB angle mapping was mirrored on the left (fixed in 3e8ffc6).** Heading runs along the
  bridge, L8..L1 then R1..R8, so Ln and R(9-n) share a heading. The MaleCNS Delta7 instances
  (`L4R5`, `L3R6`, `L1L9R8`) confirm this. The old mapping gave Ln and Rn the same angle, which
  scrambled the first-harmonic score the ring search optimises, so its best "harmonic" modes
  were only 63% first harmonic. With the fix, the search reaches a harmonic pair at 1.01 with
  every other mode at most 0.65. The 9h ring-compass runs used the mirrored mapping.
- **Constant-turn test** (bump seeded at 8 headings through a PEN kick, then turn rates from −8
  to +8°/tick; scratchpad `threshold_prototype.py`):
  - The seed works: during the kick the bump sits at the seeded heading (strength 0.6–0.74).
  - **The bump does not persist.** Once the kick stops it fades (0.64 to 0.36) and drifts within
    about 30 frames to a fixed resting pattern near 175°. The 1.01 eigenvalue is too marginal
    once tanh saturation and the tonic offset reduce the effective slope.
  - Turning shifts the phase by an offset that grows with the rate, then stops (under 1°/frame).
  - A smooth threshold on PEN release (θ 0.05–0.1) changes neither result. PEN conjunction
    cannot rotate a bump that does not persist.
- **Cause, as understood so far.** Integration needs two things: a self-sustaining bump, and PEN
  neurons gated by bump × turn. Graded signed release (ADR 0027) is linear at rest, so the
  second also needs a threshold. EPG and PEN spike in the fly. Persistence comes first.
- **Next:** stronger harmonic targets and no tonic bias, judged by persistence. Then the PEN
  threshold (ADR 0028 if it works), then FC2 (FC2A/B/C, 92 neurons in MaleCNS) as the goal
  memory read by PFL3.

#### Planned for the action head: outcome-weighted imitation (proposed 2026-11-22)

The teacher is imperfect on purpose. Drift and recovery give state coverage beyond perfect paths,
and every frame is still labelled with the teacher's corrective command. Making the teacher perfect
would remove the recovery states a student needs once it drives. Instead, score the data and let
better data count more:

- **Progress:** a potential `Φ(d)` that rises slowly with closeness to the honey and spikes near
  it, such as `1 / (1 + d)` or `exp(-d / τ)`. It is applied as the per-tick difference
  `γΦ(d') - Φ(d)` (potential-based shaping, which keeps the optimal policy unchanged).
- **Time:** a small penalty per tick.
- **Efficiency:** walked path length against the shortest walkable path from the teacher's
  `WalkGraph`.
- **Per-scene baseline:** compare each clip or segment with what its own scene allowed, as an
  advantage, so hard scenes are not mistaken for bad data.

Use the score as an imitation weight (for example `exp(advantage / β)`) or to filter the
lowest-scoring segments. Compare against unweighted imitation. Localisation targets are ground
truth and do not depend on the teacher, so this matters only for the action head. The same score
becomes the reward for Slice 10 fine-tuning.

### Slice 10 — Close the live loop

**Goal:** Drive Honey Factory through the existing lock-step protocol.

- Implement `BrainAgent`, bounded action mapping and the live client.
- Reuse the exact compiled offline model.
- Run deterministic replay before student-driven evaluation.
- Measure observation-to-action latency, failures, teacher disagreement and search success.
- Add DAgger only after stable offline imitation and replay.

**Exit:** The model completes a reproducible closed-loop evaluation without privileged inputs.

## Rough commit and pull-request organization

The slices describe scientific and architectural progress. Pull requests should remain smaller
review units: one coherent contract, structural capability or experiment at a time. Every PR must
leave the repository green, document any new public contract and avoid bundling generated datasets
or downloaded connectome artifacts into Git.

Use Conventional Commits with the `fly-brain` scope for package work and `docs` for repository
decisions. Tests normally ship in the same commit as the behavior they prove; avoid a separate
catch-all test commit unless it strengthens an already implemented contract.

### PR 1 — Accept the package and experiment boundaries

**Covers:** Slice 0.

**Purpose:** Review the decisions before they become Python APIs.

Suggested commits:

1. `docs(fly-brain): define package and observation boundaries`
2. `docs(fly-brain): record first task and evaluation criteria`

Expected contents:

- ADR for `fly-brain`, performance-over-biological-purity and metadata separation;
- confirmed first connectome source and modeled input boundary;
- first localisation metrics and dataset split policy;
- no production code.

### PR 2 — Establish the package and canonical data contracts

**Covers:** Slice 1.

**Purpose:** Add the smallest installable package and prove that still images and videos share one
input representation.

Suggested commits:

1. `build(fly-brain): add workspace package`
2. `feat(fly-brain): add sensor and feature schemas`
3. `feat(fly-brain): add sequence and example contracts`
4. `test(fly-brain): cover validation and privileged-state separation`

Keep this PR free of connectome, training-framework and real-dataset dependencies.

### PR 3 — Adapt MineSophila data into brain examples

**Covers:** Slice 2.

**Purpose:** Demonstrate the real boundary from `fly-vision` and Honey Factory artifacts without
starting model work.

Suggested commits:

1. `feat(fly-brain): load cached receptor observations`
2. `feat(fly-brain): adapt still and clip context`
3. `feat(fly-brain): derive honey localisation targets`
4. `test(fly-brain): verify fixture alignment and input isolation`

If raw clip decoding or `fly-vision` integration becomes substantial, split it into a follow-up PR
rather than enlarging the contract PR.

### PR 4 — Add the framework-independent connectome graph

**Covers:** Slice 3 through the synthetic fixture.

**Purpose:** Validate the structural model without external data acquisition or ML code.

Suggested commits:

1. `feat(fly-brain): add connectome release and graph tables`
2. `feat(fly-brain): add connectome loader protocol`
3. `test(fly-brain): add synthetic connectome fixtures`
4. `feat(fly-brain): report graph statistics and memory estimates`

### PR 5 — Import the first pinned MaleCNS graph

**Covers:** The real-data remainder of Slice 3.

**Purpose:** Isolate licensing, checksums, external formats and scale from graph-interface review.

Suggested commits:

1. `docs(fly-brain): record MaleCNS source and acquisition`
2. `feat(fly-brain): load pinned MaleCNS tables`
3. `test(fly-brain): verify MaleCNS loader invariants`

Do not commit the downloaded release. Tests should use a small derived fixture with documented
provenance, while an opt-in smoke check validates a locally available full release.

### PR 6 — Resolve and assemble named network components

**Covers:** Slice 4.

**Purpose:** Deliver the modular network pattern before executable neural dynamics.

Suggested commits:

1. `feat(fly-brain): add composable neuron selectors`
2. `feat(fly-brain): resolve named network components`
3. `feat(fly-brain): assemble immutable brain networks`
4. `feat(fly-brain): describe component connectivity`
5. `test(fly-brain): pin first visual component manifest`

The resolved component fixture should be small enough to review and should name the connectome
checksum from which it was produced.

### PR 7 — Train and evaluate the first localisation baseline

**Covers:** Slice 5.

**Purpose:** Prove the learning and evaluation path before connectome dynamics add complexity.

Suggested commits:

1. `docs(fly-brain): record framework benchmark and selection`
2. `feat(fly-brain): add localisation task and metrics`
3. `feat(fly-brain): add linear receptor baseline`
4. `feat(fly-brain): add experiment manifests and checkpoints`
5. `feat(fly-brain): train and evaluate unseen scenes`

Add the MLP in this PR only if it remains a small extension. Otherwise use a separate baseline PR.
Do not commit large checkpoints; publish or store them through a documented artifact path.

### PR 8 — Compile the first connectome-constrained model

**Covers:** Slice 6.

**Purpose:** Add one minimal executable circuit and compare it with the established baseline.

Suggested commits:

1. `feat(fly-brain): add input ports and leaky dynamics`
2. `feat(fly-brain): add connectome parameter policy`
3. `feat(fly-brain): compile sparse brain models`
4. `test(fly-brain): verify state and sequence execution`
5. `perf(fly-brain): benchmark first visual circuit`

If compiler/backend work is large, split the first three commits into a compiler PR and keep the
scientific comparison in the next PR. Do not mix unrelated performance rewrites into the initial
correctness patch.

### PR 9 — Compare input shortcuts and circuit expansions

**Covers:** Slice 7.

**Purpose:** Treat biological assumptions and shortcuts as explicit controlled experiments.

Suggested commits or small follow-up PRs:

1. `feat(fly-brain): add per-eye and regional pooling`
2. `feat(fly-brain): add neural-superposition adapter`
3. `feat(fly-brain): add visual component ablations`
4. `docs(fly-brain): report localisation ablations`

Prefer one PR per materially different transform when implementation or interpretation is large.
The final report can compare results across those merged experiment PRs.

### PR 10 — Add sequence learning and movement context

**Covers:** Slice 8.

**Purpose:** Extend the proven frame path to clips without changing canonical observations.

Suggested commits:

1. `feat(fly-brain): batch variable-length receptor sequences`
2. `feat(fly-brain): add recurrent baseline`
3. `feat(fly-brain): add egocentric movement context`
4. `feat(fly-brain): add teacher-action imitation task`
5. `test(fly-brain): cover temporal reset and masking`
6. `docs(fly-brain): report temporal and context ablations`

Split localisation-over-time and action imitation into separate PRs if either needs new dataset
contracts or substantial evaluation work.

### PR 11 — Expand candidate memory and output circuits

**Covers:** Slice 9.

**Purpose:** Add one named biological expansion at a time, always against the recurrent baseline.

Expected follow-up PR sequence rather than one large PR:

1. Visual Kenyon-cell component and offline ablation (steps 9a–9b).
2. Signed resting-potential dynamics, stability check and retention retraining (9c–9e).
3. Stateful connectome memory on occlusion-rich clips (9f–9g).
4. Candidate mushroom-body output or central-complex memory component.
5. Candidate descending-neuron component and action head.
6. Consolidated offline memory/action report.

Example titles:

- `feat(fly-brain): add visual Kenyon-cell component`
- `feat(fly-brain): add candidate heading-memory circuit`
- `feat(fly-brain): map descending outputs to actions`

Do not merge a new circuit merely because it is biologically interesting; require a measured task
result or a clearly documented negative result.

### PR 12 — Connect the trained model to the live loop

**Covers:** Slice 10.

**Purpose:** Keep deployment and environment interaction separate from model research.

Suggested commits:

1. `feat(fly-brain): add stateful inference agent`
2. `feat(fly-brain): map predictions to bounded commands`
3. `feat(fly-brain): connect to Honey Factory control loop`
4. `test(fly-brain): replay deterministic control episodes`
5. `docs(fly-brain): report closed-loop evaluation`

DAgger should be a later PR after deterministic replay and student-driven evaluation are stable.

### Pull-request review gates

Every PR should state:

- the preserved and new public contracts;
- the smallest relevant validation command and its result;
- whether any input is model-visible, teacher-only, privileged or provenance;
- whether each new transform/component is anatomical, approximate, learned or a shortcut;
- data, connectome and artifact provenance;
- measured accuracy/runtime/memory for experimental PRs;
- deferred work and known negative results.

Avoid mixing these concerns in one PR unless one cannot be tested meaningfully without the other:

- external data acquisition and graph API design;
- observation contracts and ML-framework selection;
- correctness and broad performance refactoring;
- static localisation and live control;
- multiple independent biological circuit expansions.

Branch names can follow the review unit, for example:

```text
feat/fly-brain-contracts
feat/fly-brain-connectome-graph
feat/fly-brain-components
feat/fly-brain-localisation-baseline
feat/fly-brain-first-circuit
feat/fly-brain-sequence-learning
```

## Initial comparison ladder

Keep every rung runnable under the same evaluator:

1. Direct receptor input + linear head.
2. Direct receptor input + small MLP.
3. Direct receptor input + plain recurrent model for video.
4. Per-eye or regional pooled input + MLP.
5. Small connectome visual subgraph + learned head.
6. Connectome model + pooled shortcut features.
7. Connectome model + visual Kenyon-cell expansion.
8. Connectome memory/output expansion versus the plain recurrent control.

## Review decisions before Slice 0 completes

1. Exact first task metrics: binary presence alone is insufficient; choose bearing/elevation and
   per-receptor coverage metrics.
2. First structural source: confirm MaleCNS v1.0 and the local data acquisition path.
3. First modeled boundary: inject at visual columns/lamina inputs or represent photoreceptor
   neurons explicitly.
4. Legal initial context: previous command, executed command, measured ego-motion and contact.
5. First parameter policy: learned readout only or a small trainable circuit under fixed sparsity.
6. Framework benchmark candidates on ROCm and CPU.

## Rules for later extraction

Keep one distribution until a boundary is demonstrated. Extract another package only when it has
an independent consumer or release cadence, materially different installation dependencies, or a
real dependency-cycle problem. Do not split packages merely to mirror conceptual layers.
