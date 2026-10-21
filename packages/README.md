# Packages

Build and release the two packages with their own configurations and versions.

| Package | Toolchain | Planned purpose | Current state |
|---|---|---|---|
| [fly-vision](fly-vision/) | Python / uv | Model fruit-fly vision from images and scene metadata | Package version metadata only |
| [honey-factory](honey-factory/) | Java / Gradle / Fabric | Generate and capture labelled Minecraft scenes | Mod entrypoints and an identifier helper |

The root `pyproject.toml` and `uv.lock` manage the Python workspace. The mod uses its own
Gradle wrapper and build files under `honey-factory/`.

## Development

Run these commands from the repository root:

```sh
just test fly-vision   # Python package tests
just mod-test         # Java package tests
just mod-build        # build and test the mod
just check            # Python checks and tests, then the mod build
```

Put production code inside each package and tests in [`tests/<package>/`](../tests/).
Follow the [test guide](../tests/README.md) for configuration and fixture scope.

Keep future API plans in the [architecture](../docs/architecture.md) and add source
modules when implementing them. See the [contributing guide](../docs/contributing.md)
for the development workflow.
