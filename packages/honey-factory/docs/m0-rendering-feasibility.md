# M0: Minecraft 26.3 rendering feasibility

Recorded 2026-10-22 against Minecraft 26.3, Fabric Loader 0.19.5, Fabric API
0.163.0+26.3 and Loom 1.18.4. Reference setup: OpenGL 4.6 core, Mesa 26.2.4,
AMD Radeon RX 7600 (8 GiB), Linux. The resulting decision is the accepted
[ADR 0005](../../../docs/adr/0005-shared-depth-target-annotation.md).

The M0 code was a temporary, isolated spike: `m0` packages in `src/main` and
`src/client`, two mod shaders and one client game test. M3 replaced it with the
production capture service ([ADR 0008](../../../docs/adr/0008-capture-service-and-level1-generation.md));
the shaders and mask renderer carry over, and the M3 mask gate re-checks this geometry.

## Result

The shared-depth annotation pass is viable without mixins, access wideners or raw
OpenGL. Every M0 exit-gate fixture passes with improved transparency on and off, at
64 × 64 and at the proposed 1280 × 1280 perspective default.

| Fixture | Visible px (64² / 1280²) | Unoccluded px (64² / 1280²) | Status | Criterion |
|---|---|---|---|---|
| Open | 36 / 14,168 | 36 / 14,168 | `fully_visible` | Masks pixel-identical and non-empty: pass |
| Partial (stone edge through target centre) | 18 / 6,476 | 36 / 14,168 | `partially_visible` (0.500 / 0.457) | 0 < visible < unoccluded: pass |
| Full (2 × 2 stone wall) | 0 / 0 | 36 / 14,168 | `fully_occluded` | Visible empty, unoccluded non-empty: pass |

Across all 12 configurations (3 fixtures × 2 resolutions × 2 transparency modes):

- Every mask pixel is exactly `0` or `255`; no visible pixel lies outside the
  unoccluded mask.
- **Determinism:** ten consecutive captures of each frozen fixture produced identical
  visible-mask, unoccluded-mask and RGB bytes. A second process run reproduced every
  hash from the first.
- Masks are byte-identical between improved and classic transparency. RGB differs only
  where honey is visible, from the different translucency compositing.
- The 1280² partial overlay was inspected: the visible half and the hidden half align
  with the rendered honey and the stone edge. Formal human approval of golden overlays
  remains an M3 gate.

## Hooks and classes used

| Need | 26.3 / Fabric hook |
|---|---|
| GPU abstraction | `com.mojang.renderpearl.api.*` (`GpuDevice`, `CommandEncoder`, `RenderPass`, `GpuTexture`, `GpuBuffer`, `GpuFence`) through `RenderSystem.getDevice()`. 26.3 moved these out of `com.mojang.blaze3d.systems` and has OpenGL and Vulkan backends. |
| Synchronous off-screen frame | Vanilla's `Minecraft.grabPanoramixScreenshot` sequence: `Window.setWidth/setHeight`, `RenderTarget.resize` on `GameRenderer.mainRenderTarget()`, then `GameRenderer.update(DeltaTracker.ONE)`, `extract(DeltaTracker.ONE, true)` and `renderLevel()`, run from `ClientTickEvents.END_CLIENT_TICK`. `extract`'s second argument gates level rendering, despite its name. |
| Fixed 90° projection | `Camera.enablePanoramicMode()`: FOV is fixed at 90° (vertical, JOML `setPerspective`), FOV effects are bypassed and the hand is not drawn. A square output therefore gives 90° × 90°. |
| Opaque/cutout scene depth | Fabric `LevelRenderEvents.END_MAIN`. It fires after the main frame-graph pass closes its render passes, so new passes and copies are legal. `AFTER_OPAQUE_TERRAIN` and `AFTER_SOLID_FEATURES` fire inside vanilla's open "Solid" render pass, where `CommandEncoder` rejects new passes, clears and copies. |
| Depth semantics | Reverse-Z: `Projection` swaps near/far, depth clears to `0.0` (`RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE`) and `DepthStencilState.DEFAULT` uses `GREATER_THAN_OR_EQUAL`. |
| Target model | `ModelBlockRenderer(false, true, blockColors).tesselateBlock(...)` with `ModelManager.getBlockStateModelSet().get(state)` and `state.getSeed(pos)`, the same calls `SectionCompiler` uses. It applies `Block.shouldRenderFace`, so faces against full neighbours are culled exactly as in chunk meshes. Quads go to `BufferBuilder.putBlockBakedQuad` in `DefaultVertexFormat.BLOCK`. |
| Annotation pipelines | `RenderPipelines.register(...)`, already made accessible by `fabric-transitive-access-wideners-v1`. Bind groups `Globals`, `Projection`, `DynamicTransforms` and `Sampler0`; QUADS with `RenderSystem.getSequentialBuffer`; RGBA8 target, no blending. |
| Matrix parity | Terrain computes `Position + (ChunkPosition - CameraBlockPos) + CameraOffset`. The mask vertex shader uses `Position + (target - CameraBlockPos) + CameraOffset` with the same `Globals`, `Projection` and model-view matrix (`RenderSystem.getModelViewMatrixCopy()` at `END_MAIN`). |
| Readback | `CommandEncoder.copyTextureToBuffer` into `USAGE_MAP_READ \| USAGE_COPY_DST` buffers, then `createFence()`, `submit()`, `GpuFence.awaitCompletion` and `GpuBuffer.map`. A fence cannot be awaited for the current submit, so `submit()` is required first. Rows are flipped from OpenGL's bottom-up order, as `Screenshot.takeScreenshot` does. |

