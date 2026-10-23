# honey-factory implementation roadmap

Updated 2026-10-23. M0–M5 are implemented: the rendering-feasibility spike;
configuration, data contract and deterministic ID/seed/split rules; owned dataset
worlds with arena resets and the synchronous environment coordinator; the level-1
perspective vertical slice from scene generation to a validated dataset; and the dataset
runner with level-2 and level-3 generators, storage and cancellation handling and an
independent outside validator; and 310-degree panoramic capture. Later features are not
implemented. The accepted behaviour and
success criteria live in the [package specification](spec.md).

## Scope

This roadmap delivers the phase-1 Minecraft dataset generator and environment:

- Difficulty levels 1–5.
- 90-degree perspective and 310-degree panoramic RGB capture.
- Pixel-aligned visible-honey masks and metadata.
- Deterministic scene reset, generation, validation and replay.
- Synchronous internal environment controls and basic movement actions.
- Local dataset inspection.

It does not include the collaborator's fly-vision conversion, connectome/network,
training, a live model connection, level-6 active-search datasets, mining, or the
external TCP adapter. Those remain separate integrations or extensions.

## Implementation principles

- Deliver small vertical slices and pass each exit gate before widening scope.
- Keep logical-server world mutation separate from client-only rendering/readback.
- Keep scene generation independent of capture projection and dataset storage.
- Keep Minecraft/Fabric integration in Java. Use Rust only for a measured processing
  bottleneck with a demonstrated benefit greater than its integration cost.
- Add no compatibility abstraction until a second implementation actually needs it.
- Never test destructive arena operations in an ordinary save.

## Milestones

| ID | Milestone | Depends on | Status |
|---|---|---|---|
| M0 | Minecraft 26.3 rendering feasibility | — | Exit gate passed; [findings](m0-rendering-feasibility.md), ADR 0005 accepted |
| M1 | Core configuration and data contracts | M0 findings | Exit gate passed; ADR 0006 |
| M2 | Owned world and synchronous coordinator | M1 | Exit gate passed; ADR 0007 |
| M3 | Level-1 perspective vertical slice | M0–M2 | Exit gate passed; [findings](m3-level1-vertical-slice.md), ADR 0008 |
| M4 | Dataset runner and levels 1–3 | M3 | Exit gate passed; [findings](m4-dataset-runner.md), ADR 0009 |
| M5 | 310-degree panoramic capture | M3–M4 | Exit gate passed; [findings](m5-panoramic-capture.md), ADR 0010 |
| M6 | Constructed and natural scenes | M4–M5 | Planned |
| M7 | Movement actions and environment completion | M2–M6 | Planned |
| M8 | Dataset inspector and exact replay | M4–M7 | Planned |
| M9 | Acceptance matrix and production pilot | M0–M8 | Planned |

### M0 — Minecraft 26.3 rendering feasibility

Resolve the highest-risk technical assumptions before designing a large framework.

Deliverables:

- Confirm the pinned Gradle build, tests and development client launch from a clean
  checkout.
- Map the exact 26.3/Fabric hooks for world extraction/drawing, render targets,
  opaque/cutout depth access, baked single-block rendering and asynchronous or
  synchronous texture readback.
- Create a temporary command-driven fixture with one honey block and a fixed camera.
- Capture one RGB image, one target-only visible mask and one target-only unoccluded
  mask through Blaze3D/Fabric abstractions, without raw OpenGL calls.
- Record behaviour with improved transparency enabled and disabled. Select and pin
  the initial dataset setting; do not attempt glass/water occlusion.
- Measure one capture's time and GPU-memory behaviour at a small resolution and the
  proposed perspective default.

Exit gate:

- Open, half-occluded and fully opaque-occluded honey fixtures satisfy the relevant
  mask criteria at one fixed perspective view.
- Repeating a frozen fixture ten times produces identical masks.
- The implementation path and required mixins/access widening are documented. If
  shared-depth target rendering is not viable, stop and compare a scoped ID-buffer
  pass against changing target block choice before building later milestones.

### M1 — Core configuration and data contracts

Build Minecraft-independent rules that can be unit-tested quickly.

Deliverables:

- Immutable resolved configuration types for dataset, arena, difficulty, capture,
  viewpoints, lighting, quotas, splits and output storage.
- Validation with actionable errors and no world/file mutation on invalid input.
- Versioned deterministic seed derivation for layout, target and viewpoints.
- Metadata record types for `dataset.json`, `scenes.jsonl` and `samples.jsonl`, plus
  target-status derivation and coordinate/unit helpers.
