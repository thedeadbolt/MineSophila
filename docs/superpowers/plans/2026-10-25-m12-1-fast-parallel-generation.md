# M12-1 Fast and Parallel Generation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make still-dataset generation and control-loop captures several times faster on
one machine, and run one generation job across several game instances that merge into a
single dataset, without lowering the data's quality for the fly.

**Architecture:**

- A benchmark gametest measures every phase and keeps reference output, so each later
  change is measured and quality-checked against it.
- In-process speed-ups each sit in one place:
  - frame pacing during generation;
  - change-aware readiness in `EnvironmentRuntime`;
  - parallel and asynchronous capture in `CaptureService` and `PanoramaSampling`;
  - GPU reprojection behind a setting;
  - several captures per control tick;
  - differential resets in `ArenaExecutor`.
- Parallelism across instances: the generator learns to run a slice of the scene slots, a
  Python merge tool joins the slices, and `honey-factory-tools generate --instances N`
  launches and supervises the game clients.

**Tech Stack:** Java 25, Fabric (Minecraft 26.3), Blaze3D (render pipelines and GPU
buffers, no raw OpenGL), JUnit 5, Fabric client gametests, Python 3 (standard library
`subprocess` and `concurrent.futures`) with pytest.

**Spec:** `docs/superpowers/specs/2026-10-25-m12-1-fast-parallel-generation-design.md`.
Read it first, including its quality bar and exit gate. Follow `AGENTS.md`.

## Global Constraints

