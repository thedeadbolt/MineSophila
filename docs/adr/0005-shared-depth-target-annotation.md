# 0005: Shared-depth target annotation for honey-factory masks

- **Status:** Accepted
- **Date:** 2026-10-22

## Context

The honey-factory specification requires target masks from a GPU annotation render:
re-render the known target block against scene depth retained after opaque and cutout
geometry, plus an unoccluded render against fresh depth. Milestone M0 had to show this
is possible in Minecraft 26.3 through Fabric and the game's GPU abstraction, without
raw OpenGL, before later milestones build on it. If it was not viable, a scoped
ID-buffer pass or a different target block would be compared instead.

The [M0 findings](../packages/honey-factory/docs/m0-rendering-feasibility.md) record
the hooks inspected, the fixtures and the measurements.

## Decision

- Capture synchronously between client frames, following vanilla's panorama
  screenshot: resize the main render target, enable panoramic camera mode for a fixed
  90-degree FOV, then `GameRenderer.update`, `extract` and `renderLevel`.
- Draw the annotation passes on Fabric's `LevelRenderEvents.END_MAIN`, attaching the
  main depth texture read-only for the visible mask and a private cleared depth
  texture for the unoccluded mask. Do not copy depth.
- Tessellate the target with vanilla `ModelBlockRenderer` and the baked block-state
  model, with neighbour face culling, into mod-registered `RenderPipeline`s. Label a
  fragment when its atlas texel has non-zero alpha.
- Read back with `CommandEncoder.copyTextureToBuffer`, a fence and an explicit submit.
- Require improved transparency (order-independent transparency) for capture. It is
  the setting under which main depth at `END_MAIN` contains only opaque and cutout
  geometry. Capture refuses to run with classic transparency rather than toggling the
  option itself, because changing it rebuilds every chunk mesh.
- Use no mixins or access wideners. `RenderPipelines.register` is already accessible
  through Fabric API's transitive access wideners.

## Consequences

- Masks share the RGB frame's camera matrices, viewport and depth, and were
  deterministic across repeats and process restarts on the reference setup.
- Classic transparency writes translucent depth, including the target's, so it is
  rejected. Operators must enable improved transparency, and datasets must record it in
  the manifest's render environment.
- Glass and water occlusion remain deferred. Under OIT they do not occlude the mask,
  which matches the current specification but must be revisited with those scenes.
- Only the OpenGL backend has been verified. Vulkan readback orientation and the
  between-frames `submit()` need checks before production use.
- The capture depends on vanilla methods without a Fabric API contract (`Window`
  sizing, `GameRenderer.extract`, `Camera` panoramic mode). Recheck them when
  upgrading Minecraft.
