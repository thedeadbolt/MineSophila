# fly-brain 9i step 2: fan-shaped-body goal memory — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the connectome remember the honey's direction through occlusion. FC2 holds a goal
in compass coordinates, the PFN → hΔ → FC2 vector path writes it while honey is visible, and
PFL3 compares it with the EPG heading.

**Architecture:** Staged as the compass integrator was (ADR 0028): fit each sub-circuit on a
synthetic task to reach a working regime, then clip-train the whole brain end to end.
1. FC2 and FB5A hold a seeded goal bump.
2. PFL3 decodes goal minus heading.
3. PFN gain modulation by an egocentric vector writes heading + bearing into FC2.
4. Full clip training.

The one non-connectome element is a learned projection from visual-projection activity to a
two-component egocentric vector that modulates PFN. It stands in for the unmodelled noduli
route, and its status is approximation.

How the four candidate goal-setting mechanisms are covered:
1. **FB tangentials.** FB5A is included as FC2's loop partner, for holding. A separate
   learned "write now" gate is deferred: the egocentric vector is already zero when no honey is
   seen, so writing stops by itself.
2. **Mushroom body via MBON→FB.** Deferred to #29.
3. **Plasticity in the FB.** Deferred to #29. Here the goal is held only by activity.
4. **Vector circuitry.** This is the write mechanism (Task 6).

**Tech Stack:** Python 3.14, JAX, NumPy, the uv workspace (`uv run pytest tests/fly-brain`), ruff
and pyright.

**Spec:** `docs/superpowers/plans/2026-11-19-fly-brain-foundation.md`, sections "9i step 2 plan:
the fan-shaped-body goal input to PFL3", "9i step 2 findings, 2026-11-23" and "End of 9i:
viewer deliverables". ADR 0028 is the precedent for operating-point fits. Issues: #30 (trace),
#32 (dual compass) and #33 (flow diagram).

## Global Constraints

- Never launch the game (gametest, benchmark or pilot). This plan needs only cached clips.
- No privileged input: nothing in the model may read ground-truth bearing, presence or
  visibility. Synthetic fits may use them as *targets*, and as the egocentric input to the
  write fit only (Task 6). Clip training learns that input from visual-projection activity.
- Configs without fan-shaped-body components must keep their graph hash, parameter shapes and
  outputs bit-for-bit. Existing checkpoints must load.
- Cell types to add:
  - FC2A, FC2B, FC2C (92 neurons);
  - hDeltaA–hDeltaM (189);
  - PFNa, PFNd, PFNm_a, PFNm_b, PFNv (165);
  - FB5A (4).
- The bump gain `k` comes from the fitted compass. Goal phase is in bump units: goal =
  EPG phase + `k`·bearing at the last visible frame.
- Do not hard-code heading angles. PB angles come from `pb_glomerulus_angle`, and FB angles from
  `connectivity_angles` (FC2 from PFN input, hΔ from PFN + FC2, `passes=2`).
- Commits use Conventional Commits scoped `fly-brain` and end with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Do not push.
- New ADR number: **0030**. 0029 is taken on the visualizer branch.

## Review Focus

1. **Old checkpoints without the new parameter** must load and run unchanged. The test goes in
   Task 3.
2. **A config without fan-shaped-body components** must compile to the same graph hash,
   parameter count and outputs as before. The test goes in Task 2.
3. **Clips where honey is never visible**, or is visible only after a long occlusion, must give
   a masked goal target with no NaN loss. The test goes in Task 6.
4. **FC2 or hΔ neurons with no angled input** (NaN angle) must be ignored by goal decoding, not
   propagate NaN. The test goes in Task 2.
5. **Egocentric input with no visual_projection component selected** must be rejected by
   config validation with a clear message. The test goes in Task 3.

---

### Task 1: Generic sub-circuit simulator

Factor what `integrator.simulate` does so the goal fits can reuse it. The integrator tests pin
current behaviour.

**Files:**
- Create: `packages/fly-brain/src/fly_brain/connectome/subcircuit.py`
- Modify: `packages/fly-brain/src/fly_brain/connectome/integrator.py` (build on the new module;
  public names unchanged)