- Language-neutral JSON Schemas and shared valid/invalid contract fixtures consumed
  independently by the mod and the outside tools.
- Canonical configuration and layout serialization definitions for hashing.
- Dataset ID, scene ID, sample ID and shard algorithms.
- Unit tests for defaults, invalid ranges, seed independence, schema round trips,
  target invariants, paths and hashes.

Exit gate:

- A pure-Java test constructs a resolved configuration, deterministically derives a
  scene and sample descriptor, serializes all record types, reads them back and
  rejects every specified inconsistency.

### M2 — Owned world and synchronous coordinator

Implement safe world lifecycle and the internal API skeleton before bulk generation.

Deliverables:

- Persistent dataset-world ownership marker, owned arena bounds and staging area.
- Dedicated void arena dimension for levels 1–4.
- Template restore followed by deterministic block-by-block scene deltas.
- Explicit world/player baseline state with setting snapshot and normal restoration.
- Coordinator states, operation IDs, readiness conditions, settling ticks, timeouts
  and one-operation-at-a-time enforcement.
- Working `reset`, `setViewpoint` and `status`; `observe` initially delegates to the
  M0 capture fixture and `step` may reject unsupported actions explicitly.
- Debug commands for ownership/status, reset and viewpoint placement.

Exit gate:

- Rebuilding fixed templates in a fresh owned world reproduces layout checksums.
- Bounds/sentinel tests prove reset does not modify outside geometry.
- Commands refuse an ordinary save and invalid state transitions fail clearly.

### M3 — Level-1 perspective vertical slice

Turn the rendering spike into the first complete, maintainable sample path.

Deliverables:

- Production `CaptureService` boundary with a `PerspectiveProjection` implementation.
- Configurable perspective dimensions and exact camera/projection metadata.
- RGB, visible-mask and internal unoccluded-mask capture at the same camera/tick.
- Target counts, status, ratio, bounding box and centroid derived from finalized
  mask pixels.
- Atomic PNG writing, SHA-256 descriptors and append-after-files sample commit.
- Level-1 generator with target-present and target-absent scenes and configurable
  viewpoints.
- Perspective mask fixtures for open, partial/full occlusion, near/far, image-edge,
  stair, slab, fence and cutout-foliage cases.

Exit gate:

- A small command-generated level-1 dataset reaches exact quotas, validates with
  zero errors and can be regenerated with matching layouts and masks.
- Every perspective-applicable row of the mask prototype gate passes; panorama seam
  rows remain for M5.
- Use captured evidence to set initial visibility-ratio ranges for levels 3–5 before
  accepting their generators.

### M4 — Dataset runner and levels 1–3

Generalize the vertical slice without coupling difficulties to rendering.

Deliverables:

- Bulk in-mod generation runner with lifecycle, exact quotas, retry limits,
  rejection reasons, disk preflight/reserve and terminal failure handling.
- Deterministic scene-level split assignment and leakage validation.
- Level-2 clutter/distractor generator and level-3 partial-hiding generator.
- Versioned templates and per-scene layout/target/viewpoint parameters.
- Complete manifest finalization and standalone dataset validator.
- An independent outside-tool validator for completed or failed datasets; it shares
  schemas and fixtures with the mod, not the mod's Java validation implementation.
- Generation/status/cancel commands with cancellation semantics documented and
  tested at safe operation boundaries.

Exit gate:

- Perspective smoke datasets for levels 1–3 reach their exact accepted-scene and
  target-balance quotas, satisfy visibility rules and pass integrity/leakage checks.
- Cancellation produces either a valid terminal result or a diagnostic `failed`
  dataset; it never presents partial output as `complete`.

### M5 — 310-degree panoramic capture

Add the second projection behind the shared capture boundary.

Deliverables:

- Six square off-screen cube-face captures at one frozen world/camera state.
- Versioned cube-face orientation and equirectangular 310-by-160-degree mapping with
  the 50-degree rear blind sector.
- Configurable face and output dimensions.
- RGB filtering and categorical nearest-neighbour mask reprojection.
- Panorama projection metadata and atomic multi-face failure handling.
- Direction, blind-sector, seam, pole, target-edge and resolution fixtures.

Exit gate:

- Panorama acceptance checks and mask seam criteria pass at default and one
  non-default resolution.
