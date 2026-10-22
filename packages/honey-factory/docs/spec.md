# honey-factory specification

This document records decisions as they are agreed. It will grow as we discuss the
package; it does not describe implemented features.

## Dataset difficulty levels

Agreed 2026-10-22. Levels 1–5 cover image-based honey localisation, the core project
task. Level 6 adds optional active search through sequences of observations and actions.

| Level | Task | Scene | Blocks and distractions | How honey is hidden | Visibility rule |
|---|---|---|---|---|---|
| 1 — Open platform | Find an obvious target. | Flat concrete platform in a void world; fixed lighting. | One neutral concrete colour; no distractors. | No hiding. Vary target position, camera angle and distance. | Fully visible in positive examples. |
| 2 — Clutter | Distinguish honey from distractors. | The same platform with scattered blocks and small piles. | Several concrete colours, including yellow/orange distractors; introduce a few other opaque full blocks. | No obstruction of the target; surrounding clutter and similar colours make it harder to distinguish. | Fully visible in positive examples. |
| 3 — Partial hiding | Find the visible portion of honey. | Generated walls, pillars, stacks and shallow recesses. | A broader palette of opaque full blocks with varied textures. | Hide part of the honey behind blocks, beside corners or inside recesses. | Partially visible in positive examples; movement is not required to identify the visible pixels. |
| 4 — Constructed environments | Find honey in complex scenes. | Small rooms, courtyards, alleys, ruins and artificial terrain. | Stairs, slabs, fences and vegetation alongside full blocks. Glass/water variants remain deferred until their mask behaviour is defined. | Place honey behind irregular shapes, through gaps, among layers of clutter or in shadow. | At least partly visible in positive examples. |
| 5 — Natural Minecraft worlds | Find honey in actual Minecraft terrain. | Sample locations in generated worlds across terrain, biomes and structures; vary daylight and weather. | The existing world supplies blocks, textures and clutter. | Insert honey in plausible locations with minimal changes to the surroundings. | At least partly visible in positive examples. |
| 6 — Active search (optional) | Turn or move to discover honey. | Episodes in generated arenas or actual Minecraft worlds. | Reuse the earlier scene types, with obstacles and layouts that require changing viewpoint. | Start with honey outside the view or fully behind an obstacle. Progress from turning to moving around pillars, walls or through rooms. | Initially outside the view or fully obstructed; must become visible from a reachable viewpoint using the allowed actions. |

### Shared rules

- Start with one honey block per target-present scene. Multiple targets are a later
  variant. Include target-absent examples in levels 1–5.
- Partial obstruction leaves visible honey pixels. Full obstruction leaves none;
  identifying the hidden location is not an image-only requirement.
- Levels 1–5 may also contain fully hidden views labelled as negative observations.
  An empty mask means no visible honey; metadata distinguishes a target that is
  absent, fully obstructed or outside the view.
- Level 6 records observation/action sequences. Targets must be discoverable without
  breaking blocks; honey sealed inside stone is excluded while mining is unsupported.
- Record distance, target pixel area and visible fraction alongside the level.
  The visibility measurement method and thresholds remain to be defined.
- Vary viewpoint and target position from level 1. Record seeds and generation
  settings, and keep views of the same scene/episode in the same dataset split.
- For level 5, hold out world seeds or regions for evaluation. The exact split
  policy remains to be defined.

## Arena and world-region dimensions

Agreed 2026-10-22: generated arenas and regions sampled from actual Minecraft worlds
both have configurable width, length and height, measured in blocks.

- For generated arenas (levels 1–4), start with a 32 × 32-block footprint and a
  maximum build height of 16 blocks above the floor. These are defaults, not limits.
  Smaller scenes can use a subset of the available space.
- For natural-world sampling (level 5), configure the sampled region dimensions
  independently of arena dimensions. The initial world-region defaults remain open.
- Level 6 uses the dimensions of its selected arena or world-region configuration.
- Record the actual region origin and dimensions with each scene. Width and length
  follow world X and Z; height follows Y. Exact boundary conventions remain to be
  specified with the metadata format.
- A sampled region defines the scene-selection bounds, not a wall or automatic
  clipping boundary. Surrounding terrain may remain visible; render distance and
  the extent of exported world metadata are separate choices.

## Initial capture outputs

