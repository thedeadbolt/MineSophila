# 0001: Mixed-language monorepo: uv workspace + Gradle

- **Status:** Accepted
- **Date:** 2026-10-21

## Context

We keep the Python packages and Java Minecraft mod in one repository to share tooling,
history and CI.

## Decision

- Use a uv workspace with a virtual root (`package = false`) and one `uv.lock`. Put Python
  packages in `packages/<name>/` with a `src/` layout and build them with `uv_build`.
- Keep `packages/honey-factory` as a Gradle project outside the uv workspace.
- Use a `justfile` to run commands for both toolchains.
- Use release-please for per-package versions and releases, with Conventional Commits
  scoped by package.

## Consequences

- `uv sync` at the root installs every Python package, which integration tests need.
- CI runs separate Python and Gradle workflows on pushes and pull requests.
- release-please bumps `pyproject.toml` versions but not `uv.lock`, so the release
  workflow re-locks the release PR branch.
