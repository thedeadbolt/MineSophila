# 0014: Movement actions through the player's own input and auto-jump

- **Status:** Accepted
- **Date:** 2026-10-24

## Context

M7 completes the environment API with `step`: TURN, LOOK, MOVE_FORWARD, WAIT and STOP,
with tick-based timing, input release, collision and physics, and Minecraft's own
auto-jump, which the specification names as the first movement helper and requires to be
verified rather than reimplemented. In Minecraft 26.3, auto-jump runs only on the client:
`LocalPlayer` decides to jump while moving, from the `autoJump` option it rereads every
tick, and presses jump through its `ClientInput`. The local player's movement is computed
on the client and sent to the server. Dataset viewpoints, by contrast, place a spectator
camera without collision.

## Decision

- **Movement uses the player's own input.** A step replaces the player's keyboard input
  with a scripted `ClientInput` that the player polls once per tick. MOVE_FORWARD holds
  forward for exactly the requested player ticks, WAIT holds nothing for its ticks, and
  STOP releases at once. Vanilla movement, collision, step-up and auto-jump then run
  unchanged, and auto-jump's presses are counted through the input's `makeJump`.
- **Turns and looks are instant** and in Minecraft's conventions: positive yaw turns right,
  positive pitch looks down, yaw wraps to [-180, 180) and pitch is clamped to [-90, 90]; the
  completed value is what actually changed.
- **Steps act on a body.** The first step after a reset or viewpoint switches the
  spectator camera to invulnerable adventure mode where it stands: gravity and collisions
  apply, blocks cannot change and a fall cannot end the episode. Placing a viewpoint makes
  it a camera again. The world baseline's player snapshot restores the user's own mode.
- **User state is borrowed per step.** The configured auto-jump replaces the user's option,
  and the scripted input the keyboard, only while a step runs; both are restored however
  the step ends, including when the world closes. The option is never saved.
- **A step completes after confirmation.** Once the action ends every input is released;
  the step settles until the client has rendered the result and the server reports the
  player's last input as empty, then captures. Results carry the operation, the scene's
  reset operation and an index within the scene, requested and completed values, client
  ticks, the server tick of confirmation, auto-jump presses and collision state.
- **Leaving the arena fails the step** (`OUT_OF_ARENA`) as soon as the player's feet or eye
  leave the reset volume. Mining is rejected as unsupported before any state changes.
- **Settings and history.** Action limits and auto-jump are environment settings that can
  change between operations; the coordinator keeps the last 256 finished operations in
  order. Commands, tests and the dataset runner all call the same runtime.

## Consequences

- On the movement course, auto-jump climbs a one-block step and walking alone is blocked;
  slabs and stairs are walked up and two-block walls and fences block both, the same in
  every run. Final positions vary by up to 0.02 blocks between runs, as settling takes a
  tick more or less of residual momentum.
- A failed step releases inputs on the client at once, but the server hears the release a
  tick later; only successful steps wait for its confirmation.
- The world is not paused between steps; residual momentum decays during settling and
  later ticks, and the observation's pose is the one captured.
- Movement depends on vanilla client behaviour; a controller that needs different physics
  needs a new recorded controller version.
