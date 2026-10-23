# honey-factory-tools

Minecraft-free Python tools for [honey-factory](../honey-factory/) datasets. They read
the language-neutral contract in [`schemas/honey-factory/`](../../schemas/honey-factory/)
and never load the mod, the Minecraft JAR or a running game.

The package currently validates records against the JSON Schemas and implements the
contract's canonical JSON, seed, ID and scene-slot algorithms independently of the
Java mod, so shared fixtures can catch a bug in either implementation. The full offline
validator (M4) and dataset inspector (M8) are planned in the
[roadmap](../honey-factory/docs/roadmap.md).

## Usage

```sh
uv run honey-factory-tools validate --kind sample samples.jsonl
uv run honey-factory-tools validate --kind dataset dataset.json
```

`--kind` is `dataset`, `scene` or `sample`. `.jsonl` files are validated line by line.
Schemas are found with `--schemas DIR`, the `HONEY_FACTORY_SCHEMAS` environment variable,
or by searching upwards from the current directory for `schemas/honey-factory`.

## Development

```sh
just test honey-factory-tools
```

Tests live in [`tests/honey-factory-tools/`](../../tests/honey-factory-tools/); the
cross-language contract fixtures are in
[`tests/integration/fixtures/honey-factory/`](../../tests/integration/fixtures/honey-factory/).
