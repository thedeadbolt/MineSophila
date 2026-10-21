# MineSophila: notes for Claude

Mixed-language monorepo: a uv workspace (Python) plus a Gradle Fabric mod.

- `packages/fly-vision`: Python, uv workspace member, `src/` layout, built with `uv_build`.
- `packages/honey-factory`: Java 25 Fabric mod for Minecraft 26.3. Not a uv member. Gradle
  runs on Java 25 via `gradle/gradle-daemon-jvm.properties` (auto-downloaded).
- `tests/`: integration and e2e tests only. Package unit tests live in `packages/<pkg>/tests`.

## Commands

Use `just` (run `just` to list recipes). Key ones: `just check`, `just test <pkg>`,
`just test-integration`, `just lint`, `just typecheck`, `just mod-build`.

## Rules

- Package tests run in isolation (`uv run --isolated --package <pkg>`). Never add a root
  `conftest.py`, and never import another package from a package's tests.
- Commits and PR titles use Conventional Commits, scoped to a package where relevant.
- Never edit `CHANGELOG.md` files or package versions by hand (release-please owns them).
- Record design decisions as ADRs in `docs/adr/`.
- Data is out of scope until issue #1 is resolved. `data/` is gitignored.
