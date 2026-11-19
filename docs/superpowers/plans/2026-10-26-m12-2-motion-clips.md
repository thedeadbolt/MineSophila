# M12-2 Motion Clips and the First Teacher Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate labelled motion clips by driving the player through M11's control loop,
store them as lossless video, play them in the inspector, and drive them with a first,
realizable teacher that M13 and M14 reuse.

**Architecture:**

- **Video first.** Tasks 1–4 produce clips you can watch. They cover:
  - lossless storage;
  - the clip contract;
  - a clip generator with two scripted clip types;
  - the inspector's player.
- **The teacher.** Tasks 5–7 build it as pure Java in `src/main`, testable by JUnit
  without the game:
  - what the fly may know;
  - Track with pathfinding;
  - Re-acquire and Search.
- **Clip types, drift and the dataset tooling.** Tasks 8–10 add the remaining clip types and
  drift, then make clip datasets validate, slice, merge and replay.
- **The pilot.** Task 11 runs it, measures it and records the decision.

The clip generator runs in `src/main` against a `ClipEnvironment` interface. The client
adapts `EnvironmentRuntime.controlReset` and `controlStep` to that interface, the same way
`GenerationEnvironment` serves still datasets.

**Tech Stack:**

- Java 25, Fabric (Minecraft 26.3), JUnit 5, Fabric client gametests;
- `ffmpeg` (lossless H.264 in Matroska by default, FFV1 as the alternative), run as a
  subprocess from Java and Python;
- Python 3.13 with pytest; Pillow is already a dependency, and no new Python dependency is
  added;
- the inspector's plain HTML, CSS and JavaScript.

**Spec:** `docs/superpowers/specs/2026-10-25-m12-2-motion-clips-design.md`. Read it first,
including its anti-goals and exit gate. Follow `AGENTS.md`.

## Global Constraints

- **Defaults:**

  | Setting | Value |
  |---|---|
  | Capture | `PANORAMA_310`, 2480 × 1280, 1024² faces |
  | Frames per tick | 3 |
  | Clip length | 32 ticks; detour up to 100 ticks |
  | Clips per scene | 4 |
  | Drift | correlated, on about 10% of ticks |
  | Teacher "visible" threshold | 50 visible target pixels at full resolution |
  | Motor limits | `MotorLimits.DEFAULT` (9°/tick turning, 6°/tick pitch) |

  Every value is configurable.
- **Clip mix:** approach 25%, pass-by 15%, scan 15%, occlusion 15%, detour 10%, no honey
  20%.
  - No honey is the target-absent scene class: `target.present_fraction` = 0.8.
  - The other five types are drawn per clip among target-present scenes, renormalised.
- **The teacher sees the honey's true position only when that frame shows at least 50
  visible target pixels.** Otherwise it acts on memory and its seen map. This is enforced
  by type (Task 5) and checked on every frame by both validators (Task 9).
- **Every frame comes from the controlled body**, through `controlStep`'s captures. Never
  `setViewpoint` and never a free camera.
- **Labels come from that exact frame's capture and pose.** Honey at or cut by the frame
  edge is labelled, never rejected. The still datasets' frame-edge rule and 200-pixel
  minimum do not apply.
- **No mining.** No block is changed during a clip.
- **Contract:** 1.0 is amended additively, as M12-1 did with `slice` and `worlds`.
  - A manifest without `kind` is a still dataset.
  - Still datasets, their hashes and replay are unchanged.
- **Platform:**
  - Fabric only, no raw OpenGL;
  - world changes on the server side;
  - no client classes in `src/main`.
- **Running things:**
  - compile and JUnit after each task;
  - game launches (gametests, pilot) only where a step says so;
  - long runs in the daytime only;
  - never run Gradle in `../MineSophila-aim` while a run uses it.
- **No deletes without asking.** Never clear folders, revert or drop code without the
  user's yes.
- **Commits:** Conventional Commits scoped `honey-factory`, `honey-factory-tools` or
  `schemas`, ending with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
  Decisions go in ADR 0023.

## Decisions this plan makes (the spec leaves them to the builder)

| Decision | Choice |
|---|---|
| Codec | A strategy per compression behind one `ClipCodec` interface, chosen by name in `clips.codec`, which enters the configuration hash. Each clip record names its codec. ffmpeg's version goes into the manifest's `software`. Container: Matroska `.mkv`. |
| Codecs | `h264-lossless` (**default**): `libx264rgb -qp 0 -preset medium -g 48 -pix_fmt rgb24`. It compresses across frames, for the storage saving. `ffv1`: FFV1 level 3, `gbrp`, intra-only. `h264-lossy`: `libx264 -crf <crf> -preset medium -g 48 -pix_fmt yuv444p`, with `crf` from the config (default 18). Both lossless codecs were checked pixel-exact on this machine on 2026-10-26. |
| Masks | The visible mask only, always in a lossless codec, whatever the RGB codec: `h264-lossless` gray (`libx264 -qp 0 -pix_fmt gray`) unless the RGB codec is `ffv1`. Values 0/255. Unoccluded pixel counts go in the frame records, as in still samples. |
| Lossy RGB | Allowed, but the manifest then records `rgb_lossless: false`. Replay then checks masks and truth only, and a validator warning notes that RGB is not exact. The master default stays lossless (spec). |
| Layout | `clips/<shard>/<clip id>/rgb.mkv`, `visible.mkv`, `frames.jsonl`; the shard is the scene's shard. |
| Clip ID | `<scene id>-c<clip index:02d>`, for example `s000012-c03`. |
| Records | `scenes.jsonl` (unchanged schema; `accepted_sample_count` is 0 and a new `accepted_clip_count` holds the clips), `clips.jsonl`, one `frames.jsonl` per clip. |
| Tick-level fields | Commands, teacher, senses and velocity sit only on a tick's last frame (`sub_frame == frames_per_tick`) and on tick 0's single frame. Interpolated frames carry pose and image labels. |
| Honey state | `TargetStatus`, reused: visible = `fully_visible`, partly = `partially_visible`, hidden = `fully_occluded` or `subpixel`, out of frame = `out_of_view`, absent = `absent`. |
| Bearing | Relative to the body: horizontal angle from body yaw (positive right), elevation from the horizon (positive up), distance from eye to honey centre. All null when absent. |
| Approach length | Ends 8 ticks after the honey is reached (eye within 1.5 blocks horizontally of its centre), capped at 100 ticks. Exit-gate success means reaching it. The other types run 32 ticks; detour runs up to 100. |
| Level 1 | No obstacles, so occlusion and detour shares go to approach and pass-by. Elsewhere, a clip whose scene offers no blocked start falls back to approach. Each clip records `requested_type` and `clip_type`. |
| Seeds | `seed-v1` purposes `clip-start`, `clip-drive` and `clip-drift`; scene index; attempt index = clip index × 1000 + attempt. Golden vectors are added in both languages. |
| Teacher decision timing | The command for tick t uses the observation captured at the end of tick t−1, which is what the fly would have. |
| Teacher world | Seen map over the scene's `SightGrid` (`SolidGrid` on platforms, `PatchTerrain` on level 5). Unseen cells are planned as walkable (optimistic), with a replan every tick. |

