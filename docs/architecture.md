# Architecture

This document describes the overall design. `fly-vision` is still version metadata only;
`honey-factory` implements its roadmap through M5, a dataset runner for levels 1–3 in perspective and 310-degree panorama modes
(see its [roadmap](../packages/honey-factory/docs/roadmap.md)). Everything else here is
proposed.

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

### Eye-model sampling

Agreed 2026-10-22: expose an eye-model sampling-density setting in `fly-vision`,
independent of the Minecraft capture resolution. The collaborator will define its
parameterisation, defaults and supported biological or experimental presets.

Capture resolution controls source-image detail. Eye sampling controls the spatial
sampling layout used to produce receptor signals; it is also separate from temporal
sampling rate and the choice of receptor channels. Record the eye configuration and
model version with derived data so cached inputs remain traceable to their sources.

Higher-resolution images can feed the same fixed eye layout. With unchanged receptor
signal count, update rate and network, the extra work occurs in capture/preprocessing,
not the network's per-step computation. Increasing eye sampling density may change
input dimensions and cost; a connectome with fixed sensory neurons requires an
explicit mapping rather than assuming arbitrary sample counts fit its inputs.

Cache derived inputs for offline training where useful. Regenerate them when source
data or the eye configuration changes. This setting belongs to `fly-vision`; the mod
exports images and projection metadata without choosing the receptor layout.

## honey-factory

The Fabric mod:

- Generates scenes containing honey blocks with varying amounts of occlusion, using common
  code. Levels 1–3 (open platform, clutter, partial hiding) are implemented; levels 4–5 are
  planned.
- Captures RGB frames, visible-target masks and scene/camera metadata on the client.
  The initial dataset excludes depth images. Additional channels such as block IDs
  and sky buffers remain future options, subject to the collaborator's input needs.

See the [package specification](../packages/honey-factory/docs/spec.md) for agreed
capture requirements.

Define the dataset format and storage in [#1](https://github.com/thedeadbolt/MineSophila/issues/1).

## Adapter (future)

The proposed adapter would convert honey-factory output into `Scene` objects and labels
for training and evaluation. A third Python workspace package is one option.