- **Quality bar** (the spec's):
  - masks stay exact and match their pixels and recorded statistics;
  - no capture with missing sections, half-loaded terrain, or unsettled light, fog or
    weather;
  - fixed-seed reference output before and after each change is equivalent: identical
    where content is unchanged, RGB within the recorded tolerance otherwise;
  - determinism and exact replay within the new version.
- **Keeping a change:** an optimisation stays only with a measured gain and passing quality
  checks.
- **Versions:** a change to how new datasets are generated bumps the recorded algorithm or
  renderer version. M1–M10 datasets, hashes and replay stay valid.
- **User options:** frame-rate cap, vsync and auto-jump are restored after generation and
  control sessions.
- **Platform:** Fabric and Blaze3D only, no raw OpenGL; world changes on the server side; no
  client classes in `src/main`.
- **The second game instance:**
  - the production batch may be running from `../MineSophila-aim`; never run Gradle there;
  - gametests run from this checkout as a second instance;
  - long runs happen in the daytime only, never overnight.
- **Commits:** Conventional Commits scoped `honey-factory` or `honey-factory-tools`,
  ending with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
  Decisions go in ADR 0019.

## Review Focus

1. **A skipped readiness wait captures a stale or half-compiled frame.** This can happen
   when the camera crosses a section boundary within a tick, or a chunk arrives late.
   Expect the full readiness check whenever the cheap test cannot prove nothing changed.
   Task 3 tests a capture right after crossing a section boundary and compares it with a
   fully waited capture.
2. **GPU reprojection disagrees with the CPU at the image's seams and edges.** That covers
   the ±155° sides, the −70° floor and face boundaries. Expect masks identical pixel for
   pixel. Task 6 compares whole masks, including the edge rows and columns.
3. **One instance of `--instances N` dies mid-run.** Expect the job to report which slice
   failed, mark the merged dataset failed and leave the finished slices untouched. Task 11
   kills an instance and checks the result.
4. **Slices that do not add up.** If two instances generated overlapping or missing slots,
   or the configurations differ, expect the merge to refuse with a clear error and never
   produce a valid-looking dataset. Task 10 tests overlapping, missing and mismatched
   slices.
5. **The user's options after a crash.** Expect frame cap, vsync and auto-jump restored on
   leaving the world, as auto-jump already is. Task 2 tests leaving the world mid-run.

---

### Task 1: Benchmark and reference workloads

**Files:**
- Create: `tests/honey-factory/gametest/java/io/github/thedeadbolt/honeyfactory/gametest/M12BenchmarkTest.java`, registered in `tests/honey-factory/gametest/resources/fabric.mod.json`; it runs only when named
- Modify: `packages/honey-factory/build.gradle` (forward `honeyfactory.benchmarkLabel`)

**Interfaces:**
- Produces:
  - `build/m12-benchmark/<label>/` holding:
    - one still dataset per level, panorama mode, 20 scenes × 8 viewpoints, at the default
      resolution;
    - a 200-tick control-loop run on a level-1 scene with seed 11;
    - `summary.json`.
  - Per phase in `summary.json`: count, mean, p50 and p95 for reset, readiness wait,
    render, readback, reprojection, write, validate and control step, plus samples per
    hour per level.
  - Reference output for later quality comparisons: the masks, plus SHA-256s of RGB and
    masks per sample and per tick.
  - A helper `BenchmarkCompare.compare(Path before, Path after)` in the gametest source
    set that reports identical masks, the RGB tolerance result per sample, and failures.

- [ ] **Step 1:** Write `M12BenchmarkTest` and `BenchmarkCompare`. Compare first expects the
  `before` folder. On a first run with no `before` it records the baseline; with one, it
  compares and fails on any quality failure.
- [ ] **Step 2:** Run the baseline:
  `./gradlew runClientGameTest -Dhoneyfactory.gametests=M12Benchmark -Dhoneyfactory.benchmarkLabel=baseline`.
  Expected: PASS, writing `build/m12-benchmark/baseline/summary.json`.
- [ ] **Step 3:** Run again with label `repeat` and compare it with `baseline`. Expected:
  masks identical, and RGB identical except the known rain cases. This calibrates the
  tolerance. Record the tolerance as `BenchmarkCompare.RGB_TOLERANCE`: the maximum
  per-channel difference and the fraction of differing pixels.
- [ ] **Step 4: Commit.** Message: `test(honey-factory): add the M12 benchmark and reference workloads`.

### Task 2: Frame pacing during generation

**Files:**
- Create: `packages/honey-factory/src/client/java/io/github/thedeadbolt/honeyfactory/client/runtime/FramePacing.java`
- Modify: `EnvironmentRuntime.java` (apply it for dataset runs, `observeAll` and control sessions; restore it on end and on disconnect)
- Test: extend `M12BenchmarkTest` with a pacing check, and add to `M11ControlLoopTest` a leave-the-world check for options

**Interfaces:**
- Produces: `final class FramePacing` with `static void uncap(Minecraft)`, which saves and
  then sets the frame limit to unlimited and vsync off, and `static void restore(Minecraft)`.
  Calls are idempotent and reference-counted, so nested users are safe.

- [ ] **Step 1:** Write the tests:
  - while a control session runs, the client's frame limit is unlimited and vsync is off;
  - after `controlEnd` and after leaving the world mid-session, the user's saved values
    are back (Review Focus 5).
- [ ] **Step 2:** Run them and see them fail.
- [ ] **Step 3:** Implement `FramePacing` and call it from the runtime.
- [ ] **Step 4:** Run the tests and the benchmark (label `pacing`) with a comparison
  against `baseline`. Expected: quality PASS, and the readiness wait p50 drops.
- [ ] **Step 5: Commit.** Message: `perf(honey-factory): uncap the frame rate while generating`.

### Task 3: Change-aware readiness

**Files:**
- Modify: `EnvironmentRuntime.java`: `PoseSequence` and the control step's ready check
- Create: `packages/honey-factory/src/client/java/io/github/thedeadbolt/honeyfactory/client/runtime/RenderChanges.java`
- Test: `tests/honey-factory/gametest/java/io/github/thedeadbolt/honeyfactory/gametest/M12ReadinessTest.java`

**Interfaces:**
- Produces: `final class RenderChanges` with `void mark(Minecraft)`, called at a ready frame,
  and `boolean unchangedSince(Minecraft)`. It returns true only when the camera's section
  is the one marked, no section has been queued for compilation since, no chunk arrived or
  unloaded since, and no light update is pending.
- Rule: when `unchangedSince` is true, the stable-frame wait is skipped; otherwise the full
  existing check runs.

- [ ] **Step 1:** Write `M12ReadinessTest`:
  - capture a pose, move within the same section and capture again: the second capture's
    readiness takes no stable-frame wait, and its masks equal a fully waited capture of the
    same pose;
  - move across a section boundary within one step: the full check runs, and the capture
    equals a fully waited one (Review Focus 1);
  - place a block in view: the full check runs.
- [ ] **Step 2:** Run it and see it fail.
- [ ] **Step 3:** Implement `RenderChanges` and use it in both ready checks.
- [ ] **Step 4:** Run `M12Readiness`, `M3`, `M5` and `M11`, then the benchmark (label
  `readiness`) against `baseline`. Expected: all PASS, and the readiness wait drops again.
- [ ] **Step 5: Commit.** Message: `perf(honey-factory): skip readiness waits when nothing changed`.

### Task 4: Parallel CPU reprojection

**Files:**
- Modify: `packages/honey-factory/src/main/java/io/github/thedeadbolt/honeyfactory/capture/PanoramaSampling.java`
- Test: `tests/honey-factory/java/io/github/thedeadbolt/honeyfactory/capture/PanoramaSamplingTest.java`
  (extend the existing test, or create it if absent)

**Interfaces:**
- Produces: `CapturedImages reproject(List<CapturedImages> faces)` keeps its signature. Rows
  are split across a shared daemon pool of `min(availableProcessors - 1, 16)` threads, and
  the output is byte-identical to the sequential version.

- [ ] **Step 1:** Write a test that reprojects random faces at 2480 × 1280 with 1024² faces
  sequentially (a package-private `reprojectSequential` kept for reference) and in
  parallel, and asserts identical RGB and masks.
- [ ] **Step 2:** Run it and see it fail.
- [ ] **Step 3:** Implement the row split.
- [ ] **Step 4:** Run the test and the benchmark (label `cpu-parallel`). Expected:
  identical output, and reprojection p50 drops.
- [ ] **Step 5: Commit.** Message: `perf(honey-factory): reproject panoramas on worker threads`.

### Task 5: Asynchronous readback

**Files:**
- Modify: `packages/honey-factory/src/client/java/io/github/thedeadbolt/honeyfactory/client/capture/CaptureService.java`
- Test: `M12BenchmarkTest` comparison; `M5PanoramaGateTest` and `M3MaskGateTest` must pass

**Interfaces:**
- Produces: `CaptureService.capture(...)` keeps its signature and output. Internally, view
  `n+1` renders while view `n` copies into a mapped GPU buffer, through Blaze3D's buffer
  copy and mapping. The readback of all views is waited for before `assemble`.

- [ ] **Step 1:** Run `M5` and `M3` before the change to confirm they pass.
- [ ] **Step 2:** Implement the overlap.
- [ ] **Step 3:** Run `M3`, `M5` and the benchmark (label `async-readback`) against
  `baseline`. Expected: PASS, identical masks, and readback p50 drops.
- [ ] **Step 4: Commit.** Message: `perf(honey-factory): overlap capture readback with rendering`.

### Task 6: GPU reprojection experiment

**Files:**
- Create: `packages/honey-factory/src/client/java/io/github/thedeadbolt/honeyfactory/client/capture/GpuReprojection.java`, plus a shader under `src/client/resources/assets/honey_factory/shaders/`
- Modify: `CaptureService.java` (use it when `EnvironmentSettings.gpuReprojection()` is true; on any GPU error, log it and fall back to the CPU path)
- Modify: `EnvironmentSettings.java` (add `boolean gpuReprojection`, default false until Step 5 decides)
- Test: `tests/honey-factory/gametest/java/io/github/thedeadbolt/honeyfactory/gametest/M12GpuReprojectionTest.java`

**Interfaces:**
- Produces: `CapturedImages GpuReprojection.assemble(PanoramicProjection projection, List<GpuTexture> faces)`.
  RGB uses bilinear sampling within each face; masks use nearest sampling of the exact
  directions `PanoramaSampling` uses. It reads back only the output image.

- [ ] **Step 1:** Write `M12GpuReprojectionTest`. For fixtures in all levels, plus poses
  that put the target on the ±155° sides, near the −70° floor and across cube-face
  boundaries:
  - CPU and GPU masks are identical in every pixel (Review Focus 2);
  - RGB is within `RGB_TOLERANCE`;
  - a forced shader failure falls back to the CPU and still returns correct images.
- [ ] **Step 2:** Run it and see it fail.
- [ ] **Step 3:** Implement `GpuReprojection` and the fallback.
- [ ] **Step 4:** Run the test and the benchmark twice: label `gpu`, and `cpu-parallel`
  again for a fair comparison.
- [ ] **Step 5:** Decide. If GPU masks are always identical and the time per capture is
  clearly lower (at least 25% less capture time per pose), set the default to true.
  Otherwise keep the default false and record why. Either way, record the numbers for the
  findings.
- [ ] **Step 6: Commit.** Message: `perf(honey-factory): reproject panoramas on the GPU` (or `test(honey-factory): measure GPU reprojection` if it is not kept).

### Task 7: Several captures per control tick

**Files:**
- Modify: `EnvironmentRuntime.java`:
  - `controlReset` gains `int framesPerTick` (1–8);
  - `controlStep` captures at partial ticks `k/framesPerTick` for `k = 1..framesPerTick`,
    after one readiness check;
  - `ControlResult` gains `List<CaptureService.Capture> frames`, where the last frame
    equals today's single capture.
- Modify: `TickStepper.java` (it settles the interpolation only after the last frame)
- Modify: `CaptureService.java` (capture at a given partial tick)
- Test: extend `M11ControlLoopTest`; `M11ExitTest` must pass with `framesPerTick` 1

**Interfaces:**
- Produces: `controlReset(ArenaLayout, ScenePlan, CameraPose, CaptureProjection, long seed, int framesPerTick)`.
  The old signature delegates with 1. `ControlResult.frames()` holds `framesPerTick`
  captures; the server's protocol is unchanged in this task, since M12-2 decides how clips
  carry frames.

- [ ] **Step 1:** Write tests:
  - with 3 frames per tick during a full-speed turn, the frames' yaws step evenly (about
    1/3 of the tick's turn each);
  - the third frame equals a 1-frame step's capture of the same tick;
  - masks of all frames are binary and match their pixels.
- [ ] **Step 2:** Run them and see them fail.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run the `M11` tests and the benchmark's control workload with 3 frames
  per tick, and record the per-tick time.
- [ ] **Step 5: Commit.** Message: `feat(honey-factory): capture several frames per control tick`.

### Task 8: Differential resets

**Files:**
- Modify: `packages/honey-factory/src/main/java/io/github/thedeadbolt/honeyfactory/world/ArenaExecutor.java`
  (`reset` writes only blocks that differ from the arena's current layout and resends only
  changed chunks)
- Test: `M2`, `M6` and `M8` gametests must pass; add to `M12BenchmarkTest` a check that two
  consecutive resets to the same plan change no blocks

**Interfaces:**
- Produces: `ResetResult` keeps its fields. Its layout hash is computed from the world after
  the reset, as now, so a differential reset that missed a block fails its existing hash
  check.

- [ ] **Step 1:** Write the consecutive-reset check, and a check that a reset between two
  different level-4 scenes yields the second scene's layout hash.
- [ ] **Step 2:** Run it and see the "changes no blocks" assertion fail.
- [ ] **Step 3:** Implement the difference against the last written layout. Fall back to a
  full rewrite when the last layout is unknown, after a claim or a world load.
- [ ] **Step 4:** Run `M2`, `M6`, `M8` and the benchmark (label `diff-reset`). Expected: PASS
  and a lower reset p50.
- [ ] **Step 5: Commit.** Message: `perf(honey-factory): reset only what changed`.

### Task 9: Slices of a dataset run

**Files:**
- Modify: `packages/honey-factory/src/main/java/io/github/thedeadbolt/honeyfactory/generation/DatasetGenerator.java`
  (run only the slots of the given slice)
- Modify: `ConfigResolver.java` and `ResolvedConfig.java`: `output.slice: {"index": i, "count": n}`.
  It is an output setting, so it stays out of the resolved configuration's hash, like
  `output.root`.
- Modify: `DatasetWriter` and the manifest: a slice writes lifecycle `complete` with a new
  manifest member `slice: {index, count, slots}`, plus its own world name.
- Modify: the schema `schemas/honey-factory/1.0/dataset.schema.json` (optional `slice`), and both validators (a slice validates on its own; quota checks use its slot list)
- Test: `tests/honey-factory/java/io/github/thedeadbolt/honeyfactory/generation/DatasetGeneratorTest.java`

**Interfaces:**
- Produces:
  - slot `s` of the plan belongs to slice `s % count == index`;
  - a slice's records are exactly the full run's records for its slots, with the same
    scene IDs, seeds and splits;
  - the slice's dataset directory is `<root>/<dataset id>.slice-<index>-of-<count>/`.

- [ ] **Step 1:** Write tests with the scripted environment:
  - slices 0 and 1 of 2 together contain exactly the full run's scene records, with no
    overlap;
  - the configuration hash equals the full run's;
  - each slice passes the Java validator.
- [ ] **Step 2:** Run them and see them fail.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run the JUnit suite and the Python tools suite.
- [ ] **Step 5: Commit.** Message: `feat(honey-factory): generate a slice of a dataset's scenes`.

### Task 10: Merging slices

**Files:**
- Create: `packages/honey-factory-tools/src/honey_factory_tools/merge.py`, with CLI `merge-slices OUTPUT SLICE...`
- Test: `tests/honey-factory-tools/test_merge.py`

**Interfaces:**
- Produces: `merge(slices: list[Path], output: Path) -> Path`. It:
  - checks that all slices share one configuration hash and slice count, with no missing or
    duplicate slots (Review Focus 4);
  - moves images into place;
  - writes indexes ordered by scene index and attempt;
  - writes a manifest whose counts, integrity digests and dataset ID equal what a single run
    would record, with no `slice` member, and a `worlds` list naming each slice's world for
    level-5 replay.
  - On any check failure it raises `MergeError` and writes nothing.

- [ ] **Step 1:** Write tests on a fixture made by slicing the shared mini dataset:
  - merging both slices gives a dataset equal to the original in indexes and manifest
    content, and it validates;
  - a missing slice, a duplicate slice and a slice from another configuration each raise
    `MergeError`, and the output folder does not exist afterwards.
- [ ] **Step 2:** Run them and see them fail.
- [ ] **Step 3:** Implement. Also extend the Java `ReplayScene` lookup to choose the
  scene's world from `worlds` (with a JUnit test).
- [ ] **Step 4:** Run the tools suite, ruff, pyright and JUnit.
- [ ] **Step 5: Commit.** Message: `feat(honey-factory-tools): merge dataset slices`.

### Task 11: `generate --instances N`

**Files:**
- Create: `packages/honey-factory-tools/src/honey_factory_tools/orchestrate.py`, with CLI `generate CONFIG --instances N [--root DIR]`
- Create: a gametest entry `GenerationJobTest` that reads a job file (configuration path, slice, world name) from `-Dhoneyfactory.job` and generates it; it runs only when named
- Modify: `packages/honey-factory/build.gradle`:
  - forward `honeyfactory.job`;
  - make the client run folder configurable (`-Dhoneyfactory.runDir`), so instances do not
    share one.
- Test: `tests/honey-factory-tools/test_orchestrate.py` (fake launcher), and the gametest exit run in Task 12

**Interfaces:**
- Produces: `generate(config: Path, instances: int, root: Path, launcher: Launcher) -> Path`.
  - It writes one job file per slice and starts one game client per job through
    `Launcher`.
  - The default launcher is `GradleLauncher`, which runs
    `./gradlew runClientGameTest -Dhoneyfactory.gametests=GenerationJob -Dhoneyfactory.job=<file> -Dhoneyfactory.runDir=<dir>`.
    If concurrent Gradle runs in one project conflict, a spike in Step 3 decides and
    ledgers the alternative: a launch script produced once by a Gradle task.
  - It waits for all instances, reports per-slice progress, then calls `merge`.
  - A failed instance marks the job failed, keeps the finished slices and names the failed
    one (Review Focus 3).

- [ ] **Step 1:** Write `test_orchestrate.py` with a fake launcher that creates slice
  fixtures:
  - success merges and validates;
  - one failing instance gives a failed job naming its slice, with the other slices kept;
  - `--instances 1` works without merging.
- [ ] **Step 2:** Run it and see it fail.
- [ ] **Step 3:** Spike: start two `runClientGameTest` processes from this checkout with
  different `honeyfactory.runDir`, and record whether they run concurrently. Pick the
  launcher accordingly.
- [ ] **Step 4:** Implement the orchestrator, the job gametest and the Gradle changes.
- [ ] **Step 5:** Run the tools suite, ruff and pyright.
- [ ] **Step 6: Commit.** Message: `feat(honey-factory-tools): run a generation job on several game instances`.

### Task 12: Exit run, decision record and findings

**Files:**
- Create: `docs/adr/0019-fast-parallel-generation.md`; update `docs/adr/README.md`
- Create: `packages/honey-factory/docs/m12-1-fast-parallel-generation.md`
- Modify: `packages/honey-factory/docs/roadmap.md` (split M12 into M12-1 and M12-2 and mark M12-1), `packages/honey-factory/docs/operating.md` (a section on `generate --instances N`), `packages/honey-factory/docs/spec.md` (the generation-time decisions)

- [ ] **Step 1:** Final benchmark (label `final`) against `baseline`. Record every phase,
  before and after, plus samples per hour per level and the control-loop time per tick
  with 3 frames.
- [ ] **Step 2:** Exit run:
  - `honey-factory-tools generate` with `--instances 2` for a 40-scene level-1 dataset and
    for a 24-scene level-5 dataset;
  - compare each with a single-instance run of the same configuration: equal layouts, seeds
    and masks;
  - both validators pass;
  - a level-5 scene from each instance replays exactly in its own world.
  Record the time against one instance. Daytime only.
- [ ] **Step 3:** Run `./gradlew build`, the full gametest suite and the Python checks.
  Expected: all pass.
- [ ] **Step 4:** Write ADR 0019, the findings and the doc updates, using the measured
  numbers. Include the GPU decision.
- [ ] **Step 5: Commit.** Message: `docs(honey-factory): record M12-1 and pass the exit gate`.
