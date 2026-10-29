# 0020: Motion clips and the first teacher

- **Status:** Accepted
- **Date:** 2026-10-26

## Context

The fly's vision has to work while its body moves: the scene slides when it turns, objects
swing past when it walks, and the honey leaves and re-enters view. Still images cannot teach
that. M12-2 generates short motion clips by driving the player through M11's control loop,
with labels on every frame, and builds the first teacher, the scripted controller M13 and
M14 will imitate. The design is in
`docs/superpowers/specs/2026-10-25-m12-2-motion-clips-design.md`; the findings, with the
pilot's measurements, are in `packages/honey-factory/docs/m12-2-motion-clips.md`.

## Decision

- **A clip dataset kind.** A configuration with a `clips` section makes a clip dataset:
  manifest `kind: "clips"`, `generator.clips` in the configuration hash, `clips.jsonl` beside
  `scenes.jsonl`, and per clip `clips/<split>/<shard>/<clip id>/` holding `rgb.mkv`,
  `visible.mkv` and `frames.jsonl`. Contract 1.0 gains clip and frame records additively;
  still datasets are unchanged.
- **Frames come only from the controlled body.** Each clip resets its scene at a start
  drawn as a still viewpoint is, then runs `controlStep` tick by tick, capturing 3 frames per
  tick (60 Hz) at the production panorama (2480 × 1280). Every frame is labelled from its own
  capture and pose: mask statistics, status, bearing, whether the honey touches the edge;
  each tick also records the senses, both commands, the teacher's mode and belief, drift and
  search coverage.
- **Video by a codec strategy.** `ClipCodec` has one implementation per compression, chosen
  by `clips.codec`: lossy H.264 at CRF 18 (the default since the second pilot), lossless
  H.264, or FFV1. Masks always use a lossless codec, so labels stay exact. The second pilot
  stored lossless RGB at 25–52 MB per clip on levels 1–4 and 248 MB on level 5's natural
  terrain; re-encoding its clips at CRF 18 kept them above 46 dB PSNR (no visible change) and
  24–47 times smaller. One codec on every level, so compression is never a cue to the level. ffmpeg runs as a subprocess; its version is
  recorded in the manifest's `software`.
- **A realizable teacher.** `Perception` is the only code that reads the honey's true
  position: it passes it on only when the observed frame (the previous tick's last) shows
  at least `teacher.min_visible_pixels` (50) visible pixels. The teacher keeps a memory of
  the last sighting and a map of every cell that has been in view (a ray from the eye to the
  cell's centre, or for a block to a face's centre). Its modes:
  - **Track**: steer at the honey when the way is clear of seen blocks, otherwise along an A*
    path over seen cells (unseen space planned as open), keeping a margin from walls;
  - **Re-acquire** after losing it: walk to the last sighting while a way there is known,
    then search the frontiers within 6 blocks of it for `reacquire_ticks` (60). With no
    known way it gives up 60 ticks after losing the honey. The walk back was timed from the
    loss until the fourth pilot, where an occlusion script used up most of it;
  - **Search**: walk to the frontier that best trades path length against unseen cells
    nearby, and on arrival turn towards the most unseen space.
- **Clip types and drift.** Approach, detour, search and no-honey clips execute the
  teacher's command with DART-style correlated drift (half-sine bumps on about 10% of ticks);
  scan and pass-by are scripted; occlusion walks to a waypoint whose way hides the honey for
  at least 6 ticks, then follows the teacher. Detour starts hide the honey and need a walk at
  least 1.3 times the straight distance. Search starts hide the honey with a walkable way to
  it, so the teacher searches, finds it and walks to it: the only clips in which a policy that
  sees only pixels learns to look for honey it has not seen. Detour and search draws skip the
  level's sight rule, which keeps only views that show the honey (the second pilot had no
  detour on any level until then). A type without a qualifying start falls back to approach
  and records the requested type. Level 1 has nothing to hide behind, so its occlusion, detour
  and search share goes to approach and pass-by. Approach, detour and search clips run up to 200
  ticks and occlusion up to 160, and all of them end 8 ticks after reaching the honey
  (occlusion had 32 until the second pilot showed its walk to cover outlasting the clip, then
  100 until the third showed the teacher still walking back when it ended; approach and detour
  had 100 until the fourth showed five of their timeouts within 60 ticks of the honey, then
  160 until the fifth showed four still closing in, one going round a building to its door). The
  default mix gives scripted clips 15% (scan and pass-by 7.5% each), approach 30%, detour and
  search 15% each, occlusion 10%, and no-honey the 20% of target-absent scenes.
