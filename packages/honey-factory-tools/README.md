# honey-factory-tools

Minecraft-free Python tools for [honey-factory](../honey-factory/) datasets. They read
the language-neutral contract in [`schemas/honey-factory/`](../../schemas/honey-factory/)
and never load the mod, the Minecraft JAR or a running game.

The package validates single records against the JSON Schemas and whole dataset
directories against the full contract. It implements the contract's canonical JSON,
seed, ID and scene-slot algorithms independently of the Java mod, so shared fixtures
can catch a bug in either implementation. The dataset inspector (M8) is planned in the
[roadmap](../honey-factory/docs/roadmap.md).

## Usage

```sh
uv run honey-factory-tools validate-dataset /mnt/fastvault/minesophila/datasets/<dataset id>
uv run honey-factory-tools validate --kind sample samples.jsonl
uv run honey-factory-tools validate --kind dataset dataset.json
```

`validate-dataset` checks a complete or failed dataset directory:

- the manifest and every index line, against the schemas and the single-record rules;
- the manifest's index digests;
- the cross-record rules: references, inheritance, seeds, the `scene-slots-v1` class and
  split of every scene (no split leakage), the level's visibility rule and, when
  complete, the quotas and split counts;
- every image: present, matching its byte count and SHA-256, an 8-bit PNG of the
  recorded size, and masks that are binary and match their recorded statistics;
- that no unreferenced or leftover temporary file is present.

A `generating` manifest means the run is unfinished or was interrupted, and is reported
unless `--allow-generating` is given. Every error is printed on its own line, followed by
a summary. The exit status is 1 when there are errors.

`validate` checks individual records against the schemas only. `--kind` is `dataset`,
`scene` or `sample`, and `.jsonl` files are validated line by line.
Schemas are found with `--schemas DIR`, the `HONEY_FACTORY_SCHEMAS` environment variable,
or by searching upwards from the current directory for `schemas/honey-factory`.

## Development

```sh
just test honey-factory-tools
```

Tests live in [`tests/honey-factory-tools/`](../../tests/honey-factory-tools/); the
cross-language contract fixtures are in
[`tests/integration/fixtures/honey-factory/`](../../tests/integration/fixtures/honey-factory/).
