# M7: Movement actions and environment completion

Recorded 2026-10-24 on the M0 reference setup: Minecraft 26.3, Fabric Loader 0.19.5,
Fabric API 0.163.0+26.3, Mesa 26.2.5, AMD Radeon RX 7600, Linux. The decision is
[ADR 0014](../../../docs/adr/0014-movement-actions.md).

## What exists

- **`step(action, projection)`** on `EnvironmentRuntime`, beside `reset`, `setViewpoint`,
  `observe` and `status`. Actions are `Action.Turn`, `Look`, `MoveForward`, `Wait`, `Stop`
  and `Mine`; mining is rejected as unsupported.
- **Movement through vanilla input.** A step replaces the player's keyboard input with a
  scripted one for its duration, so Minecraft's own movement, collision, step-up and
  auto-jump apply. Auto-jump presses are counted where vanilla makes them.
- **A body for steps.** The first step after a reset or viewpoint turns the spectator
  camera into an invulnerable adventure-mode player where it stands.
- **Settings.** `EnvironmentSettings` holds action limits (default ±180° turns and looks,
  1–200 ticks for moves and waits) and auto-jump (default on). `configure` changes them
  between operations, and `configureTiming` the settling minimums and timeouts.
- **Ordered results.** Each step result carries its operation ID, the scene's reset
  operation ID, its index in the scene, requested and completed values, client start and
  end ticks, the server tick at which the server confirmed the release, auto-jump presses,
  collision and ground state, the pose before and the observation after. The coordinator
  keeps the last 256 finished operations in order.
- **Commands.** `/honeyfactory_env step …`, `settings`, `history`, `reset course` and
  `viewpoint course <lane>` call the same runtime.
- **The movement course.** `FixturePlans.course()` has five lanes: a one-block step onto a
  raised floor, a two-block wall, a floor of bottom slabs, a stair onto a raised floor and a
  fence row.

## Exit gate

`M7EnvironmentTest` in a fresh dataset world, plus `ActionTest` and
`OperationCoordinatorTest`.

| Gate item | Evidence |
|---|---|
| Working synchronous `observe` and `step` | Every step completes after its action, settling and capture; observations carry the step's operation ID |
| TURN and LOOK conventions and completed values | TURN ±90 and ±180 and LOOK +30, +80, −180 and +90 from level: yaw wraps to [−180, 180), pitch clamps, so LOOK +80 from 30° completes 60 and LOOK −180 from 90° completes −180; 0 ticks; no movement |
| MOVE_FORWARD, WAIT and STOP ticks | MOVE_FORWARD 40 completes 40 player ticks on every lane; WAIT 20 completes 20 with under 0.2 blocks of residual drift; STOP completes 0 ticks, and a WAIT after it drifts under 0.001 blocks |
| Input release | After every successful step the keyboard input and the user's auto-jump option are back and the server's last player input is empty. After a failed step the server sees the release one tick later |
| Collision and physics observation | Walls, fences and an unclimbed step report a sideways collision and stop the player; every walk ends on the ground with no sideways drift |
| Vanilla auto-jump over supported blocks | See the course results below |
| Previous-setting restoration | The user's option (off) is restored after successful steps, after failed and timed-out steps, and when the world closes mid-step; `world release` restores the player's own game mode after embodiment |
| Concurrent-request rejection | While a step runs, another step, observe, viewpoint, reset and a settings change are rejected as BUSY; the running step still completes its ticks |
| Timeouts | A reset and a step given no settling time fail with TIMEOUT and leave the coordinator FAILED; a step is then rejected until a reset |
| Limits and unsupported actions | TURN 181 and MOVE_FORWARD 0 are INVALID_REQUEST and MINE is UNSUPPORTED, with no state change; a mine command starts no operation |
| Leaving the arena | Walking off the platform fails the step with OUT_OF_ARENA and restores the inputs |
| Ordered results | Step indices run 1, 2, 3 after a reset and refer to it; client and server ticks increase; the history lists the reset and the steps in order |
| Commands and the dataset runner use one coordinator | A command step appears as the next STEP operation; a small level-1 dataset's resets and captures appear in the same coordinator's history, and the dataset completes |

## Movement course

MOVE_FORWARD 40 ticks from 3.2 blocks south of each obstacle, with auto-jump on and off.
Forward and rise are in blocks, measured at the capture after settling.

| Lane | Auto-jump | Jumps | Collided | Forward | Rise |
|---|---|---:|---|---:|---:|
| One-block step | on | 1 | no | 8.07–8.09 | 1.0 |
| One-block step | off | 0 | yes | 3.2 | 0 |
| Two-block wall | on / off | 0 | yes | 3.2 | 0 |
| Bottom slabs | on / off | 0 | no | 8.44–8.45 | 0.5 |
| Stair | on / off | 0 | no | 8.44 | 1.0 |
| Fence | on / off | 0 | yes | 3.58 | 0 |

Only the one-block step separates the two settings: vanilla auto-jump climbs it, walking
alone is stopped at its face. Slabs and stairs are within the player's 0.6-block step
height, and walls and fences are beyond auto-jump's 1.2 blocks. The fence stops the player
0.375 blocks further on, at its collision box rather than its block face. The outcomes
were the same in every run; final positions differ by up to 0.02 blocks, from a tick more
or less of residual momentum during settling.

## Timing

A turn or look took 4–6 client ticks from request to result, measured over eight in one
run. That covers the server switching or confirming the body, the client rendering for
two stable frames and the server confirming the release. A move or wait adds its own
ticks.

## Still open

- The world is not paused between steps; whether it should be is left to the level-6
  extension, as the specification says.
- Discrete action presets are not implemented; limits are.
- The external control adapter is a separate extension gate.
