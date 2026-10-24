# 0012: Natural source patches for level 5

- **Status:** Accepted
- **Date:** 2026-10-24

## Context

Level 5 captures real Minecraft terrain. The specification keeps capture in the isolated
void arena: select a patch of a dataset-owned Overworld, copy it into the arena with its
biomes and block entities, relight it there, insert the target only in the copy, and keep
the source unchanged. Patches need stable IDs, splits must not leak terrain, defaults for
region size, padding and reuse were to come from an M6 benchmark, and the policy for
naturally occurring target blocks was open. One save has one world seed, so grouping
splits by world seed would put a single-save dataset in one split.

## Decision

- **A recipe base** (`natural_patch`, ADR 0011) describes the copy: a core for target and
  cameras, a height, context padding on every horizontal side, scenes per patch and a
  region separation. Sizes are whole chunks or sections. The `level5` preset uses it, and
  any level may.
- **The source is the dataset world's own Overworld.** Copies go to a separate owned region
  of the arena dimension (`ArenaLayout.NATURAL_OWNED`), about 4 km from the platform arena
  and the staging platform, so existing claims stay valid and staging never loads it.
- **Candidates** (`source-patches-v1`) lie in distinct cells of a grid over the source, at
  least the region separation apart, chosen by a seeded bijection of patch and draw. The
  vertical window ends on a section at least 8 blocks above the highest block.
- **Rejections**, in order: void would show below the lowest ground; the window holds a
  natural target block; the core has fewer than 16 target sites. Natural targets are
  therefore never present, which settles the open policy. Candidates are first screened
  with the terrain generator's noise heights, which cost no chunk.
- **Copying replaces whole sections** (states and biomes), then block entities, with bees
  removed from hives. Sections the previous copy wrote and this one does not are emptied,
  tracked in the ownership record. Light is recalculated in two passes, dropping old data
  and then enabling every chunk before any propagates; it matches the source's own light
  exactly near the surface. Chunks are resent to the client, because a reset moves the
  player away and back within one server task.
- **Verification**: the whole copy is checked once against the patch's content hash, its
  template checksum. Each scene then checks only its core, the one part a scene writes, so
  a natural scene's `layout_sha256` covers the core.
- **Reuse**: a patch serves `scenes_per_patch` consecutive scenes; the next scene restores
  only the cells the previous one changed.
- **Splits are grouped by source region** (`source_region`, `patch-slots-v1`): every patch
  is its own group, patches are apportioned to splits whole, and both validators check that
  a patch keeps one split and that patches of different splits lie at least the
  separation apart. `source_world` grouping stays reserved for datasets drawn from several
  saves.
- **Render distance**: a run refuses padding smaller than 16 blocks per chunk of render
  distance, so every chunk the client draws lies inside the copy.
- **Defaults** from the M6 benchmark: a 32 × 32 core, 128 blocks of height, 192 blocks of
  padding (render distance 12), 8 scenes per patch and 256 blocks of separation.

## Consequences

- Source chunks are generated on demand and stay in the save: tens of megabytes per patch.
- Rain and animated textures make natural RGB images vary from tick to tick; masks do not.
- Level 5 scenes take no layout parts yet; parts written for platforms assume flat ground.
