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