- The same scene/camera can switch capture mode without changing its layout or pose.
- Time, RAM, VRAM and source-face retention costs are benchmarked before deciding
  whether raw cube faces remain a debug-only option.

Implementation language for reprojection:

- Implement cube-face-to-equirectangular reprojection in Java first. Java is accepted
  only if, on the reference setup at the default sizes (six 1024 × 1024 faces into a
  2480 × 1280 panorama), it meets every criterion below:
  - RGB plus mask reprojection takes at most 50 ms per panorama, median of at least
    100 panoramas after warm-up, single-threaded on the client thread or one worker.
  - That time is at most 25 % of the whole per-sample capture time (six face renders
    and readbacks, reprojection, PNG encoding and settling).
  - It allocates no per-pixel objects. The sampling tables are reused across samples,
    and steady-state heap growth per sample is zero.
- If any criterion fails, implement reprojection in Rust behind the same
  `CaptureProjection` boundary, with the same golden fixtures and an ADR recording the
  measurements.
- Both implementations must produce byte-identical masks on the panorama fixtures. RGB
  may differ only by the documented filter rounding.

### M6 — Constructed and natural scenes

Complete the mandatory difficulty generators after both projections work.

Deliverables:

- Level-4 constructed templates/generators using stairs, slabs, fences and
  vegetation without introducing deferred glass/water semantics.
- Dataset-owned normal Overworld source dimension for level 5.
- Deterministic source-patch selection, stable patch IDs and split grouping.
- Copy of block states, biomes and required block-entity data into the void arena,
  followed by lighting readiness and target insertion only in the destination.
- Configurable core bounds, height/context padding and patch reuse.
- Detection policy for naturally occurring target blocks inside sampled regions.

Exit gate:

- Both modes generate valid level-4 and level-5 smoke datasets with no visible void
  boundary at configured render distance.
- Source terrain remains unchanged, copied-layout checksums reproduce, and source
  patch grouping passes split-leakage validation.
- Benchmark results establish initial region, padding and reuse defaults.

### M7 — Movement actions and environment completion

Finish the model-independent environment required for later integration; do not yet
create level-6 training episodes.

Deliverables:

- Working synchronous `observe` and `step` implementations.
- TURN, LOOK, MOVE_FORWARD, WAIT and STOP with documented sign/range conventions,
  requested/completed values and tick-based timing.
- Input release, collision/physics observation and configurable vanilla auto-jump
  with previous-setting restoration.
- Ordered operation results tied to scene, tick and capture IDs.
- Command-driven action diagnostics. Mining remains an explicit unsupported action.

Exit gate:

- Every environment API acceptance fixture passes, including concurrent-request
  rejection, timeouts, input release and vanilla auto-jump over supported blocks.
- The dataset runner and commands call the same internal coordinator rather than
  duplicating reset/capture/action logic.

### M8 — Dataset inspector and exact replay

Make validation practical before running expensive acceptance datasets.

Deliverables:

- Local, read-only dataset browser with lazy image loading, RGB/mask/overlay modes,
  native zoom, scene viewpoint grid, metadata display and specified filters.
- Seeded random and stratified review selection.
- External sidecar review records with pass/fail/uncertain outcomes and manifest
  identity; no completed-dataset mutation.
- Integrity failures shown as errors rather than skipped samples.
- Safe in-game scene replay, viewpoint cycling and fresh capture comparison.
- Operator documentation for generate, validate, inspect and replay workflows.

Exit gate:

- Every inspector/replay success criterion passes.
- A reviewer can find a sample by ID, inspect its entire scene, record a sidecar
  decision and reproduce the scene in Minecraft without manually editing files.

### M9 — Acceptance matrix and production pilot

Run the package-wide definition of done without adding new features during the run.

Deliverables:

- Clean-build and automated-fixture report.
- Ten-scene level/capture-mode matrix: 10 datasets and 800 expected base samples.
- Validated level-5 panoramic pilot: 1,000 scenes and 8,000 expected base samples.
- Seeded stratified human audit of 200 pilot samples with all findings resolved.
- Timing, throughput, rejection, compression, disk, RAM and VRAM report for this
  machine.
- Frozen resolved configurations, manifest hashes and inspector sidecar report.

Exit gate:

- Every mandatory package criterion in the specification passes.
- Any throughput target adopted from the benchmark is documented for the next run;
  performance work does not retroactively weaken correctness gates.

