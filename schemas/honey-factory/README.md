# honey-factory data contract

The language-neutral contract between the honey-factory mod, which writes datasets, and
every outside reader. The [package specification](../../packages/honey-factory/docs/spec.md)
defines what the fields mean; this directory defines their exact form. Decisions are
recorded in [ADR 0006](../../docs/adr/0006-dataset-contract-and-algorithms.md).

## Versions

`<major>.<minor>/` holds one contract version as JSON Schema Draft 2020-12:

| File | Validates |
|---|---|
| `dataset.schema.json` | `dataset.json` |
| `scene.schema.json` | each line of `scenes.jsonl` |
| `sample.schema.json` | each line of `samples.jsonl` |
| `common.schema.json` | shared definitions referenced by the others |

Every record carries `schema_version`. Readers reject another major version and tolerate
unknown members from a compatible minor version, so the schemas never forbid extra
members. A breaking change needs a new major version directory. Before any dataset was
published, 1.0 was amended in place: the manifest's `generator.lighting` became
`generator.scene`, the full scene recipe ([ADR 0011](../../docs/adr/0011-scene-recipes.md)).

The schemas check structure and every rule expressible within one record. Other rules
belong to the validators:

- rules needing arithmetic across members: status versus counts, ratio, bounding-box
  placement, path versus ID and shard, camera basis, configuration hash, and one viewpoint
  draw per accepted sample;
- rules across records: references, inheritance, the `scene-slots-v1` class and split of
  every scene, the level's visibility rule, quotas and split leakage.

The mod's Java validator and the independent `honey-factory-tools validate-dataset` both
enforce them. The [shared fixtures](../../tests/integration/fixtures/honey-factory/) mark
each case as `schema` or `semantic` so both layers stay honest.

## Encoding rules

- JSON is UTF-8 without a byte-order mark. Numbers are finite. Integers that hashing or
  seeding depends on stay within 0 to 2^53 - 1 so every language represents them exactly.
- JSONL has one complete object per line and `\n` after every record, including the last.
- `null` appears only where the specification makes it meaningful; other inapplicable
  members are omitted.
- Paths are dataset-root-relative, `/`-separated, with no empty, `.` or `..` segment.

## Algorithms (version 1)

All hashes are lowercase hexadecimal SHA-256 of UTF-8 text.

### `jcs-sha256-v1`: resolved-configuration hash

Hash the [RFC 8785](https://www.rfc-editor.org/rfc/rfc8785) canonical form of the object
`{difficulty, target, generator, capture, splits}` taken from `dataset.json`, with
`splits.counts` removed. The result is `resolved_config_sha256`.

### `ids-v1`: identifiers, shards and paths

| Item | Rule | Example |
|---|---|---|
| Dataset | `<generator.dataset_name>-<first 12 hex of resolved_config_sha256>` | `honey-l1-smoke-3f9a1c0b7d2e` |
| Scene | `s` + scene index, 6 digits | `s000123` |
| Sample | scene ID + `-v` + viewpoint index, 2 digits | `s000123-v03` |
| Augmented sample | base sample ID + `-` + transform name | `s000123-v03-hflip` |
| Shard | first 2 hex of SHA-256(sample ID) | `4c` |
| RGB / mask | `rgb/<split>/<shard>/<sample>.png`, `masks/<split>/<shard>/<sample>.png` | |

Dataset names match `[a-z0-9][a-z0-9-]{0,47}`. Identical resolved configurations share a
dataset ID, so a writer must refuse an existing dataset directory.

### `seed-v1`: scene seeds

For purpose `layout`, `target` or `viewpoint`:

```text
seed = first 8 bytes, big-endian, of SHA-256("honey-factory/seed-v1/<purpose>/<dataset seed>/<scene index>/<attempt index>") >> 11
```

Numbers are decimal without padding. Seeds are independent per scene and attempt, so a
rejected attempt never changes another scene.

### `scene-slots-v1`: target class and split per scene

Every accepted-scene slot `0 … N-1` receives its class and split before generation:

1. Sort slot indices by `SHA-256("honey-factory/scene-slots-v1/class/<dataset seed>/<index>")`,
   ties by index. The first `target_present_scenes` slots contain a target.
2. For each class, apportion its slots to train, validation and test by the requested
   ratios with the largest-remainder method, using the ratios' shortest decimal values.
   Equal remainders go to train, then validation, then test.
3. Sort each class by `SHA-256(".../split/<dataset seed>/<index>")`, ties by index, and
   assign train, then validation, then test.

Level-5 grouping by source world is defined when M6 introduces natural patches.

### `layout-v1`: block-layout hash

`layout_sha256` hashes this text, every line ending in `\n`:

```text
honey-factory/layout-v1
bounds <min x> <min y> <min z> <max x> <max y> <max z>
<x> <y> <z> <block state>
```

Bounds are inclusive; for scenes they are the arena's reset volume, recorded as the scene
record's `arena.bounds`. Each non-air block inside them appears once, ordered by y, then z,
then x. Block states are `namespace:path` with properties sorted by name, for example
`minecraft:oak_stairs[facing=north,half=bottom]`.

### `equirectangular-310x160-v1` and `cube-faces-v1`: panoramic capture

A `PANORAMA_310` image of width `W` and height `H` is level. Its frame is the camera's
horizontal right and forward, from the yaw alone, and world up; the camera's pitch does
not tilt it. Output pixel `(px, py)`, with pixel centres at `+ 0.5`, looks along:

```text
longitude = -155 + 310 px / W     degrees, positive to the right
elevation =   90 - 160 py / H     degrees, positive up
direction = cos(elevation) (sin(longitude) right + cos(longitude) forward) + sin(elevation) up
```

The 50-degree sector behind the camera and everything below −70 degrees are not
captured. The left and right image edges border the blind sector and do not wrap.

The six square cube faces (`cube-faces-v1`) are 90-degree renders in this order, given as
Minecraft yaw relative to the camera and absolute pitch:

| Face | Yaw offset | Pitch | Forward | Right | Up |
|---|---:|---:|---|---|---|
| front | 0 | 0 | forward | right | up |
| right | 90 | 0 | right | −forward | up |
| back | 180 | 0 | −forward | −right | up |
| left | 270 | 0 | −right | forward | up |
| up | 0 | −90 | up | right | −forward |
| down | 0 | 90 | −up | right | forward |

Each output pixel samples the face whose forward axis is closest to its direction; ties go
to the earlier face. Its face coordinates `f = S/2 (1 + a)` horizontally and `S/2 (1 − b)`
vertically, where `a` and `b` are the direction's right and up components over its forward
component, are quantized to `q = round(256 f)`:

- **Masks (`nearest-v1`):** the face pixel `q >> 8`, clamped to the face, so labels stay 0
  or 255 and the visible mask stays inside the unoccluded mask.
- **RGB (`bilinear-face-clamped-v1`):** with `s = q − 128`, the face pixels `s >> 8` and
  the next one along each axis are weighted by `w = s & 255` out of 256. At the face border
  the weight drops to 0 rather than reading the neighbouring face. Each channel is
  `(Σ value · weight + 32768) >> 16`.

