# 0018: Lock-step motor control

- **Status:** Accepted
- **Date:** 2026-10-25

## Context

The long-term goal is a fly brain that keeps honey in view and searches for it with memory.
It is trained by imitating a scripted teacher, refined with DAgger: the student drives, and
the teacher labels every state the student reaches. DAgger needs the following from the
environment:

- a world that waits while the brain thinks;
- one body and one command format for teacher, student and motion clips;
- separate records of what the student senses and the true state the teacher reads;
- determinism, so episodes replay exactly.

The M7 actions fall short:

- turns and looks snap instantly;
- forward movement is all or nothing;
- a step returns one frame after several ticks;
- the world runs on between steps.

The design is in `docs/superpowers/specs/2026-10-25-m11-animal-like-control-design.md`.

## Decision

- **Lock-step loop.** A control reset builds the scene and freezes the world. Each control
  step applies one motor command for exactly one game tick, renders and captures that tick,
  and freezes again.
- **Holding the player.** Vanilla's tick freeze exempts players, so the client level also
  skips the local player's tick unless a step permits it.
- **Settled view between steps.** After each step the player's interpolation is settled,
  so frames between steps show one still view and the renderer's section graph settles.
- **The motor command:**
  - `forward` and `strafe` in [-1, 1], fed to the player's own input vector, so walking
    keeps Minecraft's physics;
  - `turn_rate` and `pitch_rate` in degrees per tick;
  - `jump`;
  - `delay_ticks`, which must be 0 until live prediction exists.
- **Rate limits.** Turning and looking are rate-limited and acceleration-limited by
  `MotorLimits`, part of the shared environment settings: at most 9° per tick of turning
  and 6° of pitch, each changing by at most a fifth of its maximum per tick.
- **Senses and truth are separate records.**
  - A student receives `BodySenses`: its motion relative to its body, its turning rates, its
    head pitch, which way is down, contact, and its last executed command.
  - `TrueState` carries absolute pose, velocity, the scene and the target's mask
    statistics, for teachers and logs only.
  - A target cut by the frame edge is a valid state here, flagged rather than rejected.
- **Mixed control.** A step carries the student's command, the teacher's, or both, plus
  which one runs. Both are recorded.
- **M7 actions on the shared motion model.** Turns and looks now ramp through the same
  motor limits, one rate per player tick, from rest to rest, landing exactly on the
  requested angle; a 90° turn takes 14 ticks.
- **M7 steps keep their running world.** Collisions, auto-jump counting and server-confirmed
  input release are unchanged.
- **Loopback protocol.** External controllers use a length-prefixed JSON header with raw
  frame and mask bytes, served on 127.0.0.1 with a session token. This supersedes the
  specification's earlier proposal of newline-delimited JSON, which would text-encode
  every frame.

## Consequences

- One body and one rate model serve teacher, student, clips and the M7 macros.
- An M7 turn or look now takes ticks instead of none. Its completed value is unchanged.
- The world does not run between control steps however slowly the caller answers. Live
  speed, with prediction, can later turn on `delay_ticks` without a protocol change.
- Water, lava and fire animate on a client-wide counter that a reset does not restore.
  Byte-exact replay of RGB is therefore promised only for scenes without them; masks and
  the true state replay exactly regardless.