Agreed 2026-10-22: the initial export contains RGB images, aligned visible-target
masks and scene/camera metadata. Do not capture or export depth in the initial
dataset; the collaborator's converter is expected to consume images without it.
Depth can be reconsidered later if an experiment needs it.

The RGB image is the sensory input. Masks and target/world metadata are labels
for training and evaluation, not additional sensory channels for the converter.
Final metadata fields and file encodings remain to be agreed.

This excludes exported depth images, not internal depth testing or render buffers
needed to produce correct visibility and masks.

## Viewpoints, lighting and augmentation

Agreed 2026-10-22: capture multiple configurable viewpoints from each scene and
support configurable in-game lighting conditions. Image augmentation is a separate,
optional derivation step.

### Viewpoints

- Configure the number of viewpoints per scene, camera-position sampling, target
  distance range, yaw and pitch ranges. Initial defaults are 8 viewpoints and a
  horizontal target distance of 3–16 blocks.
- Sample the camera position across the full 360-degree azimuth around the target,
  subject to scene bounds and reachability. Aim the base view at the target centre,
  then apply configurable orientation jitter; initial defaults are ±15 degrees yaw
  and ±10 degrees pitch. The visibility check below remains authoritative.
- Treat all values above as configuration defaults, not format limits. Store their
  resolved values in the dataset manifest so later tuning is reproducible.
- Use the first-person player eye height agreed below. Camera positions must be
  valid for the scene: outside solid geometry and on, or reachable from, supported
  ground unless a dataset variant explicitly permits free cameras.
- For target-present samples in levels 1–5, validate the selected level's visibility
  rule after placing and orienting the camera. Re-sample invalid viewpoints rather
  than silently assigning them to another level.
- Multiple captures may share a scene, but all captures and derived augmentations
  from that scene stay in the same training, validation or test split.
- Record a viewpoint ID, position, orientation and sampling parameters. Level 6
  additionally preserves viewpoint order as part of an episode.

### In-game lighting variation

- Configure time of day and weather. Record their resolved values with the scene or
  capture, depending on whether they remain constant across its viewpoints.
- Begin level 1 with fixed clear daylight as stated in the difficulty table. Add
  configured daylight/time variants progressively; do not let an undocumented
  lighting change redefine a difficulty level.
- Keep client visual settings that affect pixels—gamma/brightness, clouds, render
  distance, graphics mode, resource pack and relevant shader settings—fixed within
  a dataset configuration and record them in its manifest.
- Freeze world and lighting state while capturing panorama faces and matching masks.

### Derived image augmentation

- Support optional paired transforms applied identically to RGB and target mask.
  A horizontal flip is the first agreed transform; it must also transform any
  directional labels and projection metadata that are retained.
- Do not enable vertical flips by default: an upside-down Minecraft world is not a
  normal observation for a walking agent. Other transforms such as crop, rotation,
  blur, noise or colour changes require separate decisions before use.
- Preserve the source sample ID, transform name and parameters. Augmented copies do
  not count as independent scenes and cannot cross dataset splits.
- Generate real camera and lighting variation in Minecraft where practical. Treat
  augmentation as extra data, not a substitute for scene diversity.

### Target-mask meaning

Agreed 2026-10-22: the binary mask marks visible target geometry. For honey,
background showing through the honey itself does not remove those pixels from the
mask. Labels come from visibility and geometry, not a colour threshold.

| Situation | Mask behaviour |
|---|---|
| Honey visible against sky, floor or other background | Mark its visible surface. |
| Background visible through honey's own translucent surface | Still mark the honey surface. |
| Opaque geometry hides part of the honey | Mark only the exposed portion. |
| Opaque geometry hides all of the honey | Empty mask; metadata records the hidden target. |
| Honey seen through intervening glass or water | Deferred until those scene variants are introduced. |

Honey remains the initial target. If the capture prototype cannot produce reliable
labels for it, substituting another block is an acceptable fallback to discuss.
Record the target block type in scene metadata so any change remains explicit.
The mask algorithm and raster-edge sampling rules remain open.

## Camera and selectable capture modes

Agreed 2026-10-22: support both a fixed 90-degree view and a 310-degree panoramic
view, selected through one capture-mode setting. These are generator requirements;
the renderer is not implemented yet.

