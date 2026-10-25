# 0017: Minimum visible target pixels

- **Status:** Accepted
- **Date:** 2026-10-25

## Context

Levels 3–5 accept a partly hidden target by its visibility ratio alone: level 5 needs only
10% of the unoccluded pixels to be visible. The ratio ignores size. The M9 pilot audit found
two samples of one scene, a honey block on a ridge about 40 blocks away and high above the
camera, where a strip of 50 or 152 pixels showed in a 2480 × 1280 panorama. Both were
correctly labelled `partially_visible` and neither is visible at normal size.

As positives, such samples teach a classifier to fire on faint texture or add loss it
cannot reduce. The fly-brain model will see frames downsampled to a few hundred facets per
eye, where these targets cover far less than one facet.

## Decision

- `visibility.min_visible_pixels` rejects a present capture showing fewer visible target
  pixels (`too_few_pixels`). It applies at every level and never to absent scenes.
- The default, 0, keeps the earlier behaviour. The resolved configuration records the
  setting only when it is set, so M1–M9 configuration hashes stay valid.
- The phase-1 production datasets (M10) use 200 pixels, at the default resolutions of
  1280 × 1280 and 2480 × 1280. In the M9 pilot it would have removed 1.1% of the present
  samples, all of them slivers.

## Consequences

- The production datasets lose their faintest positives. Small but plainly visible
  targets remain, and the low-pixel audit stratum still covers them.
- The minimum is in output pixels, so a dataset at another resolution should scale it.
- The M9 acceptance data keeps the specification defaults and is not regenerated.
