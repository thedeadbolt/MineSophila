# M11: Animal-like control, design

- **Date:** 2026-10-25
- **Status:** Approved in conversation; written for planning
- **Package:** `packages/honey-factory` (mod) and `packages/honey-factory-tools` (Python client)
- **Roadmap:** M11 in `packages/honey-factory/docs/roadmap.md`; this design also pulls a
  basic external connection forward from M14.

## Why

The long-term goal (roadmap, "Beyond phase 1") is a connectome-constrained fly brain that
keeps honey in view and searches for it with memory when it disappears. It will be trained
by imitating a scripted teacher, refined with DAgger: the student drives, and the teacher
labels every state the student reaches, including the bad ones it caused.

DAgger in freeze mode needs four things from the environment, and M11 provides them:

1. A world that waits. The brain receives a frame, takes as long as it needs, and sends one
   command. The world advances one tick and waits again.
2. One body and one command format for everyone. Teacher, student and the M12 clip
   generator all drive the same player through the same continuous commands. A teacher
   label is then always an action the student could take.
3. Two views of every step: what the student senses, and the true state the teacher reads.
   Both are logged.
4. Determinism. The same start and the same commands give the same frames, so an episode
   can be replayed, inspected and relabelled.

The M7 actions do not meet this. Turns and looks snap instantly, forward movement is all
or nothing, a step returns one frame after several ticks, and the world runs on between
steps.

DAgger was chosen over the alternatives:

- **Reinforcement learning** needs far more steps and reward tuning. It stays optional, as
  fine-tuning after DAgger.
- **Plain imitation** drifts once the student leaves the teacher's paths.
- **A learned world model** is unnecessary while the real simulator can pause.

## Goals

- **The loop.** A lock-step tick loop: `reset`, then repeated `step(command)`, each
  advancing exactly one game tick and returning the next observation.
- **The body.** An animal-like movement model with configurable, recorded limits.
- **The senses.** A fixed set of student senses, and a true-state record kept apart from
  them.
- **Mixed control.** A step can execute the student's command or the teacher's, and records
  both.
- **The connection.** A local server so a Python program outside Minecraft can drive the
  loop, with a small reference client.
- **Determinism and replay.** An episode rebuilt from its start and its executed commands
  reproduces every frame on the reference setup.

## Non-goals

These belong to later milestones or not to us:

- **Real-time driving and prediction.** The world never runs ahead of the brain in M11.
  Live speed comes later, if lock-step works.
- **Episode datasets** in the dataset contract, with their validator and inspector support
  (M12, M13).
- **The caged arena, honey teleport rules and the scripted teacher** (M13). M11 needs only
  a test scene and a stand-in command source for its tests.
- **The fly brain and its eye model.** These live in `fly-vision`, which is not ours. M11
  sends frames; what the brain does with them is out of scope.
- **Flying.** The agent stays a player: it walks, turns, looks and jumps.

## Anti-goals

M11 fails if any of these happen:

- The student receives anything a body could not sense: absolute position, compass
  heading, or the honey's location beyond what the frame shows. That would let it cheat
  the search-with-memory skill it is meant to learn.
- The view can snap instantly. Turning must build up and slow down.
- Frames depend on the caller's speed. A slow client and a fast client must get identical
  frames from identical commands.
- Teacher, student or clip generator use different motion rules or command formats.

## Decisions

### The lock-step loop

- Between steps the world is frozen, using the server's tick freeze.
- A step applies one command, advances the server and the player by exactly one tick (the
  single-tick step), renders the frame, captures it, and freezes again.
- `reset(scenario, seed)` builds the scene, places the player and returns the first
  observation, as step 0.
- Physics, collision and vanilla auto-jump are unchanged. Auto-jump stays a recorded
  per-run setting, as in M7.
- The world never advances while the caller is thinking, however long it takes.

### Commands and movement

A command holds desired values, all floating point:

| Field | Range | Meaning |
|---|---|---|
| `forward` | [−1, 1] | Desired forward or backward speed, fed to the player's own input vector |
| `strafe` | [−1, 1] | Desired sideways speed, positive right |
| `turn_rate` | ±max, degrees per tick | Desired yaw rate, positive turns right |
| `pitch_rate` | ±max, degrees per tick | Desired pitch rate, positive looks down |
| `jump` | boolean | Jump, as the jump key does |

- **Walking** uses Minecraft's own physics. Fractional `forward` and `strafe` go through
  the player's input vector, so speed builds up and decays through the game's
  acceleration and friction.
- **Turning and looking** are rate-limited with acceleration. The actual yaw rate moves
  toward `turn_rate` by at most a configured change per tick, and is capped at a maximum.
  Pitch works the same way with its own limits and stays clamped to [−90, 90].
- **Starting values**, all configurable and recorded:
  - turning: at most 9° per tick (180° per second), reaching full rate in about 5 ticks;
  - pitch: lower limits than turning;
  - walking: Minecraft's.

  The numbers are tuned once the teacher exists. Flies turn in fast saccades, so quick
  turns stay possible but never instant.
