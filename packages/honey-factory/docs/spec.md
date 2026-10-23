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
  The mask-derived measurement method is defined below. The configurable
  visibility-ratio ranges default to 0.2–0.8 for level 3 and a 0.1 minimum for levels
  4–5, set from the M3 mask-gate evidence ([M3 findings](m3-level1-vertical-slice.md)).
- Vary viewpoint and target position from level 1. Record seeds and generation
  settings, and keep views of the same scene/episode in the same dataset split.
- For level 5, hold out world seeds by default or use explicitly disjoint region
  groups as defined in the split policy below.

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

## Scene reset and reproducibility

Agreed 2026-10-22: automated generation runs only in dataset-owned worlds. The mod
must not clear or modify an ordinary personal save. Levels 1–4 use a reusable owned
volume in a dedicated void dimension, with a safe staging platform outside it.

Before clearing, verify a persistent ownership record containing the dataset world,
dimension, arena origin and bounds. Refuse the operation if the requested clear
volume falls outside those bounds or the ownership record does not match.

The configured margin surrounds the arena on its four horizontal sides and extends
above its build height, but not below its floor, which every template restores. Dataset
worlds are created with the Honey Factory Dataset world type and claimed by name; see
ADR 0007.

### Template then block-by-block construction

Reset and construct a level 1–4 scene in this order:

1. Move the player to the staging platform, release inputs and zero velocity.
2. Remove entities/items created by the previous scene and clear the owned arena
   volume plus its configured margin.
3. Restore a versioned base arena template, including the floor and other common
   static geometry for the selected difficulty/configuration.
4. Apply deterministic scene-specific changes block by block: target, distractors,
   obstacles, hiding geometry and other generated details.
5. Set explicit world/player state, validate the layout, place the player at the
   first viewpoint, and wait for chunks, lighting and rendering to become ready.

Templates are immutable inputs identified by ID, version and content checksum.
Scene-specific placement order must be deterministic. The exact template storage
format and low-level block-update flags remain implementation choices. Template
loading handles repeated bulk geometry; block-by-block placement handles the smaller
per-scene delta and keeps its contents easy to record and validate.

Use explicit baseline state for levels 1–4: disabled daylight and weather cycles,
disabled mob spawning and random block ticks, configured time/weather, zero player
velocity, fixed relevant status, empty held item, and fixed visual settings. Snapshot
any user settings/state changed for a run and restore them when it ends; dataset-owned
worlds remain required because a crash can interrupt restoration.

### Deterministic seeds and validation

Derive layout, target and viewpoint seeds independently from the dataset seed, scene
index and generation-attempt index using a documented, versioned algorithm (`seed-v1`
in the [data contract](../../../schemas/honey-factory/README.md)). Do not consume one shared random stream whose later
results change when an earlier scene is rejected.

Record the derived seeds, generator/template versions, resolved dimensions, target
state and a checksum of the resulting block layout. Rebuilding the same descriptor
with the same pinned Minecraft/mod/generator inputs must reproduce that checksum.

### Level 5 natural patches

Agreed 2026-10-22: keep level-5 capture in the isolated void arena. Add a separate
dataset-owned source dimension using normal Overworld terrain generation. Select a
source patch, then copy it into the void arena as that scene's base snapshot before
applying target and other scene-specific changes block by block.

- Configure a core sample region for camera/target placement and additional context
  padding in every horizontal direction. Copy enough context that the void boundary
  is beyond the effective render/fog distance, including for panoramic capture.
  Do not surround the camera with a visible artificial box.
- Copy block states, biome data and required block-entity data; recalculate lighting
  in the destination. Exclude mobs and other dynamic entities initially. Scheduled
  and random ticks are not part of the initial static copy.
- Keep the natural source unchanged. Honey is inserted only into the destination.
  Recopy the base patch or restore recorded deltas before the next scene.
- Give each source patch a stable ID containing the world seed, source dimension,
  chunk/region bounds and generator version. All scenes derived from one patch stay
  in the same dataset split.
- Reuse a copied patch for several scenes where useful, because copying a padded
  terrain volume can touch millions of blocks. Target placement and scene IDs remain
  distinct and reproducible.

All dimensions in one save use its world seed. Multiple level-5 world seeds therefore
require multiple dataset-owned saves or later launch orchestration. Source-region
defaults, copy height, padding and patch reuse count remain configurable and must be
benchmarked before production generation.

