# honey-factory

Fabric mod for a planned dataset of honey blocks with varying amounts of occlusion.

The [package specification](docs/spec.md) records the agreed behaviour and acceptance
criteria. The [implementation roadmap](docs/roadmap.md) orders the work into gated,
reviewable milestones.

Implemented so far: the temporary
[M0 rendering-feasibility fixture](docs/m0-rendering-feasibility.md); M1's
configuration resolver, data contract records and deterministic ID, seed and split
rules; and M2's owned dataset worlds, arena resets and environment coordinator
([ADR 0007](../../docs/adr/0007-dataset-world-and-environment-coordinator.md)). Datasets follow the language-neutral [data contract](../../schemas/honey-factory/).
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
  each other; `scene/` builds immutable scene plans; `environment/` holds the operation
  state machine. Dependencies point toward `contract/`, and none of these import
  Minecraft. `world/` applies plans to owned worlds on the logical server.
- `src/client/`: client entrypoint, the environment runtime (`client/runtime/`) and the M0
  capture fixture (`client/m0/`).
- [`tests/honey-factory/`](../../tests/honey-factory/): repository-level JUnit sources,
  configured by this project's `build.gradle`.
- `version.txt`: mod version, managed by release-please.

## Development

Run from the repository root:

```sh
just mod-test      # JUnit tests
just mod-build     # compile, test and package the mod
just mod-client    # launch a development Minecraft client
just mod-gametest  # run client game tests, including the M0 fixtures
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
/honeyfactory_env reset <empty|platform|open|partial|full>   build a fixture scene
/honeyfactory_env viewpoint fixture | <x> <y> <z> <yaw> <pitch>
/honeyfactory_env observe <label> <size>                     capture RGB and masks
/honeyfactory_env status
```

`/honeyfactory world release` restores the game rules, time, weather and player state the
scenes changed. Every reset only clears the arena in the void `honey_factory:arena`
dimension, inside the bounds the claim recorded.

### M0 fixture

`mod-gametest` opens a Minecraft window, creates a fresh world under
`build/run/clientGameTest/` and writes M0 captures and `report.json` to its
`honey-factory-m0/` directory. To run the fixture by hand, enable *Improved Transparency* in the video settings (capture
refuses classic transparency; see ADR 0005), create a world named `honey-factory-m0` in
the development client, then run
`/honeyfactory m0 fixture <open|partial|full> @s` followed by
`/honeyfactory_capture <label> <width> <height> <repeats>`. The fixture command refuses
any other world.

See the [test guide](../../tests/README.md) and [Fabric decision](../../docs/adr/0002-fabric-mod-loader.md).