- Capture from a first-person position at player eye height, with HUD, crosshair
  and player hand hidden. Interface/hand variants can be added later.
- Keep the capture projection fixed while taking a sample; gameplay FOV effects
  must not alter it. Record the actual camera position and orientation.
- Both modes produce colour images and aligned target masks. Scene generation and
  difficulty settings must work with either mode.
- Record the mode, projection, angular bounds and image dimensions in each sample.
  Visibility labels refer to the selected mode: a target outside the 90-degree
  view may be visible in the 310-degree view.

| Mode | Required horizontal coverage | Proposed projection | Capture approach |
|---|---|---|---|
| `PERSPECTIVE_90` | 90 degrees centred on the camera's forward direction | Rectilinear perspective | One forward render |
| `PANORAMA_310` | 310 degrees centred on the camera's forward direction, leaving a 50-degree rear blind sector | Equirectangular (longitude/latitude) | Six square 90-by-90-degree renders from one position, reprojected into one panorama |

The mode names, horizontal-FOV convention and implementation design below are
proposals. Do not interpret 90 as an unverified Minecraft FOV-slider setting.
For perspective output with aspect ratio W/H, a 90-degree horizontal field implies
vertical FOV = 2 atan(tan(45 degrees) × H/W).

### Configurable capture resolution

Agreed 2026-10-22: output resolution is configurable independently of capture mode
and downstream eye-model sampling density. RGB and target masks use the same output
dimensions. Record actual dimensions in each sample; do not hard-code them in readers.

The following higher-resolution presets are proposed starting values, pending tests
with small and partially occluded targets; the configurability is agreed.

| Mode | Proposed output width × height | Proposed internal render size |
|---|---|---|
| `PERSPECTIVE_90` | 1280 × 1280 | One 1280 × 1280 render |
| `PANORAMA_310` | 2480 × 1280 | Six 1024 × 1024 cube faces |

Expose panorama cube-face resolution separately from output dimensions: enlarging
the exported image alone cannot recover detail absent from the source faces. The
panorama preset assumes the proposed 310-by-160-degree angular range and provides
8 pixels per degree along each axis. The perspective preset has different angular
sampling; controlled comparisons require a matched-sampling configuration.

Record angular bounds alongside resolution. Changing perspective aspect ratio at
fixed horizontal FOV changes vertical coverage; panorama bounds remain explicit.
Resolution validation must reject invalid sizes and report unsupported allocations
rather than silently reducing quality. Exact limits remain to be measured.