## Initial capture outputs

Agreed 2026-10-22: the initial export contains RGB images, aligned visible-target
masks and scene/camera metadata. Do not capture or export depth in the initial
dataset; the collaborator's converter is expected to consume images without it.
Depth can be reconsidered later if an experiment needs it.

The RGB image is the sensory input. Masks and target/world metadata are labels
for training and evaluation, not additional sensory channels for the converter.
The metadata contract below defines the initial fields and lossless PNG encodings.

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

### Target-mask rendering

Agreed 2026-10-22: generate masks with a target-only GPU annotation render. Do not
use colour thresholding, projected bounding boxes or a CPU ray per output pixel.
Ray casting is a validation and optional metadata tool, not the source of the
canonical pixel labels.

For each perspective view or cube-map face:

1. Render the normal Minecraft scene for RGB.
2. Retain or copy scene depth after opaque and cutout geometry has rendered.
3. Re-render only the known target block's baked model at its world position into
   an off-screen annotation target, using the same camera matrices and viewport.
4. Depth-test target fragments against the retained scene depth. Write a categorical
   positive value for each surviving target fragment, with colour blending disabled.
5. Render the target once more against a fresh depth buffer to obtain an internal
   unoccluded projected-target mask.

The annotation shader uses the target model and texture coverage to decide whether
a fragment exists, but does not multiply the label by the target's translucency.
A visible honey surface is therefore positive even when its RGB appearance includes
the background. Opaque and cutout geometry in front rejects target fragments through
the depth test. Intervening translucent geometry such as glass or water remains
deferred; initial scene configurations must not rely on it for occlusion.

The internal unoccluded mask need not be exported initially. Use it to measure:

```text
visibility_ratio = visible_target_pixels / unoccluded_target_pixels
```

This ratio is defined in the selected projection from the selected viewpoint. It
can enforce difficulty visibility constraints without confusing distance or viewing
angle with occlusion. Store the resulting counts and ratio in sample metadata. A
zero unoccluded pixel count means that the target is outside the captured view or
too small to occupy a pixel and must be handled explicitly rather than divided by.

### Target-state metadata

Agreed 2026-10-22: store the underlying target evidence and derive one unambiguous
status instead of recording only a `visible` boolean. Each sample records at least
`target_present`, `target_in_projection`, `visible_pixels`, `unoccluded_pixels`,
`visibility_ratio` and `target_status`.

Derive `target_status` in this order:

| Status | Exact condition |
|---|---|
| `absent` | The scene intentionally contains no target. |
| `out_of_view` | A target exists, but its geometry is outside the selected projection. |
| `subpixel` | Target geometry intersects the projection, but the unoccluded mask contains zero pixels. |
| `fully_occluded` | `unoccluded_pixels > 0` and `visible_pixels == 0`. |
| `fully_visible` | `visible_pixels == unoccluded_pixels > 0`. |
| `partially_visible` | `0 < visible_pixels < unoccluded_pixels`. |

Set `visibility_ratio` to `null` when `unoccluded_pixels` is zero; otherwise it is
`visible_pixels / unoccluded_pixels`. Reject a sample if either count is negative,
if visible pixels exceed unoccluded pixels, or if its stored status and ratio do not
match these rules. Exact equality defines `fully_visible` initially. If prototype
tests reveal legitimate one-pixel instability, introduce an explicit, recorded
tolerance later rather than silently changing the meaning.

Use Minecraft/Fabric render-target and render-pipeline abstractions rather than raw
OpenGL so the implementation remains compatible with the supported rendering
backends. The first implementation should reuse Minecraft's block-model renderer
for the one known target position instead of adding object IDs to every chunk
vertex. Lock and record relevant graphics and transparency settings for a dataset.

Mask rasterization is deterministic:

- RGB and mask use identical camera transforms, viewport and source-face dimensions.
- Render annotation masks without multisample anti-aliasing.
- A pixel is positive when its centre produces a surviving target fragment.
- Exported masks contain only `0` and `255`.
- Reproject cube-face masks with nearest-neighbour categorical sampling; RGB may use
  its configured image filter.

For panoramic capture, produce visible and unoccluded masks for every frozen cube
face, then reproject them through exactly the same cube-map directions as RGB. Do
not create the panorama mask from the final composited RGB image.

