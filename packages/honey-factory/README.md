# honey-factory

Fabric mod for a planned dataset of honey blocks with varying amounts of occlusion.

The [package specification](docs/spec.md) records the agreed behaviour and acceptance
criteria. The [implementation roadmap](docs/roadmap.md) orders the work into gated,
reviewable milestones.

Implemented so far: the [M0 rendering-feasibility](docs/m0-rendering-feasibility.md)
findings; M1's configuration resolver, data contract records and deterministic ID, seed
and split rules; M2's owned dataset worlds, arena resets and environment coordinator
([ADR 0007](../../docs/adr/0007-dataset-world-and-environment-coordinator.md)); and M3's
capture service, level-1 generator and validated dataset writing
([findings](docs/m3-level1-vertical-slice.md),
[ADR 0008](../../docs/adr/0008-capture-service-and-level1-generation.md)); and M4's dataset
runner with level-2 clutter and level-3 partial-hiding generators
([findings](docs/m4-dataset-runner.md),
[ADR 0009](../../docs/adr/0009-dataset-runner-and-levels-2-3.md)); and M5's 310-degree
panoramic capture ([findings](docs/m5-panoramic-capture.md),
[ADR 0010](../../docs/adr/0010-panoramic-capture.md)); M6's constructed and natural scenes
built from scene recipes ([findings](docs/m6-constructed-natural.md), ADRs
[0011](../../docs/adr/0011-scene-recipes.md)–[0013](../../docs/adr/0013-dataset-throughput.md));
M7's movement actions ([findings](docs/m7-movement-actions.md),
[ADR 0014](../../docs/adr/0014-movement-actions.md)); and M8's dataset inspector, sidecar
reviews and in-game replay ([findings](docs/m8-inspector-and-replay.md),
[ADR 0015](../../docs/adr/0015-inspector-and-replay.md)). The
[operator guide](docs/operating.md) walks through generating, validating, inspecting and
replaying a dataset. Datasets follow the
language-neutral [data contract](../../schemas/honey-factory/), and
[`honey-factory-tools`](../honey-factory-tools/) validates them outside Minecraft.
See the proposed scenario generation and frame capture in the
[architecture](../../docs/architecture.md).

This is a Gradle project, separate from the uv workspace. It targets Minecraft 26.3 and
Java 25; dependency versions live in `gradle.properties`. Java 17+ can start the wrapper;
`gradle/gradle-daemon-jvm.properties` selects Java 25 for the Gradle daemon, downloading
it when necessary. Compilation also targets Java 25.

The mod id is `honey_factory`; Java packages use `io.github.thedeadbolt.honeyfactory`.

## Layout

- `src/main/`: common code and mod resources. `contract/` holds the dataset record types and
  the published canonical-JSON, ID, seed and layout algorithms; `config/` resolves and
  validates generation settings; `dataset/` plans scene slots and checks records against
  each other, writes dataset directories and validates them; `scene/` builds immutable
  scene plans and holds the level generators; `capture/` holds the capture projections;
  `environment/` holds the operation state machine, movement actions and their limits; `generation/` runs a dataset through
  an environment interface. Dependencies point toward `contract/`, and none of these
  import Minecraft. `world/` applies plans to owned worlds on the logical server.
- `src/client/`: client entrypoint, the capture service and mask renderer
  (`client/capture/`), the environment runtime (`client/runtime/`), dataset runs
  (`client/generation/`) and two capture mixins (`client/mixin/`).
- [`tests/honey-factory/`](../../tests/honey-factory/): repository-level JUnit sources,
  configured by this project's `build.gradle`.
- `version.txt`: mod version, managed by release-please.

## Development

Run from the repository root:

```sh
just mod-test      # JUnit tests
just mod-build     # compile, test and package the mod
just mod-client    # launch a development Minecraft client
just mod-gametest  # run client game tests: the M2 and M7 environment, M3 gates, M4–M6 datasets, M5 panorama gate and M8 replay
```

### Dataset worlds and the environment

Generation only changes worlds created for it. In the development client, create a new
world with **World Type: Honey Factory Dataset**, then claim it by typing its exact name:

```text
/honeyfactory world claim <world name>
/honeyfactory world status
```

The environment commands drive the same runtime that tests and the dataset runner use:

