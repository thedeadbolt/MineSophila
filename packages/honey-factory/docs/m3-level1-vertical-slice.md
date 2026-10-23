# M3: Level-1 perspective vertical slice

Recorded 2026-10-23 on the M0 reference setup: Minecraft 26.3, Fabric Loader 0.19.5,
Fabric API 0.163.0+26.3, OpenGL 4.6 core, Mesa 26.2.4, AMD Radeon RX 7600, Linux. The
design decisions are in
[ADR 0008](../../../docs/adr/0008-capture-service-and-level1-generation.md).

## What exists

- `CaptureProjection` with `PerspectiveProjection`, and the client `CaptureService`: RGB,
  visible mask and internal unoccluded mask from one frozen frame, any output size, with
  an exact 90-degree horizontal field of view.
- The level-1 generator: a light grey concrete platform, one honey block in
  target-present scenes, seeded viewpoints at 3–16 blocks with yaw and pitch jitter.
- `DatasetGenerator`, `DatasetWriter` and `DatasetValidator`: quotas, re-sampling,
  rejection reasons, atomic PNG writes, append-after-files commits and validation before
  `complete`.
- `/honeyfactory_dataset generate|status|cancel`, and `/honeyfactory_env observe`
  writing inspection files and overlays.

## Exit gate

Every row below passed in three consecutive `runClientGameTest` runs. Each run uses fresh
dataset worlds, and the runs produced identical bytes.

| Gate item | Evidence |
|---|---|
| Small command-generated level-1 dataset reaches exact quotas and validates with zero errors | `/honeyfactory_dataset generate`: 8 scenes (6 present, 2 absent) × 4 viewpoints at 640 × 480, `complete`, 0 Java validation errors; the Python schema validator also reports 0 errors for all three files |
| Regenerated with matching layouts and masks | Generated again in a second fresh world: identical `scenes.jsonl` (all layout hashes) and all 32 masks byte-identical; RGB was also identical for all 32 |
| Every perspective row of the mask prototype gate | See the next table |
| Initial visibility-ratio ranges for levels 3–5 | See [Visibility-ratio defaults](#visibility-ratio-defaults) |

### Mask prototype gate (perspective rows)

1280 × 1280 unless stated. The CPU ray check casts rays through each pixel centre and
four points 0.75 pixels away, against the target's block cell and the occluders' model
boxes. It counts a disagreement only where all five rays agree.

| Fixture | Visible / unoccluded px | Status | Ray-check disagreements |
|---|---|---|---|
| Open | 14,168 / 14,168 | `fully_visible`, masks pixel-identical | 0 |
| Partial (stone column, golden) | 6,476 / 14,168 | `partially_visible` 0.457 | 0 |
| Full (2 × 2 stone wall) | 0 / 14,168 | `fully_occluded` | 0 |
| Slab (floating bottom slabs, golden) | 3,133 / 14,168 | `partially_visible` 0.221 | 0 |
| Stairs (golden) | 5,965 / 14,168 | `partially_visible` 0.421 | 0 |
| Fence (golden) | 9,221 / 14,168 | `partially_visible` 0.651 | 0 |
| Oak leaves, cutout (golden) | 8,709 / 14,168 | `partially_visible` 0.615 | 0 target-coverage disagreements; holes are judged only by the golden mask |
| Near (≈ 1.7 blocks) | 141,245 / 141,245 | `fully_visible` | 0 |
| Far (≈ 29.5 blocks) | 506 / 506 | `fully_visible` | 0 |
| Image edge | 21,317 / 21,317, box starts at x = 0 | `fully_visible` | 0 |
| Out of view | 0 / 0 | `out_of_view` (geometric test) | 0 |
| Partial at 640 × 360 | 3,541 unoccluded | — | 0: the non-square projection is exact |

- **Output validity:** every mask is exactly 0/255 at the configured size, and no visible
  pixel lies outside the unoccluded mask.
- **Known half occlusion:** the partial fixture reproduces M0's 6,476 pixels exactly.
- **Golden masks:** partial, slab, stairs, fence and leaves match their golden masks
  exactly. The goldens are stored at `tests/honey-factory/gametest/resources/golden/m3/`;
  their overlays await explicit human approval (see below).
- **Frozen determinism:** ten captures per fixture, with server ticking frozen, give
  identical masks, RGB, world tick and pose.
- **Cross-run reproducibility:** every run rebuilds each fixture in a fresh dataset world
  and matches the golden masks; mask and RGB hashes were identical across the three runs.
- **Failure handling:** an 8 × 8 output is refused; a target in view that covers no pixel
  at 16 × 16 (ray check agrees) is `subpixel` and level 1 rejects it with `visibility`;
  an injected readback failure gives `CAPTURE_FAILED` and leaves the coordinator
  `FAILED`; classic transparency is refused. Unit tests show that a failed capture or an
  exhausted viewpoint rejects only that scene attempt, deletes its images, and never
  appends a partial pair.

### Golden overlay approval

`runClientGameTest` writes `rgb.png`, both masks and `overlay.png` for each fixture to
`packages/honey-factory/build/m3-review/<fixture>/`; red is visible target, blue is
hidden target. The specification requires explicit human approval of these overlays
before the golden masks count as accepted.

## Problems found and fixed

- **Stale chunk meshes.** Target-absent images sometimes showed the previous scene's
  honey with an empty mask. Two causes: change marks for arena sections could be dropped
  while the camera waited on the distant staging platform, and asynchronous rebuilds left
  old meshes on screen after `hasRenderedAllSections()` was already true. Reset readiness
  now marks every arena section dirty once the client shows the new layout, and a mixin
  rebuilds arena sections synchronously. The dataset test checks every target-absent
  image for honey-coloured pixels.
- **Section fade-in.** 26.3 fades newly built sections in over wall-clock time, so RGB
  depended on timing. Capture turns the fade off.
- **Chained operations.** Starting the next environment operation from a completion
  callback lost it, because the runtime then cleared its pending slot. It now clears the
  slot only if the finished operation still holds it.

## Timing

Median CPU time per 1280 × 1280 capture: about 1 ms render submission and 17 ms readback.
VRAM use, sampled from amdgpu sysfs after each fixture, stayed between 7.35 and 7.47 GB
for the whole process. Generating the 32-sample dataset took about 45 seconds, mostly
the settling ticks and frames required after every reset and viewpoint change. M4 will
measure throughput and move PNG encoding off the render thread.

## Visibility-ratio defaults

The gate shows mask counts are exact away from edges, so the ratio itself can be
trusted at any value. The bounds reflect what each level is for:

- **Level 3 (partially visible): 0.2–0.8.** Above 0.8 the target looks essentially whole.
  Below 0.2, a target at the 16-block maximum distance, about 2,000 unoccluded pixels at
  1280², would keep fewer than about 400 visible pixels. The irregular occluders
  measured 0.22–0.65, inside this range.
- **Levels 4–5 (at least partly visible): minimum 0.1,** so a positive example keeps
  about 200 or more visible pixels at the maximum distance.

These are configuration defaults recorded in each manifest, not format limits. Explicit
`null` removes a bound.

## Still open

- Vulkan readback orientation is untested (from M0).
- Clouds, cutout leaves, mipmaps and graphics presets are recorded only partially in the
  manifest. The tests pin clouds off and cutout leaves on. M4 should pin or record every
  pixel-affecting setting.
- Panorama seam rows remain for M5.