### Mask prototype success criteria

The mask design is accepted only when one pinned Minecraft/mod/configuration build
passes the following automated gate in both capture modes at their proposed default
resolutions. Fixtures use fixed seeds, camera poses and block positions. Keep their
expected counts/hashes or approved golden masks under version control where useful.

| Criterion | Passing result |
|---|---|
| Output validity | RGB, visible mask and internal unoccluded mask have the configured dimensions; every exported mask pixel is exactly `0` or `255`. |
| Open target | With no intervening geometry, the visible and unoccluded masks are pixel-identical and contain at least one positive pixel. |
| Full opaque occlusion | The visible mask contains zero positive pixels while the unoccluded mask remains non-empty. |
| Known half occlusion | A full-cube occluder covering a fixture-defined half of the projected target produces the approved golden visible mask exactly; its visible-pixel count differs from the golden count by at most one pixel. |
| Irregular occluders | Fixed slab, stair, fence and cutout-foliage fixtures match their approved golden masks exactly on the pinned reference setup, with no positive pixels in manually identified definitely-occluded regions. |
| Near, far and image-edge cases | Every target intended to occupy at least one output pixel produces a non-empty unoccluded mask; visible pixels stay inside that mask. Targets deliberately outside the projection produce an empty unoccluded mask and an explicit `out_of_view` status. |
| RGB/mask alignment | A saved overlay for every fixture receives explicit human approval before its golden data is accepted; later runs must match that golden mask exactly. |
| Panorama seams | A target crossing each tested cube-face boundary forms one connected panorama component where the target geometry itself is connected, with no duplicated component and no unlabeled gap wider than one output pixel. |
| Frozen-scene determinism | Ten consecutive captures without advancing the world produce identical mask bytes, metadata and RGB bytes on the pinned reference setup. |
| Cross-run reproducibility | Rebuilding each fixture in a fresh dataset-owned world on the same pinned setup reproduces its layout checksum and golden masks. |
| Failure handling | Missing depth, incomplete cube faces, invalid dimensions, zero-area in-view targets and render/readback failures reject the sample with a recorded reason; no partial RGB/mask pair is accepted. |

Sparse ray tests and known synthetic layouts may help diagnose failures, but rendered
geometry remains authoritative when gameplay collision/outline shapes disagree.
Record capture time and peak GPU memory for each fixture and mode. Performance is a
required benchmark output, not yet a pass/fail threshold; set a production throughput
gate only after the prototype establishes a measured baseline on this machine.

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
`PerspectiveProjection` or `PanoramicProjection`. M3 implements the interface and
`PerspectiveProjection`; `PanoramicProjection` follows in M5. No hierarchy beyond this
is needed initially.

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

The output root is required in every configuration and has no default; on this machine
it is normally `/mnt/fastvault/minesophila/datasets`. This machine-specific path is not
part of the portable dataset format and must not be hard-coded in readers.

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
  does not accumulate every image. IDs and shards follow `ids-v1` in the
  [data contract](../../../schemas/honey-factory/README.md).
- Assign the split at scene level. All viewpoints and derived samples inherit it.
  `derived/` holds fly-vision outputs or augmentations without modifying sources.
- Store paths in indexes relative to the dataset directory so datasets can move.
  JSONL records permit streaming without loading a single large JSON document.

### Metadata contract

Agreed 2026-10-22: use the package's PNG + JSONL layout as the canonical format.
COCO, Croissant, Parquet or training-framework formats may be derived later; the
generator does not depend on them. `dataset.json` is one JSON object. JSONL files
use UTF-8 without a byte-order mark, contain one complete object per line and end
every record with `\n`.

All three record types carry a semantic `schema_version`. Readers reject an
unsupported major version and tolerate unknown fields from compatible minor
versions. Field names use `snake_case`. JSON numbers must be finite: do not emit
`NaN` or infinity. Omit fields that do not apply; use `null` only where this spec
defines it as meaningful.

#### `dataset.json`

The manifest stores resolved values, including defaults, rather than only the
options explicitly supplied by the operator.