- Modify: `packages/fly-brain/src/fly_brain/cli.py` (`_compass_circuit` builds via the new
  extractor)
- Test: `tests/fly-brain/test_subcircuit.py`

**Interfaces:**
- Produces:
  - `SubCircuit` (frozen dataclass) with fields:
    - `cell_types: NDArray[str_]`, `neuron_index: NDArray[int64]` (indexes into the compiled
      brain);
    - `source`, `target`, `group: NDArray[int32]`, `weight: NDArray[float32]`,
      `group_pairs: tuple[str, ...]`, `global_groups: NDArray[int64]`;
    - `gate: NDArray[bool_]`, `leak: float`, `maximum_gain: float`.
  - `extract_subcircuit(brain: CompiledBrain, types: NDArray[str_], member_types: Sequence[str], gated_types: Sequence[str] = ()) -> SubCircuit`:
    edges with both ends inside; group pairs named `"SOURCE>TARGET"`.
  - `run_subcircuit(circuit, raw_gains: Array, bias: Array, threshold: Array, external: Array, *, transitions: int, sharpness: float) -> Array`.
    `external` is (B, T, N) and the result is the state (B, T, N) after each frame. Release is
    graded, or thresholded where `gate`, matching `LeakyRateDynamics.thresholded_release`.

- [ ] **Step 1: Write the failing tests** in `tests/fly-brain/test_subcircuit.py`:
  - `test_extract_keeps_only_internal_edges_and_names_pairs`: a 4-neuron fixture brain (types
    A, A, B, C; edges A→B, B→A, B→C, C→A) with member types (A, B) keeps 3 edges,
    `group_pairs == ("A>A", "A>B", "B>A")` restricted to the present pairs, and
    `neuron_index == [0, 1, 2]`.
  - `test_run_matches_a_hand_rolled_two_neuron_chain`: neuron 0 is driven externally by 1.0,
    with edge 0→1 of weight 0.5 and gain 2.0 (raw = logit(2/4)) and leak 0.5. After one
    transition, state[1] equals `0.5 * (0.5*tanh(2*s0_prev)*1.0)` (write the two-step
    expectation explicitly).
- [ ] **Step 2: Run** `uv run pytest tests/fly-brain/test_subcircuit.py -q`. Expected: FAIL
  (module missing).
- [ ] **Step 3: Implement** `subcircuit.py`. Make `integrator.simulate` call `run_subcircuit` and
  decode EPG from its states. `CompassCircuit` keeps its fields; build it with
  `extract_subcircuit(..., gated_types=PEN_TYPES)` plus angles and sides.
- [ ] **Step 4: Run** `uv run pytest tests/fly-brain/test_subcircuit.py tests/fly-brain/test_integrator.py -q`.
  Expected: all pass. Then run the canonical-fit regression: build the compass circuit through
  `_compass_circuit` and check that `group_pairs` and the edge count are unchanged (22 pairs).
- [ ] **Step 5: Commit** `refactor(fly-brain): share a sub-circuit simulator between operating-point fits`.

### Task 2: FB5A component and heading angles for every central-complex neuron

**Files:**
- Modify: `packages/fly-brain/src/fly_brain/connectome/central_complex.py`: add
  `GOAL_TANGENTIAL_TYPES = ("FB5A",)` and a component `"goal_tangentials"` (role MEMORY), and
  append it to `FAN_SHAPED_BODY_COMPONENTS` and `fan_shaped_body_cell_types()`.
- Modify: `packages/fly-brain/src/fly_brain/cli.py`: add `_heading_angles` and use it in
  `trace-clip` for `pb_angle`.
- Modify: `packages/fly-brain/src/fly_brain/connectome/trace.py`: add `goal_phase_strength`.
- Test: `tests/fly-brain/test_central_complex.py`, `tests/fly-brain/test_trace.py`,
  `tests/fly-brain/test_connectome_clips.py`.