## Review Focus

1. **The honey leaves the frame between interpolated frames and the end frame.** Expect the
   teacher to see only end-of-tick frames, and labels on every frame from that frame's own
   capture. Task 3 tests a scan clip whose honey crosses the ±155° seam mid-tick.
2. **A drift or scripted path walks the body out of the arena reset volume.** Today
   `controlStep` then fails with `OUT_OF_ARENA`. Expect the clip to be rejected and redrawn
   from a new start, with the scene kept. Task 8 drives a pass-by towards the edge and checks
   the redraw.
3. **ffmpeg is missing or exits mid-clip.** Expect the generator to fail before the first
   scene with a clear message when ffmpeg is absent. If ffmpeg dies mid-clip, expect no
   half-written clip in the dataset. Task 1 tests both.
4. **Honey seen once at 49 pixels, then at 50.** Expect the teacher's belief to stay
   `none` or `memory` at 49 and switch to `seen` at 50 exactly. Task 5 tests the boundary.
5. **A merged clip dataset whose slices drew different clip types for one scene.** Expect
   this to be impossible: types derive from seeds. The merge refuses mismatched
   configurations, as for stills. Task 10 merges two clip slices and compares them with one
   run.

---

### Task 1: Lossless clip storage

**Files:**
- Create: `packages/honey-factory/src/main/java/io/github/thedeadbolt/honeyfactory/clip/ClipVideo.java`
- Create: `packages/honey-factory-tools/src/honey_factory_tools/clips.py` (decoding only for now)
- Test: `tests/honey-factory/java/io/github/thedeadbolt/honeyfactory/clip/ClipVideoTest.java`, `tests/honey-factory-tools/test_clips.py`

**Interfaces:**
- Produces (Java):
  - `final class ClipVideo implements AutoCloseable`;
  - `sealed interface ClipCodec permits H264Lossless, Ffv1, H264Lossy`: the strategy.
    - `String name()`;
    - `boolean lossless()`;
    - `List<String> rgbArguments()`;
    - `List<String> grayArguments()`, which throws for a lossy codec;
    - `JsonObject toJson()`, holding `{name, crf?}`.
  - Implementations: `record H264Lossless()`, `record Ffv1()`, `record H264Lossy(int crf)`
    with `crf` in 0–51. Each holds the ffmpeg arguments from the decisions table.
  - `final class ClipCodecs`:
    - `static ClipCodec fromJson(JsonObject)` is the registry by name; an unknown name
      throws `IllegalArgumentException` naming the known ones;
    - `static ClipCodec maskCodecFor(ClipCodec rgb)` returns `Ffv1` for `Ffv1`, and
      `H264Lossless` otherwise.
  - A new compression is one more record that implements `ClipCodec`, plus one registry
    entry.
  - `static ClipVideo rgb(Path file, int width, int height, ClipCodec codec)` and
    `static ClipVideo gray(Path file, int width, int height, ClipCodec codec)` start
    `ffmpeg -f rawvideo -pix_fmt rgb24|gray -s WxH -i - <codec arguments> <file>.partial`;
  - `void write(byte[] frame)`;
  - `void close()` waits for ffmpeg, checks exit code 0, then renames `.partial` to the
    final name;
  - `void abort()` kills ffmpeg and deletes only its own `.partial` file;
  - `static String version()` returns `ffmpeg -version`'s first line;
  - `static void requireAvailable()` throws `IllegalStateException("ffmpeg is required for clip datasets: …")`.
- Produces (Python):
  - `def decode_frames(path: Path, width: int, height: int, mode: Literal["RGB", "L"]) -> Iterator[Image.Image]`
    runs `ffmpeg -i path -f rawvideo -pix_fmt rgb24|gray -`;
  - `def frame_count(path: Path) -> int` uses `ffprobe -count_frames`.

- [ ] **Step 1: Write the failing tests.**
  - `ClipVideoTest.roundTripIsPixelExact`, parameterised over both codecs: write 60
    64×32 RGB frames of a shape sliding across a gradient, and 60 gray frames of 0/255.
    Decode them with `ffmpeg … -f rawvideo -` and assert the bytes are equal. This covers
    H.264's predicted frames, beyond its keyframes.
  - `abortLeavesNoFile`: after `abort()`, neither the `.partial` nor the final file
    exists.
  - `ffmpegFailureRaises`: `close()` on a writer whose process was killed throws
    `IOException`, and no final file exists.
  - `lossyCodecIsCloseNotExact`: `H264Lossy(18)` round-trips with a mean absolute error
    under 2 levels, and the codec reports `lossless() == false`.
  - `masksAlwaysLossless`: `maskCodecFor(new H264Lossy(18))` is lossless, and
    `new H264Lossy(18).grayArguments()` throws.
  - `unknownCodecNamed`: `fromJson({"name": "x"})` throws, naming `h264-lossless`, `ffv1`
    and `h264-lossy`.
  - Python `test_decode_matches_written_pixels`: write frames with an `ffmpeg` subprocess,
    then check that `decode_frames` returns those pixels and `frame_count` is 60, for both
    codecs.
- [ ] **Step 2: Run them and see them fail.**
  - Java: `cd packages/honey-factory && ./gradlew test --tests '*ClipVideoTest*'`.
  - Python: `just test honey-factory-tools`.
- [ ] **Step 3: Implement `ClipVideo` and `clips.decode_frames`/`frame_count`.**
  - Pipe frames from a writer thread so ffmpeg's stdout and stderr never block.
  - Keep the last 4 KB of stderr for the error message.
- [ ] **Step 4: Run the tests and see them pass.**
- [ ] **Step 5: Commit.** Message: `feat(honey-factory): store clip frames as lossless video`.

### Task 2: The clip contract

**Files:**
- Create: `schemas/honey-factory/1.0/clip.schema.json`, `schemas/honey-factory/1.0/frame.schema.json`
- Modify: `schemas/honey-factory/1.0/dataset.schema.json`
  - optional `kind`: `"stills"` or `"clips"`;
  - optional `generator.clips`;
  - `counts.accepted_clips`.