Higher resolutions increase rendering, reprojection, image loading and storage work.
Doubling both dimensions quadruples pixel count, not necessarily runtime. If the
eye adapter supplies the same number of receptor signals at the same rate, capture
resolution does not increase the network's per-step computation. Offline derived
inputs can be cached; live capture/preprocessing still contributes to response time.
See [eye-model sampling](../../../docs/architecture.md#eye-model-sampling) for the
separate downstream setting.

At the proposed sizes, 100,000 uncompressed RGB8 + 8-bit-mask samples occupy about
655 GB (perspective) or 1.27 TB (panorama), excluding other channels and derived
data. The 2 TB dataset drive supports higher-resolution captures, but measure actual
compression and leave space for derived data. Raw cube-face retention remains optional.

### Small OOP boundary

Use one `CaptureMode` setting to select a `CaptureProjection` implementation:
`PerspectiveProjection` or `PanoramicProjection`. These names describe a proposed
interface, not existing Java classes. No hierarchy beyond this is needed initially.

The projection supplies the required camera views, maps output pixels to viewing
directions and describes its metadata. A shared capture service handles world-state
synchronization, rendering, labels and export. The scene generator does not branch
on projection type. Keep the mask-generation method separate from the projection.

Changing the mode should be sufficient to select valid defaults for that mode;
explicit resolution overrides can remain available. It must not require editing
the scene generator or downstream file-writing logic.

### Proposed 310-degree capture

1. Render forward, backward, left, right, up and down into six square off-screen
   targets. Each face has a 90-degree horizontal and vertical projection.
2. Use the same camera origin, world state and render interpolation time for all
   faces and their labels. Hold simulation/animation time fixed during capture;
   rotating the live player across advancing frames is not an acceptable substitute.
3. For each output pixel, calculate its longitude and latitude, convert those into
   a viewing direction and sample the corresponding cube face. This is a geometric
   remapping, not feature-based photograph stitching.
4. Limit longitude to -155 through +155 degrees relative to forward. The output
   edges border the omitted rear sector and must not wrap into each other.
5. Reproject the target labels using the same directions. Keep masks categorical:
   never bilinearly blend block IDs or binary labels. Define edge sampling alongside
   RGB filtering so tiny targets and cube-face seams remain correctly labelled.

Proposed vertical coverage is elevation -70 through +90 degrees, based on the
research below. This is pending discussion, along with resolution presets and RGB
file encoding (lossless RGB PNG is the current recommendation). With that vertical
range, equal horizontal/vertical angular increments imply an aspect ratio of 310:160.
Panorama pixels do not represent equal solid angles, so raw pixel area must not be
treated as angular target area or compared directly across projections.

This rectangle approximates the combined field of view; it does not reproduce the
irregular boundary, sampling density or binocular overlap of actual fly eyes. Both
modes use a single origin at player eye height. The collaborator's eye model handles
receptor sampling; stereo capture and biologically exact eye boundaries are separate
future choices. A panorama also cannot recover UV or polarisation from RGB.

Six scene renders plus reprojection cost more than a single render; benchmark capture
time and memory. Raw cubemap export is optional and remains undecided. Rendering all
faces must load/render geometry behind and above the player rather than reusing only
the forward view's culling results. Verify the hooks and off-screen targets against
Minecraft 26.3 before committing to an implementation.

Acceptance checks: known landmarks map to expected directions in both modes;
targets crossing cube-face boundaries keep aligned masks; the rear blind sector is
excluded; dynamic scene elements do not change between faces; switching modes keeps
the scene and camera pose unchanged. Masks must also pass the occlusion cases in the
difficulty table. These checks do not select ray casting versus an annotation pass.

## Dataset storage

Agreed 2026-10-22: each dataset directory contains exactly one difficulty level and
one capture mode. Resolution and other generation settings are fixed in its manifest.
A changed configuration produces a new dataset ID instead of mixing incompatible
samples.

The output root is configurable. The local proposed default is
`/mnt/fastvault/minesophila/datasets`; this machine-specific path is not part of the
portable dataset format and must not be hard-coded in readers.

```text
<output-root>/<dataset-id>/
├── dataset.json
├── scenes.jsonl
├── samples.jsonl
├── rgb/
│   ├── train/<shard>/<sample-id>.png
│   ├── validation/<shard>/<sample-id>.png
│   └── test/<shard>/<sample-id>.png
├── masks/
│   ├── train/<shard>/<sample-id>.png
│   ├── validation/<shard>/<sample-id>.png
│   └── test/<shard>/<sample-id>.png
└── derived/<derivation-name>/
```

- `dataset.json` is the manifest: schema and dataset IDs, lifecycle, timestamps,
  software versions, target block, difficulty, capture mode, resolution, output
  channels and the complete resolved generator/capture configuration.
- `scenes.jsonl` contains one record per scene: scene ID, split, seed, generator,
  region origin and dimensions, target presence/type/position and scene settings.
- `samples.jsonl` contains one record per paired capture: sample and scene IDs,
  viewpoint, relative RGB/mask paths, camera pose, projection metadata, visibility,
  pixel counts and augmentation provenance where applicable.
- Use lossless RGB PNGs and lossless binary mask PNGs initially. File encoding can
  become configurable later only if readers can identify it from the manifest.
- Shard files by a stable prefix derived from the sample ID so a single directory
  does not accumulate every image. The precise ID/hash algorithm remains open.
- Assign the split at scene level. All viewpoints and derived samples inherit it.
  `derived/` holds fly-vision outputs or augmentations without modifying sources.
- Store paths in indexes relative to the dataset directory so datasets can move.
  JSONL records permit streaming without loading a single large JSON document.

The manifest lifecycle is `generating`, `complete` or `failed`. Create it as
`generating`; mark it `complete` only after validating indexes and every required
RGB/mask pair. Training tools accept only `complete` datasets by default. A completed
dataset is immutable. Failed/interrupted generation keeps enough manifest information
for diagnosis; exact resume behaviour remains to be designed.

### Dataset splits

Agreed 2026-10-22: use configurable train/validation/test ratios with defaults of
80/10/10. The mod records and exports the assignment; downstream training may choose
which split to consume.

- Assign splits deterministically from the dataset seed and a stable grouping key.
  Record the algorithm/version and resolved ratios in `dataset.json`.
- For levels 1–4, the grouping key is the scene. All viewpoints and augmentations
  from one scene remain together.
- For level 5, group by world seed by default. If one world contributes to multiple
  splits, require explicitly defined, disjoint region groups with enough separation
  to prevent nearby terrain appearing across splits.
- For level 6, keep the whole episode and its source scene/region in one split.
- Validate that no grouping key occurs in more than one split before marking the
  dataset complete. Small datasets may not match ratios exactly; report actual counts.

### Generation quotas and stopping

Agreed 2026-10-22: generation targets accepted scenes, not raw attempts. All quotas,
balances and retry limits are configurable and stored in `dataset.json`.

| Setting | Initial default | Meaning |
|---|---:|---|
| Accepted scenes | 1,000 | Pilot dataset size; with 8 viewpoints this yields up to 8,000 base samples. |
| Target-present scenes | 80% | Scenes containing one honey block. |
| Target-absent scenes | 20% | Scenes containing no target block. |
| Viewpoint attempts | 50 per required viewpoint | Candidate camera placements/orientations before rejecting the scene. |
| Scene attempts | 10 × requested accepted scenes | Total generation attempts before the dataset fails incomplete. |

The 1,000-scene default is a pilot size, not a production recommendation. Production
runs should set the requested count explicitly after measuring capture time, rejection
rates, compression and disk use for the selected difficulty and capture mode. A small
smoke run may override it with only a few scenes.

- Resolve exact target-present and target-absent scene quotas before generation so
  seeded runs do not drift because of random class selection. Keep their distribution
  across splits as close to the requested ratios as scene grouping permits.
- A level 1–5 target-present scene counts only after every required base viewpoint
  has a valid RGB/mask pair satisfying that level's visibility rule. Re-sample failed
  viewpoints up to the configured limit; then reject the scene and try a new one.
- Target-absent scenes must contain no target block within the defined scene region.
  Whether naturally occurring honey outside or inside a level-5 sampled region is
  removed, rejected or retained remains to be specified.
- Fully hidden or out-of-view observations from target-present scenes are optional
  variants for levels 1–5 and are disabled by default. If enabled, give them a separate
  quota so they do not silently alter the visible-positive balance.
- Level 6 uses accepted episodes instead of independent viewpoints. Its episode
  length and success criteria remain to be specified with the control interface.
- Augmentations and fly-vision derivatives do not count toward accepted scene or
  base-sample quotas. Report base and derived counts separately.
- Record rejection counts and reasons, including scene placement, reachability,
  visibility, capture, mask validation and file-write failures.
- Before starting, estimate uncompressed output size and check available space under
  the configured output root. Keep a configurable free-space reserve and stop safely
  if it would be crossed. Exact reserve default remains open.
- Mark the dataset `complete` only when quotas and validation pass. If attempt limits,
  storage limits or unrecoverable capture errors stop the run, retain its partial data
  and mark it `failed`; never report a smaller dataset as successful without an
  explicit configuration change.

### Research references

- [Eye structure shapes neuron function in Drosophila motion vision](https://www.nature.com/articles/s41586-025-09276-5):
  measured eye directions, about a 50-degree rear blind spot, and elevation extending
  from overhead to about -70 degrees. The 310-degree rectangle is our approximation.
- [360-fov](https://github.com/xpncvr/360-fov): demonstrates six-face world rendering
  and shader reprojection in a Fabric mod. This is a feasibility reference, not a
  dependency or confirmation of compatibility with our Minecraft version.
- [Fabric rendering concepts](https://docs.fabricmc.net/develop/rendering/basic-concepts)
  and [world rendering](https://docs.fabricmc.net/develop/rendering/world): rendering
  phases and backend abstractions. The reviewed pages target 26.2; our mod targets
  26.3, so check the pinned game's APIs and use its rendering abstraction rather than
  assuming raw OpenGL access.

Mask generation, the full metadata schema, world-region defaults, controls, split
ratios and dataset sizes remain open.
