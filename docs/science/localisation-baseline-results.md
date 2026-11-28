# First localisation baseline results

The Slice 5 baseline uses four independently generated panorama datasets, source-defined
scene-level splits, 1,536 training samples, 192 validation samples and 192 untouched test samples.
The selected model is a two-hidden-layer MLP with width 64, trained for 300 Adam steps from seed 0.

The clean run used Git revision `dd7d4ed77bb425b0b9c0dd63b83b4ec956ff441b`.

| Test measurement | Result |
|---|---:|
| Presence accuracy | 69.27% |
| Presence balanced accuracy | 57.81% |
| Presence precision | 86.33% |
| Presence recall | 75.00% |
| Presence specificity | 40.62% |
| Mean bearing error | 61.01 degrees |

Per-level test balanced accuracy was 55.00%, 61.25%, 51.25% and 63.75% for levels 1–4.
Corresponding mean bearing errors were 68.45, 79.45, 48.28 and 47.85 degrees. These results are
the honest baseline, not an acceptance claim for useful navigation. The model improves on the
linear control but still overfits and is the control for the connectome-constrained model.

Artifacts are stored outside Git under
`/mnt/fastvault/minesophila/derived/brain-localization-v1/`:

| Artifact | SHA-256 |
|---|---|
| `mlp-checkpoint-final.npz` | `bf84817a700ff83d95ba9fef3981788fd01dcbe2a22cd3f5d0f15c398a06d45a` |
| `mlp-report-final.json` | `3104a13b1a7c10d9d1e43600392dadfc8680daf4922bf545953676ff9c1a3390` |

The checkpoint is a versioned NumPy archive loaded with pickle disabled. It stores normalization
statistics and every numerical parameter. Its embedded canonical manifest records the Git
revision, dataset and layout hashes, architecture, training settings, seed and aggregate and
per-dataset metrics; the archive also stores the manifest SHA-256.

Per-receptor coverage MAE remains explicitly unavailable because this first model predicts only
presence and bearing. Coverage is retained in the cached targets for the first spatial or
connectome-constrained output head; it is not reported as zero or silently inferred.
