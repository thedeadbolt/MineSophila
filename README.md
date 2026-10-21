# MineSophila

*Where's Waldo, but you're a fruit fly in Minecraft.*

MineSophila models *Drosophila melanogaster* vision and uses it to find and interact with
blocks in Minecraft. The first target is to spot honey blocks and mine them.

> **Status: scaffold.** The structure, tooling and docs are in place. Nothing is implemented yet.

## Packages

| Package | Language | What it does |
|---|---|---|
| [`fly-vision`](packages/fly-vision) | Python | Converts images and Minecraft scenes into fly vision: human, compound, R1–R8 photoreceptor and ocelli modes, plus any pair of them |
| [`honey-factory`](packages/honey-factory) | Java (Fabric mod) | Generates labelled datasets of honey blocks hidden in varied situations |

An adapter will connect the two later. See the [roadmap](docs/roadmap.md).

## Quick start

Requirements: [uv](https://docs.astral.sh/uv/), [just](https://just.systems/),
[pre-commit](https://pre-commit.com/) and Java 17+ to run Gradle (the Java 25 toolchain the
mod needs is downloaded automatically).

```sh
uv tool install rust-just pre-commit   # if you don't have them yet
just setup                             # install workspace, git hooks, commit template
just check                             # lint, typecheck, all tests, mod build
```

Run `just` to see every recipe.

## Repository layout

```
packages/
├── fly-vision/       # uv workspace member (Python)
└── honey-factory/    # Gradle project (Fabric mod), not a uv member
tests/
├── integration/      # cross-package tests
└── e2e/              # full product runs
docs/
├── architecture.md
├── roadmap.md
├── science/          # the biology behind fly-vision
└── adr/              # architecture decision records
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Commits and PR titles follow
[Conventional Commits](https://www.conventionalcommits.org/); changelogs and versions are
generated per package by release-please.

## License

[MIT](LICENSE)
