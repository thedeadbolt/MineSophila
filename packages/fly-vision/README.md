# fly-vision

Python package for modelling *Drosophila* vision from images and Minecraft scenes.

A virtual fly eye: it samples a panorama and a honey mask through a fixed ommatidial lattice
and writes per-ommatidium photoreceptor activations (R1-6, R7, R8) and honey labels. It does
no detection, no optic-lobe processing and has no learned parameters. See the
[architecture](../../docs/architecture.md), [ADR 0019](../../docs/adr/0019-fly-vision-v0-eye-model.md),
the [honey-factory interface](docs/interface.md), the [science notes](../../docs/science/fly-vision.md)
and the [assumptions register](docs/assumptions.md) (every parameter and its provenance).

## Usage

Command line, for a honey-factory panorama (`equirectangular-310x160-v1`):

```sh
uv run fly-vision convert --image p.png --mask m.png \
    --scene-id scene-0001 --frame-id frame-0001 --out out/ --plot
```

This writes `frame-0001.fly.npz`, `frame-0001.labels.npz` and, with `--plot`,
`frame-0001.png`. The mask is optional (`--panorama` is a hidden alias of `--image`).
`--projection` is `panorama` (default; an image whose aspect ratio is not 310/160 within 1% is
rejected), `equirect` (needs `--fov-h` and `--fov-v`, optional `--center-az`/`--center-el`)
or `perspective`. `--lattice` selects a lattice file (validated on load) and `--cache` a
directory for sampling matrices.

Perspective photos (placement is optional; `--fov-v` is not accepted):

```sh
# equirectangular, 360 x 180
uv run fly-vision convert --image e.png --projection equirect --fov-h 360 --fov-v 180 \
    --scene-id s --frame-id f1 --out out/
# a camera with a known horizontal field of view
uv run fly-vision convert --image photo.png --projection perspective --fov-h 65 \
    --scene-id s --frame-id f2 --out out/
# a phone photo placed by an object: a 0.3 m object 1 m away spans 120 px
uv run fly-vision convert --image photo.png --projection perspective \
    --object-width-px 120 --object-size-m 0.3 --distance-m 1.0 \
    --scene-id s --frame-id f3 --out out/
# no placement: assumes 60 degrees and marks the frame
uv run fly-vision convert --image photo.png --projection perspective \
    --scene-id s --frame-id f4 --out out/
# fly-vision: warning: no placement given; assuming a 60 deg horizontal field of view ...
```

Giving only some of the object flags, or `--fov-h` and an object that disagree by more than 1°,
is an error.

Python:

```python
from fly_vision.eye import FlyEye, load_mask, load_panorama
from fly_vision.frame import SourceRef, save_frame
from fly_vision.lattice import default_lattice
from fly_vision.projection import honey_factory_panorama

pano = load_panorama("p.png")
eye = FlyEye(default_lattice(), honey_factory_panorama(pano.shape[1], pano.shape[0]))
frame, labels = eye.sample(pano, load_mask("m.png"), SourceRef("scene-0001", "frame-0001"))
save_frame("out", frame, labels)
```

## Development

Run from the repository root:

```sh
just test fly-vision
```

Tests live in [`tests/fly-vision/`](../../tests/fly-vision/) and use the root pytest
configuration. See the [test guide](../../tests/README.md) for fixture and dependency rules.