## System scaffold and responsibility boundaries

The dataset system has two executable sides joined by files conforming to one
versioned contract. Keep these as separate packages so offline inspection never
needs to launch Minecraft or load Fabric classes.

```text
                         versioned JSON/JSONL/PNG contract
                                      │
        ┌─────────────────────────────┴─────────────────────────────┐
        │                                                           │
packages/honey-factory/                              packages/honey-factory-tools/
Java 25 + Fabric                                     Python 3.13+
live Minecraft mutation/capture                     offline validate/inspect/review
        │                                                           │
        └──────────── writes immutable dataset ────────────────────>┘
```

The initial integration is the dataset directory, not a socket or Python-to-Java
call. Bulk generation stays inside the mod. The outside tools can inspect a dataset
while it is `generating` in explicitly read-only mode, but training accepts only
`complete`. A future live controller uses the deferred adapter and does not replace
the file contract.

### Repository scaffold

Create paths only when their milestone begins; this is the intended ownership map,
not a request to add empty directories immediately.

```text
packages/
  honey-factory/                       # Fabric mod and in-game runtime
    src/main/.../honeyfactory/
      config/                          # resolution and validation of run config
      contract/                        # Java records matching public schemas
      dataset/                         # IDs, writer, lifecycle, in-mod validation
      scene/                           # pure scene plans and difficulty generators
      world/                           # server-side ownership/reset/apply/copy
      environment/                     # API types and operation state machine
      command/                         # thin in-game/debug adapters
    src/client/.../honeyfactory/client/
      runtime/                         # client coordinator/composition root
      capture/                         # capture service and projection strategies
      render/                          # render targets, annotation pass, readback
    src/main/resources/
      assets/honey_factory/shaders/    # only shaders owned by the mod

  honey-factory-tools/                 # outside, Minecraft-free Python package
    src/honey_factory_tools/
      contract/                        # schema loading and typed records
      validate/                        # independent offline validator
      inspect/                         # local browser/index/overlay logic
      review/                          # immutable sidecar review records
      report/                          # acceptance and capacity summaries

schemas/honey-factory/<major.minor>/   # language-neutral public data contract
  dataset.schema.json
  scene.schema.json
  sample.schema.json

tests/
  honey-factory/                       # fast Java unit/contract tests
  honey-factory-tools/                 # fast Python tool tests
  integration/                         # cross-language shared contract fixtures
  e2e/                                 # Minecraft-required milestone/acceptance runs
```

The exact Python UI/library choice is an M8 implementation decision. Start with the
smallest local-only tool that supports the required browser experience. Do not add a
web framework merely to establish the scaffold. The Python package joins the root uv
workspace when M1 introduces shared contract tests.

### Responsibility map

| Component | Owns | Must not own |
|---|---|---|
| Public schemas | Field names/types, required/optional rules, enums and schema compatibility | Minecraft implementation classes or UI behaviour |
| `config` | User input, defaults, validation and immutable resolved configuration | World mutation, rendering or file writing |
| `scene` | Deterministic `ScenePlan`/`ViewpointPlan` creation from config and seeds | Live world access, render calls or output paths |
| `world` | Dataset ownership, bounds, templates, block application, natural-patch copy and readiness facts on the logical server | Camera projection, PNGs or quota policy |
| `environment` | Operation requests/results, state transitions, actions and synchronization contracts | Low-level render APIs or difficulty-specific placement |
| client `runtime` | The one end-to-end coordinator that sequences server work, settling, capture and dataset commit | Reimplementing scene rules, rendering internals or schemas |
| `capture` | Camera views, projection selection, atomic observation and capture result buffers | Scene difficulty rules, dataset paths or world mutation |
| `render` | Blaze3D/Fabric pipelines, depth, target geometry, cube faces, reprojection and GPU readback | Dataset lifecycle, splits or actions |
| `dataset` | IDs, paths, atomic files, hashes, JSONL append, manifest lifecycle and in-mod final validation | World or renderer state |
| `command` | Parse/operator feedback and calls into the same runtime API used by generation | Business logic or direct world/render manipulation |
| Outside validator | Independent schema/integrity/leakage/target-invariant checks from files | Trusting in-memory mod state or repairing canonical data |
| Outside inspector | Lazy indexing, image/mask display, filtering and review UX | Mutating a completed dataset or reconstructing Minecraft itself |
| In-game replay | Rebuild exact recorded scenes and viewpoints in a verified owned world | Offline review storage or silently approximating missing versions |