- Modify: `schemas/honey-factory/1.0/scene.schema.json`: optional `accepted_clip_count`.
- Modify: `schemas/honey-factory/README.md`: the clip kind, files, IDs and seed purposes.
- Create (Java contract): `contract/ClipRecord.java`, `contract/FrameRecord.java`, `contract/DatasetKind.java`
- Modify: `contract/DatasetManifest.java` (`kind`, `ClipSettings`), `contract/Ids.java` (`clipId`, `clipDirectory`), `contract/SceneSeeds.java` (clip purposes)
- Modify (Python): `schemas.py` (kinds `clip` and `frame`), `ids.py` (`clip_id`, `clip_directory`), `records.py` (`clip_errors`, `frame_errors`)
- Test: `tests/integration/fixtures/honey-factory/1.0/valid/clip-*.json`, `frame-*.json`, and invalid counterparts marked `schema` or `semantic`; `GoldenVectorsTest` and `test_ids.py` with clip IDs and seeds; `ClipRecordTest.java`; `test_schemas.py`

**Interfaces:**
- Produces:
  - `enum DatasetKind { STILLS, CLIPS }`; a manifest without `kind` reads as `STILLS`.
  - `enum ClipType { APPROACH, PASS_BY, SCAN, OCCLUSION, DETOUR, NO_HONEY }`, a JSON enum.
  - `enum TeacherMode { TRACK, REACQUIRE, SEARCH }`.
  - `record ClipRecord(String clipId, String sceneId, Split split, int clipIndex, ClipType requestedType, ClipType clipType, Seeds seeds, CameraPose start, int framesPerTick, int tickCount, int frameCount, String endReason, Files files, List<MotorCommand> executed, Summary summary)`.
    - `Files` holds `rgb`, `visible_mask` and `frames`, each with a path and a SHA-256.
    - `Summary` holds `drift_ticks`, `reached` (`Boolean`, null unless approach),
      `coverage_final` and `ticks_by_mode`.
  - `record FrameRecord(int frame, int tick, int subFrame, CameraPose pose, double partialTick, SampleRecord.Target target, TargetStatus status, boolean touchesEdge, @Nullable Bearing bearing, @Nullable TickLabels tick)`.
    - `Bearing(double azimuthDegrees, double elevationDegrees, double distanceBlocks)`.
    - `TickLabels(BodySenses senses, double[] velocity, long serverTick, MotorCommand executed, MotorCommand teacher, TeacherMode mode, Belief belief, boolean drifting, double coverage)`.
    - `Belief(String source, int @Nullable [] position, int observedTick)`; `source` is
      `seen`, `memory` or `none`.
  - Each has `toJson()` and `fromJson()`, as `SampleRecord` does.
  - Python: `records.clip_errors(clip) -> list[str]` and
    `records.frame_errors(frame, width, height) -> list[str]` check the single-record
    rules. `TickLabels` is present iff `sub_frame == frames_per_tick` or `tick == 0`.

- [ ] **Step 1: Write the fixtures and failing tests.**
  - Valid clip and frame fixtures.
  - Invalid fixtures:
    - a frame with `tick` labels on `sub_frame` 1 (semantic);
    - a `belief.source` of `seen` with a null position (semantic);
    - an unknown `clip_type` (schema);
    - a manifest with `kind: "clips"` but no `generator.clips` (semantic).
  - Golden vectors: `clip_id(12, 3) == "s000012-c03"` and the three clip seed purposes,
    whose values come from the Java implementation and are checked in Python.
- [ ] **Step 2: Run them and see them fail.**
  - Java: `./gradlew test --tests '*GoldenVectorsTest*' --tests '*ClipRecordTest*'`.
  - Python: `just test honey-factory-tools`.
- [ ] **Step 3: Implement the schemas, the Java records, the IDs and seeds, and the Python
  kinds and record checks.**
  - Insert schema members by hand; never reformat a schema file.
- [ ] **Step 4: Run the tests and see them pass.** Also run `just test-integration`.
- [ ] **Step 5: Commit.** Message: `feat(schemas): add the clip dataset kind`.

### Task 3: Clip generator with scripted scan and pass-by (first video)

**Files:**
- Create in `packages/honey-factory/src/main/java/io/github/thedeadbolt/honeyfactory/clip/`:
  - `ClipEnvironment.java`: the interface;
  - `ClipGenerator.java`: scene slots → clips → files and records;
  - `ClipDriver.java`: `ScanDriver` and `PassByDriver` for now;
  - `ClipLabels.java`: frame labels from a capture and a pose;
  - `ClipWriter.java`: per clip, two `ClipVideo`s plus `frames.jsonl`, atomically.
- Create: `clip/Teacher.java`: the interface, with a development-only `Teacher.idle()`. It
  returns `MotorCommand.IDLE`, mode `SEARCH` and belief `none`; Task 7 replaces it in the
  generator.
- Modify: `config/ConfigResolver.java`, `config/ResolvedConfig.java`
  - add a `clips` section: `frames_per_tick`, `clips_per_scene`, `ticks` per type, `mix`,
    `drift {rate, mean_ticks, max_turn, max_forward}`, `teacher {min_visible_pixels}`,
    `codec {name, crf?}` (default `{"name": "h264-lossless"}`);
  - it enters the configuration hash;
  - its presence makes the dataset `kind: "clips"`;
  - a clips config defaults the capture to `PANORAMA_310` at 2480 × 1280 with 1024²
    faces, the same as the still datasets. `PERSPECTIVE` is still accepted but not used.
    A `ConfigResolverTest` case checks the default.
- Create: `client/generation/RuntimeClipEnvironment.java`, which adapts
  `EnvironmentRuntime.controlReset(…, framesPerTick)`, `controlStep` and `controlEnd`.
- Modify: `client/generation/DatasetRuns.java`, which starts `ClipGenerator` when the
  resolved config's kind is `CLIPS`.
- Test:
  - `tests/honey-factory/java/.../clip/ClipGeneratorTest.java`, with a scripted
    `ClipEnvironment` returning synthetic captures;
  - `ClipLabelsTest.java`;
  - `ConfigResolverTest` additions;
  - gametest `M12ClipTest.java`, run only when named.

