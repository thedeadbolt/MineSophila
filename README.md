# MineSophila

MineSophila is a project to model *Drosophila melanogaster* vision and use it to find
and mine honey blocks in Minecraft.

The repository contains package scaffolds and development tooling. Vision processing
and dataset capture remain unimplemented.

## Packages

| Package | Language | Planned purpose |
|---|---|---|
| [`fly-vision`](packages/fly-vision) | Python | Model fly vision from images and scene metadata; compare vision modes |
| [`honey-factory`](packages/honey-factory) | Java (Fabric mod) | Generate labelled Minecraft scenes containing hidden honey blocks |
| [`honey-factory-tools`](packages/honey-factory-tools) | Python | Validate and inspect honey-factory datasets without Minecraft |

## Quick start

Requirements: Python 3.13+, [uv](https://docs.astral.sh/uv/), [just](https://just.systems/),
[pre-commit](https://pre-commit.com/) and Java 17+ to start the Gradle wrapper.
Gradle downloads Java 25 to build the mod.

```sh
uv tool install rust-just              # if you don't have it yet
uv tool install pre-commit
just setup                             # install workspace, git hooks, commit template
just check                             # lint, typecheck, all tests, mod build
```

Run `just` to list commands.

## Repository layout

```
packages/
├── fly-vision/           # uv workspace member (Python)
├── honey-factory/        # Gradle project (Fabric mod), not a uv member
└── honey-factory-tools/  # uv workspace member (Python)
schemas/
└── honey-factory/        # language-neutral dataset contract (JSON Schema)
tests/
├── fly-vision/       # Python package tests
├── honey-factory/    # Java / JUnit package tests and client game tests
├── honey-factory-tools/  # Python package tests
├── integration/     # workspace checks and shared contract fixtures
└── e2e/             # reserved for full product runs
docs/
├── contributing.md   # development workflow
├── architecture.md
├── science/          # the biology behind fly-vision
└── adr/              # architecture decision records
```

See the [project roadmap](docs/roadmap.md), [package overview](packages/README.md), [test guide](tests/README.md), and
[proposed architecture](docs/architecture.md).

## Contributing

See the [contributing guide](docs/contributing.md). Commits and PR titles follow
[Conventional Commits](https://www.conventionalcommits.org/). release-please updates
package versions and changelogs. See the changelogs for
[fly-vision](packages/fly-vision/CHANGELOG.md) and
[honey-factory](packages/honey-factory/CHANGELOG.md); release-please creates one for
honey-factory-tools on its first release.

## License

[MIT](LICENSE)
