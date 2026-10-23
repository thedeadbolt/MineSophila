# 0011: Scene recipes instead of per-level generators

- **Status:** Accepted
- **Date:** 2026-10-24

## Context

Levels 1–4 each had a generator class that wrote its layout into code and shared only
the floor, target and camera placement through a base class. Combining content from
different levels needed a new class, and lighting was one fixed time and weather per
dataset. The user asked for levels to be settings objects a person creates, edits and
combines, for any level to use other lighting, and for the seed to be just another
setting.

Established synthetic-data tools share one structure: a run object holding a list of
small randomizing components, each with its own parameters (Unity Perception's Scenario
and Randomizers, NVIDIA Replicator's randomizers). Minecraft's own world generation
registers feature types by name and configures them in JSON. BlenderProc moved from
YAML pipelines to a code-first API. Unity warns that same-seed runs repeat only while the
order and number of draws stay the same.

## Decision

- **A scene recipe** is a base, layout parts in build order, a viewpoint sight rule and
  lighting. `SceneRecipe` is the editable form with chained setters and a live parts list;
  `build()` returns the immutable `SceneSpec` a `RecipeGenerator` runs.
- **Parts are registered components** (`LayoutParts`): `clutter`, `hiding_structure`,
  `background_structures`, `scattered_blocks`, `constructed_setting`, `near_aim_blocks`
  and `scattered_items`. Each is a record of its settings with a JSON form; omitted
  settings take its defaults. New content is a new part, not a new level.
- **Levels 1–4 are presets** (`ScenePresets`): `level1` to `level4`. A configuration's
  `scene` section names a preset (by default its level's) and replaces any of `base`,
  `parts`, `sight` and `lighting`; lighting members not given keep the preset's. The
  difficulty level still sets the visibility rule and labels the dataset.
- **One stream per part**, keyed by its position and type, and separate streams for the
  target, lighting and viewpoints. Editing or removing one part never shifts another's
  draws, and changing lighting never moves a block.
- **Lighting for every level.** Each scene draws its time from a range and its weather
  from a list. Presets keep fixed noon and clear weather, so level 1 still defaults to the
  specification's fixed daylight. Rain and thunder are allowed; their particles make RGB
  images not byte-reproducible, while masks are unaffected.
- **The seed is optional.** A configuration without one gets a random seed, recorded in
  the resolved configuration like any other.
- **Recorded in full.** The manifest's `generator.scene` holds the whole recipe and
  replaces `generator.lighting`; `generator.algorithms.scene_generator` is
  `scene-recipe-v1` for every level. The recipe is part of the configuration hash.
- **Contract 1.0 is amended in place.** No honey-factory dataset has been published, so
  the manifest change does not start a 1.1 or 2.0 schema.
- **People look at recipes in the game** with `/honeyfactory_env reset preset <name>` and
  `reset recipe <file>`, which build one scene and move to its first viewpoint.

## Consequences

- Datasets of levels 1–4 generated before this change used the per-level algorithms
  (`level1-open-platform-v1` to `level3-partial-hiding-v1`); their records stay valid as
  recorded, but the same configuration now produces different scenes.
- Part settings are deliberately few (counts, clearances, kinds). Palettes and
  probabilities stay in code until a dataset needs them configurable.
- Level 5 becomes a second base type and preset in the same recipe model.
