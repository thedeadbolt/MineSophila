# 0020: Image placement in the fly's visual field

- **Status:** Accepted
- **Date:** 2026-10-25

## Context

ADR 0019 fixed the rule that the lattice is the fly and an image is placed into its visual
field. Only honey-factory's equirectangular panorama could be placed. Ordinary photos need
a projection too. A mirrored, flipped or mis-weighted projection would silently put the
image on the wrong ommatidia, so orientation and solid angle have to be exact.

## Decision

- **D1. The lattice never changes for an input.** The same 1,754 ommatidia, in the same
  order, for every image.
- **D2. An image is placed, never stretched.** Where it sits and how big it appears are
  what the photographer knew (camera field of view, or object size and distance), not
  something fly-vision infers. Its pixels become rays at their true directions, so more
  pixels over the same field change nothing. A wider field covers more ommatidia because it
  shows more of the world.
- **D3. The world outside the image is no data.** Uncovered ommatidia are `valid = False`,
  never filled, padded or extrapolated.
- **D4. Pinhole model only.** No lens distortion and no roll. The image's vertical axis
  stays in the vertical plane through its centre, so the image is level, as honey-factory's
  camera is in perspective mode with pitch applied.
- **D5. Placement is opt-in; a missing placement is assumed and marked, not refused.**
  (The user revised this on 2026-10-25; the first draft required a placement.)
  `--fov-h` and the three object flags (`--object-width-px`, `--object-size-m`,
  `--distance-m`) are each optional. With neither, a perspective image uses
  `DEFAULT_FOV_H_DEG = 60`, the CLI prints a one-line warning, and `meta["placement"]`
  records `assumed: true`. The CLI errors only on contradictions: only some of the three
  object flags, or `--fov-h` and the object geometry differing by more than
  `FOV_AGREEMENT_DEG = 1`. When both agree, `--fov-h` sets the projection and the object
  geometry is recorded.
- **D6. Out of scope:** cube maps (honey-factory's cube faces are debug-only), fisheye
  lenses, stretching an image to fill the eye, and synthetic surrounds or backgrounds.

Pinhole maths, implemented by `PerspectiveSpec`. With `F = (width / 2) / tan(fov_h / 2)`
and pixel offsets `cx = x + 0.5 − width/2`, `cy = y + 0.5 − height/2` (`cy` grows
downward), the ray is `normalise(F·forward + cx·right − cy·up)`, where
`forward = to_unit(az, el)`, `right = to_unit(az + 90, 0)` and `up = to_unit(az, el + 90)`.
The pixel solid angle is `cos³θ / F²` with `cos θ = F / sqrt(F² + cx² + cy²)`. The vertical
field of view follows from square pixels: `2·atan((height/2) / F)`. The summed solid angle
equals the rectangular-pyramid solid angle `4·asin(sin(a)·sin(b))`, which a test checks.

## Consequences

- A perspective image covers only the ommatidia inside its field. A narrow photo (50°) is
  seen by a few hundred of the 1,754 ommatidia, and one placed behind the fly by none.
- The field of view is a user-supplied measurement. A wrong value scales the image on the
  eye without any warning, so it is recorded in `meta["projection"]` and in the cache key.
- Fields of view of 180° or more are rejected; wide-angle and fisheye lenses need another
  projection (D6).
