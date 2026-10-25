# M9: Acceptance matrix and production pilot

Recorded 2026-10-24 and 2026-10-25 on the M0 reference setup: Minecraft 26.3, Fabric
Loader 0.19.5, Fabric API 0.163.0+26.3, Mesa 26.2.5, AMD Radeon RX 7600 (8 GB), 46 GB RAM,
32 CPU threads, Linux. Outputs live under `/mnt/fastvault/minesophila/acceptance/`:
`2026-10-25b/matrix/` and `2026-10-25c/pilot/`, each with its datasets, `metrics/`, a copy
of its dataset world and the acceptance report. The [operator guide](operating.md#7-acceptance-runs)
describes how to run the gates again.

## What exists

- **`M9MatrixTest` and `M9PilotTest`**, the specification's acceptance runs 2 and 3. Both
  generate by command into a labelled acceptance folder, never overwrite anything, and run
  only when named, so an ordinary `mod-gametest` run skips them.
- **Per-dataset metrics** (`metrics/<dataset>.json`):
  - wall time and throughput;
  - count, total, mean, median, 95th percentile and maximum for every generation phase;
  - per-capture pose wall time and render, readback and reprojection times;
  - rejections, peak process RSS (from the kernel's counter, reset per dataset), sampled
    Java heap in use and sampled device VRAM;
  - the render setup.
- **`honey-factory-tools acceptance-report`** validates every dataset again with the outside
  validator. It reports quotas, splits, hashes, PNG sizes, disk use, capacity and the audit,
  as Markdown and JSON. It exits 0 only when everything passes.
- **`ProductionRunTest`** generates the phase-1 production datasets of M10, in batches.
- **The inspector** reports review progress and takes its filters from the URL, so an audit
  link opens on its selection.
- **The frame-edge rule.** A capture whose unoccluded target mask touches the image's first
  or last row or column is rejected as `frame_clipped`; see [the audit finding](#frame-edge-finding).

## Frame-edge finding

The first pilot audit, on 2026-10-25, found sample `s000309-v03` labelled `fully_visible`
although only a few pixels of the target showed. The target stood 72 degrees below the
horizon, beyond the panorama's −70 degree floor. The masks only cover the image, so the
visible and unoccluded masks were both cut by the bottom edge, and their ratio of 1.0 read
as fully visible. The panorama's level window and its cube-face reprojection were working
as specified. A perspective image can cut a target the same way at any of its edges.

Four of that pilot's 6,400 present samples touched the frame edge. The generator now
rejects such captures (`frame_clipped`), and the specification records the rule under
target-state metadata. Recording an in-frame fraction, which would let such samples be
kept, is planned with the frame streams of M11–M13. Because the rule changes generation,
the matrix and pilot were generated again, and gate 1 was rerun. The datasets in
`2026-10-24/`, `2026-10-25/` and `2026-10-25b/pilot/` (stopped before the end) are superseded.

## Gate 1: automated fixtures

On the M9 code with the frame-edge rule, on 2026-10-25:

- `./gradlew build` compiles the mod and passes all 250 JUnit tests.
- The full client gametest suite, M2–M8, passes in 12 minutes 55 seconds. It includes the
  mask, projection, reset, metadata, environment, safety and replay fixtures and the golden
  masks. The M9 and production tests skip themselves unless named.
- The Python checks pass: lint, format, pyright, the honey-factory-tools suite (161 tests),
  and the integration and e2e suites.

The rerun needed two test changes, neither of them in the capture code:

- The M3 image-edge fixture expected level 1 to accept its edge target. It now expects
  the `frame_clipped` rejection.
- The M6 check that batched and individual captures match failed intermittently on
  level-5 terrain. The differing pixels were the distant ocean on the horizon: animated
  water changes between any two captures, and how much of it a patch shows varies with
  the world, so a fixed byte budget was too small for some worlds. The check now ignores
  bytes that also change when the pose is captured again 20 and 31 ticks later. After the
  change, three runs passed, with at most 7 unexplained bytes.

One run also showed a single world-tick step, 36 to 37, between the first two of the M3
test's ten frozen captures, with identical images. Two M3 runs and the full suite after it
did not repeat it. It is likely a time update in flight when the test freezes ticking.

## Gate 2: configuration matrix

Ten datasets, levels 1–5 in both modes at the default resolutions (1280 × 1280 perspective,
2480 × 1280 panorama), 10 scenes × 8 viewpoints each, generated in one dataset world:

| Level | Mode | Samples | Present / absent | Validation | Duration | Samples per hour | Rejected attempts |
|---:|---|---:|---|---|---:|---:|---:|
| 1 | perspective | 80 | 8 / 2 | 0 errors | 0.1 min | 34,926 | 0 |
| 1 | panorama | 80 | 8 / 2 | 0 errors | 0.2 min | 20,656 | 0 |
| 2 | perspective | 80 | 8 / 2 | 0 errors | 0.1 min | 35,928 | 0 |
| 2 | panorama | 80 | 8 / 2 | 0 errors | 0.3 min | 19,017 | 0 |
| 3 | perspective | 80 | 8 / 2 | 0 errors | 0.3 min | 16,910 | 0 |
| 3 | panorama | 80 | 8 / 2 | 0 errors | 0.5 min | 10,203 | 1 |
| 4 | perspective | 80 | 8 / 2 | 0 errors | 0.3 min | 18,274 | 2 |
| 4 | panorama | 80 | 8 / 2 | 0 errors | 0.3 min | 15,352 | 1 |
| 5 | perspective | 80 | 8 / 2 | 0 errors | 1.1 min | 4,523 | 2 |
| 5 | panorama | 80 | 8 / 2 | 0 errors | 0.9 min | 5,564 | 0 |

All ten are `complete` with exact quotas: 800 base samples, 0 Java and 0 Python validation
errors, and the acceptance report passes (`2026-10-25b/matrix-report.md`). Generation took
4.0 minutes in all. Mean RGB PNG size runs from 186 KB (level 1, perspective) to 2.3 MB
(level 5, panorama); peak RSS stayed under 10 GB and device VRAM under 2.8 GB. Rates this
short overstate throughput, because the fixed costs of each dataset barely register.

## Gate 3: production pilot

Level 5, `PANORAMA_310` at 2480 × 1280 with 1024² faces, 1,000 accepted scenes, 8 viewpoints,
the default 80/20 balance, 8 scenes per source patch, in one uninterrupted run started
detached from any session:

| Measure | Result |
|---|---|
| Outcome | `complete`, exactly 1,000 scenes (800 present, 200 absent) and 8,000 samples, 0 Java and 0 Python validation errors |
| Duration | 80.2 minutes, of which the final validation took 14.1 |
| Throughput | 5,986 samples per hour; 0.60 s per sample including validation |
| Scene attempts | 1,041: 1,000 accepted, 41 rejected (20 visibility, 19 viewpoint placement, 2 frame-clipped), none failed |
| Source patches | 125, prepared in the background: median 6.9 s, 95th percentile 19.2 s; waiting for one took a mean 6 ms per scene |
| Reset | median 2.31 s, 95th percentile 4.97 s |
| Per pose, move to finished capture | median 204 ms, 95th percentile 455 ms, longest 3.3 s |
| Inside a capture | render median 6 ms (95th percentile 145 ms), readback 36 ms (70 ms), reprojection 36 ms (39 ms) |
| Writing a sample | median 162 ms on worker threads; the generator waited a median 153 ms per scene |
| Disk | 16.8 GB dataset (RGB PNG mean 2.09 MB) and 3.4 GB world copy |
| Memory | peak process RSS 14.4 GB; peak device VRAM 1.7 GB of 8 GB; sampled heap in use reached the 11.7 GB maximum |
| Failures | none: no out-of-memory, no rendering or capture failure, disk reserve kept |

The heap is sampled including garbage not yet collected, so reaching the maximum between
collections is expected and caused no out-of-memory error. The production runs keep the
same heap; a level-5 run that fails with an out-of-memory error would need a larger one.

**Where the time goes.** Per sample, resets take about 0.27 s and capturing about 0.31 s.
Writing adds only about 0.02 s of waiting, because it overlaps with capture. The final
validation re-decodes all 16,000 PNGs on one thread. Parallelising it, and the resets, are
the obvious throughput work for the production runs.

**Target positions.** In the matrix's perspective images, 90% of target centroids lie
within x 0.37–0.62 and y 0.41–0.58. In its panoramas, every centroid lies in the middle
fifth of the width. This follows from the specification's default aim, which looks at the
target and adds ±15 degrees of yaw and ±10 of pitch. The M10 production datasets use the
image-uniform aim of ADR 0016 instead. The M9 runs keep the default, as the specification
sets it.

## Audit

200 pilot samples in `review-selection-v1` stratified order, seed 2026 (selection SHA-256
`9fe53f7935c696e9…`). The selection covers every split, status, ratio range, low pixel
count and seam, and 100 of the 125 source patches; the report finds no coverage gaps.

The reviewer went through the whole selection in the inspector on 2026-10-25 and found
one problem; everything else was judged correct. The verdict was given in the working
session, not saved sample by sample, so the review sidecar holds no records and the
acceptance report's audit line reads "200 not yet reviewed". Its other checks pass.

**Finding: slivers counted as visible.** `s000733-v04` and `s000733-v07` show a honey block
on a ridge about 40 blocks away and high above the camera. Only a strip of its top shows
over the terrain: 50 visible of 400 unoccluded pixels (22 × 5) and 152 of 572 (54 × 9).
Both satisfy level 5's 0.1 ratio and are labelled `partially_visible`, correctly by the
masks, but neither can be seen at normal size. The masks were checked against the RGB and
are right; the level rule has no minimum size.

- In the pilot, 7 present samples show fewer than 100 visible pixels (0.1%), 72 fewer
  than 200 (1.1%) and 336 fewer than 400 (5.2%); 50 is the smallest.
- Resolution, agreed with the reviewer: a `visibility.min_visible_pixels` setting rejects
  smaller targets (`too_few_pixels`), and the M10 production datasets use 200 (ADR 0017).
  The M9 data follows the specification as written and is not regenerated.

## Exit gate

- Gate 1: the clean build, all JUnit tests and the full gametest suite pass on the final
  M9 code.
- Gate 2: all ten matrix datasets are complete and valid with exact quotas.
- Gate 3: the level-5 panorama pilot is complete and valid with exact quotas, with no
  failures and the capacity measurements above.
- Audit: the 200-sample selection was reviewed with no coverage gaps. Its two findings,
  the frame edge and the slivers, are resolved: the first by the `frame_clipped` rule and
  a regenerated matrix and pilot, the second by the pixel minimum for M10. The review
  verdict is recorded here rather than in the review sidecar.
- Throughput for the production runs: about 6,000 level-5 panorama samples per hour on
  this machine, of which the final validation takes a sixth. The M10 runs also use the
  image-uniform aim of ADR 0016, measured on a 25-scene trial of every M10 configuration
  (`/mnt/fastvault/minesophila/trials/image-aim-2026-10-25/`). It spread the targets across
  the image width in both modes and over the height in perspective images; only level-3
  perspective ran slower, by about a quarter.

M9 passed on 2026-10-25.
