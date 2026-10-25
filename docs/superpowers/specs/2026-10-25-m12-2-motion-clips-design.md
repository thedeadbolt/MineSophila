# M12-2: Motion clips and the first teacher, design

- **Date:** 2026-10-25
- **Status:** Agreed in conversation; written for planning
- **Package:** `packages/honey-factory` (mod), `packages/honey-factory-tools` (tools,
  inspector), `schemas/honey-factory` (contract)
- **Depends on:** M11 (lock-step control) and M12-1 (fast generation, several captures per
  tick, `generate --instances N`).

## Why

The long-term goal is a fly brain that tracks honey and goes to it, searching with memory
when it disappears (roadmap, "Beyond phase 1"). Its vision has to work while its own body
moves: the whole scene slides when it turns, objects swing past when it walks, and the honey
leaves and re-enters view. Still images cannot teach that.

M12-2 generates short **motion clips**: frame streams made by driving the player through
M11's control, with labels on every frame. It also builds the **first version of the
teacher**, the scripted controller that M13 and M14 will use for imitation and DAgger.
Every clip records the teacher's command at every tick, so clips serve both vision training
and imitation warm-up.

## Research behind the teacher

The user asked for this research before the spec.

- **Labels must be right; states must not be perfect.**
  - DART ([Laskey et al. 2017](https://arxiv.org/abs/1703.09327)) perturbs the teacher's
    executed actions and records the teacher's correct action as the label, so the data
    covers off-course states, each with its correction.
  - Conditional imitation learning for driving ([Codevilla et al.
    2018](https://arxiv.org/pdf/1710.02410)) does the same with temporally correlated
    drift on about 10% of the data, and trains on the recovery.
- **The imitation gap.** A teacher acting on information the student cannot see produces
  provably poor student policies ([Weihs et al., NeurIPS
  2021](https://arxiv.org/abs/2007.12173)). So when the honey is out of sight, the teacher
  may act only on what the fly could know ("realizable", [Kim et al.
  2025](https://arxiv.org/html/2505.09546v1)).
- **Search must be demonstrated.** Imitating shortest paths produced poor object search;
  demonstrations that explore (peeking, scanning in place) taught real search ([Habitat-Web,
  CVPR 2022](https://arxiv.org/abs/2204.03514)). Frontier-based exploration, aimed at the
  edge between seen and unseen space, is the standard systematic way to search.
- **Real flies** fixate and saccade, and search locally around where food was last seen. The
  user chose **efficiency** over fly-likeness for the teacher's search, as the simpler thing
  to teach.

## Goals

1. **A realizable teacher** with three modes:
   - **Track** (honey visible): turn to centre it and approach it. When the way is blocked,
     detour with pathfinding around obstacles the teacher has seen.
   - **Re-acquire** (honey just lost): turn towards where it was last seen, go there, then
     search the area around it.
   - **Search** (lost for a while, or never seen): systematic, efficient frontier coverage.
     Go to the nearest worthwhile frontier, the edge between space that has been in view
     and space that has not, and scan in place on arrival.
2. **What the teacher may know:**
   - the honey's true position only while the honey is visible in the frame;
   - its memory: where the honey was last seen, and a map of which cells have ever been in
     view (ray-cast from the eye over the scene's blocks);
   - the scene's blocks only as far as they have been seen.
   - It never steers towards honey it has no way of knowing about.
3. **A clip driver** that executes the teacher's command, with temporally correlated drift
   (gradual departures from the teacher's command) on about 10% of ticks. The teacher then
   recovers from wherever the drift left it. The rate is configurable.
4. **Six clip types,** each with its own driving rule:

   | Clip type | Driving |
   |---|---|
   | Approach | the teacher tracks and approaches, with drift |
   | Pass-by | a scripted walk passing the honey at an angle, not towards it |
   | Scan | turning in place, so the honey enters and leaves the frame |
   | Occlusion | a path that puts a block or wall between the eye and the honey for a while |
   | Detour | honey behind an obstacle; the teacher paths around it, so the honey disappears and reappears |
   | No honey | the teacher searches a scene without honey |

   The teacher's command is computed and logged at every tick in every clip type, including
   where the executed motion is scripted.
5. **Labels for every frame:**
   - the visible-target mask;
   - the honey's bearing relative to the body (horizontal and vertical angle) and its
     distance;
   - its state: visible, partly visible, hidden behind something, out of frame, or absent;
   - whether its mask touches the frame edge;
   - the body senses (M11);
   - the executed command, the teacher's command and the teacher's mode;
   - the true state (M11).
   - Honey near or cut by the frame edge is labelled, not rejected. The still datasets'
     frame-edge rule and 200-pixel minimum do not apply.
6. **Clip datasets for all five levels,** one dataset per level, as in M10.

## Decided parameters

| Setting | Default | Note |
|---|---|---|
| Capture mode | `PANORAMA_310` | configurable; a fly's eye is wide |
| Resolution | 2480 × 1280, 1024² faces | configurable; same as the still datasets, downscale later |
| Frames per tick | 3 (60 Hz) | configurable; see below |
| Clip length | 32 ticks (96 frames) | detours up to 100 ticks; configurable per type |
| Clip mix | approach 25%, pass-by 15%, scan 15%, occlusion 15%, detour 10%, no honey 20% | configurable |
| Drift | correlated, about 10% of ticks | configurable |
| Motor limits | M11's: 9° per tick turning, 6° pitch | shared with M11 and M13 |
| Mining | none | possibly a separate step before M13 |
| Clips per scene | 4, from different start poses | configurable; my call |
| Start pose | drawn as still viewpoints are (3–16 blocks from the honey, valid ground, image-uniform aim); scan and no-honey clips face a random direction | my call |
| "Visible" for the teacher | at least 50 visible target pixels at full resolution | configurable; my call. Below this, the honey counts as not seen, so a sliver the fly could not resolve never steers the teacher |

**Why 60 Hz.**

- The fly-vision eye model samples our panorama with 1,714 ommatidia, spaced about 4° apart
  at the front and 6–7° at the sides.
- Nyquist requires the scene to move less than about one ommatidium spacing per frame.
- Walking past a block a metre away sweeps about 200° per second, so the frame rate must be
  at least 50 Hz. Three frames per tick (60 Hz) is the smallest setting above that.
- Very close passes, such as honey half a block away, still alias somewhat.
- A real fly sees at about 240 Hz; 60 Hz is a compute compromise the user chose.
- The extra frames are Minecraft's interpolated camera positions between ticks. The world
  still steps once per tick.

## Storage

- **The master is lossless video.** Each clip's RGB frames are stored as one lossless video,
  pixel-exact (FFV1, or another lossless codec of the builder's choice). Masks are stored
  losslessly too. Frame records hold the per-frame labels.
- **Derived copies are optional.** A tool can export lossy or downscaled copies for training;
  the master stays exact. Training is expected to convert clips once into eye samples on the
  fly-vision side, which is not ours.
- **Estimates:**
  - Panorama PNG frames measured in M9: 0.31 MB at level 1, up to 2.31 MB at level 5.
  - A pilot of 1,000 clips is about 127,000 frames: about 100 GB as PNG, or an estimated
    10–20 GB as lossless video.
  - Lossless storage fits the 1.8 TB drive even for the full set. If it ever does not,
    lossy masters become an option, by the user's rule.

## Volume

1. **A pilot first:** 1,000 clips, 200 per level. Check labels, the teacher's behaviour and
   the look in the inspector; measure time per clip and the compression ratio.
2. **Then decide the final set** from the pilot's measurements. The candidates use M10's
   weighting (2 : 5 : 10 : 10 : 20):
   - option A: about 9,400 clips, about 210 GB lossless;
   - option B: about 4,700 clips.
3. **Runs:** generation uses M12-1's `generate --instances N`, in daytime batches.

## Contract, tools and inspector

- **A clip dataset kind.** It has clip records (scene, type, seeds, frame count, video files,
  the commands for replay) and frame records (per-frame labels), with schemas, both
  validators, and the dataset manifest rules of M1. Splits are by scene: all of a scene's
  clips share a split. The still datasets' contract is unchanged.
- **Replay:** a clip rebuilds from its scene seed and its executed commands. Masks and the
  true state replay exactly; RGB replays exactly in scenes without animated blocks.
- **The inspector plays clips like GIFs:**
  - play, pause, frame-by-frame stepping, speed and a scrub bar;
  - the mask overlay and box follow each frame;
  - a timeline under the player shows the honey's state, its bearing and the teacher's mode
    across the clip, so a wrong label stands out.
  - Exporting a clip as a GIF is optional.
- **A reader in `honey-factory-tools`** iterates a clip's frames and labels, so the
  fly-vision side can convert clips without knowing the storage details.

## Non-goals

- Mining.
- The caged arena, teleporting honey and full episodes (M13). M12-2's teacher is the one M13
  reuses.
- DAgger rounds and the live adapter (M14).
- The fly-vision eye model and training (not ours).
- Lossy masters, unless storage forces them.

## Anti-goals

M12-2 fails if any of these happen:

- The teacher acts on the honey's true position while the honey is not visible.
- Any frame comes from a free camera rather than the controlled body.
- A label disagrees with its frame. Masks, bearing and state are always derived from the
  capture and the true state of that exact frame.
- A clip cannot be replayed from its record.

## Exit gate

- **The pilot:** 1,000 clips across levels 1–5, generated with `--instances N`, pass both
  validators.
- **Teacher sanity, measured on the pilot:**
  - approach clips without drift reach the honey on platform levels in at least 95% of
    cases;
  - search clips cover the reachable space systematically, measured as the share of cells
    seen over time;
  - a check over every frame confirms that the teacher never used the honey's position while
    it was hidden.
- **Inspector:** it plays pilot clips with labels and the teacher timeline; a short audit of
  sampled clips finds labels consistent with the frames.
- **Replay:** sampled clips from each level replay exactly, as defined above.
- **Measurements recorded:** time per clip, frames per second of generation, disk use and
  compression ratio, all feeding the final-volume decision.
- **Full suite:** `./gradlew build`, the full gametest suite and the Python checks pass.

## Left to the builder

- **The teacher's algorithms:** pathfinding (for example A* over the seen block grid,
  respecting M11's body and auto-jump), frontier selection, and the gains that turn targets
  into rate commands within the motor limits.
- **The drift process:** its exact form, for example a smoothly varying offset added to the
  teacher's command for a few ticks at a time.
- **Clip-type details:** how pass-by and occlusion paths are chosen from the scene, and how
  detour scenes are guaranteed to have an obstacle between the start and the honey.
- **Storage details:** codec, container, the mask storage format and the record field names.
- **Inspector layout,** within the requirements above.
