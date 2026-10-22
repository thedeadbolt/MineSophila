# Roadmap

Updated 2026-10-22. All phases are planned; the packages are currently scaffolds.

The project succeeds if the model can locate honey blocks in unseen Minecraft
scenes using fly-vision inputs. Movement and mining are extensions.

Our current scope is `honey-factory`: the Minecraft dataset generator and
environment. The collaborator owns `fly-vision`.

## Phase 1 — Dataset generator and vision processing

The two packages can be developed in parallel, with an agreed capture format.

| Owner | Deliverable |
|---|---|
| Collaborator: `fly-vision` | Image processing for compound vision and R1–R8; requirements for later ocelli inputs |
| Us: `honey-factory` | Configurable arenas, labelled images, repeatable resets and basic movement controls |

The mod will:

- Generate bounded arenas with a floor in a void world.
- Vary target placement, viewpoint, distractors and occlusion from a recorded seed.
- Capture RGB images, aligned visible-target masks and scene/camera metadata.
- Record capture timing, configuration and scene identity. Keep labels and world
  truth separate from the inputs given to the model.
- Support reset, observation and basic actions through manual or scripted control.

Generate separate difficulty datasets using the same format:

1. Visible honey at varied distances and viewpoints.
2. Honey among distractors, including similar-looking blocks.
3. Partially occluded honey.
4. Later, search/navigation episodes with honey outside the initial view or obstacles.

Include target-absent examples and distinguish absence from occlusion or being
outside the view. Split training and evaluation by scene/episode.

**Done when:** scenes can be regenerated, masks are verified against rendered
images, and environment controls work without a model. Ocelli adaptation is deferred;
agree on any extra capture requirements with the collaborator.

## Phase 2 — Dataset integration and network design

- Convert captures into a Minecraft fly-vision dataset, keeping labels aligned.
- Agree on sensory channels, sampling rates and the adapter between packages.
- Select a connectome and the visual, learning and output pathways to model.
  Investigate relay/projection neurons and Kenyon cells without assuming one
  serial pathway handles all vision and movement.
- Record which connections and parameters come from measurements or assumptions.
- Benchmark candidate network sizes on the [local hardware](hardware-and-capacity.md).

**Done when:** the dataset feeds the selected network and its memory use and runtime
have been measured.

## Phase 3 — Honey identification

- Train progressively across the difficulty datasets.
- Set evaluation criteria before training and compare against baseline models.
- Evaluate localisation on unseen scenes.

**Done when:** the agreed identification criteria are met. This completes the core
project. Learning to choose movement actions is optional.

## Phase 4 — Optional gameplay

- Connect model outputs to the environment controls from phase 1.
- Evaluate searching, turning and approaching honey.
- Record which actions come from the model and which come from movement assistance.
- Add mining only as a further extension.

If action learning requires interaction, build this connection during phase 3.

## Decisions needed for the mod specification

| Topic | Current position / open question |
|---|---|
| Masks | Required. Compare ray casting and an annotation render pass; define occlusion and honey translucency. |
| Capture format | RGB + target mask + metadata. Decide whether depth, instance IDs and extra views are required. Resolve [issue #1](https://github.com/thedeadbolt/MineSophila/issues/1) before storage implementation. |
| Arena and capture settings | Choose initial dimensions, block palette, resolution and difficulty parameters. |
| Movement | Prefer forward movement with automatic jumping over supported obstacles. Define limits; pathfinding is separate. |
| Block labels | Preserve useful world metadata. Classifying every block type is not required for honey localisation. |
| Ocelli | Defer the adapter. Determine directional brightness/sky observations with the collaborator; pose and ray hits are not light measurements. RGB does not measure UV or polarisation. |
| Storage | Plan dataset output on the 2 TB NVMe at `/mnt/fastvault`; choose the project directory and storage layout. |
| Rust | Available for measured processing bottlenecks. Keep Fabric integration in Java. |

## Next milestone

Write a short `honey-factory` specification covering arena generation, capture
outputs, environment controls and acceptance checks. Keep unresolved choices marked.
Then build one scene with one honey block and verify RGB/mask alignment from several
viewpoints, including partial and full occlusion, before generating a large dataset.

## References

- [Architecture](architecture.md): proposed package boundaries.
- [Hardware and capacity](hardware-and-capacity.md): inspected machine, memory
  estimates and benchmark plan.
- [Fly-Saber](https://github.com/jackie-0110/Fly-Saber): reference for visual control
  and neurotransmitter gain controls, not a required implementation.
- [Visual inputs to Kenyon cells](https://www.nature.com/articles/s41467-024-49616-z).
- [Ocellar and compound-eye development](https://pmc.ncbi.nlm.nih.gov/articles/PMC5223745/).
