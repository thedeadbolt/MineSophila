# 0010: 310-degree panoramic capture

- **Status:** Accepted
- **Date:** 2026-10-23

## Context

Milestone M5 adds `PANORAMA_310` behind the capture boundary of ADR 0008. The spec asks for:

- six 90-degree cube faces from one position and frozen world state;
- an equirectangular image from −155 to +155 degrees of longitude and −70 to +90 degrees
  of elevation;
- categorical mask reprojection;
- atomic failure handling;
- benchmarks.

The roadmap adds an explicit rule: implement reprojection in Java, and switch to Rust if
Java misses any measured criterion.

## Decision

- **The panorama is level.** Longitude is measured around world up from the camera's yaw,
  and elevation from the horizontal plane. The camera's pitch does not tilt the panorama.
  - This follows vanilla's panorama screenshot.
  - An elevation range ending at the zenith only makes sense in a gravity-aligned frame.
  - The faces need no camera roll.
  - The recorded pose is unchanged, so switching modes never changes it.
- **Faces by turning the player, as vanilla does.** For each face, `CaptureService` sets
  the player's yaw and pitch (and their previous-tick values), then updates, extracts and
  renders the level with `DeltaTracker.ONE`.
  - All six faces see the same tick and interpolation, with simulation frozen as in M3.
  - The player's rotation is restored in a `finally` block.
  - The faces are fixed in the level frame. Each is `CameraBasis.of(yaw + offset, pitch)`;
    a unit test checks this against all six faces at four yaws.
- **One sampling table per size.** The level frame makes the face lookup independent of
  the camera. `PanoramaSampling` therefore precomputes, per output pixel, the nearest face
  and its face coordinates quantized to 1/256 pixel. The table is computed with
  `StrictMath`, takes 8 bytes per pixel and is reused across captures.
  - Masks take the nearest face pixel, so they stay binary and the visible mask stays
    inside the unoccluded one.
  - RGB is 8-bit fixed-point bilinear, clamped at face borders.
  - The algorithms are defined in the contract README (`equirectangular-310x160-v1`,
    `cube-faces-v1`) and named in the projection metadata (`nearest-v1`,
    `bilinear-face-clamped-v1`), so a second implementation can reproduce them exactly.
- **In-view test.** The out-of-view regions, the rear blind sector and the cone below −70
  degrees, are each convex. A box is out of view only when all eight corners lie in one of
  them. A box spanning both without entering the image counts as in view and becomes
  `subpixel`. It is never wrongly called `out_of_view`.
- **Atomic capture.** A failure in any face fails the whole capture with
  `CAPTURE_FAILED` and returns no images.
- **Java, not Rust.** At the default size (six 1024² faces into 2480 × 1280), Java
  reprojection meets every roadmap criterion:
  - a median of 34 ms over 100 runs, against a budget of 50 ms;
  - at most 8.1 % of the time spent per sample (about 435 ms), against 25 %;
  - no measurable heap growth.
- **Raw cube faces stay debug-only.** Keeping them would more than double the PNG bytes
  and encoding time per sample. `/honeyfactory_env observe … panorama <face>` writes them
  for inspection.
- **Size limits.** Faces are 16–4096 pixels, and a panorama has at most 2²⁵ pixels. The
  resolver rejects larger requests instead of silently lowering quality.

## Consequences

- Scene generation, datasets, validators and commands work in either mode unchanged.
  Switching mode keeps scenes and poses byte-identical.
- A target's faces that touch a full block are left out of both masks, because the
  renderer culls them, as in perspective mode. Gate fixtures avoid such contacts so the
  ray check stays exact.
- Readback, not reprojection, dominates panorama capture: about 63 ms for six faces of
  three textures each. That is where to optimise first, for example by batching the
  copies.
