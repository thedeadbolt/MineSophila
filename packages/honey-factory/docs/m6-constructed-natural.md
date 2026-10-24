# M6: Constructed and natural scenes

Recorded 2026-10-24 on the M0 reference setup: Minecraft 26.3, Fabric Loader 0.19.5,
Fabric API 0.163.0+26.3, OpenGL 4.6 core, Mesa 26.2.5 (upgraded from 26.2.4 during the
milestone; every earlier golden still matches), AMD Radeon RX 7600, 32 CPU threads, Linux.
Decisions are in [ADR 0011](../../../docs/adr/0011-scene-recipes.md) (scene recipes),
[ADR 0012](../../../docs/adr/0012-natural-source-patches.md) (natural patches) and
[ADR 0013](../../../docs/adr/0013-dataset-throughput.md) (throughput).

M8 replay later found that level-5 captures could be taken before the weather had faded
in or the section occlusion graph caught up with the camera, and that particles and rain
could remove mask pixels. Those fixes are in the
[M8 findings](m8-inspector-and-replay.md); regenerate level-5 datasets made before them.

## What exists

- **Scene recipes.** Every level is a preset of one generator: a base, registered layout
  parts in build order, a viewpoint sight rule and lighting. Recipes are edited in Java
  (`SceneRecipe`) or through a configuration's `scene` section, and shown in the game with
  `/honeyfactory_env reset preset|recipe`. Any level may vary time and weather per scene,
  and a missing dataset seed is drawn and recorded.
- **Level 4.** Rooms, courtyards, alleys, ruins and terraces of full blocks, stairs, slabs,
  fences and vegetation (`constructed_setting`, `near_aim_blocks`, `scattered_items`).
  Sight-line estimates use each block's drawn boxes; leaves and plants never block. A view
  is kept when part of the target is clear and part lies past a block.
- **Level 5.** A `natural_patch` base copies a patch of the dataset world's Overworld into
  the arena with biomes and block entities, relit, the target placed on the surface. Splits
  are grouped by source region.
- **Throughput.** Viewpoints of a scene are captured in one batch, images are written on
  worker threads, and natural patches are generated in parallel, off the server thread and
  one ahead.

## Masks where the target touches other blocks

The renderer culls a block face only where a neighbour's occlusion shape covers it
entirely. A target face against a full cube or a double slab is culled and is absent from
both masks; it is physically covered, so no visible pixel is lost. Against a bottom slab,
a stair's open side, a fence, leaves or a plant, the face is drawn, and only what actually
hides it in the depth buffer keeps it out of the visible mask. Masks therefore follow what
the renderer draws, which is what a camera could see; the gate's palette check ties the
level-4 shape table to the same occlusion shapes.

## Exit gate

`M6DatasetTest`, `M6NaturalTest` and `M6ThroughputTest`, in fresh dataset worlds, plus the
unit tests and both validators.

| Gate item | Evidence |
|---|---|
| Level-4 generator using stairs, slabs, fences and vegetation, no glass or water | 219 distinct block states over 300 scenes, all valid Minecraft states in canonical form; slab and straight-stair boxes match Minecraft's outline shapes exactly, fence boxes lie inside them, full cubes occlude, leaves and plants do not |
| Both modes generate valid level-4 datasets | Perspective 640 × 480 and panorama 1240 × 640, 8 scenes × 4 views each, `complete`, 0 Java and 0 Python validation errors; every positive sample at least 0.1 visible |
| Both modes generate valid level-5 datasets | Perspective 6 scenes and panorama 4 scenes, 4 views each, `complete`, 0 errors in both validators; forest, cherry grove and plains biomes, clear and rain |
| No visible void boundary at the configured render distance | At render distance 12 and the default 192-block padding, 0 of the chunks the client would draw around any core corner lie outside the copy; review panoramas show terrain fading into fog at the horizon |
| Source terrain unchanged | The source patch's `layout-v1` hash is equal before and after copying, reusing and recopying it |
| Copied-layout checksums reproduce | The copy matches the patch's content hash read from the source; copying the same patch again after another patch gives the same scene hash |
| Lighting readiness | Sky and block light of 13,312 cells around the core's surface equal the source's own, 0 mismatches |
| Source-patch grouping passes split-leakage validation | Three patches of one dataset landed in train, validation and test, each patch in one split; both validators check patch grouping and separation |
| Natural target policy | A candidate whose window holds a target block is rejected, so none is ever present |
| Benchmarks establish region, padding and reuse defaults | See below |

## Benchmark: padding and reuse

One patch per padding, in its own world, 32 × 32 core, 128 blocks high, with parallel
terrain generation (ADR 0013). Times are wall time.

| Padding | Chunks | Prepare | Rejected candidates | Copy | Relight | Reset with copy | Reset reusing | World growth |
|---:|---:|---:|---|---:|---:|---:|---:|---:|
| 64 | 100 | 0.8 s | 1 screened (ocean) | 0.12 s | 0.06 s | 0.68 s | 0.32 s | 5 MB |
| 128 | 324 | 2.0 s | 2 screened (ocean) | 0.22 s | 0.18 s | 1.21 s | 0.34 s | 11 MB |
| 192 | 676 | 3.5 s | none | 0.38 s | 0.42 s | 1.91 s | 0.73 s | 16 MB |
| 256 | 1,156 | 20.7 s | 3 void below, 3 screened (ocean) | 0.59 s | 0.65 s | 3.27 s | 0.57 s | 65 MB |

Copying and relighting stay under a second even for 1,156 chunks. Preparation grows with
the candidates that must be generated and rejected: the larger the copy, the more often its
window cannot cover both peaks and valleys. Before the throughput work, the same 192-block
patch took 14 s on one thread and 256 blocks took 113 s.

**Defaults:** a 32 × 32 core and 128 blocks of height keep most land patches; 192 blocks of
padding covers render distance 12, the largest that stays cheap, while 256 multiplies
rejections and disk; 8 scenes per patch spread preparation over enough scenes that the next
patch finishes in the background; 256 blocks of separation keep neighbouring patches of
different splits apart.

## Throughput

8 scenes × 8 viewpoints at the default capture sizes (1280 × 1280 perspective, 2480 × 1280
panorama), per accepted image:

| | Before | After |
|---|---:|---:|
| Level 4, perspective | 0.42 s | 0.09 s |
| Level 4, panorama | 0.57 s | 0.21 s |
| Level 5, perspective | 0.68 s | 0.26 s |
| Level 5, panorama | 0.88 s | 0.40 s |

Before, about 0.2 s per image went into moving the camera through the server and settling,
up to 0.1 s into waiting for a tick before each capture, and 13 s per natural patch into
single-threaded generation. Batched captures match server-placed ones byte for byte in a
level-4 scene. On natural terrain the masks match and RGB differs by at most 741 bytes
(0.08 %) in perspective and 2,342 in a panorama, while two ordinary captures 20 ticks apart
differ by up to 19,246: animated textures, not the batching. In one full-suite run, after
other tests had changed the client, two ordinary level-4 captures 20 ticks apart differed
by 5,746 bytes and batched ones by at most 18; the test therefore compares RGB with that
baseline rather than demanding zero.

## Still open

- Natural bases take no layout parts; parts written for flat platforms would need ground
  heights.
- Several game instances on separate saves would scale further, since the GPU is mostly
  idle; that needs dataset sharding.
- Vulkan readback orientation is still untested (from M0).
