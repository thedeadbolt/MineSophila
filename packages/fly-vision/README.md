# fly-vision

Convert images and Minecraft scenes into *Drosophila* vision.

> Status: scaffold. The modules describe their planned responsibilities; nothing is implemented yet.

## Vision modes

| Mode | What it shows |
|---|---|
| `human` | Normal RGB vision (baseline) |
| `compound` | Ommatidial sampling only (~750–800 ommatidia per eye) |
| `photoreceptor` | Compound vision split into R1–R6, R7 and R8 channels |
| `ocelli` | The three ocelli added on top |

Any two modes can be compared, and the facade can yield all 6 pairwise combinations.
See [docs/architecture.md](../../docs/architecture.md) and the
[science notes](../../docs/science/fly-vision.md).

## Layout

```
src/fly_vision/
├── scene.py        # Scene: image + optional metadata
├── eye.py          # ommatidial lattice and sampling
├── vision.py       # FlyVision facade
├── modes/          # one module per vision mode
└── receptors/      # R1–R6, R7, R8, ocelli
tests/              # unit tests: may only import fly_vision
```

## Development

```sh
just test fly-vision   # runs this package's tests in an isolated environment
```