- **Who drove.** Every tick records its `driver`: `teacher`, `teacher_drift` or `script`;
  the teacher's command is recorded on every tick and is the policy's training target, never
  a script's executed command. Scripted clips stay for the vision they show (the honey
  sliding through the frame) and for the teacher's labels in states it would not choose;
  M13 decides by ablation whether they stay in the final volume.
- **Edges.** The pilot showed bodies walking off the platform. The teacher plans drops onto
  seen floor, or onto floor not seen yet as it plans level steps, steers straight only over
  seen floor under its whole width, watches honey it has no known way to instead of walking
  at it, and passes every command through an edge brake that stops at a ledge, or, where the
  floor ahead is unseen, walks to within 0.3 blocks of it, stops and looks down at it (from a
  wall top, its foot is only seen from the edge). The arena's edge counts as a ledge. The executed command passes an edge guard
  that uses the true layout (scripts may know it): drift and scripts take the body close to
  ledges, never over, and the tick is recorded as `guarded`. A share of drift episodes
  (`hazard_fraction`, 0.3) pushes towards the nearest ledge or wall within 4 blocks
  (SAFE-GIL), so the data holds the teacher's recoveries near hazards rather than avoiding
  them. Pass-by clips take a line that stays on the floor.
- **Falls and misses are measured.** A clip whose body still falls (one drop more than 3
  blocks below where it last stood; walking downhill is not a fall) or leaves the arena is
  redrawn and counted in the manifest (`counts.clip_redraws`, by reason and clip type); the
  gate needs 0. A seeking clip that misses the honey records why, from the true layout:
  `timeout` (it gained at least 2 cells of the true way from where the teacher took over, or
  a script drove every tick), `unreachable`, or `teacher_failure`, which the gate also needs
  at 0.
- **Validation of realizability.** Both validators check every tick: a `seen` belief needs
  the observed frame to show the threshold and names the scene's honey; `memory` names the
  last sighting; nothing is seen without honey. They also check every frame's mask against
  its labels, the videos' frame counts, commands within the motor limits, drift, mode,
  driver and guard totals, and that scripts drive only scan, pass-by and an occlusion
  clip's lead-in. During generation each accepted clip's masks are checked once on a
  background thread while generation goes on; frame counts come from container packets,
  so the final validation decodes nothing and never runs on the render thread (the first
  pilot's slices froze the game window for about nine minutes).
- **Replay.** A clip replays from its scene seeds and executed commands; poses, labels and
  masks must match exactly, and RGB too unless the codec is lossy or the scene animates.
- **Inspector and reader.** The inspector plays clips like GIFs with a mask overlay, box,
  scrub bar and a timeline of status, bearing and teacher mode; `honey_factory_tools.clips`
  reads clips as frames with labels.
- **Captures write the global settings uniform.** The terrain shaders place sections from
  the camera block and offset in that uniform, which the game writes only in a full frame
  render. Frames captured between ticks rendered the level without it, so their terrain came
  from the tick's end while their masks followed the real camera. Captures now write it.

## Rejected

- **FFV1 as the default.** Lossless and intra-only: 324 KB per level-1 frame against 195 KB
  for lossless H.264, which predicts frames from earlier ones. It stays available.
- **PNG frames.** About 310 KB per level-1 panorama frame and 97 files per clip.
- **A teacher that knows where hidden honey is.** It would teach a student behaviour it can
  never reproduce (the imitation gap).
- **Full-circle scans on arrival.** With a 310° panorama they add little; turning towards
  the unseen space covers more in the same time.
- **Invisible barriers at platform edges.** The student would see the body stop against
  nothing, and barriers would hide teacher failures instead of fixing them.
- **Rejecting near-edge clips.** Redrawing clips that come near an edge removes exactly the
  states a student most needs labels for; the guard keeps them and counts what it did.

## Consequences

- Clip generation runs at about 200 ms per tick for three full panorama frames, roughly 15
  frames per second per game instance; the pilot's per-level figures are in the findings.
- In-between frames generated before this decision (M12-1's 3-frame control sessions) drew
  stale terrain; nothing was published from them.
- The pilot's measurements feed the final-volume decision (spec options A and B).
