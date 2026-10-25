# 0016: Image-uniform viewpoint aim

- **Status:** Accepted
- **Date:** 2026-10-25

## Context

Every viewpoint so far aimed the camera at the target's block centre and then added
uniform jitter, ±15 degrees of yaw and ±10 of pitch by default. The M9 acceptance data
shows what that does to the images. In perspective images 90% of target centroids fall
within x 0.37–0.62 and y 0.41–0.58. In the level-5 panorama pilot they fall within
x 0.46–0.54. A detector trained on these images can learn that the honey is straight
ahead, and the active search planned for later milestones needs targets anywhere in view.

Wider jitter would spread the targets, but not evenly. Large yaw jitter in a perspective
image pushes the target out of the frame more often than it places it near an edge, and
in a panorama it piles the targets up wherever the jitter range ends. The same audit also
added a rule that a capture whose target touches the frame edge is rejected
(`frame_clipped`). Any new aim must keep that rejection rare, because each rejected
viewpoint costs a full capture.

## Decision

- **A new setting, `viewpoints.aim`.** `target_jitter`, the default, keeps the earlier
  behaviour. `image_uniform` draws the output pixel of the target block's centre
  uniformly over the image and solves the camera orientation to put it there. Jitter is 0
  and may not be set with it.
- **The solve belongs to the projection.** `CaptureProjection.orientationPlacing` returns
  the yaw and pitch for a direction and a pixel:
  - a perspective camera is solved in closed form;
  - a panorama is level, so only the column can be placed; its pitch looks along the
    target, and the row follows from the target's elevation.
- **The fit is checked before rendering.** A draw is re-sampled when no camera with pitch
  in [-90, 90] reaches the pixel, or when any corner of the target block projects within
  2 pixels of the image edge (`target_off_frame`). For a panorama, a block whose corners
  span half the image's width or more also counts as outside: it crosses the rear blind
  sector or stands over the camera. `frame_clipped` remains the authoritative check on
  the rendered mask.
- **Old hashes stay valid.** The resolved configuration records `aim` only when it is not
  the default, so every M1–M9 configuration keeps its hash and replays as before.
- **Scope.** The M9 acceptance runs keep the specification defaults. The phase-1 production
  datasets (M10) use `image_uniform`.

## Consequences

- Target positions in M10 datasets are spread over the image's width in both modes, and
  over its height in perspective images. A panorama's row distribution still comes from
  scene geometry, mostly near the horizon. Changing it would mean drawing camera heights
  or distances, which is left for later.
- Re-sampling happens before capture, so it costs generator time, not renders. The
  rejection cost was measured on a trial before the production runs (see the M10
  findings).
- Images where the target is cut by the frame edge are still excluded. Labelling them
  would need an in-frame fraction, planned with the frame streams of M11–M13.
