# 0003: Isolated package tests + root integration/e2e tests

- **Status:** Accepted
- **Date:** 2026-10-21

## Context

Package tests should be isolated: a package must not pass its tests by accident because
another workspace package, or a dev tool, happens to be installed in the shared
environment. We also need full-product integration and end-to-end tests.

## Decision

- **Unit tests** live in `packages/<pkg>/tests/`. Each package has its own pytest config.
  They run with `uv run --isolated --package <pkg>` locally, and with
  `uv sync --package <pkg>` in CI, so the environment contains only that package and
  its declared dependencies.
- **Integration and e2e tests** live in root `tests/integration/` and `tests/e2e/` and run
  against the whole workspace.
- There is **no root `conftest.py`**. Shared fixtures live in `tests/conftest.py`, which
  only integration and e2e tests can see.
- pytest uses `--import-mode=importlib`, so no `__init__.py` files are needed in test
  directories and module names can repeat.
- Strict markers: `integration`, `e2e`, `minecraft`. Tests that need a live Minecraft
  instance are deselected by default.
- The mod uses JUnit through Gradle (`src/test/java`).

## Consequences

- Undeclared dependencies surface as test failures.
- A package's tests can't reach for another package's fixtures. Shared test helpers, if
  ever needed, become a declared dev dependency.
