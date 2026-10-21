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
    pre-commit install --install-hooks
    git config commit.template .gitmessage

# --- Python ---------------------------------------------------------------

# Lint and check formatting
lint:
    uv run ruff check .
    uv run ruff format --check .

# Auto-fix lint issues and format
fmt:
    uv run ruff check --fix .
    uv run ruff format .

# Static type checking
typecheck:
    uv run pyright

# Unit tests for one package, in an isolated env with only its declared deps
test package *args:
    uv run --isolated --package {{package}} --directory packages/{{package}} pytest {{args}}

# Unit tests for every Python package, each isolated
test-packages:
    for p in {{py_packages}}; do just test "$p"; done

# Cross-package integration tests
test-integration *args:
    uv run pytest tests/integration {{args}}

# End-to-end tests (excluding ones that need Minecraft)
test-e2e *args:
    uv run pytest tests/e2e {{args}}

# Tests that need a running Minecraft instance
test-minecraft *args:
    uv run pytest tests -m minecraft {{args}}

# Every Python test layer
test-all: test-packages test-integration

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

# --- Everything -----------------------------------------------------------

# What CI runs
check: lint typecheck test-all mod-build