**Interfaces:**
- Produces:
  - `interface ClipEnvironment`:
    - `CompletableFuture<Step> reset(ArenaLayout arena, ScenePlan plan, CameraPose start, CaptureProjection projection, long seed, int framesPerTick)`;
    - `CompletableFuture<Step> step(MotorCommand executed, MotorCommand teacher)`;
    - `void end()`;
    - `preparePatch`, `software()` and `renderEnvironment()` as in `GenerationEnvironment`;
    - `record Step(ControlStep step, List<Frame> frames)` and
      `record Frame(CameraPose pose, double partialTick, CapturedImages images)`.
  - The adapter calls `controlStep(executed, teacher, Executed.STUDENT)`: the clip's
    executed command travels in the student slot and the teacher's in the teacher slot.
  - `interface Teacher`:
    - `Decision decide(TeacherInput input)`;
    - `void reset(TeacherContext context)`;
    - `record Decision(MotorCommand command, TeacherMode mode, FrameRecord.Belief belief)`.
    - `TeacherInput` and `TeacherContext` are defined in Task 5. Here they are minimal
      records with the fields Task 5 names.
  - `interface ClipDriver`: `MotorCommand next(int tick, TeacherInput input, Teacher.Decision teacher)`.
    - `ScanDriver` turns in place at a constant rate drawn from [4, 9] °/tick and a random
      sign.
    - `PassByDriver` walks forward along a line chosen so that the honey's closest approach
      is 2–5 blocks, at 20–70° off the initial bearing. It steers to hold that line.
  - `ClipLabels.frame(…)` returns a `FrameRecord` from a `Frame`, the target cell and the
    projection, reusing `TargetStatus.derive` and `MaskStatistics`.
- Rules:
  - A clip is rejected and redrawn with the next attempt when:
    - a step fails with `OUT_OF_ARENA`;
    - the body falls more than 3 blocks;
    - ffmpeg fails.
  - After 20 failed attempts the clip slot is skipped and counted.
  - The scene is kept whenever any of its clips succeeded.

- [ ] **Step 1: Write the failing tests.**
  - `ClipGeneratorTest.writesClipsWithFrameCountsAndValidRecords`: a scripted environment,
    2 scenes × 2 clips, 4 ticks, 3 frames per tick → 13 frames per clip and 13 frame
    records. Tick labels appear only on frames 0, 3, 6, 9 and 12. Videos decode to 13
    frames.
  - `rejectsAndRedrawsAfterOutOfArena`: the environment fails step 2 of attempt 0. The clip
    is written from attempt 1, and the record's seeds use attempt 1.
  - `ffmpegMissingFailsBeforeFirstScene` (Review Focus 3): `ClipVideo` is stubbed to throw
    in `requireAvailable`. The run fails with that message, and no clip folder exists.
  - `ClipLabelsTest`:
    - the bearing of a honey 90° to the right is azimuth +90;
    - a target outside the projection gives `out_of_view` and still a non-null bearing;
    - an absent target gives a null bearing and `absent`;
    - a mask touching column 0 gives `touchesEdge = true`, and the frame is not rejected.
  - Gametest `M12ClipTest` (named `M12Clip`): a level-1, 2-scene clips config with scan
    and pass-by only and 32 ticks, in `PANORAMA_310` at 2480 × 1280 with 1024² faces. It
    asserts:
    - the dataset completes;
    - the manifest's capture mode is `PANORAMA_310`;
    - each clip's videos are 2480 × 1280 (checked with `ffprobe`) and have 97 frames;
    - honey at the seam: in one scan clip, some frame's status changes between
      `out_of_view` and a visible status, and its labels match its own mask (Review Focus
      1).
- [ ] **Step 2: Run the JUnit tests and see them fail.**
- [ ] **Step 3: Implement.**
  - The config section, `ClipEnvironment` and its client adapter.
  - `ClipGenerator`: slots from `SceneSlots`, as `DatasetGenerator`; start poses from
    `SceneGenerator.viewpoint`.
  - The two drivers, `ClipLabels` and `ClipWriter`.
  - The generator writes `dataset.json` with `kind: "clips"`, `scenes.jsonl` and
    `clips.jsonl`. Validation hooks in at the end; Task 9 fills in the clip checks.
- [ ] **Step 4: Run the JUnit tests and see them pass.** Then ask the user before launching:
  `./gradlew runClientGameTest -Dhoneyfactory.gametests=M12Clip`. Expected: PASS, with
  clips under `build/m12-clip/`.
  - Then measure the codecs on these real panorama clips.
  - Re-encode one clip's RGB with each codec from its decoded frames: `h264-lossless`,
    `ffv1`, and `h264-lossy` at crf 18 and 23.
  - Record size, encode frames per second, and decode time for the whole clip and for one
    random frame, in `build/m12-clip/codecs.json`.
  - Report this to the user. It confirms or changes the default before the pilot.
- [ ] **Step 5: Commit.** Message: `feat(honey-factory): generate motion clips with scripted scan and pass-by`.

### Task 4: The inspector plays clips

**Files:**
- Modify: `packages/honey-factory-tools/src/honey_factory_tools/catalog.py`: load clip
  datasets, meaning `clips.jsonl` and frames on demand.
- Modify: `inspector.py` with these routes:
  - `GET /api/clips` (filters: split, type, level, status seen);
  - `GET /api/clips/<id>` (record and frame records);
  - `GET /api/clips/<id>/frames/<n>.png?scale=<0.25|0.5|1>`;
  - `GET /api/clips/<id>/masks/<n>.png?scale=`.
- Modify: `static/index.html`, `app.js`, `app.css`: a clip view.
- Modify: `clips.py`: `class ClipFrames`, which decodes a clip once at a scale into an LRU
  cache of PNG bytes (8 clips).
- Test: `tests/honey-factory-tools/test_inspector.py` additions; a fixture clip dataset
  built with ffmpeg in `conftest.py` (`make_clip_dataset`)

**Interfaces:**
- Consumes: `clips.decode_frames` (Task 1); clip and frame records (Task 2).
- Produces: `ClipFrames(path_rgb: Path, path_mask: Path, width: int, height: int)` with
  `frame_png(n: int, scale: float) -> bytes` and `mask_png(n: int, scale: float) -> bytes`.
  Scaling uses ffmpeg `-vf scale=…:flags=neighbor` for masks and `area` for RGB.
- UI requirements (spec):
  - play and pause;
  - frame stepping (`,` and `.` keys);
  - speed 0.25× to 2× at 60 fps nominal;
  - a scrub bar;
  - a mask overlay toggle;
  - the bounding box from the frame's mask statistics;
  - a canvas timeline under the player with three lanes: honey status (colour per
    `TargetStatus`), bearing azimuth (a line over −155° to +155°) and teacher mode (colour
    per mode), plus drift ticks shaded;
  - clicking the timeline seeks.

- [ ] **Step 1: Write the failing tests.**
  - `test_clip_list_and_record`: the fixture dataset lists 2 clips, and `/api/clips/<id>`
    returns 13 frames.
  - `test_frame_png_is_scaled`: the frame PNG at scale 0.5 is 32×16, and frame 12 decodes
    to the fixture's pixels at scale 1.
  - `test_unknown_frame_404`.
  - `test_still_dataset_unchanged`: the existing inspector tests still pass.
- [ ] **Step 2: Run them and see them fail.** Command: `just test honey-factory-tools`.
- [ ] **Step 3: Implement the catalog, routes, `ClipFrames` and the clip view.**
  - The player prefetches all frames of the clip at the chosen scale before playing, then
    draws them on a canvas with `requestAnimationFrame`.
