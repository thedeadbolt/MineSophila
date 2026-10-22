# Tests

Find package tests and workspace checks here.

| Suite | Location | Command from repository root |
|---|---|---|
| Python package | `tests/fly-vision/` | `just test fly-vision` |
| Java package | `tests/honey-factory/java/` | `just mod-test` |
| Minecraft client game tests | `tests/honey-factory/gametest/` | `just mod-gametest` |
| Workspace / integration | `tests/integration/` | `just test-integration` |
| End-to-end (planned) | `tests/e2e/` | `just test-e2e` |
| All Python suites | `tests/` | `just test-all` |

`just check` runs Python lint, type checking and all Python suites, then builds the mod
and runs its JUnit tests. `just test-all` runs each package in a fresh environment, then
runs integration and e2e tests together in the workspace environment. Individual suite
recipes accept extra pytest arguments, for example `just test fly-vision -v`.

## Current coverage

The Python package test checks version metadata; the workspace smoke test imports
`fly_vision` after installation. The Java test checks the identifier helper's mod namespace.
Add tests for vision processing, dataset generation and package interactions as you implement them.

The Minecraft client game test runs the M0 rendering fixtures in a fresh world. It needs
a display and GPU, and `just mod-build` does not run it. There are no Python end-to-end
or live Minecraft tests yet. Running `just test-e2e` or
`just test-minecraft` exits with pytest's “no tests collected” status (5).
The full Python suite includes the e2e directory and will discover tests added there.

## Configuration and dependencies

Configure pytest, markers and coverage in the root `pyproject.toml`.
Gradle's test source set points to `tests/honey-factory/java/`; add Java test resources
under `tests/honey-factory/resources/` when needed.

`just test <package>` uses a fresh uv environment with the package's runtime and development
dependencies, including pytest. Check runtime dependency declarations during review; the
test environment can hide missing declarations. See
[uv's workspace limitations](https://docs.astral.sh/uv/concepts/projects/workspaces/#when-not-to-use-workspaces).

## Fixtures and markers

- Put fixtures in the narrowest applicable suite, such as `tests/fly-vision/conftest.py`
  or `tests/integration/conftest.py`. Do not add a repository-root or shared
  `tests/conftest.py`: its fixtures would be visible to package tests too.
- Python package tests should import the package under test and its declared dependencies.
- pytest uses `--import-mode=importlib`; test directories need no `__init__.py` files.
- Mark workspace and cross-package checks `integration`, full product runs `e2e`, and
  tests requiring a live Minecraft instance `minecraft`. Unknown markers fail the run.
- The default pytest configuration skips Minecraft tests. Run them with `just test-minecraft`.

See [ADR 0004](../docs/adr/0004-centralized-tests.md) for the layout decision.