```text
/honeyfactory_env reset <fixture>                         build a fixture scene, e.g. open, partial, fence
/honeyfactory_env reset preset <name> [seed [index]]      build one scene of a preset, e.g. level4 7 2
/honeyfactory_env reset recipe <file> [seed [index]]      the same for a recipe file in the game directory
/honeyfactory_env reset course                            build the movement course
/honeyfactory_env viewpoint fixture | <x> <y> <z> <yaw> <pitch>
/honeyfactory_env viewpoint course <lane>                 stand at a course lane: step, wall, slab, stairs, fence
/honeyfactory_env observe <label> <width> <height>        capture into honey-factory-debug/<label>/
/honeyfactory_env observe <label> <width> <height> panorama <face size>
                                                          the same as a panorama, with each cube face in faces/
/honeyfactory_env step turn|look <degrees>                turn right / look down (negative: left / up)
/honeyfactory_env step move_forward|wait <ticks>          walk forward / stand still for player ticks
/honeyfactory_env step stop                               release every input
/honeyfactory_env settings [autojump <true|false>]        show settings, or set auto-jump for later steps
/honeyfactory_env history                                 the last ten finished operations, in order
/honeyfactory_env status
```

A step moves the player's body with gravity and collisions: the first step after a reset
or viewpoint turns the camera into an invulnerable adventure-mode player where it stands.
Movement goes through Minecraft's own input and auto-jump, which is on by default; your
auto-jump option and keyboard are borrowed only while a step runs. Each step reports the
requested and completed values and ticks, auto-jump presses, collisions and the server
tick, and captures a 320 × 240 view. Mining is rejected. In Java, `EnvironmentRuntime.step`
takes an `Action` and a projection, and `configure` sets the action limits and auto-jump.

`/honeyfactory world release` restores the game rules, time, weather and player state the
scenes changed. Every reset only clears the arena in the void `honey_factory:arena`
dimension, inside the bounds the claim recorded.

### Generating a dataset

Enable *Improved Transparency* in the video settings (ADR 0005), claim a dataset world as
above, then write a configuration (see `ConfigResolver` for every setting) and start it.
Paths are relative to the game directory; `output.root` must be absolute.

```json
{
  "dataset": {"name": "honey-l1-smoke", "seed": 1, "difficulty": 1},
  "quotas": {"accepted_scenes": 10},
  "output": {"root": "/mnt/fastvault/minesophila/datasets", "free_space_reserve_bytes": 10737418240}
}
```

```text
/honeyfactory_dataset generate honey-l1-smoke.json
/honeyfactory_dataset status
/honeyfactory_dataset cancel
```

The dataset is written to `<output root>/<dataset id>/`. Levels 1–5 have generators.
Without `dataset.seed` a random seed is drawn and recorded in the manifest.

### Scene recipes

Every level is a preset recipe (ADR 0011): a base, layout parts built in order, a rule
for which viewpoints to keep, and lighting. A configuration's `scene` section starts from
its level's preset, or the one it names, and replaces what it gives. This level-3
dataset adds level-4 vegetation and draws each scene's time and weather:

```json
"scene": {
  "parts": [
    {"type": "hiding_structure"},
    {"type": "constructed_setting", "kinds": ["courtyard"]},
    {"type": "near_aim_blocks", "count": [1, 3]}
  ],
  "lighting": {"time_of_day_ticks": [1000, 11000], "weathers": ["clear", "rain"]}
}
```

| Setting | Values |
|---|---|
| `preset` | `level1` … `level5` |
| `base` | `{"type": "platform", "floor": <full-cube block state>}` or a `natural_patch` (below) |
| `parts` | `clutter`, `hiding_structure`, `background_structures`, `scattered_blocks`, `constructed_setting`, `near_aim_blocks`, `scattered_items`, each with its own settings (see its class) |
| `sight` | `any`, `all_clear`, `partly_hidden`, `some_clear`, `clear_and_obstructed` |
| `lighting` | `time_of_day_ticks` as a number or `[min, max]`; `weather` or `weathers` |

The same recipe in Java:

```java
SceneSpec spec = SceneRecipe.of("level3")
    .addPart(new ConstructedSettingPart(List.of("courtyard")))
    .lighting(Lighting.of(new IntRange(1000, 11000), Weather.CLEAR, Weather.RAIN))
    .build();
RecipeGenerator generator = new RecipeGenerator(spec, arena, targetStates, viewpoints);
```

