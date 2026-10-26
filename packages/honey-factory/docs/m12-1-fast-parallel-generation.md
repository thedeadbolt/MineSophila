# M12-1 findings: fast and parallel generation

- **Date:** 2026-10-26
- **Decision record:** ADR 0019
- **Design:** `docs/superpowers/specs/2026-10-25-m12-1-fast-parallel-generation-design.md`
- **Plan:** `docs/superpowers/plans/2026-10-25-m12-1-fast-parallel-generation.md`

## Benchmark

`M12BenchmarkTest` generates one panorama still dataset per level (20 scenes of 8
viewpoints, configured as phase-1 production) and runs a 200-tick level-1 control loop.

- **Fresh world:** levels 1–4 and the control run use a fresh world, where two runs of one
  build repeat byte for byte.
- **World snapshot:** level 5 runs in a saved world snapshot, because natural terrain
  differs between fresh worlds. Lighting there is not exactly repeatable, so clear level-5
  RGB may differ in up to 2% of pixels, and rainy level-5 RGB is not compared: rain streaks
  are random.
- **Masks:** always compared exactly.

The reference (`baseline`) is the pre-M12 generator with frames capped as before, plus the
stale-section fix below. It ran on the reference machine, an RX 7600 under Linux with
OpenGL, while the M10 production batch ran beside it.

Medians per phase, baseline → M12-1:

| | Baseline | M12-1 |
|---|---|---|
| Benchmark total, five levels | 265 s | 184 s |
| Level 1 samples per hour | 17,500 | 24,400 |
| Level 4 samples per hour | 13,100 | 25,300 |
| Level 5 samples per hour | 6,000 | 7,100 |
| Pose: move to finished capture | 119 ms | 50 ms |
| Readback, per pose | 65 ms | 16 ms |
| Reprojection, per pose | 36 ms | 4 ms |
| Control step, 1 frame per tick | 188 ms | 120 ms |
| Control step, 3 frames per tick | — | 166 ms |
| Platform reset | 285 ms | 278 ms |
| Level-5 reset | 2.7 s | 2.8 s |

Quality against the reference: 0 mask differences, and every platform and control frame
identical, with one exception. The first capture of the first scene in a fresh world
differed once by 1 level in 46 pixels. The same frame passed in other runs, so it is
treated as first-frame noise.

## What changed, and what it did

- **Frame pacing.** The test client ran with vsync, a 120 fps cap and vanilla's AFK limit.
  The AFK limit drops the frame rate to 30 after a minute without input and to 10 after ten
  minutes. An unattended production run therefore crawled at 10 frames per second. On the
  benchmark, readiness waits fell from 10–37 ms to 2–4 ms per pose, and long runs gain more.
- **Stale back views (a quality fix).** Uncapping exposed a long-standing bug. The game
  rebuilds only sections in view, so after a reset a panorama's back views drew sections the
  camera had not looked at with the previous scene's meshes. Under the cap this showed only
  now and then. Readiness now rebuilds every changed arena section first.
  `M12ReadinessTest` compares a capture right after a reset with one 40 ticks later: it
  failed before the fix (1,336 pixels) and passes after. **Datasets made before M12-1,
  including M10 levels 1–4, may hold such regions in some scenes' first viewpoint.**
- **Reprojection on worker threads:** 36 → 4 ms, byte-identical.
- **Overlapped readback:** 65 → 16 ms. One fence per capture instead of one per view, and
  the conversions run on worker threads.
- **Several frames per tick.** In-between frames place the camera at the interpolated pose
  with the world frozen. Each costs about 25 ms, so a 3-frame step takes 166 ms.

A pose now waits mostly for one client tick, which samples the camera's surroundings: about
28 ms of its 50. That wait is the next limit on one instance.

## Rejected

| Change | Why |
|---|---|
| GPU reprojection | Capture work 16 ms against 21 ms on the CPU, but a pose takes 50 ms either way. The first version's colours were wrong (sRGB decoded) and some masks were missing. Fails the 20% gate. |
| Differential resets | No gain: a reset is bound by its 5 settle ticks. Unchanged translucent sections kept an old sort, which shifted honey faces by 1–2 levels. |
| Skipping readiness waits | Saves at most 2–4 ms per pose once frames are uncapped, and risks stale frames. |

## Parallel instances: exit run

The exit run was `honey-factory-tools generate` with 1 and with 2 instances. Its output is
in `/mnt/fastvault/minesophila/acceptance/m12-1-exit/`.

| Dataset | 1 instance | 2 instances | Check |
|---|---|---|---|
| Level 1, 40 scenes, panorama | 34.5 s generating, 56 s in all | 18.6 s per instance, 53 s in all | every scene, seed, layout and all 320 masks identical |
| Level 5, 24 scenes, panorama | 81.2 s, 107 s in all | 52 s per instance, 85 s in all | same scenes, seeds and splits; terrain differs per world, as accepted |

- Both merged datasets pass both validators.
- A level-5 scene from each instance's world replays exactly in that world:
  `M12ExitReplayTest`, layout and every mask.
- Each instance takes about 20–30 s to start Gradle and the game, so the gain shows on
  larger datasets.
- Two Gradle launches from one checkout run side by side with separate game folders.

## Exit gate

- **Benchmark:** before and after numbers above.
- **Quality:** masks identical everywhere; platform and control frames identical, except the
  one first-frame noise pixel set.
- **Parallel generation:** merged datasets validate; level 1 equals the single run; level-5
  scenes replay in their own worlds.
- **Clip path:** M11 tests pass with 3 frames per tick; the per-tick cost is measured.
- **Full suite:**
  - `./gradlew build` passes;
  - the full gametest suite passes (18 min);
  - the Python packages, integration and e2e tests, ruff and pyright pass.

## Left for later

- M10 levels 1–4 were generated before the stale-section fix. Level-4 panorama stopped at
  800 of 1,250 scenes when its game closed. Regenerating them on the M12-1 generator,
  together with level 5, gives a consistent phase-1 set. Regenerated datasets keep their
  IDs, so they need a new output root.
- Each pose waits for one client tick. Removing that wait safely needs a test that the
  camera's sampled colours stay correct.