- [ ] **Step 4: Run the tests and see them pass.** Then open the inspector on
  `build/m12-clip/` and check the clips play, by hand or through the browser.
- [ ] **Step 5: Commit.** Message: `feat(honey-factory-tools): play clips in the inspector`.

### Task 5: What the teacher may know

**Files:**
- Create in `packages/honey-factory/src/main/java/io/github/thedeadbolt/honeyfactory/teacher/`:
  - `TeacherInput.java`
  - `TeacherContext.java`
  - `SeenMap.java`
  - `HoneyMemory.java`
  - `Perception.java`
- Test: `tests/honey-factory/java/.../teacher/SeenMapTest.java`, `PerceptionTest.java`

**Interfaces:**
- Produces:
  - `record TeacherContext(SightGrid grid, BlockBox walkable, MotorLimits limits, int minVisiblePixels, double eyeHeight, CaptureProjection projection)`.
    `walkable` is the arena core in arena coordinates.
  - `record TeacherInput(int tick, CameraPose pose, BodySenses senses, int @Nullable [] honeySeen, SeenMap seen, HoneyMemory memory)`.
    It has no other path to the honey.
  - `final class Perception` is the only code that reads the true target. It exposes
    `static TeacherInput perceive(int tick, CameraPose pose, BodySenses senses, int @Nullable [] trueTarget, int visiblePixels, TeacherContext context, SeenMap seen, HoneyMemory memory)`.
    - It sets `honeySeen = trueTarget` iff `visiblePixels >= context.minVisiblePixels()`,
      and otherwise null.
    - It updates `memory` and `seen` first.
  - `final class SeenMap`:
    - `void observe(double[] eye, float yaw, float pitch, CaptureProjection projection, SightGrid grid)` marks every cell whose centre lies in the projection and within 32 blocks, with a clear line through `grid.blocks`;
    - `boolean seen(int x, int y, int z)`;
    - `double coverage(Set<Long> reachable)`;
    - `List<int[]> frontiers(Set<Long> reachable)`: walkable floor cells that are seen and
      have an unseen walkable neighbour.
  - `final class HoneyMemory`:
    - `void sawAt(int[] cell, int tick)`;
    - `int @Nullable [] lastSeen()`;
    - `int ticksSinceSeen(int tick)`, which is `Integer.MAX_VALUE` if never seen.
- [ ] **Step 1: Write the failing tests.**
  - `PerceptionTest.belowThresholdHidesHoney`: 49 pixels gives `honeySeen == null`; 50
    gives the cell (Review Focus 4).
  - `memoryKeepsLastSeenPosition`: the honey is seen at tick 3 and not at 4–9.
    `lastSeen` is tick 3's cell and `ticksSinceSeen(9) == 6`.
  - `SeenMapTest.wallHidesCellsBehindIt`: on a `SolidGrid` with a wall, cells behind it
    stay unseen and cells in front are seen.
  - `outsideProjectionNotSeen`: with a 90° perspective, cells behind the eye are unseen.
  - `frontiersLieOnTheSeenEdge`: every frontier is seen and has an unseen walkable
    neighbour.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement.**
  - Projection test: reuse `CaptureProjection.intersects` on the cell's box.
  - Line test: walk the eye-to-centre segment with the existing `SightLines` voxel walk.
- [ ] **Step 4: Run the tests and see them pass.**
- [ ] **Step 5: Commit.** Message: `feat(honey-factory): give the teacher only what the fly could know`.

### Task 6: Track, with pathfinding

**Files:**
- Create in `teacher/`:
  - `WalkGraph.java`
  - `PathPlanner.java`
  - `Steering.java`
  - `TrackBehaviour.java`
- Create: `teacher/BodySim.java`, a JUnit-only kinematic body for closed-loop tests: player
  walk speed 0.2158 blocks/tick, `MotorState.apply`, auto-jump of 1 block. It lives in the
  test source set.
- Test: `tests/honey-factory/java/.../teacher/PathPlannerTest.java`, `TrackBehaviourTest.java`

**Interfaces:**
- Produces:
  - `WalkGraph.of(SightGrid grid, BlockBox walkable, SeenMap seen)`. Nodes are floor cells
    with air at the feet and head. Edges join the 8 neighbours, with step-ups of 1 and drops
    of up to 3 blocks. A diagonal edge exists only when both orthogonal cells are free.
    Unseen cells count as free.
  - `PathPlanner.path(WalkGraph graph, int[] from, int[] goal) -> @Nullable List<int[]>`,
    A* with octile distance.
  - `Steering.toward(CameraPose pose, MotorState motor, double[] point, MotorLimits limits) -> MotorCommand`.
    - It sets turn rate = clamp(1.0 × yaw error, ±9).
    - Pitch aims the honey or the horizon, at clamp(0.8 × pitch error, ±6).
    - forward = 1 when the absolute yaw error is under 30°, scaling down to 0 at 90°.
    - jump is never set; auto-jump handles steps.
  - `TrackBehaviour.decide(TeacherInput input, TeacherContext context) -> Teacher.Decision`.
    - It plans a path to the cell beside the honey.
    - It steers at the path point 2 cells ahead. When the straight line is walkable, it
      steers at the honey itself.
    - It stops (forward 0) within 1.5 blocks and keeps the honey centred.
- [ ] **Step 1: Write the failing tests.**
  - `PathPlannerTest.goesAroundAWall`: the path avoids the wall cells and is no more than
    20% longer than the optimal grid path.
  - `noPathReturnsNull`.
  - `stepUpOfOneAllowedTwoNot`.
  - `TrackBehaviourTest.reachesHoneyOnOpenPlatform`: 50 random starts 3–16 blocks away,
    with `BodySim` and the honey visible throughout. The honey is reached within 100 ticks
    in ≥ 95% of starts; the exit gate's bar is tested here first.
  - `commandsWithinLimits`: every command passes `MotorLimits.DEFAULT.check`.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement `WalkGraph`, `PathPlanner`, `Steering` and `TrackBehaviour`.**
- [ ] **Step 4: Run the tests and see them pass.**
- [ ] **Step 5: Commit.** Message: `feat(honey-factory): teach tracking with pathfinding around seen obstacles`.

### Task 7: Re-acquire, Search and the full teacher

**Files:**
- Create in `teacher/`:
  - `ReacquireBehaviour.java`
  - `SearchBehaviour.java`
  - `RealizableTeacher.java`
- Modify: `clip/ClipGenerator.java`, which uses `RealizableTeacher` and drops `Teacher.idle()`
  from production paths.
