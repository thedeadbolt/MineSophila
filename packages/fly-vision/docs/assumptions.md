# Assumptions register

Every chosen or estimated parameter gets a row here in the task that introduces it.
Types: **measured** (with citation), **estimated**, **chosen**.

| Parameter | Value | Type | Source | Code location |
|---|---|---|---|---|
| Ommatidia per eye | from FlyGym or ~750 | measured / estimated | FlyGym eye model | lattice (planned) |
| Interommatidial angle | ~5° | measured, approximate | literature | lattice (planned) |
| Acceptance angle | ~5° FWHM | measured, approximate | literature | `config.py` (`acceptance_fwhm_deg`) |
| Pale / yellow ratio | 30 / 70, random | measured | literature | lattice (planned) |
| Panorama field of view | 310° × 160°, elevation −70…+90°, level frame (honey-factory `equirectangular-310x160-v1`, ADR 0010) | chosen by honey-factory; check against literature and the eye map's own coverage | honey-factory contract | `projection.py` (planned) |
| Rhodopsin curves | Salcedo et al. 1999 or Govardovskii template | measured / model | Salcedo et al. 1999 | `spectral.py` (planned) |
| RGB primaries | sRGB standard | chosen | sRGB | `spectral.py` (planned) |
| R7 (UV) | Rh3/Rh4 catch from RGB primaries via visible-light curve tails; true UV missing (`uv_modelled = False`) | chosen (RGB has no UV); curves measured | Salcedo et al. 1999 | `spectral.py` (planned) |
| `eps` in receptor ratios | small constant at computation time, not stored | chosen (numerical) | n/a | at point of computation |
| R1–R6 pooled as one channel per direction | neural superposition approximation | chosen | n/a | `spectral.py` (planned) |
| Male vs female eye | FlyGym is female; MaleCNS is male | known mismatch, phase 2 | n/a | n/a |
