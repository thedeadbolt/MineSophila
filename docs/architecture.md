# Architecture

> Status: planned design. The package modules exist as documented stubs.

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

The input is a **`Scene`**: an RGB image plus optional metadata (depth, per-pixel block
ids, sky mask, sun position).

Plain images carry only the image. Scenes from honey-factory also carry metadata.
Minecraft renders RGB, but fly R7 photoreceptors are UV-sensitive. Polarisation and the
ocelli's horizon cues depend on the sky. The metadata lets those channels be derived from
what is in the scene. Without it, they are estimated from RGB.

| Component | Responsibility |
|---|---|
| `Eye` | Ommatidial lattice geometry and sampling of a Scene |
| `Receptor` subclasses | One per receptor type (R1–R6, R7/R8 pale and yellow, DRA, ocelli), each with a spectral sensitivity |
| `VisionMode` subclasses + registry | `human`, `compound`, `photoreceptor`, `ocelli` |
| `FlyVision` facade | `render(scene, mode)`, `compare(scene, a, b)`, `pairs(scene)` |

`pairs()` yields every combination of two registered modes (4 choose 2 = 6 today). A new
mode is a new `VisionMode` subclass and is picked up automatically.

## honey-factory

A Fabric mod. Planned responsibilities:

- **Scenario generation** (common code): place honey blocks in varied, partially hidden
  situations.
- **Capture** (client code): export colour frames plus depth, block-id and sky buffers,
  together with ground-truth annotations of where each honey block is.

The dataset format and its storage are open questions, tracked in
[#1](https://github.com/thedeadbolt/MineSophila/issues/1).

## Adapter (future)

The adapter turns honey-factory output into `Scene` objects for fly-vision, along with labels
for training and evaluation. It will most likely be a third Python workspace package.