**Interfaces:**
- Consumes: `pb_glomerulus_angle`, `connectivity_angles` (compass.py).
- Produces:
  - `_heading_angles(brain: CompiledBrain, graph: ConnectomeGraph, root: Path) -> NDArray[float64]`:
    radians per compiled neuron. It uses the PB instance angle for every neuron whose instance
    parses (EPG, PEN, PEG, PFN, PFL). FC2, hΔ and FB5A then get `connectivity_angles` with
    `passes=2`. Everything else is NaN. `_compass_layout` is unchanged, because the ring search
    depends on EPG-only angles.
  - `goal_phase_strength(activity: NDArray, angles: NDArray) -> tuple[NDArray, NDArray]`: like
    `bump_phase_strength`, but over the finite-angle subset of the given neurons.

- [ ] **Step 1: Write the failing tests:**
  - `test_goal_tangentials_component_selects_fb5a` (test_central_complex.py): `"FB5A"` is in
    `fan_shaped_body_cell_types()` and not in `central_complex_cell_types()`.
    `FAN_SHAPED_BODY_COMPONENTS[-1] == "goal_tangentials"`.
  - `test_goal_phase_ignores_neurons_without_angles` (test_trace.py): 9 neurons, 8 on a ring
    plus one NaN angle with high activity. The phase equals the 8-neuron phase within 1e-6, and
    the result is finite.
  - `test_configs_without_fan_shaped_body_keep_their_graph` (test_connectome_clips.py): with the
    existing compass fixture config, the compiled `neuron_ids`, `edge_gain_count` and one
    forward output match a stored expectation computed before the change. Pin them as literals
    in the test.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement** the component, `_heading_angles` and `goal_phase_strength`.
  `trace-clip` writes `pb_angle` from `_heading_angles`. When `goal_memory` is selected it adds
  `goal_phase` and `goal_strength` arrays.
- [ ] **Step 4: Run** `uv run pytest tests/fly-brain -q`. Expected: all pass.
- [ ] **Step 5: Commit** `feat(fly-brain): add FB5A and heading angles for fan-shaped-body neurons`.

### Task 3: Egocentric vector input to PFN, plus ADR 0030

**Files:**
- Modify: `packages/fly-brain/src/fly_brain/connectome/compiler.py`:
  - `BrainParameters` gains `egocentric_weights: jax.Array = jnp.zeros((0, 2))`;
  - `BrainModelSpec` gains `egocentric: EgocentricProjection | None = None`;
  - `CompiledBrain` gains `egocentric_source_indexes`, `egocentric_target_indexes` and
    `egocentric_target_offsets` (all optional);
  - `initialize_parameters` draws `egocentric_weights` as N(0, 1e-3) when the projection is set;
  - `step` adds the egocentric drive.
- Create: `EgocentricProjection` in `packages/fly-brain/src/fly_brain/connectome/ports.py` with
  `source_ids: NDArray[int64]`, `target_ids: NDArray[int64]`,
  `target_offsets: NDArray[float64]` (radians) and `status: BiologicalStatus`.
- Modify: `packages/fly-brain/src/fly_brain/models/connectome.py`: `ConnectomeModelConfig`
  gains `egocentric_input: Literal["none", "pfn_vector"] = "none"`. Validation requires
  `"vector_inputs"` and `"visual_projection"` in components. The message for a missing
  visual_projection is `"pfn_vector egocentric input reads visual_projection"`.
- Modify: `packages/fly-brain/src/fly_brain/cli.py`: `_build_connectome` builds the projection.
  The source is the visual_projection neurons and the targets are the PFN neurons. Offsets come
  from `_pfn_offsets`.
- Create: `docs/adr/0030-fan-shaped-body-goal-memory.md`, and add a line to the README index.
- Test: `tests/fly-brain/test_compiler.py`, `tests/fly-brain/test_compass.py`,
  `tests/fly-brain/test_checkpoints.py`.