- Test: `tests/honey-factory/java/.../teacher/RealizableTeacherTest.java`, `SearchBehaviourTest.java`

**Interfaces:**
- Produces:
  - `RealizableTeacher implements Teacher`. Mode rules:
    - **Track** when `honeySeen != null`.
    - **Re-acquire** when the honey was seen within the last 60 ticks. The teacher turns
      to the last-seen bearing, walks to the last-seen cell, then searches frontiers
      within 6 blocks of it.
    - **Search** otherwise.
  - `SearchBehaviour`:
    - The goal is the frontier that minimises path length − 2 × (unseen walkable cells
      within 4 blocks).
    - On arrival, the teacher scans in place: a full turn at 9 °/tick (40 ticks), then
      picks the next frontier.
    - With no frontiers, it scans in place.
  - `Belief`:
    - `seen` with the cell when tracking;
    - `memory` with `lastSeen` when re-acquiring;
    - `none` with a null position when searching.
    - `observedTick` is t−1.
- [ ] **Step 1: Write the failing tests.**
  - `RealizableTeacherTest.neverUsesHiddenHoney`: random scripted inputs where the honey is
    hidden. The decision's belief is never `seen`. A teacher given a different true target
    while hidden makes an identical decision. The second clause is the realizability
    property, proven by construction.
  - `switchesTrackToReacquireToSearch`: seen through tick 10, hidden after. The modes are
    Track up to tick 11, Re-acquire for ticks 11–70, then Search.
  - `SearchBehaviourTest.coverageGrowsSystematically`: on an empty and a cluttered
    `SolidGrid`, with `BodySim`, coverage after 200 ticks is ≥ 0.9 of reachable cells. It
    grows faster than a random-walk baseline by at least 1.5× at tick 100.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement the behaviours and `RealizableTeacher`, and wire it in.**
- [ ] **Step 4: Run all JUnit tests and see them pass.**
- [ ] **Step 5: Commit.** Message: `feat(honey-factory): add re-acquire and frontier search to the teacher`.

### Task 8: Drift and the remaining clip types

**Files:**
- Create in `clip/`:
  - `Drift.java`
  - `ApproachDriver.java`
  - `OcclusionDriver.java`
  - `DetourDriver.java`
  - `NoHoneyDriver.java`
  - `ClipStarts.java`: start pose and type per clip.
- Modify: `ClipGenerator.java`
- Test: `tests/honey-factory/java/.../clip/DriftTest.java`, `ClipStartsTest.java`, `ClipGeneratorTest` additions

**Interfaces:**
- Produces:
  - `Drift(long seed, double rate, int meanTicks, double maxTurn, double maxForward)`:
    - `MotorCommand apply(int tick, MotorCommand teacher)`;
    - `boolean active(int tick)`.
    - Episodes start with a per-tick probability chosen so the expected active share equals
      `rate`. Lengths are geometric with mean `meanTicks` (default 6).
    - The offset is a smooth raised-cosine bump in turn rate (up to ±`maxTurn`, default
      6 °/tick) and in forward (up to ±`maxForward`, default 0.5). It is clamped to the motor
      limits.
  - Drivers:
    - **Approach and no honey:** the teacher's command plus drift.
    - **Occlusion:** a start whose straight walk to a waypoint behind a blocker relative to
      the honey makes the honey line pass a blocking cell for at least 6 ticks. It is
      chosen by `SightLines.clearFraction` checks along the planned line, plus drift.
    - **Detour:** a start with `clearFraction == 0` to the honey whose shortest walk is at
      least 1.3 × the straight distance. The teacher drives, plus drift. Up to 100 ticks.
  - `ClipStarts.choose(GeneratedScene scene, SceneGenerator generator, ClipType requested, long seed, int attempt) -> Start(ClipType type, CameraPose pose)`.
    - It draws from `generator.viewpoint`.
    - Scan and no-honey starts turn to a random yaw.
    - It falls back to `APPROACH` when occlusion or detour finds no qualifying start in 30
      draws.
- [ ] **Step 1: Write the failing tests.**
  - `DriftTest.activeShareNearRate`: over 100,000 ticks the share is 0.10 ± 0.01, and the
    mean episode length is 6 ± 0.5.
  - `driftStaysWithinLimits`.
  - `driftIsDeterministicPerSeed`.
  - `ClipStartsTest.detourStartIsBlocked`: on a scene with a wall between start and honey,
    detour starts have `clearFraction == 0` and walk ratio ≥ 1.3.
  - `level1FallsBackToApproach`: on an empty platform, detour falls back to approach and
    records `requested_type = detour`.
  - `ClipGeneratorTest.passByTowardEdgeRedraws` (Review Focus 2): a scripted environment
    returning `OUT_OF_ARENA`. The clip is redrawn, and the scene is kept.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement `Drift`, the drivers and `ClipStarts`, and wire them in.**
- [ ] **Step 4: Run the tests and see them pass.** Then ask before rerunning `M12Clip`,
  extended to all six types on level 3.
- [ ] **Step 5: Commit.** Message: `feat(honey-factory): add drift and the approach, occlusion, detour and no-honey clips`.

### Task 9: Validating clip datasets

**Files:**
- Create: `dataset/ClipConsistency.java`
- Modify: `dataset/DatasetValidator.java`, which dispatches on `DatasetKind`.
- Modify (Python): `dataset.py`, which dispatches on `kind`; new `clip_consistency(...)` and
  `clip_file_errors(...)`.
- Test: `tests/honey-factory/java/.../dataset/ClipConsistencyTest.java`, `tests/honey-factory-tools/test_dataset.py` clip cases, shared fixtures under `tests/integration/fixtures/honey-factory/1.0/clips/`

**Interfaces:**
- Produces, in both languages, the same rules:
  - **References:** every clip's scene exists, and the clip's split equals the scene's
    split.
  - **Counts:** `accepted_clip_count` per scene, `counts.accepted_clips`, and quotas met.
  - **Files:**
    - files exist and their SHA-256s match;
    - each video's frame count equals `frame_count = 1 + tick_count × frames_per_tick`;
    - `frames.jsonl` has that many records, with frames in order and `tick` and
      `sub_frame` consistent.
  - **Labels match pixels:** decode the visible mask and recompute pixel count and box per
    frame. A full validation does this for every frame; `--quick` samples 5% of clips.
  - **Realizability, on every tick record:**
    - `belief.source == "seen"` ⇒ the previous tick's end-frame `visible_pixels` ≥
      `teacher.min_visible_pixels`, and the position equals the scene's target cell;
    - `memory` ⇒ the position equals the most recent `seen` position in the clip;
    - `none` ⇒ the position is null;
    - no `seen` in a `no_honey` clip.
  - **Commands:** `executed` in the clip record equals the tick records' executed commands
    in order. Every command is within `MotorLimits` from the manifest.