| Field | Required content |
|---|---|
| `schema_version` | Metadata contract version. |
| `dataset_id` | Unique immutable dataset identifier. |
| `content_version` | Version assigned to these generated contents, independent of the schema version. |
| `lifecycle` | `generating`, `complete` or `failed`. |
| `created_at_utc`, `completed_at_utc` | RFC 3339 UTC timestamps; completion is `null` until terminal. |
| `difficulty` | The one difficulty level represented by this dataset. |
| `target` | Target block ID, allowed block states and target-present/absent quotas. |
| `generator` | Complete resolved scene, arena, quota, seed and retry configuration plus algorithm versions. |
| `capture` | Capture mode, output/source dimensions, projection parameters, mask convention, viewpoints and augmentations. |
| `splits` | Requested ratios, grouping rule, split algorithm/version and final scene/sample counts. |
| `software` | Minecraft, Fabric Loader, Fabric API, mod and Java versions; generator Git commit and dirty-worktree flag. |
| `render_environment` | Graphics backend, GPU/driver, graphics/transparency settings, render distance, gamma, shaders and ordered resource/mod-pack identities with checksums where applicable. |
| `storage` | Relative index/image roots, image encodings, sharding rule and exported channels. Do not store the machine-specific output root. |
| `counts` | Requested, attempted, accepted, rejected and failed scene/sample totals with rejection reasons. |
| `provenance` | Creation tool and any source/parent dataset IDs or derivations. Empty when this is an original capture. |
| `integrity` | Hash algorithm (`sha256` initially), index byte counts/hashes and final validation result. Populated at completion. |

The manifest may record machine and performance information needed to reproduce or
benchmark capture, but it must not include usernames, absolute home paths or other
unnecessary host-specific data. Dataset licence and intended-use fields are required
before external publication, but may remain explicitly `unassigned` for local pilots.

#### `scenes.jsonl`

One scene record describes one world arrangement shared by all of its viewpoints.

| Field | Required content |
|---|---|
| `schema_version`, `scene_id` | Schema version and ID unique within the dataset. |
| `split`, `difficulty` | Scene-level split and difficulty; all related samples inherit them. |
| `generation` | Scene index, accepted attempt index, independent layout/target/viewpoint seeds and their derivation algorithm version. |
| `template` | Immutable template ID, version and SHA-256 checksum. |
| `arena` | Dimension ID, integer origin/bounds, configured core size and context margin. |
| `source_patch` | Level-5 source world seed, dimension, bounds, generator version and patch ID; omitted otherwise. |
| `target` | Presence, namespaced block ID, canonical block state and integer block position `[x,y,z]`; position/state are omitted when absent. |
| `environment` | Resolved time, weather, gamerules and other scene-varying world settings. |
| `layout_sha256` | Hash of the canonical reconstructed block layout representation. |
| `accepted_sample_count` | Number of base samples belonging to the scene after validation. |

Do not list every arena block in the ordinary scene record. Template identity,
versioned generation parameters and seeds reproduce the layout; `layout_sha256`
verifies it. A full block snapshot may be stored as an optional diagnostic artifact.
The canonical layout serialization used for hashing is `layout-v1` in the
[data contract](../../../schemas/honey-factory/README.md).

#### `samples.jsonl`

One sample record describes one accepted RGB/mask pair. Failed attempts never appear
here; record them in the generation log and aggregate their reasons in the manifest.

| Field | Required content |
|---|---|
| `schema_version`, `sample_id`, `scene_id` | Schema version, sample ID and owning scene ID. |
| `split`, `viewpoint_index` | Inherited scene split and zero-based viewpoint index. |
| `files` | `rgb` and `mask` descriptors containing relative path, media type, width, height, byte length and SHA-256 of finalized PNG bytes. |
| `camera` | Player and eye positions, eye height, yaw/pitch and world-space forward/right/up unit vectors. |
| `projection` | Capture mode plus the manifest projection reference and all per-sample overrides, if any. |
| `capture` | Operation ID, world tick, render interpolation value and readiness/settling result. |
| `target` | Presence/projection evidence, target status, mask counts, ratio and mask-derived spatial statistics. |
| `conditions` | Scene time/weather plus camera-local biome, sky/block light and RGB mean luminance where available. |
| `augmentation` | Source sample ID, transform and parameters for derived images; omitted for base captures. |

The `target` object contains:

- `target_present`, `target_in_projection`, `visible_pixels`,
  `unoccluded_pixels`, `visibility_ratio` and `target_status` as defined above.