**Interfaces:**
- Produces:
  - The egocentric drive in `CompiledBrain.step`:
    ```python
    vector = release[:, egocentric_source_indexes] @ parameters.egocentric_weights  # (B, 2)
    drive = drive.at[:, egocentric_target_indexes].add(
        vector[:, :1] * cos(offsets)[None] + vector[:, 1:] * sin(offsets)[None]
    )
    ```
  - `_pfn_offsets(brain, graph, angles) -> NDArray[float64]`: per PFN, the weighted circular mean
    of (target angle − own PB angle) over its edges onto angled hΔ and FC2 neurons. The
    connectome shows about ±45° by hemisphere. Record the measured means in ADR 0030.

- [ ] **Step 1: Write the failing tests:**
  - `test_egocentric_projection_adds_offset_weighted_drive` (test_compiler.py): 1 source and 2
    targets with offsets 0 and π/2. Weights [[1, 0]] with source release r should drive the
    targets by (r, 0). With weights [[0, 1]] the drive is (0, r).
  - `test_brains_without_egocentric_projection_ignore_the_parameter` (test_compiler.py):
    `initialize_parameters().egocentric_weights.shape == (0, 2)` and the step output is
    unchanged.
  - `test_old_checkpoints_load_without_egocentric_weights` (test_checkpoints.py): save a
    checkpoint dict with only the four original fields. `BrainParameters(**values)` works and
    `egocentric_weights.shape == (0, 2)`.
  - `test_pfn_vector_input_requires_its_components` (test_compass.py): the config with
    `egocentric_input="pfn_vector"` but no `visual_projection` raises the message above;
    without `vector_inputs` it raises.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement** compiler, config and CLI wiring. Write ADR 0030 (Proposed):
  - context: the 9i step 2 findings and the PFL3/FC2 input tables;
  - decision: FB components, connectivity angles, the PFN egocentric projection (approximation:
    it stands in for the noduli route), the staged fits, and clip training;
  - consequences: what is learned, what is fixed, and that only activity holds the goal within
    a clip (plasticity is #29).
- [ ] **Step 4: Run** `uv run pytest tests/fly-brain -q`, `uv run ruff check packages/fly-brain tests/fly-brain`
  and `uv run pyright packages/fly-brain/src tests/fly-brain`. Expected: tests pass, ruff is
  clean, and pyright shows only the 6 known `test_male_cns` errors.
- [ ] **Step 5: Commit**:
  - `feat(fly-brain): add a learned egocentric vector input to PFN neurons`
  - `docs(fly-brain): propose ADR 0030 on fan-shaped-body goal memory`

### Task 4: Goal-hold fit (stage 1)

**Files:**
- Create: `packages/fly-brain/src/fly_brain/connectome/goal.py`
- Modify: `packages/fly-brain/src/fly_brain/models/connectome.py`: add
  `goal_operating_point: Literal["none", "fitted"] = "none"`, `goal_gains: dict[str, float]`
  and `goal_type_bias: dict[str, float]`, validated like `compass_gains`.
- Modify: `packages/fly-brain/src/fly_brain/cli.py`:
  - `_train_connectome_clips` applies the fitted goal after the fitted compass, through
    `type_pair_raw_gains`;
  - new command `fit-goal-memory`, with the same arguments as `fit-compass-integrator` plus
    `--compass-fit PATH` (the integrator JSON).
- Create: `packages/fly-brain/configs/goal-memory-fit-v1.json`
- Test: `tests/fly-brain/test_goal.py`

**Interfaces:**
- Consumes: `SubCircuit`, `extract_subcircuit` and `run_subcircuit` (Task 1);
  `goal_phase_strength` (Task 2); `_heading_angles` (Task 2).
- Produces:
  - `GoalHoldConfig` (frozen) with `steps`, `learning_rate`, `frames=120`, `kick_frames=10`,
    `kick=0.3`, `minimum_strength=0.4`, `transitions_per_frame=4`, `batch_size=32` and `seed`.
  - `fit_goal_hold(circuit: SubCircuit, angles: NDArray, initial_gains: NDArray, config: GoalHoldConfig) -> GoalFit`.
    The circuit is FC2 + FB5A + hΔ. It learns internal group gains and a bias per type. The loss
    is 1 − cos(FC2 phase − seed) after the kick, plus relu(0.4 − strength).
  - `GoalFit` with `gains: dict[str, float]`, `type_bias: dict[str, float]`,
    `hold_error_deg: float` and `history`.
  - `fit-goal-memory` writes `{"format": "fly-brain-goal-memory-v1", "model_overrides": {"goal_operating_point": "fitted", "goal_gains", "goal_type_bias"}, "hold": {...}, "pfl3": {...}, "write": {...}}`.
    The `pfl3` key is filled by Task 5 and `write` by Task 6.

- [ ] **Step 1: Write the failing tests** (`tests/fly-brain/test_goal.py`, using a fixture of
  8 FC2-like neurons on a ring with neighbour excitation and one inhibitory tangential, in the
  style of `test_integrator.ring_circuit`):
  - `test_goal_hold_fit_lowers_hold_error`: on a fixed validation batch, the hold error after
    40 steps is below the error at start.
  - `test_goal_fit_returns_every_internal_pair`: `set(fit.gains) == set(circuit.group_pairs)`
    and every gain is in (0, 4).
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement** `goal.py`, the config fields and the command.
- [ ] **Step 4: Run the tests.** Expected: PASS. Then run the command on batch B:
  ```bash
  D=/mnt/fastvault/minesophila/derived/brain-localization-v3
  uv run fly-brain fit-goal-memory $D/temporal-level3-occ-b-s1-ports-v1.fly-brain.npz \
    --layout-cache $D/phase1-l1-panorama-9a4fbed92104.fly-brain.npz \
    --connectome-root /mnt/fastvault/minesophila/connectomes/male-cns-v1.0 \
    --config packages/fly-brain/configs/connectome-clips-central-complex-goal-memory-v1.json \
    --initial-checkpoint $D/connectome-level1-central-complex-graded-frozen-rest-v1.checkpoint.npz \
    --compass-fit $D/compass-integrator-occ-b-s1-v1.json \
    --fit-config packages/fly-brain/configs/goal-memory-fit-v1.json \
    --out $D/goal-memory-occ-b-s1-v1.json
  ```
  Acceptance: `hold.hold_error_deg` < 20 after 100 frames, with strength ≥ 0.4. Create the
  config referenced here, with the compass-integrator config's model block plus the four FB
  components and `readout_components: ["steering", "goal_memory"]`.
- [ ] **Step 5: Commit** `feat(fly-brain): fit the fan-shaped body to hold a goal direction`.

### Task 5: PFL3 compares goal with heading (stage 2)

**Files:**
- Modify: `packages/fly-brain/src/fly_brain/connectome/goal.py`
- Modify: `packages/fly-brain/src/fly_brain/cli.py` (`fit-goal-memory` fills `pfl3`)
- Test: `tests/fly-brain/test_goal.py`

**Interfaces:**
- Produces: `goal_heading_decoding(states_pfl3: NDArray (S, N), offset: NDArray (S,)) -> dict[str, float]`.
  It is a ridge decoder (λ = 1e-3) of (cos, sin) of the goal − heading offset from PFL3 states,
  with a 75/25 split over trials. It returns `{"error_deg", "trials"}`.
- Probe: run compass (fitted) + FB (fitted) + PFL3, seeding EPG at h and FC2 at g by kicks
  (64 random pairs). Read PFL3 at frame 30.

- [ ] **Step 1: Write the failing test** `test_goal_heading_decoding_recovers_a_planted_offset`:
  synthetic PFL3 states = [cos(o), sin(o), noise·0.01] give `error_deg` < 2. With states that
  are pure noise, `error_deg` > 60.
- [ ] **Step 2: Run it.** Expected: FAIL.
- [ ] **Step 3: Implement** the decoder and the probe.
- [ ] **Step 4: Run it** and the command. Acceptance: `pfl3.error_deg` < 20. If not, extend the
  hold fit's learned groups with the `FC2*>PFL3` and `hDelta*>PFL3` pairs, with the decoding
  loss as a second term. Record what was needed in the results doc.
- [ ] **Step 5: Commit** `feat(fly-brain): measure PFL3 decoding of goal minus heading`.

### Task 6: Write the goal through PFN → hΔ → FC2 (stage 3)

**Files:**
- Modify: `packages/fly-brain/src/fly_brain/connectome/goal.py`
- Modify: `packages/fly-brain/src/fly_brain/cli.py` (`fit-goal-memory` fills `write`; the
  overrides gain the write-stage gains and `egocentric_scale`)
- Modify: `packages/fly-brain/configs/goal-memory-fit-v1.json` (stage settings)
- Test: `tests/fly-brain/test_goal.py`

**Interfaces:**
- Consumes: the fitted compass (frozen), the Task 4 gains (trainable start), and the PFN
  offsets from Task 3.
- Produces:
  - `goal_targets(epg_phase: NDArray (B, T), bearing: NDArray (B, T), visible: NDArray (B, T), bump_gain: float) -> tuple[NDArray, NDArray]`:
    the target FC2 phase, equal to `epg_phase + bump_gain * bearing` at the last visible frame,
    carried forward, and a mask that is false until the first visible frame.
  - `fit_goal_write(...) -> GoalFit`:
    - sub-circuit: compass + PFN + hΔ + FC2 + FB5A;
    - inputs: real train-split yaw, and the true egocentric vector
      `(cos(k·b), sin(k·b))·visible` injected through the PFN offsets (fit only);
    - learns PFN→hΔ, PFN→FC2, hΔ→FC2 and FB-internal gains, FB biases and the egocentric scale;
    - loss: 1 − cos(FC2 phase − target), masked.

- [ ] **Step 1: Write the failing tests:**
  - `test_goal_targets_carry_the_last_sighting`: with visible [F, T, T, F, F] and bearing
    [0, 10, 20, 30, 40] degrees, EPG phase constant at 0 and k = 0.5, the targets are
    [masked, 5, 10, 10, 10] degrees.
  - `test_goal_targets_mask_clips_never_visible`: all-false `visible` gives an all-false mask,
    and the masked loss is finite (0.0).
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement** it. Use `k` = the compass fit's measured gain, the slope of
  `constant_turn.velocity_deg_per_frame` against rate over |rate| ≤ 4.
- [ ] **Step 4: Run** the tests and the command. Acceptance on batch-B test clips, in the
  sub-circuit: goal-minus-heading error on occluded frames is < 30° (the dead-reckoning ceiling
  is 16.8°).
- [ ] **Step 5: Commit** `feat(fly-brain): fit the PFN vector path that writes the goal`.

### Task 7: Clip training, evaluation and the end-of-9i deliverables

**Files:**
- Modify: `packages/fly-brain/configs/connectome-clips-central-complex-goal-memory-v1.json`:
  merge the goal-memory fit overrides and set `egocentric_input: "pfn_vector"`.
- Modify: `docs/science/connectome-clip-memory-results.md` (new section) and the foundation
  plan (9i status).
- Modify: `packages/fly-brain/src/fly_brain/cli.py`: the `trace-clip` header gains `bump_gain`.
- Test: none new; this is runs and documents.

- [ ] **Step 1: Train** batch B and batch A level 3, detached (`setsid nohup`), from the
  level-1 checkpoint, in the `queue8` command form with the new config. Check that memory is
  free (about 10 GB per job) before starting.
- [ ] **Step 2: Score** `test_occluded` and `test_visible` from each report. Run `probe-compass`
  and the scratch `eval_full.py`, both pointed at the trained checkpoint.
- [ ] **Step 3: Compare** against the GRUs (batch B: 64.6/65.3°), the projection readout
  (51.1°), the fitted-integrator compass run (queue8), and the 16.8° ceiling. Write the results
  section with the table and the limits.
- [ ] **Step 4: Trace** one test clip with an occlusion. Check `goal_phase` holds while
  `bump_phase` turns. Comment the field list on #30 and #32.
- [ ] **Step 5: Commit** `docs(fly-brain): add fan-shaped-body goal memory results`. Then update
  ADR 0028 and ADR 0030 to Accepted if the user agrees.