- [ ] **Step 1: Write the fixtures and failing tests.**
  - One semantic fixture per rule above, including a `seen` belief at 49 pixels and a
    `memory` position that differs from the last seen one.
  - A valid two-clip fixture passes in both validators.
- [ ] **Step 2: Run them and see them fail.**
  - Java: `./gradlew test --tests '*ClipConsistencyTest*'`.
  - Python: `just test honey-factory-tools` and `just test-integration`.
- [ ] **Step 3: Implement both validators.**
- [ ] **Step 4: Run the tests and see them pass.** Also validate the Task 8 gametest output
  with `honey-factory-tools validate-dataset build/m12-clip/<id>`.
- [ ] **Step 5: Commit.** Message: `feat(honey-factory): validate clip datasets, including the teacher's realizability`.

### Task 10: Slices, merge, replay and the reader

**Files:**
- Modify: `ClipGenerator.java`, which honours `output.slice` exactly as `DatasetGenerator`
  does.
- Modify: `merge.py`, which merges clip slices (hard-links `clips/`, concatenates and sorts
  `clips.jsonl`).
- Modify: `orchestrate.py`, which needs no change beyond accepting clip configs. Check
  this.
- Create: `replay/ReplayClip.java` (in main), which rebuilds a clip from its scene and
  executed commands.
- Modify: `client/replay/ReplayRuns.java`, `ReplayCommands.java`: `/honeyfactory replay clip <dataset> <clip id>`
- Modify: `clips.py`: the public reader.
- Test:
  - `test_merge.py` clip cases;
  - `test_clips.py` reader tests;
  - gametest `M12ClipReplayTest.java`, run only when named, which generates 2 clips per
    level 1–5, replays them and compares.

**Interfaces:**
- Produces:
  - `honey_factory_tools.clips.open_clip(dataset: Path, clip_id: str) -> Clip`.
    `Clip.record` is a dict, and `Clip.frames()` is an
    `Iterator[ClipFrame(index, rgb: Image.Image, visible_mask: Image.Image, labels: dict)]`.
  - `iter_clips(dataset: Path, split: str | None = None) -> Iterator[Clip]`.
  - `ReplayClip.verify(…)`:
    - masks, and the true state (pose to 1e-6 and target statistics), must match on every
      frame;
    - RGB must match exactly unless the scene has animated blocks (level 5 water and lava,
      rain), or the dataset's RGB codec is lossy;
    - it writes `metrics/clip-replay.json`.
- [ ] **Step 1: Write the failing tests.**
  - `test_merge_clip_slices_equals_single_run` (Review Focus 5): fixture slices 0/2 and 1/2
    merge to the same `clips.jsonl` and file hashes as a 1-slice fixture.
  - `test_merge_refuses_mixed_kinds`.
  - `test_reader_yields_labels_with_frames`.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement slicing, merging, `ReplayClip`, the command and the reader.**
- [ ] **Step 4: Run the tests and see them pass.** Then ask before launching
  `-Dhoneyfactory.gametests=M12ClipReplay`. Expected: masks and truth replay exactly on all
  levels, and RGB exactly on levels 1–4.
- [ ] **Step 5: Commit.** Message: `feat(honey-factory): slice, merge, replay and read clip datasets`.

### Task 11: The pilot and the exit gate

**Files:**
- Create: `packages/honey-factory-tools/src/honey_factory_tools/clip_report.py`, plus the
  CLI `clip-report DATASET...`.
- Create: pilot configs `packages/honey-factory/configs/m12-2-pilot-level{1..5}.json`,
  each with 50 scenes × 4 clips = 200 clips, in `PANORAMA_310` at 2480 × 1280 with 1024²
  faces.
