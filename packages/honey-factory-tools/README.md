# honey-factory-tools

Minecraft-free Python tools for [honey-factory](../honey-factory/) datasets. They read
the language-neutral contract in [`schemas/honey-factory/`](../../schemas/honey-factory/)
and never load the mod, the Minecraft JAR or a running game.

The package validates single records against the JSON Schemas and whole dataset
directories against the full contract. It implements the contract's canonical JSON,
seed, ID and scene-slot algorithms independently of the Java mod, so shared fixtures
can catch a bug in either implementation. It also inspects datasets in a local browser
page, selects samples for review reproducibly and keeps review records beside, never
inside, a dataset.

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

## Inspecting and reviewing

```sh
uv run honey-factory-tools inspect <dataset directory> --reviews <review root> [--reviewer NAME]
uv run honey-factory-tools select --mode stratified --seed 7 --count 200 [filters] <dataset directory>
uv run honey-factory-tools reviews --reviews <review root> <dataset directory>
```

`inspect` serves the dataset read-only on `127.0.0.1` (`--port`, default any free port) and
opens a browser unless `--no-browser` is given. The page offers:

- lookup of a scene or sample by ID, and filters on split, target status and presence,
  visibility ratio, pixel count, distance, scene and viewpoint;
- seeded `random` or `stratified` selection, or every sample in `scene` order (by scene ID, then viewpoint);
- RGB, mask and overlay views with nearest-neighbour zoom, and the sample's and scene's
  metadata;
- every viewpoint of a scene in one grid;
- review recording, and the `/honeyfactory_replay` command that rebuilds the scene in
  Minecraft.

Each sample's files are checked before they are shown; a sample that fails shows its
errors instead of images. The whole dataset is validated in the background. Without
`--reviews`, the page is view-only.

Selections (`review-selection-v1`) order samples by
`SHA-256("honey-factory/review-selection-v1/<seed>/<sample_id>")`. Stratified selection
puts each sample in its first stratum and takes from the strata in turn. The strata, in
order, are absent, fully occluded, not visible, edge or panorama seam, low pixel count
(`--low-pixels`, default 64), partially visible and fully visible. Filters on `select`
mirror the page's: `--split`, `--status`, `--present`, `--scene-id`, `--sample-id`,
`--viewpoint`, and `--min-`/`--max-` with `ratio`, `pixels` or `distance`.

### Workspaces

The rail on the left of the page switches between three workspaces ([ADR 0029](../../docs/adr/0029-viewer-workspaces.md)).
All three follow the same sample, or the same clip and frame, so the clip's transport, scrub
bar and timeline drive every view. `#eye`, `#brain`, `#brain-circuit` or `#brain-runs` in
the URL opens a workspace, and for a clip dataset `?clip=s000123-c02` opens that clip.

- **Scene**: the images and clips described above. A clip also shows a top-down map of the
  fly's path, its 310° view and the honey, and **Record video** saves one play-through of
  the player as WebM.
- **Eye**: the frame sampled through fly-vision's default eye (310° panoramas only). It
  shows the camera frame; the image decoded from the receptors, where each pixel takes its
  nearest ommatidium; both eyes' ommatidia by viewing direction; and a globe you can turn.
  Each view can show **Colour**, R1_6, R7, R8, a false-colour mix or honey coverage.
  Colour is computed from the receptors: for each ommatidium, the linear R, G, B light that
  would produce its R1_6, R7 and R8 catches under its subtype's rhodopsins (a regularised
  inverse of fly-vision's spectral model, which is linear while adaptation is off). Point at an
  ommatidium to read its receptors and rhodopsins. The side panel plots the rhodopsin
  curves on both pigment axes. `--no-eye` turns the workspace off.
- **Brain**: fly-brain output, read from files. `--runs DIR` lists the `*.report.json`
  files in a sortable table, and opening one shows its loss curve, scores by split and clip
  type, and connectome components. **Circuit** draws the selected run, or the most complete
  one, from the eye through the optic lobe and the compass pathway to the readout. It uses
  the report's components, neuron counts, input and readout components, and its
  component-to-component synapse counts once fly-brain writes them. `--traces DIR` adds
  **Clip playback** for clips with a `fly-brain-clip-trace-v1` file: activity by cell type,
  the compass ring with its bump against the fly's heading, and the readout against the
  truth, next to the fly's own view of that frame. In Brain the clip list shows only clips
  that have a trace. During playback the circuit's boxes also fill with each stage's
  activity.
- **Network** (Brain): the connectome itself, from `--connectome FILE`. One node per cell
  type, or every central-complex neuron with the compass neurons on a ring at their
  bridge angle. Edges are synapse counts, coloured by the presynaptic transmitter. The
  graph is laid out in the order signals flow. Point at a node to see its strongest inputs
  and outputs. During playback, nodes glow with their cell type's activity. The same graph
  gives the circuit real synapse counts between components.

Build the graph once from the MaleCNS feather files (about 40 s). It uses fly-brain's
component rules:

```sh
uv run honey-factory-tools connectome-graph /mnt/fastvault/minesophila/connectomes/male-cns-v1.0 \
    --out connectome-graph.json
```

While anything loads, the masthead shows what it is waiting for, how many requests are
done and the elapsed time.

```sh
uv run honey-factory-tools inspect <clip dataset> \
    --runs /mnt/fastvault/minesophila/derived/brain-localization-v3 --traces <trace directory> \
    --connectome connectome-graph.json
```

Reviews are appended to `<review root>/<dataset id>/<manifest hash prefix>.jsonl`, one
JSON object per line in the format `honey-factory-review-v1`:

```json
{"format": "honey-factory-review-v1", "dataset_id": "...", "manifest_sha256": "...",
 "subject": {"kind": "sample", "id": "s000001-v00", "scene_id": "s000001"},
 "outcome": "fail", "reason": "mask_misaligned", "note": "...",
 "reviewed_at_utc": "...", "inspector_version": "...", "reviewer": "...", "selection": {...}}
```

The outcome is `pass`, `fail` or `uncertain`. A `fail` or `uncertain` review needs a
reason other than `ok`. The other reasons are `mask_misaligned`, `mask_missing_pixels`,
`mask_extra_pixels`, `wrong_target_status`, `target_ambiguous`, `render_artifact`,
`seam_artifact`, `scene_invalid`, `metadata_mismatch` and `other`. The latest review of
a subject counts; earlier ones remain as history. A review root inside the dataset is
refused, and so is any review once `dataset.json` changes.

## Development

```sh
just test honey-factory-tools
```

Tests live in [`tests/honey-factory-tools/`](../../tests/honey-factory-tools/); the
cross-language contract fixtures are in
[`tests/integration/fixtures/honey-factory/`](../../tests/integration/fixtures/honey-factory/).
