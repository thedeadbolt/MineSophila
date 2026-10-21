# 0004: Centralized tests by package

- **Status:** Accepted
- **Date:** 2026-10-21
- **Supersedes:** [0003](0003-test-layout.md)

## Context

We want to browse tests in one directory and identify their package from the path.

## Decision

- Put Python tests in `tests/fly-vision/` and Java tests in `tests/honey-factory/java/`,
  retaining the Java package hierarchy.
- Configure Python tests in the root `pyproject.toml`. Point Gradle's test source set at
  `tests/honey-factory/` and use Gradle to compile Java and run JUnit.
- Put workspace installation and cross-package checks in `tests/integration/`.
  Reserve `tests/e2e/` for complete product runs.
- Put Python fixtures inside each package or test layer. Avoid a shared
  `tests/conftest.py`, which would expose fixtures to all Python suites.
- `just test <package>` uses a fresh uv environment for the selected Python package.
  That environment includes development dependencies, which can hide missing runtime
  declarations. Test built distributions in a separate environment as runtime code develops.
- `just test-all` discovers all Python layers. Gradle runs Java tests through
  `just mod-test` or `just mod-build`; `just check` includes both toolchains.

## Consequences

You can browse tests in one place and reuse pytest settings while keeping fixtures within
each suite. Gradle needs source paths outside its project directory. Run Python tests
from a repository checkout; the source distribution does not include them.

See the [test guide](../../tests/README.md) for commands and current coverage.
