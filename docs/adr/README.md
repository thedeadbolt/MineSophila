# Architecture Decision Records

Record design decisions and their reasons here. Copy
[`template.md`](template.md) to `NNNN-short-title.md` with the next number.
Preserve accepted decisions. To change one, write a new ADR that supersedes it.

| ADR | Title | Status |
|---|---|---|
| [0001](0001-mixed-language-monorepo.md) | Mixed-language monorepo: uv workspace + Gradle | Accepted |
| [0002](0002-fabric-mod-loader.md) | Fabric for honey-factory | Accepted |
| [0003](0003-test-layout.md) | Isolated package tests + root integration/e2e tests | Superseded by 0004 |
| [0004](0004-centralized-tests.md) | Centralized tests by package | Accepted |
| [0005](0005-shared-depth-target-annotation.md) | Shared-depth target annotation for honey-factory masks | Accepted |
| [0006](0006-dataset-contract-and-algorithms.md) | honey-factory dataset contract and deterministic algorithms | Accepted |
| [0007](0007-dataset-world-and-environment-coordinator.md) | Dataset world ownership, arena reset and environment coordinator | Accepted |
| [0008](0008-capture-service-and-level1-generation.md) | Capture service, level-1 generation and dataset commit | Accepted |
| [0009](0009-dataset-runner-and-levels-2-3.md) | Dataset runner, levels 2–3 and the outside validator | Accepted |
| [0010](0010-panoramic-capture.md) | 310-degree panoramic capture | Accepted |
| [0011](0011-scene-recipes.md) | Scene recipes instead of per-level generators | Accepted |
