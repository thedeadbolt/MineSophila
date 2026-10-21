# Architecture

This document describes a proposed design. The implementation consists of `fly-vision`
version metadata and `honey-factory` entrypoints with an identifier helper.

## Overview

```
                ┌─────────────────┐     scenes + labels     ┌──────────┐
 Minecraft ───▶ │  honey-factory  │ ──────────────────────▶ │ adapter  │ (future)
                │  (Fabric mod)   │   image + metadata      └────┬─────┘
                └─────────────────┘                              │ Scene
                                                                 ▼
  plain images ─────────────────────── Scene ─────────────▶ ┌────────────┐
                                                            │ fly-vision │ ──▶ fly-eye views
                                                            └────────────┘
```

## fly-vision

The proposed `Scene` input contains an RGB image and optional metadata (depth, per-pixel block
ids, sky mask, sun position).

For a plain image, supply RGB alone. The proposed honey-factory capture would add scene
metadata. R7 photoreceptors respond to UV; modelling polarisation and the ocelli's horizon
cues requires sky information. Define and validate the assumptions behind synthetic channels,
and distinguish measured inputs from estimates and missing values. RGB frames and block
labels do not measure UV or polarisation.

| Proposed component | Responsibility |
|---|---|
| `Eye` | Ommatidial lattice, viewing directions, interommatidial and acceptance angles, and sampling of a Scene |
| `Receptor` subclasses | One per receptor type (R1–R6, R7/R8 pale and yellow, DRA, ocelli), each with a spectral sensitivity |
| `VisionMode` subclasses + registry | `human`, `compound`, `photoreceptor`, `ocelli` |
| `FlyVision` facade | `render(scene, mode)`, `compare(scene, a, b)`, `pairs(scene)` |

The four proposed modes are `human` (RGB baseline), `compound` (ommatidial sampling),
`photoreceptor` (sampling with spectral channels), and `ocelli` (the three simple eyes).
`pairs()` would yield the six combinations of those modes. The class hierarchy and registry
remain open choices. Revisit them while implementing the first modes.

Add source modules with working code and tests. Keep future design notes here.

## honey-factory

The proposed Fabric mod would:

- Generate scenes containing honey blocks with varying amounts of occlusion, using common code.
- Capture colour frames, depth, block IDs and sky buffers on the client, with ground-truth
  annotations for honey-block locations.

Define the dataset format and storage in [#1](https://github.com/thedeadbolt/MineSophila/issues/1).

## Adapter (future)

The proposed adapter would convert honey-factory output into `Scene` objects and labels
for training and evaluation. A third Python workspace package is one option.
