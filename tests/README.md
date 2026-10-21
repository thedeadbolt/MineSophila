# Tests

Tests are split into layers, and each layer can only see what it is allowed to.

| Layer | Location | Environment | Command |
|---|---|---|---|
| Unit (per package) | `packages/<pkg>/tests/` | Only that package and its declared deps | `just test <pkg>` |
| Mod unit | `packages/honey-factory/src/test/` | Gradle / JUnit | `just mod-test` |
| Integration | `tests/integration/` | Whole workspace | `just test-integration` |
| End-to-end | `tests/e2e/` | Whole workspace (+ Minecraft for `minecraft` tests) | `just test-e2e` |

Rules:

- Package tests import only their own package. They run in an isolated environment,
  so an undeclared dependency fails loudly.
- There is no `conftest.py` at the repo root. Shared fixtures live in
  `tests/conftest.py` and are visible only to integration and e2e tests.
- pytest uses `--import-mode=importlib`, so no `__init__.py` files are needed and
  test modules with the same name in different packages don't clash.
- Markers: `integration`, `e2e`, `minecraft`. Markers are strict, so a typo fails the run.

See [ADR 0003](../docs/adr/0003-test-layout.md).
