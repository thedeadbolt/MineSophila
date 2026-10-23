# 0009: Dataset runner, levels 2–3 and the outside validator

- **Status:** Accepted
- **Date:** 2026-10-23

## Context

Milestone M4 widens the level-1 vertical slice (ADR 0008) into a runner for real
datasets. It must:

- protect the output disk;
- define what cancellation does;
- add the level-2 clutter and level-3 partial-hiding generators without coupling them
  to rendering;
- record enough per scene to replay it;
- prove split integrity;
- validate datasets with a tool that shares the contract's schemas and fixtures but not
  the mod's Java validation code.

## Decision

- **Shared platform generator.** `PlatformGenerator` holds what levels 1–3 share: the
  floor template, the target cell, the viewpoint draws and the player-placement checks.
  Each level adds two hooks:
  - `layout`, the blocks around the aim cell, drawn from the layout seed;
  - `checkSight`, which says whether a viewpoint's view of the aim cell suits the level.

  Target-absent scenes get the same layout around the same empty cell, so the two classes
  differ only by the target. Level 1 moved onto the base unchanged: its pinned layout and
  viewpoint draws still match.
- **Generators estimate sight lines before capture.** `SightLines` casts a 3 × 3 grid of
  rays to each face of the aim cell that faces the eye, through the plan's solid cells.
  Level 2 (`level2-clutter-v1`) keeps a viewpoint only when every ray is clear. Level 3
  (`level3-partial-hiding-v1`) keeps it only when some rays are clear and some are not.
  This discards hopeless draws cheaply. The saved mask still decides acceptance, so the
  estimate never affects labels.
- **Layouts.**
  - Level 2: 6–14 items of clutter, kept at least two cells from the target. Each item is
    a single block or a small pile, 40 % from yellow and orange distractors (including
    honeycomb).
  - Level 3: a hiding structure against the target (wall, corner, recess with an optional
    lintel, or pillars), plus walls, pillars, stacks and scattered blocks at least three
    cells away, from a broader textured palette.
  - All palette blocks are opaque full cubes without properties, so a block ID is also its
    canonical state.
  - The palettes are ordered lists; editing one needs a new generator version.
- **Versioned templates.** All three levels restore the same `level1-platform` template,
  version 1. A template is named for its contents, not for the level using it. Its
  SHA-256 is recorded with every scene.
- **Per-scene parameters.** `scenes.jsonl` gains an optional `generation.parameters`
  object:
  - `aim_block`;
  - `viewpoint_draws`, the accepted draw of each viewpoint in order, enough to rebuild
    every camera pose exactly;
  - `layout`, the generator's own counts and choices.

  The validators check that there is one draw per accepted sample and that the aim block
  lies inside the arena.
- **Every pixel-affecting option is recorded.** `render_environment.settings` stores
  every other client option that can change pixels, by name. That includes the graphics
  preset, cutout leaves, ambient occlusion, mipmaps, filtering, biome blend, shadows,
  particles, bobbing and the screen effects.
- **Disk preflight and reserve.**
  - `output.free_space_reserve_bytes`, default 10 GiB, sits beside the output root. Neither
    enters the configuration hash.
  - Before the dataset directory is created, the free space must hold the whole dataset
    uncompressed plus the reserve.
  - Each scene attempt starts only if one more uncompressed scene still fits.
  - Crossing the reserve stops the run safely with the failure `storage_reserve`.
- **Cancellation semantics.** Cancelling is a request, honoured at the next safe
  boundary: before the next reset, viewpoint change, capture or scene attempt.
  - An operation already running always finishes, so no reset or capture is cut short.
  - The interrupted attempt is abandoned: its images are deleted, nothing of it reaches
    the indexes, and it counts as failed.
  - A scene whose last capture finished before the boundary is still accepted, so
    cancelling after the final scene completes the dataset normally.
  - Otherwise the dataset is marked `failed` with the failure `cancelled`. Its integrity
    section records the validation of what was kept.
  - Leaving the world fails the running operation, with the same clean-up.
  - A crash leaves the manifest `generating`, which every reader must treat as
    interrupted.
- **Split integrity is checked from the slot plan.** Both validators recompute
  `scene-slots-v1` from the manifest. Every scene must have the class and split its index
  was assigned, and samples inherit their scene's split. Scene IDs are unique and every
  index has exactly one slot, so no grouping key can reach two splits. Every
  target-present base sample must also satisfy the level's visibility rule.
- **Outside validator.** `honey-factory-tools validate-dataset` reimplements the
  contract in Python:
  - the schemas and single-record semantic rules;
  - the cross-record rules, including the slot plan and the visibility rule;
  - the quotas and index digests;
  - every image: byte count, SHA-256, an 8-bit non-interlaced PNG of the recorded size,
    binary masks with matching statistics, and no unreferenced or temporary files.

  It checks PNG headers itself and decodes with Pillow. A pure-Python decoder would take
  seconds per 1280² image. The shared fixtures now include real PNG files. They are
  written with stored deflate blocks, so their bytes do not depend on a zlib version.

## Consequences

- Levels 1–3 can be generated by command. Level 4 and above still has no generator, and
  `SceneGenerators` refuses it.
- Older scene records without `generation.parameters` stay valid. New datasets always
  write them.
- The outside validator adds a Pillow dependency to `honey-factory-tools`. The Java mod
  is unaffected.
- The free-space preflight counts uncompressed images, so a large dataset needs room for
  its worst case even though PNGs are smaller. Lower the reserve, or split the run, when
  that is too strict.
- The sight-line estimate assumes full cubes. Level 4 (stairs, slabs, fences, foliage)
  needs a shape-aware estimate or capture-only filtering.
