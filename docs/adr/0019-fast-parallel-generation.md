# 0019: Fast and parallel generation

- **Status:** Accepted
- **Date:** 2026-10-26

## Context

M12-2 will generate motion clips: panorama frames at the still datasets' resolution, three
per game tick, across all five levels. The M10 still datasets and any later ones share the
same machinery. M12-1 makes that machinery faster on one machine and runs one generation
job across several game instances. The design is in
`docs/superpowers/specs/2026-10-25-m12-1-fast-parallel-generation-design.md`.

The rule for every change: keep it only with a measured gain and with the quality bar met.
The bar is masks identical, no half-loaded or stale frames, and RGB identical to a
fixed-seed reference wherever content is unchanged.

A benchmark gametest (`M12Benchmark`) measures every phase on fixed-seed reference
workloads and compares frames with a reference. It covers one 20-scene panorama still
dataset per level and a 200-tick level-1 control run. Its findings are in
`packages/honey-factory/docs/m12-1-fast-parallel-generation.md`.

## Decision

- **Frame pacing.** Dataset runs, pose batches and control sessions lift the frame-rate cap
  and vsync. They also lift vanilla's inactivity limit, which drops an unattended game to
  30 and then 10 frames per second. The user's options come back when the run ends and when
  the world closes.
- **Every arena section is rebuilt before a capture.** The game rebuilds only the sections
  in view. A panorama looks every way, so after a reset its back views drew sections the
  camera had not looked at with the previous scene's meshes. The old frame cap hid this
  most of the time. Readiness now rebuilds every changed arena section first.
- **Parallel CPU reprojection.** Bands of panorama rows are reprojected on a shared worker
  pool, byte for byte as before.
- **Overlapped readback.** Each view's copy into mappable buffers is submitted without
  waiting. One fence after the last view waits for all of them, and worker threads convert
  the pixels.
- **Several frames per control tick.** `controlReset` takes 1 to 8 frames per tick. The
  in-between frames place the client camera at the interpolated pose, with the world
  frozen, and turn yaw the short way. The last frame equals the single frame of a 1-frame
  session.
- **Slices and merging.**
  - `output.slice {index, count, world}` makes one instance generate the scene groups
    `g % count == index`: scenes on a platform, source patches on natural terrain.
  - A slice is an output setting, outside the configuration hash.
  - A slice is a complete dataset in `<dataset id>.slice-<i>-of-<n>`, with a `slice`
    manifest member.
  - `honey-factory-tools merge-slices` joins slices into the dataset a single run writes.
    Images are hard-linked, so the slices stay untouched. The merged manifest lists each
    slice's world and slots under `worlds`.
- **`honey-factory-tools generate CONFIG --instances N --root DIR`** starts one game client
  per slice through `runClientGameTest` (`GenerationJobTest`). Each client has its own game
  folder (`-Dhoneyfactory.runDir`) and its own world, `<dataset name>-i<index>`, which is
  copied beside the output. The command waits for every client, names any failed slice,
  keeps the finished ones, and merges. Two Gradle launches from one checkout run side by
  side.
- **Level 5 keeps one world per slice.** Natural terrain differs between worlds, so a merged
  level-5 dataset replays each scene in the world that generated it. Replay names that world.

## Rejected

- **GPU reprojection.** A fragment shader repeated the CPU's integer arithmetic over atlases
  of the faces. Its first output was wrong: colours were decoded as sRGB, and some masks
  were missing. More decisively, it saves only about 5 ms of capture work per pose, while a
  pose now takes 50 ms, most of which waits for a client tick. It fails the user's 20% gate.
  It was dropped.
- **Differential resets.** Writing only the blocks that differ from the last scene gave no
  measurable gain. A platform reset takes about 275 ms either way, bound by its minimum of
  5 settle ticks. Unchanged sections also kept a translucent sort from an earlier camera
  position, which changed the honey target's faces by 1–2 levels in a few scenes. Reverted.
- **Skipping readiness waits when nothing changed.** Once frames were uncapped, the wait it
  targeted was 2–4 ms per pose, and skipping waits is what produced stale frames.
- **A shared world for level-5 slices.** Pre-generating one world would add a pass; the user
  accepted one world per slice.

## Consequences

- Still-dataset generation on one instance is about 31% faster on the benchmark: 265 s →
  184 s. A pose takes 50 ms instead of about 119 ms, and a control step 120 ms instead of
  188 ms. Unattended runs gain more, because they no longer fall to 10 frames per second
  after ten minutes.
- Two instances generated a 40-scene level-1 dataset in 18.6 s instead of 34.5 s, with
  every scene, seed, layout and mask identical to the single run. A 24-scene level-5
  dataset took 52 s instead of 81 s. Each instance pays roughly 20–30 s of start-up.
- With 3 frames per tick, a control step takes about 166 ms: about 18 clip frames per second
  per instance.
- A pose now waits mostly for one client tick, which samples the camera's surroundings.
  That wait is the next limit on one instance; more instances are the way past it.
- Datasets generated before M12-1 may hold stale back-view regions in some scenes' first
  viewpoint after a reset. The M10 levels 1–4 are among them. Regenerating them on the
  M12-1 generator removes this. New and old datasets are told apart by the mod version and
  git commit in the manifest. The configuration hash is unchanged, since the algorithms it
  covers did not change.
