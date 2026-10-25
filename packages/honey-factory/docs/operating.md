# Operating honey-factory: generate, validate, inspect, replay

This guide takes one dataset from configuration to a reviewed, reproducible result. It
assumes the repository is checked out and `just setup` has run. Commands in `text` blocks
are typed in the Minecraft chat; `sh` blocks run from the repository root. If `just` is
not installed, run the recipe's command from the [justfile](../../../justfile).

## 1. Build and start the client

```sh
just mod-build     # compile, run the JUnit tests and package the mod
just mod-client    # start a development Minecraft client with the mod
```

In the client, open *Options → Video Settings* and turn on *Improved Transparency*. The
target masks depend on it ([ADR 0005](../../../docs/adr/0005-shared-depth-target-annotation.md)),
and generation and replay refuse to start without it.

## 2. Create and claim a dataset world

Generation and replay only change worlds created for them. Create a new world with
**World Type: Honey Factory Dataset**, then claim it by typing its exact name:

```text
/honeyfactory world claim <world name>
/honeyfactory world status
```

Keep this world for level-5 datasets. Their terrain comes from its own Overworld, and
Minecraft decorates chunks differently in every new world, even with the same seed. So a
level-5 scene can only be replayed exactly in the world that generated it, or a copy of
it.

## 3. Generate

Write a configuration into the game directory (`packages/honey-factory/run/` for the
development client). Every setting and its default is listed in `ConfigResolver`;
`output.root` must be absolute.

```json
{
  "dataset": {"name": "honey-l3-pilot", "seed": 3, "difficulty": 3},
  "quotas": {"accepted_scenes": 100},
  "output": {"root": "/mnt/fastvault/minesophila/datasets", "free_space_reserve_bytes": 10737418240}
}
```

```text
/honeyfactory_dataset generate honey-l3-pilot.json
/honeyfactory_dataset status
/honeyfactory_dataset cancel          stop at the next safe point; the dataset is marked failed
```

The dataset is written to `<output root>/<dataset id>/`; the ID ends with the first 12
hex digits of the resolved configuration's hash. It is marked `complete` only when the
mod's own validation passes.

## 4. Validate outside the game

```sh
uv run honey-factory-tools validate-dataset /mnt/fastvault/minesophila/datasets/<dataset id>
```

This is an independent implementation of the contract. It checks the manifest and
indexes against the schemas, the cross-record rules and every image. Exit status 0 means
valid; every error is printed on its own line.

## 5. Inspect and review

```sh
uv run honey-factory-tools inspect /mnt/fastvault/minesophila/datasets/<dataset id> \
    --reviews /mnt/fastvault/minesophila/reviews --reviewer <your name>
```

A browser page opens on `127.0.0.1`; nothing leaves the machine. The page lets you:

- **Find** a scene or sample by typing its ID in the header (`s000123` or `s000123-v04`).
- **Filter** by split, target status, presence, visibility ratio, pixel count, distance,
  scene and viewpoint.
- **Order** samples by scene, or select them in `random` or `stratified` order with a seed and a count. The same settings
  always give the same samples. Stratified order takes absent, occluded, edge and seam,
  low-pixel, partial and full cases in turn.
- **View** RGB, mask and an overlay with adjustable opacity. Zoom uses nearest-neighbour
  scaling, so mask pixels are never blurred or shifted. *Box and centroid* draws the
  recorded mask statistics.
- **Browse the scene**: every viewpoint of the scene is shown in a grid, with
  previous/next buttons for samples and scenes.
- **Review**: choose pass, fail or uncertain, a reason and a note, and save the review for
  the sample or for its whole scene.

Samples whose files are missing, damaged or inconsistent show their errors instead of
images. A banner reports the background validation and any dataset-level errors.

Reviews are appended to `<review root>/<dataset id>/<manifest hash prefix>.jsonl`, never
inside the dataset. Summarize them with:

```sh
uv run honey-factory-tools reviews /mnt/fastvault/minesophila/datasets/<dataset id> \
    --reviews /mnt/fastvault/minesophila/reviews
```

`select` prints the same selections for scripts, such as an audit list:

```sh
uv run honey-factory-tools select --mode stratified --seed 2026 --count 200 --json <dataset directory>
```

## 6. Replay a scene in the game

Every sample page shows a command like this one. Copy it and type it in the game, in a
claimed dataset world (the generation world for level 5):

```text
/honeyfactory_replay load s000123-v04 /mnt/fastvault/minesophila/datasets/<dataset id>
/honeyfactory_replay viewpoint next          or previous, or a viewpoint index
/honeyfactory_replay capture                 compare this viewpoint with the stored images
/honeyfactory_replay verify                  compare every viewpoint of the scene
```

Loading rebuilds the scene from the recorded configuration and seeds and checks its
layout hash. It refuses a personal world, a level-5 scene in a world with another seed,
and any template, setting or algorithm version this build would resolve differently.
`verify` reports whether the replay is exact: the layout matches and every fresh mask
equals the stored mask. It also lists RGB differences and any render settings that
differ from the dataset's. Fresh images are written to `honey-factory-replay/` in the
game directory.

## 7. Acceptance runs

The specification's acceptance gates run from the repository as client game tests. They
write to `/mnt/fastvault/minesophila/acceptance/<label>/`, which must exist and be
writable; `-Dhoneyfactory.acceptanceRoot` changes the root. Nothing is overwritten.

```sh
just mod-build && just mod-gametest          # gate 1: every automated fixture
cd packages/honey-factory
./gradlew runClientGameTest -Dhoneyfactory.gametests=M9Matrix -Dhoneyfactory.acceptanceLabel=<label>
./gradlew runClientGameTest -Dhoneyfactory.gametests=M9Pilot -Dhoneyfactory.acceptanceLabel=<label>
```

- **The matrix** generates 10 scenes × 8 viewpoints for levels 1–5 in both modes, 800
  samples.
- **The pilot** generates one level-5 panorama dataset of 1,000 scenes and 8,000 samples.
- Each run writes its datasets, `metrics/<dataset>.json` (timings, percentiles, memory) and
  a copy of its dataset world under `worlds/`. Open that copy in the development client to
  replay level-5 scenes.
- Neither test runs in an ordinary `mod-gametest` run; each runs only when named.

Then audit 200 pilot samples. In the inspector, set **Mode** to *stratified*, enter the
seed and a count of 200, review every sample, and build the report:

```sh
uv run honey-factory-tools inspect <label>/pilot/<dataset id> --reviews <review root>
uv run honey-factory-tools acceptance-report --reviews <review root> --audit-seed <seed> \
    --output <label>/report <acceptance root>/<label>
```

The report:

- validates every dataset again;
- lists quotas, splits, hashes, PNG sizes, disk use, durations, percentiles, rejections
  and peak memory;
- checks that the audit selection covers every split, status, ratio range, low pixel
  counts, seams and several source patches.

It exits 0 only when every dataset is complete and valid and every audited sample passed.

## 8. Clean up

`/honeyfactory world release` restores the game rules, time, weather and player state
that scenes changed. The arena stays claimed for the next run.
