# Level-1 static input comparison

Recorded 2026-11-20. This experiment asks whether compound-eye preprocessing removes useful
information for static level-1 honey localisation. Both models use the same 1,000 scenes, scene-level
splits, 6,400 training views, 800 validation views and 800 test views.

| Model | Allocated parameters | Validation balanced accuracy | Test balanced accuracy | Validation bearing error | Test bearing error |
|---|---:|---:|---:|---:|---:|
| Fly spatial coverage model | 6,562 | 97.03% | 97.50% | 4.60° | 3.51° |
| Raw RGB direction-aware CNN | 6,629 | 100.00% | 100.00% | 3.85° | 3.95° |

The fly model consumes 1,726 three-channel ommatidial samples and a fixed angular-neighbour graph.
The CNN consumes a deterministic 20× area reduction of the 2480×1280 panorama (124×64 RGB) plus
fixed per-pixel panorama ray directions. Both use three shared spatial layers, 3,000 Adam/AdamW
updates, batch size 64, learning rate 0.001 and validation-selected presence thresholds.

The comparison supports two conclusions. First, `fly-vision` retains enough information for strong
static level-1 localisation: its test presence score is within 2.5 points of raw RGB and its bearing
error is slightly lower. Second, raw RGB establishes a marginally higher identification ceiling on
this easy dataset. The earlier 62% four-level result was therefore cross-difficulty interference,
not evidence that level-1 information was destroyed by compound-eye sampling.

The models are close in capacity but not identical in supervision. The fly model also predicts and
is trained against dense per-ommatidium honey coverage; the CNN is trained against presence and
bearing only. The fly checkpoint allocates a 65-parameter optional presence head that the selected
coverage-pooling configuration does not activate. Runtime and multi-seed variance remain to be
added before making a stronger architecture claim.

Artifacts are stored outside Git under
`/mnt/fastvault/minesophila/derived/brain-localization-v3/`:

- `spatial-coverage-level1-v1.report.json` and `.checkpoint`
- `image-cnn-level1-v2.report.json` and `.checkpoint`

The selected experiment configs are
`packages/fly-brain/configs/spatial-coverage-v1.json` and
`packages/fly-brain/configs/image-cnn-level1-v2.json`.
