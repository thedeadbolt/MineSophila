# M11: Animal-like control

Recorded 2026-10-25 on the M0 reference setup: Minecraft 26.3, Fabric Loader 0.19.5, Fabric
API 0.163.0+26.3, Mesa 26.2.5, AMD Radeon RX 7600 (8 GB), Linux. The runs were made while the
M10 production batch ran in a second game instance on the same machine. Design:
`docs/superpowers/specs/2026-10-25-m11-animal-like-control-design.md`. Decision:
[ADR 0018](../../../docs/adr/0018-lock-step-motor-control.md). Protocol:
[control-protocol.md](control-protocol.md).

## What exists

- **`TickStepper`** freezes the server and holds the local player. Vanilla's tick freeze
  exempts players, so a client-level mixin skips the player's tick. Each `advance` runs
  exactly one server tick and one player tick, with the caller's input applied just before
  it, then waits for a frame showing that tick.
- **The motor model**:
  - `MotorCommand`: `forward`, `strafe`, `turn_rate`, `pitch_rate`, `jump`, `delay_ticks`;
  - `MotorLimits`, defaulting to 9° per tick of turning and 6° of pitch, each reaching full
    rate in 5 ticks;
  - `MotorState`, a linear slew of the actual rates toward the commanded ones.
- **The records**:
  - `BodySenses`: body-relative motion, rates, pitch, which way is down, contact and the
    last command;
  - `TrueState`: absolute pose, velocity, scene, target mask statistics and an edge flag;
  - `ControlStep`, which records both commands and which one ran.
- **The control loop** in `EnvironmentRuntime`: `controlReset`, `controlStep`,
  `lastControlStep` and `controlEnd`. The user's input and auto-jump option are borrowed
  and given back.
- **The M7 actions as macros.** `ActionMacros` turns a turn or look into a rate profile
  from rest to rest under the same limits, landing exactly on the angle: 90° takes 14
  ticks, 180° takes 24. `step()` keeps its running world.
- **`ControlServer`** on 127.0.0.1:
  - a session token, one controller at a time, claimed worlds only;
  - student and privileged views;
  - `/honeyfactory_control start [privileged] | stop | status`.
- **`honey_factory_tools.control`**: the Python client (`ControlClient`, `Command`,
  `Observation`), plus `honey-factory-tools control-demo`, which drives a reproducible
  schedule and logs every step.

## Exit gate

`M11ExitTest` at 1,000 steps (`-Dhoneyfactory.controlSteps=1000`), a level-1 scene with
seed 11 and 64 × 64 perspective frames:

| Run | Result |
|---|---|
| Python client, no delay | 1,000 steps in 202.0 s, 4.95 steps per second, exit status 0 |
| Python client, 50 ms sleep before each step | 1,000 steps in 202.2 s, exit status 0 |
| The two runs compared | identical RGB hashes, mask hashes, senses and true state at every step, apart from the world clock (below) |
| In-game replay of run 1's commands | all 1,001 frames and masks byte-identical to the log, 213.4 s |

- **The world clock.** The absolute `server_tick` differs between runs, by the same offset
  at every step, because the world's game time keeps running across resets. The test
  leaves it out of the comparison and checks instead that it advances by exactly one per
  step.
- **The routine test** runs 300 steps, about a minute per pass.

The delayed run took as long as the undelayed one: about 0.2 s per step is the game's own
work, not the client's. That is the render, the capture, and the readiness check that waits
for two stable frames with every section compiled. Speeding this up is the obvious work
before DAgger runs need many episodes. The candidates are skipping readiness waits that a
single tick cannot need, and smaller frames.

## Other gates

- **`M11TickTest`.** A held player stays exactly still for 40 ticks. Each step advances
  exactly one server tick. A fall from rest moves 0 and then −0.0784 blocks, vanilla's first
  tick of gravity with its float drag constant.
- **`M11ControlLoopTest`:**
  - turning ramps 1.8, 3.6, 5.4, 7.2 and then 9° per tick, and the yaw follows exactly;
  - walking speed builds up over the first ticks;
  - a teacher step runs the teacher's command and records both;
  - walking into a wall sets `bumped`;
  - walking off the platform fails with `OUT_OF_ARENA`;
  - refused commands advance nothing;
  - ending the session gives back keyboard input and auto-jump.
- **`M11ServerTest`:**
  - a wrong token gives `UNAUTHORISED`, and a second connection `BUSY`;
  - student observations carry one RGB blob and no truth, commands or masks;
  - a client that vanishes right after sending a step leaves the runtime idle with input
    and auto-jump restored;
  - a malformed frame closes only its own connection;
  - an unclaimed world is refused (`NOT_OWNED`).
- **`M7EnvironmentTest`** passes with ramped turns and looks.
- **The full suite.** `./gradlew build` (282 JUnit tests) and the full client gametest
  suite, M2–M11, pass in 20 minutes 14 seconds, run beside the production batch.

## Findings

Four problems surfaced while building M11. All are fixed and covered by the tests above:

1. **Players ignore the tick freeze.** Without holding the local player, the body kept
   falling and coasting between steps. The client-level mixin fixed this.
2. **The camera swung between steps.** A held player still renders with a cycling partial
   tick, so the camera moved between its last two positions. Near x = 0, a section boundary,
   the renderer's section graph rebuilt every few frames and a step never became ready. The
   stepper now settles the interpolation after each tick, and measures the tick's
   displacement itself.
3. **Leftover momentum.** Releasing the body after a walk left it moving: a spectator
   drifts with inertia, and the last command kept driving it, so the next reset never
   settled at its start pose. The input goes idle and the velocity is cleared before every
   release.
4. **A blocking start deadlocked.** Checking the world's claim by blocking the client
   thread on the server thread deadlocks in client gametests, and would stall the game in
   play. Starting the server is now asynchronous.

## Scope notes

- **Students never receive masks.** A mask shows exactly where the honey is, so masks go
  only to privileged connections, with the true state and the teacher's commands.
- **Platform scenes only.** The server resets platform recipes only; natural terrain
  answers `UNSUPPORTED` until patch preparation is wired in.
- **Exact RGB needs no animated blocks.** Water, lava and fire animate on a client-wide
  counter that a reset does not restore. Masks and the true state replay exactly
  regardless.
- **Live speed is later.** `delay_ticks` exists on the wire and must be 0. Live speed with
  prediction is M14.
