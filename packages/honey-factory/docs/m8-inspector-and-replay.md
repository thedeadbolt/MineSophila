# M8: Dataset inspector and exact replay

Recorded 2026-10-24 on the M0 reference setup: Minecraft 26.3, Fabric Loader 0.19.5,
Fabric API 0.163.0+26.3, Mesa 26.2.5, AMD Radeon RX 7600, Linux. The decision is
[ADR 0015](../../../docs/adr/0015-inspector-and-replay.md); the operator workflow is in the
[operator guide](operating.md).

## What exists

- **`honey-factory-tools inspect`**: a local browser page served on 127.0.0.1 by the
  standard library, with no new dependencies. It provides:
  - exact ID lookup;
  - filters on split, status, presence, ratio, pixels, distance, scene and viewpoint;
  - seeded random and stratified selection;
  - RGB, mask and overlay views, with nearest-neighbour zoom and the recorded box and
    centroid;
  - the metadata of the sample and its scene, and a grid of every viewpoint in the scene;
  - previous/next navigation and review forms;
  - the replay command for the sample.
- **Integrity**: each sample is checked before its images are served, and the whole
  dataset is validated in the background. Bad samples show their errors instead of
  images.
- **Sidecar reviews** (`honey-factory-review-v1`), appended outside the dataset and keyed
  by dataset ID and manifest hash. `reviews` summarizes them and `select` prints
  selections.
- **`/honeyfactory_replay`** `load`, `viewpoint`, `capture`, `verify` and `status`, over
  `ReplayScene` (load and check, Minecraft-free), `ReplayComparison` and the client's
  `ReplayRuns`.

## Exit gate

Evidence:

- `M8ReplayTest` in fresh dataset worlds;
- `ReplaySceneTest` and `ConfigResolverTest` for the Minecraft-free replay logic;
- `test_inspector.py` against the shared contract dataset, which exercises the HTTP API
  end to end.

| Criterion | Evidence |
|---|---|
| Pair inspection | Any intact sample's RGB and mask are served byte for byte. The page draws RGB, mask and an overlay at native resolution, scaled only by nearest-neighbour zoom |
| Scene inspection | A scene lists exactly its samples in viewpoint order, with previous/next scenes |
| Search and sampling | Exact ID lookup. Split, status, presence, ratio, pixel, distance, scene and viewpoint filters, with inclusive ranges that exclude a missing value. Random and stratified selections are identical across runs and input orders, and the order follows the documented key |
| Integrity reporting | Truncated, missing and corrupt files return their errors with HTTP 409 instead of images; background validation marks affected samples and lists dataset-level errors such as stray files |
| Review safety | Recording reviews through the API or the store changes no byte or modification time under the dataset. Each review names the full manifest hash. A review root inside the dataset, and any review after the manifest changes, are refused |
| Replay safety | Refused in a personal world and in an unclaimed dataset world (NOT_OWNED). A level-5 scene is refused in a world with another seed and reported as a source mismatch in a fresh world with the same seed. An unknown template version is refused before any operation starts. No dataset file changed across all replays |
| Replay fidelity | Every scene of three reference datasets reproduces its layout hash and every stored mask pixel for pixel: level 1 perspective (3 scenes × 3 viewpoints), level 4 panorama 620 × 320 (2 × 2) and level 5 natural (2 × 2, one of them raining) |
| Find, inspect, review, reproduce without editing files | A sample is loaded by command from its ID, its viewpoints cycled by command (1 → 2 → 0 → 2 → 0) and a fresh capture compared, using the command the inspector shows |

## RGB after replay

RGB is compared and reported but not required to match.

- **Level 4:** every pixel matched.
- **Level 1:** at most 46 pixels differed, each by 1.
- **Level 5:** 3,500–21,000 of 76,800 pixels differed. They lie in animated water, in
  falling rain, and in faint sky dithering, all of which Minecraft animates by game tick.

## What replay found in capture

Before the fixes in ADR 0015, replaying level-5 scenes showed frames changing for up to
100 ticks after a reset reported ready. Probes that captured the same scene at +0, +100
and +400 ticks pinned the causes down one at a time:

| Cause | Effect before the fix | Fix |
|---|---|---|
| Rain and thunder fade by 0.01 per tick | A rain scene was captured looking clear, and a clear scene after it with fading rain | Set rain and thunder levels at once, send them to clients, and wait until the client shows them |
| Rain fog eases a fifth per tick | A uniform 4–6 level shift for about a second after a weather change | Wait until the client's rain fog multiplier stops changing |
| Opaque particles write scene depth | Falling leaves removed mask pixels at random | Captures leave particles out |
| The improved-transparency composite writes the nearest translucent depth | Rain streaks removed 9–307 mask pixels per view | Test the visible mask against the depth copied as the translucent stages begin |
| The occlusion graph is rebuilt in the background after the camera moves | Readiness found compiled sections with a stale graph 89 times in 24 level-5 captures. A stale graph can leave sections undrawn, consistent with the chunk-aligned holes of missing ground seen under tree canopies in one M6 sample | Wait until the graph is current for the camera's position |
| The camera samples nearby biomes' sky and fog colours once per tick | In batched captures the sky could keep the previous pose's colour; once, near a biome edge, it shifted by one level across 0.63 % of a panorama's bytes | Each batched pose waits for one tick after a frame drawn there |

Level-5 datasets generated before these fixes may show weather that their records do not
match. They should not be used for acceptance runs.

With every fix, batched captures match server-placed ones within 3 RGB bytes on natural
terrain, against up to 15,095 before. The waits cost throughput. In the M6 profile
(8 scenes × 8 viewpoints at default sizes), seconds per accepted image went:

| | M6 | M8 |
|---|---:|---:|
| Level 4, perspective | 0.09 | 0.21 |
| Level 4, panorama | 0.21 | 0.32 |
| Level 5, perspective | 0.26 | 0.54 |
| Level 5, panorama | 0.40 | 0.55 |

## Natural terrain is world-specific

Two fresh dataset worlds with the same seed chose the same source patch for the same
dataset seed, but 40,777 of its 173,056 columns differed. The changes were in ore and
stone-variant blobs, flowers and seagrass, and were not concentrated at chunk borders.
Decoration seeds are deterministic, but these features overlap neighbouring chunks and
only replace base stone or air, so the result depends on the order chunks generate in.
In another world the different terrain can also change which candidate patch passes its
checks. Replay therefore reports a source patch mismatch outside the generating world.
The dataset world, which keeps its generated chunks on disk, replays exactly.

## Still open

- The inspector's HTTP API is tested end to end, but the page's script has only been
  syntax-checked. Nothing has driven it in a browser yet; that check is due before the M9
  audit relies on it.
- Reviews are not yet aggregated across datasets; the M9 audit will define its report.
