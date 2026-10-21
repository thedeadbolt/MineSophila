# The *Drosophila* visual system

Working notes for `fly-vision`. Values are commonly cited approximations. Check each one
against the primary source when you implement the parameter that depends on it, and
record the exact reference next to the code.

## Compound eye

- About 750–800 **ommatidia** per eye, arranged in a hexagonal lattice, covering a wide,
  almost panoramic field of view.
- Interommatidial angle is about 5°, so spatial resolution is very low compared with
  human vision.
- Each ommatidium has 8 photoreceptors, **R1–R8**.

## Photoreceptors

| Receptor | Rhodopsin | Peak sensitivity | Role |
|---|---|---|---|
| R1–R6 (outer) | Rh1 | ~480 nm (broadband, blue-green) | Achromatic, motion vision |
| R7 pale | Rh3 | ~345 nm (UV) | Colour vision |
| R8 pale | Rh5 | ~440 nm (blue) | Colour vision |
| R7 yellow | Rh4 | ~375 nm (UV) | Colour vision |
| R8 yellow | Rh6 | ~510 nm (green) | Colour vision |
| R7/R8 dorsal rim area | Rh3 | UV | Polarised-light detection (skylight) |

- About 30% of ommatidia are *pale* and about 70% are *yellow*, randomly distributed.
- R7 and R8 sit on top of each other in the same optical path, which makes them a natural
  pair for colour comparison.

## Ocelli

- Three simple eyes on top of the head, expressing **Rh2** (~420 nm).
- Low spatial resolution. They are thought to support flight stability and horizon or
  light-level detection.

## Implications for Minecraft input

- Minecraft renders RGB, so there is **no UV information** in plain frames. R7 channels
  must be estimated from RGB or derived from scene metadata such as per-block material
  properties.
- Polarisation depends on the sun's position and the sky, which honey-factory can export.

## Connectome

- **FlyWire**: whole-brain connectome of an adult female *Drosophila*, including the optic
  lobes. This is the reference for future lamina and medulla models.

## References

To be completed with full citations as parameters get implemented:

- Salcedo et al., 1999, *J. Neurosci.*: spectral sensitivities of the *Drosophila* rhodopsins.
- Dorkenwald et al., 2024, *Nature*: FlyWire whole-brain connectome.
- Matsliah et al., 2024, *Nature*: optic lobe cell types and wiring (FlyWire).
- [FlyWire](https://flywire.ai/)