- `visible_bbox_xywh` as the smallest integer half-open rectangle containing all
  positive saved-mask pixels, or `null` for an empty mask.
- `visible_centroid_xy` as the arithmetic mean of positive pixel centres, or `null`
  for an empty mask.
- `distance_blocks`, for a present target, as the Euclidean distance from the camera eye
  to the centre of the target's block cell.

Bounding boxes and centroids are calculated from the finalized saved mask, not from
an independent geometric projection. The generator validates them by reading or
equivalently hashing/counting the finalized pixel buffer before accepting the record.

Future level-6 observation records add `episode_id`, zero-based
`observation_index`, and `action_before_observation`. The action object records the
requested action and parameters, completed amount/ticks and termination reason.
Levels 1–5 omit these fields.

#### Coordinates and units

- World and block arrays use `[x,y,z]`: positive X is east, positive Y is up and
  positive Z is south, matching Minecraft.
- Positions are in blocks, distances in blocks, durations in game ticks and angles
  in degrees. Field names include a unit suffix where ambiguity is likely.
- Minecraft yaw `0` faces south (`+Z`); yaw increases toward west (`-X`). Pitch `0`
  is horizontal, positive pitch looks down and negative pitch looks up.
- Image origin is the upper-left. X increases right, Y increases down, and integer
  pixel `(x,y)` represents centre `(x+0.5,y+0.5)`.
- `[x,y,width,height]` boxes cover half-open ranges `[x,x+width)` and
  `[y,y+height)`.
- Any exported matrix states its semantic direction, row/column ordering and clip
  depth convention. Analytic projection parameters and camera basis vectors remain
  canonical across graphics backends.
- Paths are dataset-root-relative, use `/`, and cannot be absolute or contain `..`.
- Timestamps are RFC 3339 UTC strings.

Perspective projection metadata supplies width/height, horizontal and vertical FOV,
pixel principal point, and focal lengths in pixels. Panorama metadata supplies the
projection/version, longitude and elevation bounds, blind-sector definition,
cube-face size/orientations and pixel-centre/reprojection convention. Fixed values
live in the manifest; a sample refers to that configuration rather than duplicating
it, while its camera pose and any actual override remain in its record.

#### File integrity and commit order

SHA-256 is mandatory for every exported RGB and mask PNG. Keep the internal
unoccluded mask only for validation by default; store its pixel count, not its image
file. Distinguish the file hashes, layout hash and resolved-configuration hash.

Write each image to a temporary file, finish and validate it, atomically move it to
its final relative path, then append the sample record. A record must never point to
a partial file. Before marking the manifest `complete`, validate JSONL syntax,
unique IDs, scene references, split consistency, file dimensions/hashes, target
invariants, quotas and split leakage; then store final index hashes in the manifest.

### Dataset inspection and scene replay

Agreed 2026-10-22: the package is not complete without a practical way to inspect
generated data. Provide both an offline visual inspector and deterministic in-game
scene replay. Neither tool modifies canonical images, indexes or a completed
manifest.

#### Local visual inspector

The inspector opens a dataset directory locally and presents samples in a browser
or equivalent desktop view without uploading data or requiring an external service.
It reads the manifest and streams/indexes JSONL records instead of loading every
full-resolution image into memory.

For each sample it displays:

- RGB alone, mask alone and an RGB/mask overlay with adjustable opacity.
- Native-resolution and zoomed views; mask zoom uses nearest-neighbour sampling so
  inspection does not invent grey or shifted label pixels.
- Target status, visible/unoccluded pixel counts, visibility ratio, mask bounding
  box and centroid.
- Camera pose, projection, split, scene/sample IDs, target position and relevant
  lighting/world metadata.
- Every viewpoint from the same scene in one grid, with direct previous/next scene
  and sample navigation.

It supports filtering by split, target status, visibility-ratio range, scene/sample
ID and other indexed scalar fields. It also supports seeded random and stratified
review selection so a reviewer can deliberately inspect absent, fully visible,
partially visible, fully occluded, edge/seam and low-pixel-count cases rather than
only the first records.

Mask overlays are generated on demand and are not stored as additional canonical
images. The inspector clearly reports missing files, hash/dimension mismatches,
invalid masks and inconsistent metadata instead of displaying them as valid samples.

#### Review records