- Create: `docs/adr/0023-motion-clips-and-teacher.md` and an ADR README row.
- Create: `packages/honey-factory/docs/m12-2-motion-clips.md` (findings).
- Modify: roadmap (M12-2 done), operating docs ("Generating clips") and spec ("Motion
  clips").
- Test: `tests/honey-factory-tools/test_clip_report.py`

**Interfaces:**
- Produces: `clip-report`, which writes `clip-report.json` and prints a table:
  - approach success without drift on platform levels: the share of approach clips with
    `reached`, among clips whose drift ticks are 0, plus a separate figure for all;
  - search coverage curves: the mean coverage at ticks 8, 16 and 32 for no-honey clips;
  - realizability violations, from the validator (must be 0);
  - status and mode distributions;
  - time per clip, generation frames per second, disk per clip, and the compression ratio
    against the PNG size estimated from M9's figures.

- [ ] **Step 1: Write `test_clip_report.py`** on the Task 9 fixtures: the numbers it
  reports.
- [ ] **Step 2: Implement `clip_report`, run the test, and commit.** Message:
  `feat(honey-factory-tools): report pilot clip metrics`.
- [ ] **Step 3: Ask the user, then run the pilot in the daytime.** Command:
  `honey-factory-tools generate packages/honey-factory/configs/m12-2-pilot-level<N>.json --instances <N> --root /mnt/fastvault/minesophila/pilot/m12-2`.
  Run it per level.
- [ ] **Step 4: Run the exit gate.**
  - Validate every pilot dataset with both validators.
  - Run `clip-report`, which must show approach success ≥ 95% on levels 1–4 and 0
    realizability violations.
  - Replay sampled clips from each level with `M12ClipReplay`, pointed at the pilot.
  - Audit about 20 clips in the inspector together with the user.
  - Run the full suite: `./gradlew build`, the full gametest suite, `just check`.
- [ ] **Step 5: Write the findings doc and ADR 0023 with the measurements and the
  final-volume recommendation (option A or B), then commit.** Message:
  `docs(honey-factory): record M12-2 and the pilot`.

---

## Revision 2026-10-26 evening: a safer teacher and who-drove metadata

The user watched the first pilot and saw bodies miss the honey in frame and walk off the
platform. The cause is the scripted pass-by (a straight line with no floor check); the
teacher also plans unseen space as open floor and steers straight with a centre-line check
only. The generator redraws a clip whose body leaves the arena, which hides the failures and
drops near-edge states from the data. User decisions:

- Keep near-edge data: the teacher must recover, not the generator reject (SAFE-GIL: push the
  expert towards hazards and keep its recoveries).
- Keep scan and pass-by clips, with metadata on who drove each tick; the policy target is
  always the teacher's command. Scripted share falls from 30% to 15%; M13 decides by ablation.
- The pilot stopped after level 1 (kept as the speed and storage baseline); Task 11's
  close-out moves to Task 18.

Tasks 12–18 run in order; each follows the TDD steps of the earlier tasks, and each ends with
`./gradlew test -q` and the tools suite. Commit per task; scopes as before.

### Task 12: Who drove each tick

- Contract (additive): `FrameRecord.TickLabels.driver` (`Driver` enum: `teacher`,
  `teacher_drift`, `script`), on every tick-label frame except tick 0 (null there);
  `ClipRecord.Summary.ticksByDriver` (map, all three keys). Schemas `frame.schema.json`,
  `clip.schema.json`; README; Python `records`.
- `ClipDriver.driver(int tick)` names it: Scan and PassBy `script`; Teacher `teacher` or
  `teacher_drift` on a drift tick; Occlusion `script` until its waypoint, then as Teacher.
- Validators (Java ClipConsistency, Python clip_dataset): `script` only in scan, pass-by and
  occlusion clips; occlusion's `script` ticks precede its other ticks; `teacher_drift` ticks
  equal `summary.drift_ticks`; driver totals match the summary. New cases in
  `clip-cases.json`; fixture regenerated by `make_clips_fixture.py`.
- Reader `ClipFrame.driver`; inspector timeline row for the driver; clip-report counts ticks
  by driver.
- Tests: `ClipDriverTest.namesWhoDrove`, `ClipConsistencyTest` cases, `test_clip_frame_driver`,
  fixture cases `script-in-approach`, `driver-totals-differ`.

### Task 12b: Clip validation off the render thread, without needless decoding

Found in the pilot: at the end of each slice both game windows showed "not responding" for
many minutes. `ClipGenerator.finish` runs the validator on the render thread; it counts
frames with `ffprobe -count_frames` (decodes every full-size frame), decodes the masks again
for the pixel check, and validates twice (candidate, then stored). The decoding is ffmpeg's
C code already, so no Rust or C port: the fix is doing less work, elsewhere.

- Frame counts by `ffprobe -count_packets` (container only, no decoding), Java and Python.
- Each clip's mask check runs once, in a background worker right after the clip is written,
  overlapping generation; the end-of-run pass checks records, hashes and packet counts, and the
  stored pass skips pixels.
- `finish` validates on a worker thread; only `environment.end()` touches the client.
- Tests: `ClipConsistencyTest.countsFramesWithoutDecoding` (a video whose packets are counted),
  `ClipGeneratorTest.validatesOffTheEnvironmentExecutor`, timing in the pilot findings.

### Task 13: An edge-aware teacher, from what it has seen

- `SeenMap.floorSeen(x, y, z)`: true once the block under a standing cell has been seen solid,
  false while unseen. `WalkGraph`: an unseen cell stays plannable (open) for path costs, but the
  first step of a plan and the cells within `LOOK_AHEAD` of the body must have a seen floor; a
  seen cell without floor below (a seen drop deeper than `MAX_DROP`) is never walkable.
- `TrackBehaviour.clear` checks the body's width (`BODY_RADIUS` either side of the line), and
  every column on the line needs a seen floor.
- `Steering`: forward speed scales down to 0 when the next 1.0 block along the heading has no
  seen floor (an edge brake), so even a straight steer stops at an edge.
- Tests: `WalkGraphTest.neverStepsOntoUnseenFloor`, `WalkGraphTest.seenDropIsNotWalkable`,
  `TrackBehaviourTest.clearChecksBodyWidth`, `SteeringTest.brakesAtAnEdge`,
  `RealizableTeacherTest.honeyAcrossAGapDoesNotWalkOff` (platform with a 2-block gap, honey
  beyond it: the body ends on the platform).

### Task 14: An edge guard on the executed command; a pass-by that stays on floor

- `EdgeGuard` (clip package) uses the generator's true layout (scripts may know it; the
  teacher may not): it limits forward and strafe so the body's footprint after the tick keeps
  floor under it, by simulating the tick with `BodySim`. It applies to every executed command;
  the teacher's label is never changed. `TickLabels.guarded` (boolean) records that it acted;
  the summary counts `guarded_ticks`.
- `PassByDriver`: chooses, among the side and offset draws, a line whose first `ticks` of
  walking stay on floor; if none, the shortest such line, and the guard stops it at the edge.
- Tests: `EdgeGuardTest.stopsAtThePlatformEdge`, `EdgeGuardTest.leavesSafeCommandsAlone`,
  `PassByDriverTest.lineStaysOnFloor`; validator case `guarded-count-differs`.

### Task 15: Drift towards hazards

- `Drift` gains a hazard share (`clips.drift.hazard_fraction`, default 0.3): an episode starts,
  with that probability, as a bump that turns and pushes towards the nearest edge or wall within
  4 blocks (true layout); otherwise as now. In the config hash.
- Tests: `DriftTest.hazardEpisodesPushTowardsTheEdge`, `DriftTest.noHazardKeepsTheOldBumps`,
  config resolution and schema.

### Task 16: Falls, endings and reasons for misses

- A fall (body below the start by more than `MAX_FALL_BLOCKS`, or out of the arena) is no longer
  redrawn silently: the clip still stops and is not written, but `GenerationTally` counts
  `clip_falls` by clip type and the metrics report it; the run logs it with the clip type.
- Detour and occlusion clips end `approach_linger_ticks` after reaching the honey, as approach.
  `summary.reached` is set for approach, detour and occlusion.
- `summary.miss_reason` for those types when not reached: `timeout` (the true shortest path
  from the end pose is shorter than from the start pose by at least 2 blocks), `unreachable`
  (no true path to a cell beside the honey), otherwise `teacher_failure`. Null otherwise.
- clip-report: falls, reach by type, miss reasons; the gate needs falls 0, teacher failures 0,
  reach ≥ 95% on levels 1–4.
- Tests: `ClipGeneratorTest.fallIsCountedNotHidden`, `ClipGeneratorTest.detourEndsOnReach`,
  `MissReasonTest` (three cases), validator cases, `test_report_counts_falls_and_reasons`.

### Task 17: Clip mix

- Default mix: approach 0.35, pass_by 0.075, scan 0.075, occlusion 0.15, detour 0.15,
  no_honey 0.20 (level 1's redistribution unchanged). Pilot configs keep the defaults.
- Tests: `ClipSettingsTest.defaultMix`, `CommittedConfigsTest`.

### Task 18: Checks, pilot again, close-out (was Task 11's remainder)

- `./gradlew build`, gametests M12Clip, M12Frames, M12ReplayClip; `just check`.
- Pilot again (1,000 clips, levels 1–5, 2 instances) at a time the user approves.
- Validate, clip-report gate, replay sample, inspector audit with the user; findings doc
  `packages/honey-factory/docs/m12-2-motion-clips.md` (with level 1 of the first pilot as the
  baseline); ADR 0023 updated (driver, edge-aware teacher, edge guard, hazard drift, falls).
- Final whole-branch self-review.
