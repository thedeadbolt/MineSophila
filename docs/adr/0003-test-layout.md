# 0003: Isolated package tests + root integration/e2e tests

- **Status:** Superseded by [0004](0004-centralized-tests.md)
- **Date:** 2026-10-21

## Context

We wanted package tests to reveal undeclared imports without relying on other packages
or tools in a shared environment. Integration and end-to-end tests needed the whole workspace.

## Decision

- **Unit tests** live in `packages/<pkg>/tests/`. Each package has its own pytest config.
  Run them with `uv run --isolated --package <pkg>` during development, and with
  `uv sync --package <pkg>` in CI, so the environment contains only that package and
  its declared dependencies.
- **Integration and e2e tests** live in root `tests/integration/` and `tests/e2e/` and run
  against the whole workspace.
- Keep shared fixtures in `tests/conftest.py` and omit a repository-root `conftest.py`.
  Under this layout, package tests do not inherit the shared fixtures.
- pytest uses `--import-mode=importlib`, so no `__init__.py` files are needed in test
  directories and module names can repeat.
- Strict markers: `integration`, `e2e`, `minecraft`. Tests that need a live Minecraft
  instance are deselected by default.
- The mod uses JUnit through Gradle (`src/test/java`).

## Consequences

- We expected undeclared dependencies to cause test failures. See the limits of this
  assumption in [0004](0004-centralized-tests.md).
- Keep fixtures within each package. Declare any shared test helpers as a development dependency.
