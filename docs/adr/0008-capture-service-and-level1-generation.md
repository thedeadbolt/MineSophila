# 0008: Capture service, level-1 generation and dataset commit

- **Status:** Accepted
- **Date:** 2026-10-23

## Context

Milestone M3 turns the M0 rendering spike and the M2 environment into the first complete
sample path: production capture, a level-1 generator, atomic dataset writing and
validation. It must work for non-square perspective outputs, reproduce layouts and masks
on regeneration, and pass every perspective row of the mask prototype gate.

## Decision

- **Projection boundary.** `CaptureProjection` (sealed) supplies the renders a capture
  needs, maps output pixels to world directions, tests whether a box is in view and
  describes its manifest metadata. `PerspectiveProjection` is the only implementation
  until M5. Nothing else branches on the capture mode.
- **One capture service.** `CaptureService` replaces the M0 fixture. It reuses the
  panorama-screenshot sequence and the shared-depth mask renderer (ADR 0005). During a
  capture it switches off view bobbing, the block outline and section fade-in, then
  restores them. Fade-in follows wall-clock time, so leaving it on made RGB depend on
  timing.
- **Exact horizontal field of view.** One client mixin, `CameraMixin`, replaces panoramic
  mode's fixed 90-degree vertical field of view with the projection's
  `2 atan(H / W)` during a capture. Vanilla then builds the projection from the window's
  aspect ratio, so any output size keeps exactly 90 degrees horizontally.
- **No stale meshes in the arena.** A second mixin, `LevelRendererMixin`, compiles
  changed sections synchronously while the client is in the arena dimension.
  `hasRenderedAllSections()` turns true as soon as a worker takes a task, so an
  asynchronous rebuild could still show the previous scene's blocks after readiness.
  This happened: target-absent images showed an earlier scene's honey with an empty
  mask. Synchronous rebuilds in the frame that sees the change remove the race.
- **In-projection is geometric.** `target_in_projection` comes from clipping the target's
  block cell against the view frustum, not from the masks. A target in view that covers
  no pixel centre is therefore `subpixel`, and a disagreement with the masks rejects the
  sample.
- **Generation through an interface.** `DatasetGenerator` drives `reset`,
  `setViewpoint` and `observe` through `GenerationEnvironment`. The client adapts the
  environment runtime to it; unit tests use a scripted environment that ray-casts the
  masks. It is asynchronous and confined to one thread. Viewpoints are redrawn up to the
  configured attempt limit. A scene attempt that runs out of draws, or whose capture
  fails, is rejected and retried with the next attempt index. The run fails on the scene
  attempt limit, on cancellation or on any other error.
- **Portable generator randomness.** Generators draw from `SeededRandom`, which is
  SHA-256 in counter mode keyed by purpose, seed and index, and never from a JDK random
  number generator. Each viewpoint draw has its own key, so re-sampling one never shifts
  another. Level 1 is recorded as `generator.algorithms.scene_generator =
  level1-open-platform-v1`; levels without a generator omit the member.
- **Commit order.** `DatasetWriter` refuses an existing dataset directory. Each image goes
  to a temporary sibling, is forced to disk and atomically moved into place. A scene's
  record and then its samples are appended only once the scene is accepted, so the
  indexes never reference a rejected scene or a partial file. `DatasetValidator` checks
  records, cross-record rules, file sizes, hashes, PNG dimensions, binary masks and the
  mask-derived statistics. The manifest becomes `complete` only after it passes, both
  before and after the final manifest is written.
- **Build provenance.** Gradle writes `honey_factory/build-info.json` with the Git commit
  and dirty flag at build time for the manifest's `software` section.

## Consequences

- The mod now has two client mixins. Both are small, documented and only active during a
  capture or in the arena dimension.
- Capture runs on the client thread, and so do PNG encoding and file writes. That is
  enough for M3; M4 measures throughput before moving encoding off the render thread.
- Golden masks in `tests/honey-factory/gametest/resources/golden/m3/` need human
  approval of their overlays. Later runs must match them exactly on the pinned setup.
- The M0 fixture command and capture class are gone. Their evidence stays in
  `m0-rendering-feasibility.md`, and the M3 gate re-checks the same geometry.
