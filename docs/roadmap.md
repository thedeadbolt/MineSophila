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
4. Constructed environments with irregular geometry.
5. Natural Minecraft terrain copied into the isolated arena.
6. Optionally, active-search episodes with honey initially hidden or outside view.

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

## Phase 4 — Active search with the fly brain

Agreed 2026-10-24. Phase 4 is the long-term goal, no longer optional gameplay. The
connectome-constrained fly brain keeps honey in view and, when it disappears, searches for
it with memory instead of randomly. The agent stays a player that walks, turns and looks.

- `honey-factory` provides four things:
  - animal-like lock-step control, with one frame per tick;
  - motion clips generated through that control;
  - a caged level-6 environment with teleporting honey and a scripted teacher;
  - a live adapter.

  These are roadmap milestones M11–M14 of the
  [`honey-factory` roadmap](../packages/honey-factory/docs/roadmap.md#beyond-phase-1-active-search-with-the-fly-brain).
- The brain learns mostly offline by imitating the teacher from recorded frame streams.
  DAgger rounds run in the live loop, and reinforcement-learning fine-tuning is optional.
  A plain recurrent network is the baseline.
- Memory should come from the connectome's own recurrent circuits, such as the central
  complex and the mushroom body. Which circuits, and how motor neurons map to controls, is
  phase-2 network design with the collaborator.
- Mining remains a further extension.

## Former phase 4 — Optional gameplay

Superseded by the plan above; kept for its original scope.

- Connect model outputs to the environment controls from phase 1.
- Evaluate searching, turning and approaching honey.
- Record which actions come from the model and which come from movement assistance.
- Add mining only as a further extension.

If action learning requires interaction, build this connection during phase 3.

## Mod decisions and remaining checkpoints

| Topic | Current position / open question |
|---|---|
| Masks | Target-only GPU annotation pass with scene depth; ray casting is validation-only. The M0 prototype must verify the exact 26.3 hooks. |
| Capture format | RGB + visible-target mask + metadata; no exported depth initially. Perspective and panoramic resolutions are configurable. |
| Arena and capture settings | Configurable with initial defaults in the package spec. Prototype measurements still need to set level 3–5 visibility ranges and level-5 region defaults. |
| Movement | Synchronous tick-based actions with vanilla auto-jump initially enabled; no custom navigation/pathfinding. |
| Block labels | Preserve useful world metadata. Classifying every block type is not required for honey localisation. |
| Ocelli | Defer the adapter. Determine directional brightness/sky observations with the collaborator; pose and ray hits are not light measurements. RGB does not measure UV or polarisation. |
| Storage | Portable PNG + JSONL datasets under a configurable root; local proposed root is `/mnt/fastvault/minesophila/datasets`. |
| Rust | Use only for a measured bottleneck after the Java/Fabric implementation is benchmarked. |

## Next milestone

Follow the [`honey-factory` implementation roadmap](../packages/honey-factory/docs/roadmap.md),
starting with its M0 Minecraft 26.3 rendering-feasibility gate. Prove target-only
annotation masks for open, partially occluded and fully hidden honey before building
generic scene generation or panoramic capture.

## References

- [Architecture](architecture.md): proposed package boundaries.
- [Hardware and capacity](hardware-and-capacity.md): inspected machine, memory
  estimates and benchmark plan.
- [Fly-Saber](https://github.com/jackie-0110/Fly-Saber): reference for visual control
  and neurotransmitter gain controls, not a required implementation.
- [Visual inputs to Kenyon cells](https://www.nature.com/articles/s41467-024-49616-z).
- [Ocellar and compound-eye development](https://pmc.ncbi.nlm.nih.gov/articles/PMC5223745/).
