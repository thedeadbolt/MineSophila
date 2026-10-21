# The *Drosophila* visual system

Research notes for `fly-vision`. Treat the values below as provisional approximations.
Check each parameter against a primary source before using it in code, and cite that source
beside the implementation.

## Compound eye

- About 750–800 **ommatidia** per eye in a hexagonal lattice, covering a wide field of view.
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

- About 30% of ommatidia are *pale* and about 70% are *yellow*, with a random distribution.
- R7 and R8 share an optical path and provide paired signals for colour comparison.

## Ocelli

- Three simple eyes on top of the head, expressing **Rh2** (~420 nm).
- Low spatial resolution. Proposed roles include flight stability and detection of the
  horizon or light levels.

## Implications for Minecraft input

- Minecraft renders RGB. Any UV response inferred from frames or block metadata will
  depend on synthetic modelling assumptions; the model and its validation are still open.
- Sky and sun metadata are planned inputs to a polarisation model. Honey-factory does not
  export them yet. Define the model before treating its output as a biological signal.

## Connectome

- **FlyWire**: whole-brain connectome of an adult female *Drosophila*, including the optic
  lobes. Use it as a reference for future lamina and medulla models.

## References

Add full citations and link parameters to their sources before implementation:

- Salcedo et al., 1999, *J. Neurosci.*: spectral sensitivities of the *Drosophila* rhodopsins.
- Dorkenwald et al., 2024, *Nature*: FlyWire whole-brain connectome.
- Matsliah et al., 2024, *Nature*: optic lobe cell types and wiring (FlyWire).
- [FlyWire](https://flywire.ai/)
