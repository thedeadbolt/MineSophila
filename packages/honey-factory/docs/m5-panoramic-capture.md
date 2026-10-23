# M5: 310-degree panoramic capture

Recorded 2026-10-23 on the M0 reference setup: Minecraft 26.3, Fabric Loader 0.19.5,
Fabric API 0.163.0+26.3, OpenGL 4.6 core, Mesa 26.2.4, AMD Radeon RX 7600, Linux. The
design decisions are in [ADR 0010](../../../docs/adr/0010-panoramic-capture.md); the exact
projection and sampling are defined in the
[data contract](../../../schemas/honey-factory/README.md).

## What exists

- `PanoramicProjection` (`equirectangular-310x160-v1`): a level panorama from longitude
  −155 to +155 degrees and elevation −70 to +90 degrees, built from six 90-degree cube
  faces (`cube-faces-v1`).
- `PanoramaSampling`: one precomputed table per size. Masks use nearest sampling; RGB uses
  8-bit fixed-point bilinear sampling clamped at face borders.
- The capture service renders every view of a projection with the player turned to that
  view, at one frozen tick. Failure is atomic, and the player's rotation is restored.
- Panorama datasets through the same runner, validators and commands. Switching mode keeps
  scenes and poses byte-identical.
- `/honeyfactory_env observe <label> <width> <height> panorama <face>`, which also writes
  each raw cube face.

## Exit gate

`M5PanoramaGateTest`, in fresh dataset worlds. The camera stands at the arena centre at
yaw 30 and pitch −12, so no face lines up with the block grid. The table is from the
final run. The two verification runs after it matched the approved goldens exactly, and
their panorama images were byte-identical to each other.

| Gate item | Evidence |
|---|---|
| Panorama acceptance checks at the default and one non-default resolution | 12 fixtures at 2480 × 1280 (1024² faces) and 1240 × 640 (512² faces). Every status was as expected, with 0 ray-check mismatches in both masks. The ray check casts five rays per pixel through the panorama's own directions against the target and occluders |
| Mask seam criteria | Targets across the front/right seam, the left/back seam and the bottom edge each form exactly one 8-connected region. Across the seams, the RGB step between neighbouring columns is 5–6 levels, against 47–157 at real edges |
| Known directions | Mask centroids point within 0.3–0.65 degrees of the target centre (front, right and both seams) |
| Rear blind sector excluded | A target at 180 degrees is `out_of_view` with empty masks. A target straddling +155 degrees reaches the right image edge and no further |
| Elevation range | A target overhead fills the top rows (185,620 px). A target straddling −70 degrees reaches the bottom row. A target 83–84 degrees below is `out_of_view` |
| Occlusion behind the camera | Targets at −138 degrees are partly hidden (3,473 of 7,395 px) and fully hidden by stone, and the ray check agrees. Geometry behind the player is therefore rendered |
| Mask prototype criteria in panorama mode | Partial, slab, stairs, fence and leaves (the M3 fixtures) match approved golden panorama masks, with 0 ray mismatches. The open target's visible and unoccluded masks are identical. Ten frozen repeats per fixture are byte-identical. A far target in a 32 × 16 panorama is `subpixel` and rejected by the level-1 visibility rule |
| Switching mode keeps the layout and pose | Perspective, then panorama, then perspective of one scene: the layout hash, pose and player rotation are unchanged, and the two perspective captures are byte-identical. The mask centroids of the two modes point 0.29 degrees apart |
| Atomic multi-face failure | A failure injected before the fourth face fails the capture with `CAPTURE_FAILED`, returns no images and restores the player's rotation. The next capture succeeds |
| Datasets | Level 1 at the default size (4 scenes × 4 views) and level 3 at 1240 × 640 are `complete` and pass both validators |
| Time, RAM, VRAM and face-retention costs benchmarked | See below |

## Costs at the default size

| Measure | Result |
|---|---|
| Render submission, six faces | 1.7–3.2 ms |
| Readback, six faces × three textures | 63 ms median |
| Reprojection (RGB and both masks) | 34.7 ms median, 36.3 ms p95 over 100 runs. The first capture also builds the table, about 120 ms once |
| Time per accepted sample, level-1 dataset | 432–438 ms, including settling, capture and PNG encoding. Reprojection is 7.9–8.1 % of it |
| Heap growth over 100 reprojections | none measurable (−14,776 bytes after GC) |
| CPU buffers per capture | 31.5 MB of faces, 15.9 MB of output; sampling table 25.4 MB, held once |
| Device VRAM in use (amdgpu, process-wide) | 2.12 GB throughout the fixtures |
| PNG per sample, panorama | 369 KB, 82 ms to encode |
| PNG per sample, raw faces | 792 KB, 163 ms to encode |

At 1240 × 640 with 512² faces, readback takes 17 ms and reprojection 8.5 ms.

**Java or Rust.** All three roadmap criteria pass with a wide margin: 34.7 ms against a
50 ms budget, at most 8.1 % against 25 %, and no heap growth. Reprojection stays in Java.

**Raw faces stay debug-only.** Keeping them would more than double the bytes and the
encoding time per sample, while the panorama already carries every captured direction.

Readback dominates the capture. If panorama throughput matters, batch the 18 copies into
one submission before touching reprojection.

## Still open

- The ray check assumes each target is a whole cube. Faces touching a full block are
  culled by the renderer and left out of both masks, so gate fixtures keep targets clear
  of other blocks. Level-4 contact cases need the face-aware semantics written down.
- Vulkan readback orientation is still untested (from M0).
