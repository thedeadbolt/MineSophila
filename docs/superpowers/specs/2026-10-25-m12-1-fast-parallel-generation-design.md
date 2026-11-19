# M12-1: Fast and parallel generation, design

- **Date:** 2026-10-25
- **Status:** Agreed in conversation; written for planning
- **Package:** `packages/honey-factory` (mod) and `packages/honey-factory-tools` (orchestrator)
- **Roadmap:** M12 is split into M12-1 (this document: speed and parallelism) and M12-2
  (motion clips, designed separately).

## Why

M12-2 will generate motion clips: panorama frames at the still datasets' resolution, three
frames per game tick (60 Hz), across all five levels. The weighted full set (about 9,400
clips, 1.2 million frames) would take about 68 hours at today's speed. The remaining M10
datasets and any future still datasets share the same machinery and are slow for the same
reasons. M12-1 makes that machinery fast and parallel before M12-2 builds on it.

Measured on the M9 pilot (level-5 panorama, still images):

- **Overall:** about 0.6 s per sample, of which resets take about 0.27 s and capture about
  0.31 s.
- **Waiting dominates a pose.** One pose takes a median 204 ms from moving the camera to the
  finished capture, but only about 78 ms of that is work: render 6 ms, GPU readback 36 ms,
  CPU reprojection 36 ms. The rest is waiting for readiness frames.
- **The frame rate paces that waiting.** The generator runs with vsync on and a 120 fps cap,
  so every readiness frame costs 8–17 ms.
- **The control loop** (M11) takes about 0.2 s per step, mostly readiness waits. A clip tick
  will add three panorama captures on top.
- **One game instance leaves the machine idle.** It uses a fraction of the 32 threads and
  about 2–3 GB of the 8 GB VRAM. A second instance ran beside a production batch on
  2026-10-25 without hurting it noticeably.

## Goals

- Faster still-dataset generation and faster control-loop steps (the clip path) on one
  machine. The rough target is 3–5× per machine; parallel instances and shorter waits give
  most of it.
- One command runs a generation job on several game instances at once and produces the same
  single dataset per configuration as one instance would.
- A benchmark that measures time per phase, so every change is judged by numbers.

## The quality bar (the user's rule)

Output need not be byte-identical to today's generator, but the data must stay high quality
for the fly. Concretely:

- Masks stay exact: binary, and matching the frame's pixels and the recorded statistics.
- No frame is captured with sections missing, terrain half-loaded, or lighting, fog or
  weather not yet settled.
- Fixed-seed reference datasets (still and control-loop) generated before and after each
  change look equivalent. Wherever the frame content is unchanged, frames are identical.
  Elsewhere, RGB stays within a strict per-pixel tolerance, set by the builder and
  recorded.
- A short inspector audit of the reference data shows no new artefacts.
- Determinism holds within the new version: the same configuration and seed give the same
  dataset, and replay stays exact.

An optimisation is kept when its speed gain is meaningful and these checks pass. Anything
that degrades frames is dropped, however fast.

## Non-goals

- Clip types, clip records and the clip contract (M12-2).
- Changing what a dataset contains: levels, labels, resolutions, quotas.
- Lossy storage. Video storage belongs to M12-2.
- The level-5 M10 batches. They wait and run on the faster generator once M12-1 lands, so no
  M10 dataset mixes versions.

## Decisions

### 1. Benchmark first

- A benchmark gametest generates fixed-seed reference workloads and reports time per phase:
  - a small still dataset per level, in both modes;
  - a short control-loop run with captures, 3 per tick.
- Phases reported: reset, waiting for readiness, render, readback, reprojection, write,
  validate; per sample and per tick.
- It saves its output as the reference for the quality comparisons above.
- Every later change is measured against this baseline. The M12-1 findings record before and
  after numbers.

### 2. Parallel instances: `honey-factory-tools generate … --instances N`

- **One command** launches N game clients and supervises them.
- **Slices.** Each client gets its own run folder and its own claimed dataset world, and
  generates a slice of the configuration's scene indices.
- **Merge.** Scenes are derived from the dataset seed and their index, so a slice produces
  exactly the scenes a single run would. A merge step combines the slices into one dataset:
  manifest, indexes, images and quotas. The dataset ID and configuration hash are those of
  the single-run dataset. Both validators then check the merged result.
- **Level 5.** Natural scenes replay only in the world that generated them, and every new
  world decorates chunks slightly differently. So a merged level-5 dataset keeps all N
  generation worlds beside it, and each scene records which world it came from. The user
  accepted this as the simplest and most efficient approach; pre-generating one shared world
  was rejected as an extra pass. Replay loads the scene's own world.