- **Limits** live in one shared settings object, the same for teacher, student and clips.
  The resolved values are recorded with every run.
- **The M7 step API** becomes a macro layer. A TURN or MOVE_FORWARD expands into a sequence
  of these commands over several ticks. Its observable contract changes: a turn now takes
  ticks. This needs a recorded decision, and M7's tests must be updated.
- **Delay hook (my call, agreed as optional).** A command may say it takes effect after
  `delay_ticks`. M11 supports only 0 and rejects anything else. Live prediction can later
  turn delay on without changing the protocol.

### What the student senses each step

Agreed field by field with the user:

- **The frame.** Perspective or panorama, at a resolution set per run.
- **Its own motion, relative to its body:** forward, sideways and vertical speed, and
  turning rate (yaw rate and pitch rate).
- **Head pitch, and which way is down.**
- **Contact:** on the ground or not, bumped into something this tick, in water.
- **The command executed last step**, as an efference copy.

The student does not receive absolute position, compass heading, or any honey information
beyond the frame.

### The true state, for the teacher and the logs

Every step also records, separately from the senses:

- the player's absolute position, yaw, pitch and velocity;
- the scene's identity (layout hash, seed and tick);
- the honey's position and target metadata: the mask, the visible and unoccluded pixel
  counts, and the target status.

A teacher reads this. A student must never receive it over the connection, unless it
explicitly opens the privileged channel described below.

**Frame labels differ from the still datasets.** A honey partly or wholly out of frame is
a normal state here and is labelled, not rejected:

- `frame_clipped` and the 200-pixel minimum do not apply to steps;
- each step records whether the target touches the frame edge, so later milestones can add
  an in-frame fraction.

### Mixed control

- Each step can carry up to two commands: the student's and the teacher's. A per-step
  choice says which one runs; DAgger mixes them, giving the student more control each
  round.
- The step log records the student command, the teacher command, which ran, and the
  command the body actually achieved after the limits were applied.

### The external connection

- **Transport:** a server inside the mod, listening only on 127.0.0.1.
- **Protocol:** language-neutral. Each message is length-prefixed and holds a small JSON
  header plus raw frame and mask bytes, so frames are not text-encoded. This supersedes the
  spec's earlier proposal of newline-delimited JSON, which would base64-encode every frame.
- **Messages:** `hello` (version and settings), `reset`, `step`, `close`, and errors.
  Requests are strictly one at a time.
- **Access:**
  - a session token is required, printed or written locally, as the inspector does;
  - only one controller is connected at a time;
  - the server runs only in a claimed dataset world, never a personal one.
- **Two channels:** a student connection receives only the senses. A privileged connection,
  meant for teachers and recorders, also receives the true state.
- **The reference client** is a small Python library in `honey-factory-tools`, with a typed
  `Observation`, a `Command`, and `reset`, `step` and `close`. It is easy to program
  rather than as fast as possible: the per-step cost is the tick and the render, not the
  connection.
- **Protocol document:** written so a client in another language could be implemented from
  it alone.

### Test scene and exit test

- **Test scene:** a small fixed scene, an existing level-1 or level-2 platform recipe with
  one honey, from a fixed seed. No cage yet.
- **Exit test:**
  1. A Python script uses the reference client to drive the player for 1,000 steps of
     varied commands: ramped turns, walks that start and stop, pitch changes, jumps and
     bumping into blocks.
  2. Replaying the logged executed commands from the same reset reproduces every frame and
     mask, and the true state, byte for byte on the reference setup.
  3. A second run with an artificial client delay gives the same frames.

  If 1,000 steps turns out to take too long for a routine gametest, the count may drop. The
  full run is still recorded in the M11 findings.

## Constraints

- **Platform rules carry over:** Fabric and Blaze3D (no raw OpenGL), world changes on the
  server side, no client classes in `src/main`.
- **No modified player saves:** the server only runs in claimed dataset worlds.
- **Repository rules:** decisions go in an ADR in `docs/adr/`, and the spec is updated
  where M11 changes it. That covers the M7 action model, the adapter proposal and the
  level-6 record fields.
- **M1–M10 compatibility:** existing datasets, their hashes and replay are unchanged.
  M11 adds an environment mode and changes nothing in the still-image generator.
- **The reference setup** is the M0 machine. Determinism claims are made for it.

## Left to the builder

- **The tick mechanics:** how to drive exactly one tick with the server's freeze and step
  while the client renders that tick, built on the M7 step driver.
- **Rate model details:** the exact form of the acceleration limit (linear slew, or
  something smoother), as long as it is deterministic and recorded.
- **Wire details:** the message format (field names, framing, port selection and how the
  token is shared), within the decisions above.
- **Stream frame resolution:** the default for streams, likely small, since the fly eye is
  coarse. The resolution must be configurable.
- **Log format:** how the M11 step log is stored. It is not yet a dataset type; M12 and
  M13 define that.
- **Performance:** how much per-step capture cost can be saved, for example by skipping
  the panorama's unused faces or skipping readiness waits that a single tick does not
  need, as long as determinism holds.
