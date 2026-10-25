# 0019: fly-vision v0 eye model

- **Status:** Accepted
- **Date:** 2026-10-25

## Context

fly-vision is a virtual *Drosophila* eye that turns honey-factory panoramas into
per-ommatidium photoreceptor activations. It feeds a later connectome-constrained model, so
it has to be a fixed sensor: no optic-lobe processing and no learned parameters, otherwise
what the network learns cannot be told apart from what the sensor did. Many variants of the
same frames will be generated (spectral variants now, resolution variants later), so every
output has to say how it was made.

## Decision

- **The input is honey-factory's level panorama.** fly-vision follows the current contract,
  `equirectangular-310x160-v1` (ADR 0010): level, longitude −155…+155°, elevation −70…+90°,
  image centre at elevation +10°. The eye frame is that level frame, so the head is assumed
  level and pitch is ignored. The contract numbers live in one function,
  `honey_factory_panorama()`. The older description of 1600 × 825 pixels, about 160° and a
  horizon at mid-height was outdated collaborator documentation.
- **Pixels become directions first.** The sampler consumes a `Projection` protocol (pixel
  to unit ray, pixel solid angle, cache key). The panorama is never resized, filtered or
  cropped as an image. Neighbourhoods are found in direction space, so the 310° panorama
  does not wrap and a 360° one would.
- **The lattice is generated.** A hexagonal lattice at 5° spacing, 877 ommatidia per eye,
  with pale, yellow, dorsal yellow and dorsal-rim subtypes from a recorded seed, stored as
  package data. A FlyGym or real lattice replaces it later without code changes.
- **Gaussian sparse sampler.** Each ommatidium weights pixels by a Gaussian of the angle to
  its axis (5° FWHM, cut at 3 sigma) times the pixel solid angle, normalised per
  ommatidium. The sparse matrix is cached by a key naming every input. Ommatidia with too
  little coverage are `valid = False`.
- **Spectral model.** Linear RGB is integrated against Govardovskii A1 templates at the
  Salcedo et al. 1999 peaks, using assumed Gaussian primaries, to give Rh1, Rh3, Rh4, Rh5
  and Rh6 catches, mapped to `R1_6`, `R7`, `R8` by subtype. R7 comes from the visible tails
  of the UV pigments. It is never a constant and never rescaled, and `uv_modelled` is
  always false.
- **Adaptation is off by default.** `frame_mean` exists for the day/night comparison, uses
  only the current frame and is experimental.
- **Labels are a pure transformation.** `FlyLabels.honey_coverage` is the honey mask through
  the same sampling matrix, plus id metadata. fly-vision does no detection or
  classification and has no visibility field. honey-factory's own `target_status` stays
  untouched in `meta["source"]`.
- **Frames are `.npz` with provenance.** Inputs and labels are separate files, written
  without pickle, with `meta` as JSON: package version, lattice hash, matrix key, spectral
  hash, config hash and the SHA-256 of the pixels.
- **Design rule for other image types.** The lattice is the fly and is fixed. An image is
  placed into the fly's visual field, meaning where its pixels point relative to the head.
  Ommatidia it does not cover are `valid = False`. The lattice is never adapted to an
  image. Perspective photos and cube maps follow this rule and are deferred.

## Consequences

- Other panorama layouts, or a 360° panorama, need only a new `Projection`.
- Every parameter is in the assumptions register, so changes can be compared later through
  the stored hashes.
- R7 is only as good as the assumed blue primary: a 10 nm shift changes it by a factor of
  about 2.5. It is not an informative colour feature until checked against real frames.
- The generated lattice mirrors the right eye, so in the −15…+15° binocular overlap many
  left and right ommatidia nearly coincide (median partner distance about 1.2°) and sample
  almost identical values. The overlap carries less independent information than its
  density suggests; a real lattice should replace it.
- Dark 8-bit frames have few distinct levels, which linearisation amplifies; frames record
  `input_levels` and `low_bit_depth`, and 16-bit arrays are accepted by the API.
- The head is assumed level and the panorama has no view behind or far below, so
  ommatidia pointing there are invalid.
- A calibration capture with honey at known directions is requested from honey-factory; the
  test skips until it exists.
