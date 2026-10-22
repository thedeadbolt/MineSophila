# honey-factory

Fabric mod for a planned dataset of honey blocks with varying amounts of occlusion.

The [package specification](docs/spec.md) records the agreed behaviour and acceptance
criteria. The [implementation roadmap](docs/roadmap.md) orders the work into gated,
reviewable milestones.

The common entrypoint logs startup. The only feature is the temporary
[M0 rendering-feasibility fixture](docs/m0-rendering-feasibility.md); see the proposed
scenario generation and frame capture in the [architecture](../../docs/architecture.md).

This is a Gradle project, separate from the uv workspace. It targets Minecraft 26.3 and
Java 25; dependency versions live in `gradle.properties`. Java 17+ can start the wrapper;
`gradle/gradle-daemon-jvm.properties` selects Java 25 for the Gradle daemon, downloading
it when necessary. Compilation also targets Java 25.

The mod id is `honey_factory`; Java packages use `io.github.thedeadbolt.honeyfactory`.

## Layout

- `src/main/`: common code and mod resources.
- `src/client/`: client entrypoint and future rendering/capture code.
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

`mod-gametest` opens a Minecraft window, creates a fresh world under
`build/run/clientGameTest/` and writes M0 captures and `report.json` to its
`honey-factory-m0/` directory. To run the fixture by hand, enable *Improved Transparency* in the video settings (capture
refuses classic transparency; see ADR 0005), create a world named `honey-factory-m0` in
the development client, then run
`/honeyfactory m0 fixture <open|partial|full> @s` followed by
`/honeyfactory_capture <label> <width> <height> <repeats>`. The fixture command refuses
any other world.

See the [test guide](../../tests/README.md) and [Fabric decision](../../docs/adr/0002-fabric-mod-loader.md).
