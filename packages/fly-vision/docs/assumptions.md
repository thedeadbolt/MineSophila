# Assumptions register

Every chosen or estimated parameter gets a row here in the task that introduces it.
Types: **measured** (with citation), **estimated**, **chosen**.

| Parameter | Value | Type | Source | Code location |
|---|---|---|---|---|
| Ommatidia per eye | 877 (35 rows), result of the ranges and spacing below; FlyGym count not yet checked | estimated | FlyGym eye model (to check) | `lattice.py` (`generate_hex_lattice`) |
| Interommatidial angle | 5° (hexagonal lattice, `spacing_deg`) | measured, approximate | literature | `lattice.py` (`generate_hex_lattice`) |
| Acceptance angle | ~5° FWHM | measured, approximate | literature | `config.py` (`acceptance_fwhm_deg`) |
| Pale / yellow ratio | 30 / 70, random per ommatidium (seeded) | measured | Wernet & Desplan 2004 | `lattice.py` (`_PALE_FRACTION`) |
| Panorama field of view | 310° × 160°, elevation −70…+90°, level frame (honey-factory `equirectangular-310x160-v1`, ADR 0010) | chosen by honey-factory; check against literature and the eye map's own coverage | honey-factory contract | `projection.py` (planned) |
| Rhodopsin curves | Salcedo et al. 1999 or Govardovskii template | measured / model | Salcedo et al. 1999 | `spectral.py` (planned) |
| RGB primaries | sRGB standard | chosen | sRGB | `spectral.py` (planned) |
| R7 (UV) | Rh3/Rh4 catch from RGB primaries via visible-light curve tails; true UV missing (`uv_modelled = False`) | chosen (RGB has no UV); curves measured | Salcedo et al. 1999 | `spectral.py` (planned) |
| `eps` in receptor ratios | small constant at computation time, not stored | chosen (numerical) | n/a | at point of computation |
| R1–R6 pooled as one channel per direction | neural superposition approximation | chosen | n/a | `spectral.py` (planned) |
| Male vs female eye | FlyGym is female; MaleCNS is male | known mismatch, phase 2 | n/a | n/a |
| Eye azimuth ranges | right eye −15…155°, left eye mirrored (−155…15°) | chosen, to match the 310° panorama; check against FlyGym / literature | n/a | `lattice.py` (`_AZ_RIGHT`) |
| Eye elevation range | ±75° (below −70° falls outside the panorama, so those ommatidia are invalid) | chosen | n/a | `lattice.py` (`_EL_RANGE`) |
| DRA (dorsal rim area) | top lattice row of each eye | chosen | n/a | `lattice.py` (`generate_hex_lattice`) |
| Dorsal yellow third | non-DRA ommatidia with elevation ≥ el_max − (el_max − el_min)/3 are `pale` or `dorsal_yellow` | chosen | n/a | `lattice.py` (`generate_hex_lattice`) |
| Eye frame | level panorama frame of honey-factory (head assumed level, A1) | chosen | honey-factory contract | `lattice.py`, `projection.py` (`to_unit`) |
| Acceptance FWHM | 5° (sigma = FWHM / 2.3548) | measured, approximate | literature | `config.py` (`acceptance_fwhm_deg`), `sampler.py` |
| Acceptance cutoff | 3 sigma; weights beyond are dropped | chosen | n/a | `config.py` (`cutoff_sigmas`), `sampler.py` |
| Valid ommatidium threshold | coverage fraction (summed weight / 2 pi sigma²) ≥ 0.5; invalid rows are zero | chosen | n/a | `config.py` (`valid_coverage_min`), `sampler.py` |
| Edge-truncated cones | ommatidia with 0.5 ≤ coverage < ~0.95 (near the ±155° edges and the −70° bottom) are valid but renormalised over the visible part of their cone, so they are biased toward what is in view | chosen | n/a | `sampler.py` (`valid_coverage_min`) |
