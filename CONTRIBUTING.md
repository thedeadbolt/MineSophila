# Contributing

Thanks for helping. This guide covers the workflow. The [README](README.md) covers setup.

## Setup

```sh
uv tool install rust-just pre-commit
just setup
```

`just setup` installs the uv workspace, the git hooks (lint, format, lockfile check,
commit-message check) and the commit message template (`.gitmessage`).

## Workflow

1. Open or pick an issue. For a design decision, write an ADR in `docs/adr/`.
2. Branch off `main`: `feat/fly-vision-r7-receptor`, `fix/honey-factory-crash`, …
3. Make the change, with tests in the right layer (see [tests/README.md](tests/README.md)).
4. Run `just check`.
5. Open a PR. It is **squash-merged**, so the PR title becomes the commit on `main`.

## Commit messages and PR titles

We use [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <subject>
```

| Types | `feat` `fix` `docs` `style` `refactor` `perf` `test` `build` `ci` `chore` `revert` |
|---|---|
| Scopes | `fly-vision` `honey-factory` `docs` `repo` `deps` `ci` `tests` |

- `feat` bumps the minor version, `fix` bumps the patch, and `!` or `BREAKING CHANGE:` marks a breaking change.
- release-please uses each commit's changed files to decide which package's changelog and version it updates.
- The `commit-msg` hook checks local commits, and CI checks PR titles.

## Releases

release-please keeps a release PR open on `main`. Merging it tags each changed
package (`fly-vision-v0.2.0`, `honey-factory-v0.2.0`) and updates its `CHANGELOG.md`.
Don't edit changelogs or versions by hand.

## Code style

- Python: ruff (lint and format) and pyright (strict). Run `just fmt` to fix.
- Java: follow the Fabric template conventions (tabs).
- Keep packages independent. A package may import only what its `pyproject.toml` declares.
