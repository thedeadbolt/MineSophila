# MineSophila: notes for Claude

Python workspace and Fabric mod in one repository.

- `packages/fly-vision`: Python uv workspace member, `src/` layout, `uv_build` backend.
- `packages/honey-factory`: Java 25 Fabric mod for Minecraft 26.3, outside the uv workspace.
  Gradle downloads Java 25 as specified in `gradle/gradle-daemon-jvm.properties`.
- `tests/<package>/`: package tests, including Java under `tests/honey-factory/java/`.
  Integration and e2e suites share the `tests/` parent directory. See `tests/README.md`.

## Commands

Run `just` to list commands. Use `just check` for the full check suite or
`just test <pkg>` for a Python package. Other commands include `just test-integration`,
`just lint`, `just typecheck`, and `just mod-build`.

## Rules

- Python package tests use a fresh environment (`just test <pkg>`), which also contains
  development tooling. Keep fixtures in each test suite; do not add a repo-root or
  shared `tests/conftest.py`. Import only the package under test and its declared dependencies.
- Keep planned APIs in `docs/architecture.md`; add source modules when implementing them.
- Commits and PR titles use Conventional Commits, scoped to a package where relevant.
- Let release-please update `CHANGELOG.md` files and package versions; do not edit them by hand.
- Record design decisions as ADRs in `docs/adr/`.
- Resolve issue #1 before adding dataset storage. Git ignores `data/`.
