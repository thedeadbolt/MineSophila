# honey-factory

A [Fabric](https://fabricmc.net/) mod that generates labelled datasets of honey blocks
placed in varied, partially hidden situations, recording where each one is.

> Status: scaffold. The mod loads and does nothing else yet.

- Minecraft **26.3**, Fabric Loader 0.19.5, Fabric API (see `gradle.properties`)
- Java **25**. You don't need it installed: Gradle downloads the toolchain.
  Gradle itself runs on Java 17 or newer.
- Mod id `honey_factory`, package `io.github.thedeadbolt.honeyfactory`

This is a Gradle project, not a uv workspace member. See
[ADR 0001](../../docs/adr/0001-mixed-language-monorepo.md).

## Layout

```
src/main/      # common code (runs on client and server)
src/client/    # client-only code: rendering, frame capture
src/test/      # JUnit tests
version.txt    # mod version, managed by release-please
```

## Development

```sh
just mod-build     # ./gradlew build (compiles + JUnit tests)
just mod-client    # launch a dev Minecraft client with the mod
```