- **Failure.** If one instance fails or is cancelled, the job reports which slice failed and
  marks the merged dataset failed. Its finished slices stay on disk, and the builder decides
  whether a slice can be rerun. No partial dataset is ever marked complete.
- **Limit.** N is limited by memory and VRAM. The benchmark establishes how throughput
  scales with N on the reference machine: 2–3 instances are expected to fit in 8 GB of VRAM.

### 3. Shorter waits

- **Frame rate.** During generation and control sessions, uncap the frame rate and turn off
  vsync, then restore the user's options afterwards (as auto-jump is restored).
- **Wait only when needed.** Skip the stable-frame and section waits when nothing that
  affects them changed: the camera stayed in the same section, and no blocks or chunks
  changed since the last ready frame. Keep the full readiness check after resets, after any
  move into a new section, and whenever the cheap test cannot prove nothing changed.

### 4. Faster capture

- **Asynchronous readback.** Start rendering the next view while the previous one copies
  back from the GPU, so readback overlaps rendering.
- **GPU reprojection, as an experiment behind a setting.**
  - A shader samples the six cube faces into the panorama on the GPU, for RGB and both
    masks, so only the 2480 × 1280 output is read back instead of six 1024² faces. It reads
    back half the pixels and needs no CPU reprojection.
  - The CPU path stays as the reference and the fallback; a GPU failure falls back to it.
  - Masks must equal the CPU reprojection pixel for pixel, since both use nearest-neighbour
    sampling of the same directions. RGB must stay within the tolerance.
  - It is kept only if it clearly beats the fallback below.
  - The user asked that it must not harm the GPU. A shader cannot damage hardware; the
    realistic worst case is a driver reset that crashes the game. It is a light pass
    compared with rendering the scene.
- **Fallback: reprojection on worker threads.** The CPU reprojection is split across rows on
  a thread pool. Its output is identical to today's.

### 5. The clip path: one readiness check per tick

A clip tick captures 3 frames (partial ticks 1/3, 2/3 and 1) of one frozen world state. The
three frames differ only in camera position. The tick runs one readiness check, then
captures them back to back; frames 2 and 3 skip the waits unless their camera crosses into
a new section.

### 6. Faster resets

Level-5 resets take a median 2.3 s. Three options:

- change only the blocks that differ from the previous scene;
- resend only the chunks that changed;
- prepare the next scene's plan and patch while the current scene captures (patch
  preparation is already in the background).

Each is kept if it is measurably faster and passes the quality checks.

## Exit gate

- **Benchmark.** The benchmark reports before and after numbers per phase for the still and
  control-loop workloads.
- **Speed.** The combined gain is recorded. The 3–5× target is a target, not a gate; any kept
  optimisation must show a measured gain.
- **Quality.** Every quality check above passes on the reference workloads.
- **Parallel generation.**
  - `honey-factory-tools generate --instances 2` (or more, if it fits) produces a merged
    dataset that both validators pass, for a platform level and for level 5;
  - its scenes equal those of a single-instance run of the same configuration in layout,
    seeds and masks;
  - a level-5 scene from each instance replays exactly in its own world.
- **Clip-path measurement.** The control-loop exit test (M11) still passes, and the
  per-tick cost with 3 captures is measured.
- **Full suite.** `./gradlew build`, the full gametest suite and the Python checks pass.
- **Findings and decision.** The findings document and an ADR record the measurements and
  the decisions, including whether GPU reprojection was kept.

## Constraints

- **Platform rules:** Fabric and Blaze3D only, no raw OpenGL; world changes on the server
  side; no client classes in `src/main`.
- **Player data:** player saves and user options are restored after generation, as today.
- **Repository rules:** decisions in an ADR in `docs/adr/`; Conventional Commits.
- **The second game instance:** no Gradle in a worktree another instance runs from.
- **Existing datasets keep their meaning:** M1–M10 datasets, hashes and replay stay valid.
  A change that alters how new datasets are generated bumps the relevant algorithm or
  renderer version recorded in the manifest, so old and new data are never confused.

## Left to the builder

- **The orchestrator's mechanics:** how clients are launched (Gradle run tasks with
  separate run folders, or the client directly), how slices are assigned, the merge
  format, and whether a failed slice can be rerun.
- **The tolerance:** the exact per-pixel RGB tolerance and how it is measured and
  reported.
- **The cheap no-change test:** which signals prove nothing affecting readiness changed.
- **The GPU shader:** its implementation and texture formats, within Blaze3D.
- **Order of work:** after the benchmark, which optimisations to do first.
