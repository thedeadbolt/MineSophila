# Contributing

See the [README](../README.md#quick-start) for prerequisites.

## Setup

```sh
uv tool install rust-just
uv tool install pre-commit
just setup
```

`just setup` installs the Python workspace and Git hooks for lint, formatting, lockfile
and commit-message checks. It configures Git to use `.github/commit-template.txt`.

Hook definitions live in [`.github/pre-commit-config.yaml`](../.github/pre-commit-config.yaml).
Run `just setup` to refresh installed hooks after moving the config file. To check all files:

```sh
pre-commit run --all-files --config .github/pre-commit-config.yaml
```

## Workflow

1. Open or pick an issue. Record design decisions in [docs/adr/](adr/).
2. Branch from `main`, for example `feat/fly-vision-r7-receptor` or `fix/honey-factory-crash`.
3. Make the change and test it in the [appropriate suite](../tests/README.md).
4. Run `just check`.
5. Open a PR. Use its title as the squash commit message when merging into `main`.

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
Let release-please make these updates; do not edit changelogs or versions by hand.

## Code style

- Python: ruff (lint and format) and pyright (strict). Run `just fmt` to fix.
- Java: follow the Fabric template conventions (tabs).
- Declare Python runtime dependencies in the package's `pyproject.toml`. Check those
  declarations during review: test environments include testing tools that can hide missing dependencies.
- Put package tests in `tests/<package>/` and fixtures in the suite that needs them.
  Avoid a shared `tests/conftest.py`, which would expose fixtures to all Python suites.
