# 0002: Fabric for honey-factory

- **Status:** Accepted
- **Date:** 2026-10-21

## Context

We need access to the Minecraft client renderer to capture frames, depth and other
buffers for honey-factory. We considered Fabric and NeoForge.

## Decision

- Use Fabric and start from the official `fabric-example-mod` template.
- Target Minecraft 26.3 with Fabric Loader 0.19.5 and Fabric API 0.163.0+26.3.
- Use Java 25 for Minecraft 26.x and Loom, including the Gradle daemon.
  `gradle/gradle-daemon-jvm.properties` configures Gradle to download Java 25.
  Contributors need Java 17+ to start the wrapper.
- Mod id `honey_factory`, Java package `io.github.thedeadbolt.honeyfactory`.

## Consequences

- Use Fabric mixins and events to access the client renderer for frame capture.
- Review Minecraft, Loader, Loom and Fabric API versions together when upgrading.
  Dependabot ignores these dependencies.
- The template uses a `-SNAPSHOT` Loom version, so a new build can resolve a different
  plugin without a repository change. Pin a release if this causes build failures.