Rain and thunder images are not byte-reproducible; masks are. Write a `scene` section to a
file and run `/honeyfactory_env reset recipe <file>` to look at it before generating.

### Natural terrain (level 5)

A `natural_patch` base copies a patch of the dataset world's own Overworld into a separate
region of the arena (ADR 0012). Targets and cameras stay in the core; the padding around it
is scenery, so the copy reaches past the render distance and no void edge shows. All sizes
are whole chunks or sections:

```json
"scene": {"base": {"type": "natural_patch", "core_width": 32, "core_length": 32, "height": 128,
                   "context_padding": 192, "scenes_per_patch": 8, "region_separation_blocks": 256}}
```

These are the defaults, and `level5` uses them with daylight times from 1000 to 11000 ticks
in clear weather or rain. A run refuses padding below 16 blocks per chunk of render
distance: lower the render distance and the padding together to generate faster. Patches
that would show void below their ground, contain a natural target block or offer fewer than
16 places for a target are skipped. Every patch is its own split group, and patches are at
least the region separation apart. New terrain is generated on first use and stays in the
save, tens of megabytes per patch. `/honeyfactory_env reset preset level5 <seed> <index>`
prepares the patch a dataset with that seed would use and shows one of its scenes.

### Throughput

A run logs where its time went when it ends (`Dataset timings: …`). On the reference machine
at the default sizes, one image takes about 0.09 s on a platform in perspective mode, 0.21 s
as a panorama, and 0.26 s and 0.40 s on natural terrain (ADR 0013).

- **Before starting:** the run refuses to start unless the output file system holds the
  whole dataset uncompressed plus the free-space reserve.
- **While running:** it stops safely if one more scene would cross the reserve.
- **Status:** shows progress, rejection reasons and throughput.
- **Cancel:** lets the running reset, viewpoint change or capture finish, discards the
  unfinished scene and marks the dataset `failed`.
- **Finishing:** the dataset is marked `complete` only after validation passes; otherwise
  it is kept as `failed` for diagnosis.

Check any dataset outside the game with
`uv run honey-factory-tools validate-dataset <dataset directory>`.

### Inspecting and replaying

`uv run honey-factory-tools inspect <dataset directory> --reviews <review root>` opens the
dataset in a local browser page; see [`honey-factory-tools`](../honey-factory-tools/).
Each sample page shows the command that rebuilds its scene in the game:

```text
/honeyfactory_replay load <scene or sample id> <dataset directory>
/honeyfactory_replay viewpoint <index> | next | previous
/honeyfactory_replay capture        compare a fresh capture of this viewpoint with the stored one
/honeyfactory_replay verify         compare every viewpoint of the scene
/honeyfactory_replay status
```

Replay needs a claimed dataset world, and refuses any other. It rebuilds the scene from
the dataset's recorded configuration and seeds, reporting any version this build lacks,
and checks the layout hash. Level-5 scenes replay only in the dataset world that
generated them, or a copy of it. Fresh images go to `honey-factory-replay/` in the game
directory; the dataset itself is never written.

### Game tests and golden masks

`mod-gametest` opens a Minecraft window and creates fresh worlds under
`build/run/clientGameTest/`. The M3 mask gate writes RGB, masks and overlays for every
fixture to `build/m3-review/`, and the dataset test writes two regenerated datasets to
`build/m3-datasets/`. The M4 gate writes level 1–3 smoke datasets and a cancelled run to
`build/m4-datasets/`. The M5 gate writes panoramas, overlays and raw faces to
`build/m5-review/` and panorama datasets to `build/m5-datasets/`. Golden visible masks
live in `tests/honey-factory/gametest/resources/golden/m3/` and `golden/m5/`. The M6 tests write
level-4 and mixed-recipe datasets to `build/m6-datasets/`, level-5 datasets, a review
panorama and a padding benchmark to `build/m6-natural/`, and the throughput profile to
`build/m6-throughput/`. The M7 test writes the movement-course results to
`build/m7-environment/`, and the M8 test writes its reference datasets and replay
results to `build/m8-replay/`. A missing golden fails the gate and is
written for review. After a reviewed change, rewrite them all with
`./gradlew runClientGameTest -Dhoneyfactory.regenerateGolden=true`.

See the [test guide](../../tests/README.md) and [Fabric decision](../../docs/adr/0002-fabric-mod-loader.md).
