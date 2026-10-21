# Roadmap

## Phase 0: Seed ✅

Monorepo, tooling, CI, docs, and package scaffolds.

## Phase 1: fly-vision from images

- `Scene` for plain images.
- `Eye`: Drosophila ommatidial lattice and sampling.
- Receptors: R1–R6, R7/R8 (pale and yellow), ocelli, with spectral sensitivities from the literature.
- Modes: `human`, `compound`, `photoreceptor`, `ocelli`, plus the `FlyVision` facade with pairwise combinations.

## Phase 2: honey-factory datasets

- Scenario generation: honey blocks hidden in varied situations.
- Frame capture with metadata (depth, block ids, sky mask).
- Ground-truth annotations.
- Dataset storage and versioning ([#1](https://github.com/thedeadbolt/MineSophila/issues/1)).

## Phase 3: Adapter

- Convert honey-factory output into `Scene` objects and labels.
- Use the metadata to derive UV, polarisation and ocelli channels.
- First end-to-end tests.

## Phase 4: Find and mine

- Detect honey blocks from fly vision.
- Act in-game: aim and mine. How the agent plays is still open.

## Later

- Model downstream neural layers (lamina, medulla) from the FlyWire connectome.
- More targets than honey blocks.
- A frontend (web or app, to be decided).