## Improved transparency (OIT) interaction

26.3's improved transparency renders translucent terrain, features, clouds, weather
and the world border through OIT stages whose pipelines all set
`writeDepth = false`. At `END_MAIN`, the main depth attachment therefore contains only
opaque and cutout terrain plus solid features: exactly the occlusion depth the
specification requires, including the honey's own surface being absent from it.

With classic transparency, `TRANSLUCENT_TERRAIN` inherits `DepthStencilState.DEFAULT`
and writes depth, so honey (and any glass or water) is already in main depth at
`END_MAIN`. The visible pass then passes against the honey's own depth only because
the mask and chunk vertices produce matching depth values under `GREATER_THAN_OR_EQUAL`.
That matched here, but it is a numerical coincidence rather than a guarantee, and any
intervening translucent surface would occlude the target.

**Decision (ADR 0005, accepted):** improved transparency is required. The M0 capture
now refuses to run with classic transparency, and the game test checks that refusal.
The classic-mode results in this document come from the measurement runs made before
the setting was pinned. Datasets must record the setting in the manifest's render
environment.

## Timing and GPU memory

CPU wall time per capture, median of ten frozen repeats from the second run. "Render"
is `update`/`extract`/`renderLevel` submission; "total" adds GPU completion and
readback of three RGBA8 images.

| Size | Mode | Total median | Total max | Estimated capture allocation |
|---|---|---|---|---|
| 64 × 64 | OIT | 0.75–0.86 ms | 6.5 ms (first repeat) | 0.4 MiB |
| 1280 × 1280 | OIT | 6.0–7.0 ms | 25 ms (first repeat) | 150 MiB |
| 64 × 64 | Classic | 0.54–0.60 ms | 1.5 ms | 0.1 MiB |
| 1280 × 1280 | Classic | 4.6–5.1 ms | 9.7 ms | 50 MiB |

The allocation estimate counts the resized main target, OIT internal targets (64 B per
pixel), the mask textures and the readback buffers: 96 B per pixel with OIT and 32
without. Device VRAM from amdgpu sysfs rose by about 119 MiB on the first 1280²
capture and otherwise moved by roughly ±15 MiB, within background noise. These are
fixture costs on one GPU, not throughput targets, and exclude PNG encoding.

## Remaining M0 caveats

None blocks the exit gate. Carry these into M1–M3:

- **Vulkan untested.** Only the OpenGL backend ran. The row flip assumes OpenGL's
  bottom-up readback, so it must be verified per backend before Vulkan is allowed.
- **Extra `submit()` between frames.** It is safe here (vanilla's frame submit does
  the same), but its interaction with per-frame transient memory needs a soak test
  under bulk generation.
- **Square outputs only.** Panoramic mode fixes the vertical FOV at 90°. Non-square
  `PERSPECTIVE_90` needs an explicit projection override in M3.
- **Golden masks** for the exact half-occlusion count, irregular occluders and image
  edges are M3 deliverables. M0 checks only the open/partial/full relations.
- **World guard** is a level-name check. M2 replaces it with an ownership record.
- **VRAM** is sampled from amdgpu sysfs, so it is AMD/Linux-specific and coarse.