The inspector can mark a sample or scene `pass`, `fail` or `uncertain` with a reason
code and optional note. Save these decisions to a configurable sidecar review root,
keyed by dataset ID, manifest hash, scene/sample ID and inspector version. Do not
write them into the immutable dataset directory. Reviews are audit evidence, not
label replacements; fixing a failed sample requires a new dataset or explicitly
versioned derived dataset.

#### In-game replay

Add a debug command/API operation that loads a scene descriptor into a dataset-owned
world, rebuilds it using its recorded template, seeds and parameters, checks the
layout checksum, and places the player at any recorded viewpoint. It must refuse to
replay in a personal/non-owned save and must report unavailable template/generator
versions instead of approximating the scene.

The replay controls allow cycling through a scene's viewpoints and requesting a
fresh RGB/mask capture for comparison. A replay is considered exact only when the
layout checksum matches and, on the pinned render setup, regenerated masks match the
stored masks. Different graphics hardware may be inspected visually but cannot be
claimed as byte-identical without passing the same reproducibility check.

#### Inspector success criteria

| Criterion | Passing result |
|---|---|
| Pair inspection | Any valid sample can show RGB, binary mask and aligned overlay at native resolution. |
| Scene inspection | Opening a scene exposes all and only its indexed viewpoints with their metadata. |
| Search and sampling | Exact ID lookup, status/split/range filters, seeded random selection and stratified selection return reproducible results. |
| Integrity reporting | Missing, corrupt, dimension-mismatched or hash-mismatched files are visibly rejected, not silently skipped. |
| Review safety | Saving a review changes no byte under a completed dataset directory and the sidecar identifies the exact manifest reviewed. |
| Replay safety | Scene replay runs only in a verified dataset-owned world. |
| Replay fidelity | The reference fixtures reproduce their layout checksums and stored masks on the pinned setup. |

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
  if it would be crossed. The reserve is `output.free_space_reserve_bytes`, 10 GiB by
  default; like the output root, it is not part of the dataset configuration.
- Mark the dataset `complete` only when quotas and validation pass. If attempt limits,
  storage limits or unrecoverable capture errors stop the run, retain its partial data
  and mark it `failed`; never report a smaller dataset as successful without an
  explicit configuration change.

## Environment control

Agreed 2026-10-22: build a synchronous environment API inside the mod and keep it
independent from any particular controller. Bulk dataset generation calls this Java
API directly. In-game commands provide manual/debug access. A local external adapter
for a future Python agent can be added after the internal API works.

The conceptual interface is:

```text
reset(scene configuration) -> scene state
setViewpoint(camera pose)   -> resolved camera pose
observe()                   -> captured observation
step(action)                -> resulting state and observation
status()                    -> current operation and last error
```

Exact Java types and method names remain implementation details. Scene generation
must not depend on chat commands or an external transport.

### Synchronization and operation state

- Accept one state-changing operation at a time. Each request completes only after
  its world changes/actions, readiness checks and requested capture have completed.
- Express action duration in game ticks rather than wall-clock milliseconds. Record
  requested and completed ticks and associate results with an operation/request ID.
- Release movement inputs after each step. Between steps the controller commands no
  movement; whether the wider world is paused between requests remains to be tested
  and configured rather than assumed.
- Wait for required chunks, lighting and client rendering to become ready after a
  reset or teleport. Use readiness conditions with a timeout; do not rely only on a
  fixed sleep. A configurable settling-tick minimum may supplement those conditions.
- Treat the six renders of one panorama and their masks as one atomic capture with
  a fixed camera origin, game state and interpolation time.

The initial operation states are:

```text
IDLE -> PREPARING_SCENE -> READY -> ACTING -> SETTLING -> CAPTURING -> READY
                              \______________________________________-> FAILED
```

Invalid operations for the current state return an error instead of overlapping or being
silently queued.

Dataset cancellation (agreed with M4, ADR 0009) is a request honoured at the next safe
boundary: before the next reset, viewpoint change, capture or scene attempt.

- An operation already running finishes.
- The unfinished scene attempt is discarded with its images.
- A scene whose last capture already finished is kept.
- The dataset is marked `failed` with the reason `cancelled`, unless no work remained,
  in which case it completes normally.

