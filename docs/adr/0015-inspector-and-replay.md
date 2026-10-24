# 0015: Local inspector, sidecar reviews and in-game replay

- **Status:** Accepted
- **Date:** 2026-10-24

## Context

M8 makes generated datasets practical to check before the acceptance runs. The
specification asks for a local, read-only visual inspector, reproducible random and
stratified review selection, review records kept outside the immutable dataset, and an
in-game replay that rebuilds a recorded scene exactly or says why it cannot. The roadmap
leaves the inspector's UI technology to M8, asking for the smallest local tool that
supports the required experience and no web framework for its own sake.

Building replay showed that the earlier capture readiness and mask rendering were not
exact for natural scenes. Rain and thunder faded in after a reset, rain fog eased for a
second or two after that, opaque particles wrote the depth that masks are tested
against, and the improved-transparency composite wrote the nearest translucent surface,
rain included, into that depth.

## Decision

- **Inspector.** `honey-factory-tools inspect` serves one dataset on 127.0.0.1 with the
  Python standard library's HTTP server and a static page; it adds no dependencies. Every
  request must name that host and port, and the API needs the session token the page is
  served with. Metadata is indexed from the JSONL files, and each image is read only when
  shown. Overlays and nearest-neighbour zoom are drawn on a canvas and never stored.
- **Integrity.** A sample's files are checked against their records before they are
  served. A sample that fails shows its errors instead of its images. A background
  validation, the same one `validate-dataset` runs, marks every affected sample and scene.
- **Selection** (`review-selection-v1`). Samples are ordered by
  `SHA-256("honey-factory/review-selection-v1/<seed>/<sample_id>")`. Stratified selection
  puts each sample in its first matching stratum and takes from the strata in turn. The
  strata, in order, are absent, fully occluded, other non-visible, edge or panorama seam,
  low pixel count, partially visible and fully visible. A `scene` order lists samples by
  scene ID and viewpoint for browsing. `select` prints the same selections from the
  command line.
- **Reviews.** Reviews are appended to
  `<review root>/<dataset id>/<manifest sha256 prefix>.jsonl` in the format
  `honey-factory-review-v1`. Each one records the outcome, reason code, note, inspector
  version, full manifest hash and, when the sample came from a selection, that selection
  and its hash. A review root inside the dataset is refused, and so is a review after the
  manifest changes.
- **Replay** (`/honeyfactory_replay`). Loading a scene reads the dataset, re-resolves its
  recorded configuration with this build and requires the recorded configuration hash,
  then derives the slot and seeds again. It regenerates the plan and checks the template
  or source patch, target, aim block, layout parameters, every recorded viewpoint pose
  and, on a platform, the layout hash. The scene is then built through the environment
  runtime's reset, so only a verified dataset-owned world accepts it, and the world's
  layout hash is compared with the record. A template, setting or algorithm this build
  resolves differently is reported as unavailable. A replay is exact when the layout hash
  matches and every fresh mask equals the stored one. Fresh captures are written outside
  the dataset, and render settings that differ from the recorded setup are listed.
- **Level 5 replays in its own world.** Minecraft decorates chunks in an order-dependent
  way: ore, stone-variant and flower placements overlap across chunks. So a fresh world
  with the same seed produces different terrain in the same patch. Natural scenes
  therefore replay in the dataset world that generated them, or a copy of it. Elsewhere
  replay reports a source patch mismatch, or refuses a world with a different seed before
  doing any work.
- **Capture fixes.**
  - A reset sets rain and thunder at full strength at once and waits until the client
    shows them, its rain fog has stopped easing and its queued light updates are applied.
  - Captures leave particles out.
  - A frame counts as ready only once the section occlusion graph has been rebuilt for
    the camera's position. Minecraft rebuilds it in the background after the camera moves.
  - Each pose of a batched capture waits for one client tick after a frame drawn there,
    because that tick is when the camera samples the nearby biomes' sky and fog colours.
  - The visible mask is tested against the scene depth copied as the translucent stages
    begin, which is what ADR 0005 intended: opaque and cutout terrain plus solid features.

## Consequences

- Replays of platform scenes and natural scenes, in perspective and panorama, reproduce
  their layout hashes and stored masks pixel for pixel on the reference setup. RGB can
  still differ where Minecraft animates by game tick, such as water, falling rain and
  snow, and sky dithering. The specification does not require RGB to match.
- Level-5 datasets generated before this change may show a weather fade or particles
  that their masks do not reflect. They should be regenerated before acceptance runs.
- Captures render without particles, and frames wait for weather, fog, light, the
  occlusion graph and a biome sample at each pose. In the M6 throughput profile this
  roughly doubles the time per image, to 0.21–0.55 s; see the M8 findings.
- The inspector stays usable without network access or extra packages. A richer UI
  can replace the page later without changing the API, selection or review formats.
- Reviews are evidence about one exact manifest. A regenerated dataset needs new reviews.
