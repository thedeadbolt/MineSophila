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
[ADR 0009](../../docs/adr/0009-dataset-runner-and-levels-2-3.md)). Datasets follow the
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
  `environment/` holds the operation state machine; `generation/` runs a dataset through
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
just mod-gametest  # run client game tests: the M2 environment, M3 gates and M4 datasets
```

### Dataset worlds and the environment

Generation only changes worlds created for it. In the development client, create a new
world with **World Type: Honey Factory Dataset**, then claim it by typing its exact name:

```text
/honeyfactory world claim <world name>
/honeyfactory world status
```

The environment commands drive the same runtime that tests and the future dataset runner
use:

```text
/honeyfactory_env reset <fixture>                         build a fixture scene, e.g. open, partial, fence
/honeyfactory_env viewpoint fixture | <x> <y> <z> <yaw> <pitch>
/honeyfactory_env observe <label> <width> <height>        capture into honey-factory-debug/<label>/
/honeyfactory_env status
```

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

The dataset is written to `<output root>/<dataset id>/`. Levels 1–3 have generators.

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

### Game tests and golden masks

`mod-gametest` opens a Minecraft window and creates fresh worlds under
`build/run/clientGameTest/`. The M3 mask gate writes RGB, masks and overlays for every
fixture to `build/m3-review/`, and the dataset test writes two regenerated datasets to
`build/m3-datasets/`. The M4 gate writes level 1–3 smoke datasets and a cancelled run to
`build/m4-datasets/`. Golden visible masks live in
`tests/honey-factory/gametest/resources/golden/m3/`. A missing golden fails the gate and is
written for review. After a reviewed change, rewrite them all with
`./gradlew runClientGameTest -Dhoneyfactory.regenerateGolden=true`.

See the [test guide](../../tests/README.md) and [Fabric decision](../../docs/adr/0002-fabric-mod-loader.md).
