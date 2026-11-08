# 0021: Spectral remap of the fly's pigments into the visible

- **Status:** Proposed
- **Date:** 2026-11-08

## Context

ADR 0019 models the fly's receptors from linear RGB through the physical rhodopsin curves
(Rh1 478, Rh3 345, Rh4 375, Rh5 437, Rh6 508 nm). RGB carries no UV, so R7 (Rh3/Rh4) only
sees the far tails of its curves over the blue primary. In v0, R7 is redundant. Its catch
row lies almost in the span of the R1_6 and R8 rows: the relative residual is 5.8e-5 for
pale and 0.0116 for yellow. On the example frames, R1_6 and R8 predict R7 with
R² = 1.0000. R7 of white is 8.1e-6 for pale and 1.56e-3 for yellow (assumptions
register). Adding a real UV band to honey-factory is out of scope for now.

## Decision

- **D1. Remap now, UV later.** A pigment remap stands in for UV input. The spectral model
  is built so a real UV band can be added later without redesign.
- **D2. Remapped by default, everywhere.** `FlyVisionConfig().spectral`,
  `fly-vision convert` and `build_spectral_model()` with no pigment map all give the
  remap. The physical model has to be requested: `spectral="physical"`,
  `--spectral physical` or `pigments=SPECTRAL_MODES["physical"]`.
- **D3. Affine warp of the whole pigment axis.** Each template is evaluated at the source
  wavelength `300 + (λ − 420) · 300 / 230`. This maps 300–600 nm onto 420–650 nm, moving
  each curve and narrowing it by 230/300. The order of the pigments and the overlap
  between every pair of curves are kept. The mapped peaks are Rh3 454.5, Rh4 477.5,
  Rh5 525.0, Rh1 556.5 and Rh6 579.5 nm. Moving only Rh3 and Rh4 is rejected: pale R7
  would land on top of pale R8 (Rh5, 437 nm).
- **D4. `uv_modelled` stays `False` in both modes.** The remap puts no UV into the input.
  The mode is recorded in `meta["spectral"]`.
- **D5. Input bands are general, UV scaling is deferred.** `PrimaryConfig` takes any number
  of named bands, each scaled by its luminance weight. A band with weight ≤ 0, which a
  real UV band would have, raises `ValueError` until its radiometric scaling is decided.
- **D6. Remap and UV don't combine.** Once a UV band exists, the remap should be off:
  physical pigments with real UV is the faithful model. No code enforces this yet,
  because no UV band exists.
- **D7. Measured reason.** With the default primaries, the relative residual of R7
  against span(R1_6, R8) goes from 5.8e-5 (pale) and 0.0116 (yellow) physical to 0.629
  and 0.959 remapped. The condition number of the catch matrix drops from 280 to 5.1.
  R7 of white rises to 0.500 (pale) and 0.598 (yellow).
- **D8. Clean break for old frames.** No frames from the physical default need to stay
  comparable. Default activations, `spectral_hash` and `config_hash` change once. Frames
  without a `meta["spectral"]` key are pre-remap physical frames.
- **D9. The CLI warns only for physical.** `--spectral physical` prints one stderr note
  that R7 is almost redundant. The default run prints nothing extra.

## Consequences

- By default, frames describe a fly that doesn't exist. Results from them must be
  reported as remapped-fly results.
- R7 stays almost purely blue-driven: the catch rows over `[R, G, B]` are
  Rh3 ≈ `[0, 0.0002, 0.500]` and Rh4 ≈ `[0, 0.015, 0.583]`. It becomes informative only
  because R1_6 and R8 move towards green and red (Rh1 goes from `[0.001, 0.233, 0.766]`
  to `[0.100, 0.728, 0.172]`). R7 encodes "blueness", not UV-ness.
- The fly becomes clearly sensitive to red, where real flies are barely sensitive: the
  Rh6 red catch goes from 0.023 to 0.312.
- Honey is easier to tell from blue. The `metamer_report` distance is 3.82 remapped
  against 2.10 physical, about 1.8×, so the honey task is easier for the default fly than
  for a real one.
- Default frames change once (D8). Physical frames keep their activations, but their
  `spectral_hash` and `config_hash` change because both now cover the spectral mode.
- Open question: how to scale a UV band radiometrically, since it has no luminance
  weight.
- This amends, but does not supersede, ADR 0019. It changes the default pigment axis. The
  physical mode keeps 0019's model and its rule that R7 is never rescaled. The remap
  moves the pigments rather than rescaling R7.
