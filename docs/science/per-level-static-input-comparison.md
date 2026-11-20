# Per-level static input comparison

Recorded 2026-11-20. This experiment measures the single-difficulty ceiling of compound-eye and
raw-image inputs for static honey localisation. Each level uses scene-level train, validation and
test splits. Validation results select configurations and presence thresholds; the test split is
reported only after selection.

The working targets are 80% balanced accuracy for levels 1 and 2, 75% for level 3 and 70% for
level 4. These are project progress targets, not claims that every architecture must meet them.

## Selected results

### Level-1 results

1,000 scenes; 6,400 training, 800 validation and 800 test views.

| Model | Parameters | Validation balanced accuracy | Test balanced accuracy | Validation bearing error | Test bearing error |
|---|---:|---:|---:|---:|---:|
| Fly spatial model | 6,562 | 97.03% | 97.50% | 4.60° | 3.51° |
| Raw RGB CNN | 6,629 | 100.00% | 100.00% | 3.85° | 3.95° |

Both models exceed the 80% target.

### Level-2 results

1,500 scenes; 9,600 training, 1,200 validation and 1,200 test views.

| Model | Parameters | Validation balanced accuracy | Test balanced accuracy | Validation bearing error | Test bearing error |
|---|---:|---:|---:|---:|---:|
| Fly spatial model | 14,450 | 79.43% | 76.82% | 25.47° | 24.96° |
| Raw RGB CNN | 6,629 | 83.65% | 80.78% | 23.67° | 24.23° |

The CNN exceeds the 80% target. The spatial model finishes within 0.57 percentage points on
validation but does not meet it on the untouched test split.

### Level-3 results

3,000 scenes; 19,200 training, 2,400 validation and 2,400 test views.

| Model | Parameters | Validation balanced accuracy | Test balanced accuracy | Validation bearing error | Test bearing error |
|---|---:|---:|---:|---:|---:|
| Fly spatial model | 14,450 | 69.43% | 67.58% | 33.67° | 33.71° |
| Raw RGB CNN | 15,232 | 68.23% | 69.30% | 30.82° | 30.01° |

Neither model meets the 75% target. Their complementary validation and test ordering does not
support a claim that either input representation is clearly better at this level.

### Level-4 results

5,000 scenes; 32,000 training, 4,000 validation and 4,000 test views.

| Model | Parameters | Validation balanced accuracy | Test balanced accuracy | Validation bearing error | Test bearing error |
|---|---:|---:|---:|---:|---:|
| Fly spatial model | 14,450 | 69.75% | 69.94% | 15.61° | 14.82° |
| Raw RGB CNN | 15,232 | 74.31% | 74.45% | 11.97° | 11.44° |

The CNN exceeds the 70% target. The spatial model finishes within 0.25 percentage points on
validation and 0.06 points on test, which is operationally close but remains a strict miss.

## Method and fairness

The fly model consumes 1,726 three-channel ommatidial samples and a fixed angular-neighbour graph.
The CNN consumes a deterministic 20× area reduction of each 2480×1280 panorama (124×64 RGB) plus
fixed per-pixel panorama ray directions. Both model families use three shared spatial layers,
batch size 64, learning rate 0.001 and validation-selected presence thresholds.

Level 1 uses the matched approximately 6.6k-parameter models for 3,000 updates. Later levels may
use level-specific capacity and training duration. When enlarged, the fly model has 14,450
allocated parameters and the CNN has 15,232 trainable parameters; both run for 6,000 updates.

The models are close in capacity within each tuned comparison but not identical in supervision.
The fly model also predicts and learns dense per-ommatidium honey coverage; the CNN learns only
presence and bearing. The fly parameter count includes an optional presence head that coverage
pooling does not activate. Runtime and multi-seed variance remain future work, so these results
compare the selected runs rather than estimating statistical significance.

## Tuning trace

| Level | Model | Candidate | Parameters | Updates | Validation balanced accuracy | Test balanced accuracy |
|---:|---|---|---:|---:|---:|---:|
| 2 | Fly spatial | baseline | 6,562 | 3,000 | 71.56% | 67.24% |
| 2 | Fly spatial | longer training | 6,562 | 6,000 | 76.41% | 74.27% |
| 2 | Fly spatial | wider selected model | 14,450 | 6,000 | 79.43% | 76.82% |
| 3 | Fly spatial | baseline | 6,562 | 3,000 | 57.86% | 56.04% |
| 3 | Fly spatial | wider selected model | 14,450 | 6,000 | 69.43% | 67.58% |
| 3 | Raw RGB CNN | baseline | 6,629 | 3,000 | 60.08% | 61.64% |
| 3 | Raw RGB CNN | wider model | 15,232 | 3,000 | 65.34% | 64.48% |
| 3 | Raw RGB CNN | longer selected model | 15,232 | 6,000 | 68.23% | 69.30% |
| 4 | Fly spatial | baseline | 6,562 | 3,000 | 60.91% | 64.52% |
| 4 | Fly spatial | wider, longer selected model | 14,450 | 6,000 | 69.75% | 69.94% |
| 4 | Raw RGB CNN | baseline | 6,629 | 3,000 | 62.38% | 61.58% |
| 4 | Raw RGB CNN | wider, longer selected model | 15,232 | 6,000 | 74.31% | 74.45% |

Reports and checkpoints are stored outside Git under
`/mnt/fastvault/minesophila/derived/brain-localization-v3/`. Versioned experiment configs live in
`packages/fly-brain/configs/`. The selected configurations are:

- Level 1: `spatial-coverage-v1.json` and `image-cnn-level1-v2.json`
- Level 2: `spatial-coverage-level2-v3.json` and `image-cnn-level1-v2.json`
- Level 3: `spatial-coverage-level3-v2.json` and `image-cnn-level3-v3.json`
- Level 4: `spatial-coverage-level4-v2.json` and `image-cnn-level4-v2.json`
