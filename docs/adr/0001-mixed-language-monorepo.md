# 0001: Mixed-language monorepo: uv workspace + Gradle

- **Status:** Accepted
- **Date:** 2026-10-21

## Context

The project has Python packages (fly-vision, a future adapter) and a Minecraft mod, which
must be Java. We want one repository with shared tooling, history and CI.

## Decision

- The repo root is a **uv workspace** with a virtual root (`package = false`) and a single
  `uv.lock`. Python packages live in `packages/<name>/` with a `src/` layout and are built
  with `uv_build`.
- `packages/honey-factory` is a self-contained **Gradle** project. It is deliberately not a
  uv workspace member.
- A `justfile` gives one command surface across both toolchains.
- Each package is versioned and released independently by **release-please**, with
  Conventional Commits scoped by package.

## Consequences

- `uv sync` at the root installs every Python package, which integration tests need.
- CI runs separate Python and Gradle workflows, filtered by path.
- release-please bumps `pyproject.toml` versions but not `uv.lock`, so the release
  workflow re-locks the release PR branch.
- If the repo grows large, we may need affected-only builds (e.g. Pants). We'll revisit then.