Leaving the world fails the running operation with the same clean-up. A manifest still
`generating` after the game stopped records an interrupted run. Recovering such a run in
place is not supported; regenerate it instead.

### Dataset viewpoints and environment episodes

- For levels 1–5, place the player/camera directly at each validated viewpoint.
  These independent samples do not claim the player walked between positions.
- For level 6, use normal player movement, collision and physics. Preserve the
  ordered `observation -> action -> observation` sequence and resulting player pose.
- The mod owns world mutation on the logical server side and capture on the client
  side. A coordinator must confirm both refer to the same scene/tick before export.

### Initial action model

| Action | Configurable parameter | Meaning |
|---|---|---|
| `TURN` | yaw degrees | Turn in place. Positive/negative convention must be documented. |
| `LOOK` | pitch degrees | Change look pitch within Minecraft limits. |
| `MOVE_FORWARD` | game ticks | Apply forward player movement for the requested duration. |
| `WAIT` | game ticks | Advance without commanded movement. |
| `STOP` | none | Release all movement inputs. |
| `MINE` | game ticks | Deferred until mining is in scope. |

Action ranges and optional discrete presets remain configurable. Degree/tick actions
are the environment contract; keyboard press duration is not the public abstraction.

Use Minecraft Java Edition's existing auto-jump setting as the first movement helper.
Make it configurable per environment run, initially enabled for assisted movement,
and record its resolved value in episode metadata. Test that programmatic forward
movement invokes the same vanilla behaviour in Minecraft 26.3. If the mod temporarily
changes a user's option, restore the previous value when the run ends.

Vanilla auto-jump is movement assistance, not navigation: it does not choose turns,
targets or routes. Do not add custom jump/pathfinding logic unless tests show vanilla
auto-jump cannot meet the environment requirement; any replacement would require a
new recorded controller version.

### Control surfaces

- Add `/honeyfactory` commands for starting/status/cancelling dataset generation,
  resetting a scene and requesting a capture. Exact command syntax comes with the
  package roadmap and implementation.
- Run bulk generation in the mod to avoid a Python round trip for every scene/view.
- Design for a future loopback-only external control adapter. Newline-delimited JSON
  over local TCP is the current proposal, but its port, messages, authentication and
  lifecycle are deferred. It must call the same environment API as other controls.
- The first implementation does not require the external adapter or a live model.

## Package-wide definition of done

Agreed 2026-10-22: `honey-factory` is ready for phase-1 dataset production only when
all mandatory gates below pass. A successful build or one convincing screenshot is
not package completion. Levels 1–5, both capture modes, the internal environment API
and inspection tooling are mandatory. Level 6 and the external controller adapter
are separate extension gates.

### Mandatory functional gates

| Area | Passing result |
|---|---|
| Build and launch | A clean checkout builds, runs its automated tests and launches the pinned Minecraft 26.3 development client with the documented Java/Fabric versions. |
| Configuration | Every agreed generator, world, viewpoint, lighting, capture, resolution, quota, split and output-root setting is configurable, validated before generation and written back as a resolved manifest value. Invalid settings fail before world mutation or file creation beyond a diagnostic manifest. |
| World safety | Generation and replay refuse non-dataset-owned worlds. Sentinel blocks/entities immediately outside configured owned bounds remain unchanged through reset, failure and cancellation tests. Temporarily changed player/client settings are restored after normal completion and handled explicitly after failure. |
| Scene generation | Levels 1–4 rebuild their template and deterministic block delta; level 5 copies a recorded natural patch into the isolated arena. Accepted scenes satisfy their difficulty and target-presence rules. |
| Reproducibility | Rebuilding every fixed acceptance fixture from a fresh dataset-owned world reproduces its layout checksum. On the pinned render setup it also reproduces stored masks. |
| Perspective capture | `PERSPECTIVE_90` produces aligned RGB/mask pairs at its default and at one non-default valid resolution. |
| Panorama capture | `PANORAMA_310` atomically captures and reprojects all cube faces, excludes the rear blind sector and produces aligned RGB/mask pairs at its default and at one non-default valid face/output resolution. |
| Mask correctness | Every mask-prototype success criterion above passes, including translucency, full/partial occlusion, irregular geometry, image edges and panorama seams. |
| Target metadata | Status, counts, ratio, bounding box and centroid validate against the finalized mask for every accepted sample; all six target statuses have explicit fixtures. |
| Dataset integrity | The writer produces the specified portable tree and metadata contract. The final validator reports zero schema, hash, dimension, reference, quota, target-invariant or split-leakage errors before lifecycle becomes `complete`. |
| Environment API | `reset`, `setViewpoint`, `observe`, `step` and `status` obey the synchronous operation model. TURN, LOOK, MOVE_FORWARD, WAIT and STOP fixtures report requested/completed ticks or degrees; inputs are released afterward and vanilla auto-jump is verified. |
| Inspection | The local inspector and in-game replay pass every inspection/replay success criterion above, including immutable sidecar reviews. |
| Documentation | A new operator can build the mod, create the dataset-owned world, run the acceptance smoke command, generate a pilot, validate it, inspect it and replay a scene using repository documentation alone. |

