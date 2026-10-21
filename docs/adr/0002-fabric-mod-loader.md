# 0002: Fabric for honey-factory

- **Status:** Accepted
- **Date:** 2026-10-21

## Context

honey-factory needs to generate scenes and capture frames, including depth and other
buffers, from the client renderer. The main candidates were Fabric and NeoForge.

## Decision

- **Fabric**, set up from the official `fabric-example-mod` template.
- Target **Minecraft 26.3**, the latest stable version at the time of writing, with Fabric
  Loader 0.19.5 and Fabric API 0.163.0+26.3.
- **Java 25**, as required by Minecraft 26.x and Loom. Gradle itself must run on Java 25.
  `gradle/gradle-daemon-jvm.properties` makes Gradle download and use a Java 25 runtime
  automatically, so contributors only need Java 17+ to start the wrapper.
- Mod id `honey_factory`, Java package `io.github.thedeadbolt.honeyfactory`.

## Consequences

- Fabric is lightweight, updates quickly after Minecraft releases, and makes client
  rendering hooks (mixins, events) easy, which suits frame capture.
- Minecraft, Loader, Loom and Fabric API versions must be bumped together by hand, so
  Dependabot ignores them.
- Loom is a `-SNAPSHOT` plugin version, following the template, so builds can change
  under us. Pin it if that causes trouble.
