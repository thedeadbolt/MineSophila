set shell := ["bash", "-euo", "pipefail", "-c"]

# Python workspace members (uv packages)
py_packages := "fly-vision"
mod_dir := "packages/honey-factory"

# List recipes
default:
    @just --list

# One-time setup: install the workspace, git hooks and commit template
setup:
    uv sync --all-packages
    pre-commit install --install-hooks --config .github/pre-commit-config.yaml
    git config commit.template .github/commit-template.txt

# --- Python ---------------------------------------------------------------

# Lint and check formatting
lint:
    uv run ruff check .
    uv run ruff format --check .

# Fix lint issues and format files
fmt:
    uv run ruff check --fix .
    uv run ruff format .

# Check Python types
typecheck:
    uv run pyright

# Test one Python package in a fresh environment with its development dependencies
test package *args:
    uv run --locked --isolated --package {{package}} pytest tests/{{package}} {{args}}

# Test Python packages in separate fresh environments
test-packages:
    for p in {{py_packages}}; do just test "$p"; done

# Run workspace and cross-package checks
test-integration *args:
    uv run --locked pytest tests/integration {{args}}

# Run end-to-end tests that do not need Minecraft
test-e2e *args:
    uv run --locked pytest tests/e2e {{args}}

# Run tests against a running Minecraft instance
test-minecraft *args:
    uv run --locked pytest tests -m minecraft {{args}}

# Run Python package suites, then integration and e2e tests
test-all: test-packages
    uv run --locked pytest tests/integration tests/e2e

# --- Mod (Gradle) ---------------------------------------------------------

# Compile the mod and run its JUnit tests
mod-build:
    cd {{mod_dir}} && ./gradlew build

# Run the mod's JUnit tests
mod-test:
    cd {{mod_dir}} && ./gradlew test

# Launch a dev Minecraft client with the mod loaded
mod-client:
    cd {{mod_dir}} && ./gradlew runClient

# --- Combined checks ------------------------------------------------------

# Run Python checks and tests, then build and test the mod
check: lint typecheck test-all mod-build