### Acceptance runs

Use three increasingly expensive gates; later gates do not replace earlier fixture
coverage.

1. **Automated fixtures:** run unit/integration tests and every fixed mask, projection,
   reset, metadata, environment and safety fixture. This is the normal development
   gate.
2. **Configuration matrix smoke:** generate 10 accepted scenes for every combination
   of levels 1–5 and both capture modes using 8 base viewpoints. Each run uses exactly
   8 target-present and 2 target-absent scenes, producing 800 base RGB/mask pairs in
   total if no configured optional hidden views are added. Validate every dataset.
3. **Production-scale pilot:** generate one uninterrupted dataset with level 5,
   `PANORAMA_310`, the proposed default resolutions, 1,000 accepted scenes, 8
   viewpoints and the default 80/20 target-present/absent balance. The expected base
   output is 8,000 accepted RGB/mask pairs. The run passes only if it reaches the
   exact quotas, completes validation and lifecycle transition, stays within the
   preflight disk reserve, and encounters no out-of-memory or unrecovered rendering
   failure.

The production pilot is an acceptance workload, not a claim that 1,000 scenes are
enough for final model training. Changing this workload requires an explicit spec
revision rather than silently reducing it after a slow run.

### Human audit and measured capacity

After automated validation, inspect all fixed visual fixtures and a seeded,
stratified set of 200 production-pilot samples. The selection must cover every split
and target status present, visibility-ratio bins, low target-pixel counts, panorama
seams and multiple source patches. Every reviewed item must be `pass`; investigate
and resolve `fail` or `uncertain` results before accepting the package. Save the
sidecar review record and selection seed with the acceptance report.

The matrix smoke and production pilot report total duration, accepted scenes and
samples per hour, mean and percentile capture times, rejection counts/reasons, PNG
size distribution, final disk use and peak process RAM/GPU memory where measurable.
There is no invented throughput threshold before the first reference benchmark.
After that run, adopting a minimum throughput or maximum latency requires a recorded
spec decision and must not weaken correctness, safety or integrity gates.

### Separate extension gates

Level 6 is complete only after episode length, discovery/success rules and episode
quotas are specified, and deterministic fixtures show that targets initially marked
`out_of_view` or `fully_occluded` can become visible through the allowed actions
without mining. The future loopback adapter additionally requires its own protocol,
authentication, lifecycle and equivalence tests against the internal API. Neither is
required to declare the phase-1 levels 1–5 dataset generator complete.

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
- [Minecraft 26.3 Snapshot 2](https://feedback.minecraft.net/hc/en-us/articles/47030118645389-Minecraft-Java-Edition-26-3-Snapshot-2):
  documents the new order-independent transparency path and its shader changes. The
  annotation pass must not infer target labels from the final translucent composite.
- [Minecraft Raytrace dataset](https://huggingface.co/datasets/shinohararin/minecraft-raytrace):
  demonstrates ray-derived spatial labels, but its coarse sampling also supports
  keeping per-pixel canonical masks on the render path instead.
- [Minecraft Java Edition auto-jump option](https://minecraft.wiki/w/Options.txt_%28Java_Edition%29):
  records the `autoJump` client option. Behaviour must still be verified in our
  programmatic control path and pinned Minecraft version.

The 26.3 rendering hooks found by M0 are recorded in the
[M0 findings](m0-rendering-feasibility.md) and accepted in ADR 0005, which requires
improved transparency for capture. World-region defaults, external protocol, level-6 episode
limits and final model-training dataset sizes remain open.
