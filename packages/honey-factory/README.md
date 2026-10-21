# honey-factory

Fabric mod for a planned dataset of honey blocks with varying amounts of occlusion.

The common entrypoint logs startup; the client entrypoint has no behaviour yet.
See the proposed scenario generation and frame capture in the
[architecture](../../docs/architecture.md).

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
```

See the [test guide](../../tests/README.md) and [Fabric decision](../../docs/adr/0002-fabric-mod-loader.md).