The outside validator intentionally does not call the Java validator. Both consume
the same schemas and golden valid/invalid fixtures; independent implementations can
catch writer/validator bugs that shared code would reproduce.

### Dependency direction

Dependencies point toward contracts and plans, never back from low-level adapters
into orchestration:

```text
config ──> scene plans ──> world executor ──┐
                                            ├──> client runtime ──> dataset writer
environment types/actions ──────────────────┤             │
capture projection ──> render/readback ─────┘             └──> immutable files
                                                               │
                                      outside validator/inspector <── schemas
```

Enforce these rules:

- Scene generators return immutable plans; they never place blocks themselves.
- World code applies plans and reports facts; it never requests screenshots.
- Render code consumes a capture request and target descriptor; it never decides
  difficulty, split, quota or file path.
- The dataset writer consumes immutable completed records/buffers; it never queries
  Minecraft state to fill missing metadata.
- Only the client runtime coordinator knows the whole live workflow.
- Commands and future transports are adapters over that coordinator, not alternate
  implementations.
- Outside tools depend on public schemas and dataset files only. They do not depend
  on the mod JAR, Minecraft JAR or a running game.
- Avoid a shared `utils` layer. Put helpers with the component that owns their
  semantics, and promote only genuine public contracts.

### Runtime and thread ownership

Minecraft imposes real execution boundaries; represent each crossing as an explicit
request/result rather than sharing mutable objects across threads.

| Execution context | Responsibilities |
|---|---|
| Logical server thread | Validate ownership, mutate/reset the world, apply actions and report authoritative ticks/player/world state. |
| Client/main coordinator | Own the operation state machine, dispatch server work, wait for readiness, request captures and commit accepted results in order. |
| Render thread | Create/use render targets, render RGB/annotation passes, reproject panoramas and copy completed pixel buffers out of GPU-owned state. |
| Bounded I/O worker | Encode PNGs, calculate SHA-256, atomically move files and append metadata without blocking rendering indefinitely. |
| Outside tool process | Read finalized files, build disposable indexes/caches and write reviews only to the configured sidecar root. |

Apply backpressure when the bounded I/O queue is full; do not keep capturing and
accumulating full-resolution buffers. A sample becomes committed only after the
server snapshot, capture metadata, RGB and mask all refer to the same operation ID
and the finalized files have been validated. Panorama faces remain one render-thread
operation even though PNG encoding may occur afterward.

The common Fabric initializer remains safe on a dedicated server, but capture and
generation commands report that a local client/render runtime is required. Never
reference client-only Minecraft classes from `src/main`.

### Incremental scaffold plan

- **M0:** Keep the render spike isolated under client code. Do not create every
  package above or pretend the spike is the final capture API.
- **M1:** Add public schemas, shared fixtures, the Java contract/config types and a
  minimal `honey-factory-tools` CLI capable of validating schema fixtures.
- **M2:** Add scene plans, world executor, environment state machine and client
  runtime coordinator boundary.
- **M3–M5:** Add capture/render implementations and dataset writer behind those
  boundaries; complete the independent offline validator.
- **M6–M7:** Add generators, natural-patch execution and actions without changing
  the public dataset contract unless a versioned migration is justified.
- **M8:** Grow the outside tool into the inspector/reviewer and add in-game replay as
  a separate mod adapter.
- **M9:** Freeze versions for acceptance; do not restructure packages during the
  production pilot.

## Decision checkpoints

| Checkpoint | Evidence needed | Decision unlocked |
|---|---|---|
| After M0 | Depth sharing, target-model rendering, OIT and readback results | Final annotation-pass integration and any required mixins/access widening |
| After M3 | Pixel-count distributions across distance/occlusion fixtures | Initial level 3–5 visibility-ratio ranges |
| After M5 | Panorama quality, capture time and VRAM at configured resolutions | Final default face size and raw-face retention policy |
| After M6 | Patch-copy time, lighting time, boundary visibility and disk impact | Initial level-5 region, padding and reuse defaults |
| After M9 | End-to-end throughput, storage and rejection measurements | Production batch sizing and any justified optimization/Rust work |

## First implementation task

Start M0 only: verify the current build/client, inspect the mapped 26.3 rendering
classes, and implement the smallest fixed-camera annotation-render fixture. Do not
start generic scene generators or the panorama until open, partial and fully hidden
honey masks pass the M0 exit gate.
