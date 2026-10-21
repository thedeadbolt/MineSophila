# fly-vision

Python package for modelling *Drosophila* vision from images and Minecraft scenes.

The package exposes version metadata. See the [architecture](../../docs/architecture.md)
for the proposed `Scene`, `Eye`, receptors, vision modes and `FlyVision` interface, and
the [science notes](../../docs/science/fly-vision.md) for assumptions and references.

Add code in `src/fly_vision/` with tests for its behaviour. Keep API proposals in the
architecture document.

## Development

Run from the repository root:

```sh
just test fly-vision
```

Tests live in [`tests/fly-vision/`](../../tests/fly-vision/) and use the root pytest
configuration. See the [test guide](../../tests/README.md) for fixture and dependency rules.
