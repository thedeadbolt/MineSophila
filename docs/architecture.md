# Architecture

This document describes the overall design. `fly-vision` implements a v0 virtual eye
(panorama in, per-ommatidium activations out; see below), and
`honey-factory` implements its roadmap through M5, a dataset runner for levels 1–3 in perspective and 310-degree panorama modes
(see its [roadmap](../packages/honey-factory/docs/roadmap.md)). The adapter, other
projections and further vision modes are proposed.

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

### Implemented in v0

`FlyEye` (`eye.py`) turns a panorama and an optional honey mask into a `FlyFrame`
(activations `(N, 3)` in the order `R1_6`, `R7`, `R8`, validity, provenance) and `FlyLabels`
(`honey_coverage` only). It is built from:

| Component | Responsibility |
|---|---|
| `Projection` protocol, `EquirectSpec` (`projection.py`) | pixel to unit ray and pixel solid angle; `honey_factory_panorama` is honey-factory's `equirectangular-310x160-v1` |
| `Lattice` (`lattice.py`) | generated ommatidium directions, eye and subtype (`pale`, `yellow`, `dorsal_yellow`, `dra`), stored as package data |
| Sampling matrix (`sampler.py`) | sparse Gaussian weights in direction space, cached by key; the same matrix serves colours and mask |
| Spectral model (`spectral.py`) | linear RGB to Rh1/Rh3/Rh4/Rh5/Rh6 catches and then to channels; R7 from the visible curve tails |
| `adapt` (`adaptation.py`) | off by default; `frame_mean` is experimental |
| `frame.py`, `diagnostics.py`, `plot.py`, `cli.py` | `.npz` files, coverage and metamer checks, hex plots, `fly-vision convert` |

The decisions are in [ADR 0016](adr/0016-fly-vision-v0-eye-model.md). The input contract for
honey-factory is in the [interface note](../packages/fly-vision/docs/interface.md).

**Design rule for other image types.** The lattice is the fly and is fixed. An adapter for
another image type (a perspective photo, a cube map) places the image in the fly's visual
field, meaning where its pixels point relative to the head, by implementing `Projection`.
Ommatidia the image does not cover are `valid = False`. The lattice is never adapted to the
image. These adapters are deferred.

### Proposed

The vision-mode interface below remains a proposal; add modules when implementing it.

| Proposed component | Responsibility |
|---|---|
| `Scene` | RGB image plus optional metadata (depth, per-pixel block ids, sky mask, sun position); R7 and polarisation need sky information, which RGB frames and block labels do not measure |
| `Receptor` subclasses | One per receptor type (R1-R6, R7/R8 pale and yellow, DRA, ocelli), each with a spectral sensitivity |
| `VisionMode` subclasses + registry | `human`, `compound`, `photoreceptor`, `ocelli` |
| `FlyVision` facade | `render(scene, mode)`, `compare(scene, a, b)`, `pairs(scene)` |

The four proposed modes are `human` (RGB baseline), `compound` (ommatidial sampling),
`photoreceptor` (sampling with spectral channels), and `ocelli` (the three simple eyes).
`pairs()` would yield the six combinations of those modes. The class hierarchy and registry
remain open choices. Revisit them while implementing the first modes.

Distinguish measured inputs from estimates and missing values; the assumptions register
records which is which.

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

- Runs a lock-step control loop (M11): `EnvironmentRuntime.controlReset` and
  `controlStep` advance one frozen tick per motor command and return the frame, the body's
  senses and the true state. A loopback server (`ControlServer`) exposes the loop to
  external programs in the protocol of
  [control-protocol.md](../packages/honey-factory/docs/control-protocol.md);
  `honey_factory_tools.control` is the Python client.

See the [package specification](../packages/honey-factory/docs/spec.md) for agreed
capture requirements.

Define the dataset format and storage in [#1](https://github.com/thedeadbolt/MineSophila/issues/1).

## Adapter (future)

The proposed adapter would convert honey-factory output into `Scene` objects and labels
for training and evaluation. A third Python workspace package is one option.
