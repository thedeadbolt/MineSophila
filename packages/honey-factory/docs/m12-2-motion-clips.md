# M12-2 findings: motion clips and the first teacher

Design: `docs/superpowers/specs/2026-10-25-m12-2-motion-clips-design.md`. Decisions:
ADR 0020. Plan: `docs/superpowers/plans/2026-10-26-m12-2-motion-clips.md`.

## First pilot (2026-10-26, level 1 only)

The pilot started at 20:00 with 200 clips per level on two game instances. It stopped
after level 1, because watching it showed bodies walking off the platform and missing
honey that was in plain view.

| Measure | Level 1 |
|---|---|
| Clips | 200 (approach 84, pass-by 48, scan 28, no honey 40), 50 scenes |
| Generation | 15.0 min on 2 instances, about 800 clips per hour |
| End-of-slice validation | 557 s per instance, on the render thread (window "not responding") |
| Merge validation | 18 min (Python, decoding every frame to count it) |
| Storage | 4.65 GB; 23.2 MB per clip; RGB 201 KB per frame, mask 0.3 KB per frame; 47× smaller than raw |
| Approach reached the honey | 84 / 84 |
| Falls (redraws) | 1 (out of the arena at the platform edge) |

What it showed:

- **The falls were scripted pass-bys.** A pass-by walks a straight line past the honey at
  full speed and did not check for floor. The honey sliding past the frame looked like
  the teacher missing it, but no teacher drove those ticks. The teacher itself planned
  drops onto unseen floor, steered straight at honey across a gap, and walked at honey it
  had no way to: none happened in this pilot, but three unit tests reproduced each.
- **Falls were hidden.** The generator redrew a clip whose body left the arena, which
  removed exactly the near-edge states a student needs labels for.
- **Validation froze the game.** Counting frames with `ffprobe -count_frames` decoded every
  full-size frame (1.96 s per video against 0.17 s for counting packets). The masks were
  decoded again, and everything ran twice, on the render thread.

What changed (ADR 0020): a `driver` on every tick; an edge-aware teacher with an edge brake;
an edge guard on the executed command, recorded as `guarded`; pass-by lines that stay on
the floor; drift towards hazards (`hazard_fraction` 0.3); falls counted in
`counts.clip_redraws`; reasons for every missed approach, detour or occlusion; detour and
occlusion clips ending on reaching the honey; 15% scripted clips; and validation off the
render thread, with packet counts and one mask check per clip as it is accepted.

## Second pilot

To run: 1,000 clips over levels 1–5 on two instances, configs
`packages/honey-factory/configs/m12-2-pilot-level{1..5}.json`. Gate: falls 0, teacher
failures 0, realizability violations 0, approach reach at least 95% on levels 1–4, every
dataset valid in both validators, sampled clips replaying exactly.

### Result (2026-10-27, 10:20–12:51, commit `4a2e59d`): the gate fails

| Measure | Level 1 | Level 2 | Level 3 | Level 4 | Level 5 |
|---|---|---|---|---|---|
| Clips | 200 | 200 | 200 | 193 | 200 |
| Generation (2 instances) | 23 min | 24 min | 24 min | 28 min | 39 min |
| Storage | 4.7 GB, 25.0 MB per clip | 5.4 GB, 28.6 MB | 7.0 GB, 37.0 MB | 9.5 GB, 52.2 MB | 47 GB, 247.9 MB |
| Approach reached (incl. fallbacks) | 126/126 | 113/113 | 106/107 | 63/104 | 106/133 |
| Falls (`clip_redraws`) | 0 | 0 | 0 | 0 | 11 (approach) |
| Real detour clips | – | 0 of 29 | 0 of 35 | 0 of 30 | 0 of 37 |

Both validators passed every level (0 errors); 0 realizability violations; Python
validation took 2–4 minutes per level. The clip report counts 68 teacher failures.

What each failure turned out to be (investigated read-only, nothing changed yet):

- **Detour never happens.** Every viewpoint draw passes the level's sight rule first
  (`ScenePresets`: level 2 `ALL_CLEAR`, 3 `PARTLY_HIDDEN`, 4 `CLEAR_AND_OBSTRUCTED`, 5
  `SOME_CLEAR`), and each of those rejects a view with no clear sight line. A detour start
  needs exactly that view, so it can never qualify and always falls back to approach. The
  planned search clip would hit the same wall: hidden starts need draws that skip the
  sight rule.
- **The occlusion "teacher failures" are not the teacher's.** In 67 of 74 occlusion clips the
  teacher drove no tick: the scripted walk to cover (3–10 blocks, up to 46 ticks) outlasts
  the 32-tick cap. `MissReasons` blames the teacher without checking that it drove. Of the 68
  teacher failures, 62 are occlusion clips; 6 are approach or detour fallbacks (level 4: 3,
  level 5: 3).
- **The level-5 "falls" look like walking downhill.** The fall test is "more than 3 blocks
  below the start pose", cumulative. In s000036 the honey is at y=144; the draws started at
  y=148, 146, 145 and 144; all six "falls" stopped at y≈144.9, about 5 blocks from the honey,
  and only the draw starting at y=144 succeeded. Kept level-5 clips already descend up to
  exactly 3 blocks. The 11 falls are 3 clips (s000036 ×6, s000009 ×3, s000022 ×2). Not yet
  confirmed by a trace of a redrawn clip.
- **Level-4 approach times out near the honey.** 26 timeouts, some starting 3–8 blocks away,
  run 100 ticks mostly in track mode (a 6-block walk takes about 30 ticks). The teacher gets
  stuck close to the honey among level 4's near-aim blocks; to be watched in the inspector.
  12 more are `unreachable` (no walk to a cell beside the honey), which should not count
  against reach.
- **Level 4 is 7 clips short.** "Clip N of sNNN has no placeable start": no draw in the
  viewpoint budget passed `CLEAR_AND_OBSTRUCTED`, and the clip was skipped without trying
  its remaining attempts (s000002, s000006, s000024, s000048).
- **Level-5 storage is 5–10× the other levels** (248 MB per clip, 6× smaller than raw against
  30–48× elsewhere): natural terrain compresses badly losslessly. 200 clips took 47 GB.

What changed (each with a test that failed first):

- **Falls** are one drop more than 3 blocks below where the body last stood (`FallCheck`);
  walking downhill is not a fall. Not yet confirmed against a trace of the level-5 clips.
- **Occlusion** runs up to 100 ticks, and a miss measures progress from where the teacher took
  over; a clip a script drove throughout ran out of time.
- **Starts:** each attempt draws its own viewpoints, so a redraw starts somewhere new, and a
  clip with no placeable start tries its next attempt instead of being skipped.
- **Detour and search starts** skip the level's sight rule.
- **Search clips** (new): the honey hidden with a way to it, the teacher drives, up to 200
  ticks. Default mix: approach 30%, pass-by and scan 7.5% each, occlusion 10%, detour 15%,
  search 15%; no-honey 20% of scenes.
- **clip-report** counts reach against clips with a way to the honey (level 4 above: 63/92,
  not 63/104) and prints reach by type and miss reasons for each dataset.

Left open: level-4 approach getting stuck near the honey (to watch in the inspector), and
level-5 storage. A short pilot of levels 2–5 checks the fixes.
