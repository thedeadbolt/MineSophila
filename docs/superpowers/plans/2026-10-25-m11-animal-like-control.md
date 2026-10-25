# M11 Animal-like Control Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A frozen, lock-step tick loop that a Python program drives through a local
server. Each step applies one continuous motor command for exactly one game tick and
returns the frame, the body's senses and, to privileged callers only, the true state.

**Architecture:** Pure, unit-tested logic lives in `src/main`:
- the motor model;
- the senses and true-state records;
- the wire codec.

Client-only code lives in `src/client`:
- a tick stepper that keeps the world and the player frozen between steps;
- the control loop in `EnvironmentRuntime`;
- the loopback server.

The Python reference client lives in `honey-factory-tools`. The M7 actions become a macro
layer over the same tick loop.

**Tech Stack:** Java 25, Fabric (Minecraft 26.3, Loader 0.19.5, API 0.163.0), Blaze3D
capture as in M3–M9, `java.net.ServerSocket`, Gson, JUnit 5, Fabric client gametests;
Python 3 standard library (`socket`, `json`, `struct`) and pytest.

**Spec:** `docs/superpowers/specs/2026-10-25-m11-animal-like-control-design.md`. Read it
before any task. Also follow `AGENTS.md` and `packages/honey-factory/docs/spec.md`.

## Global Constraints

- Fabric and Blaze3D only, no raw OpenGL; world mutation on the logical server; no client
  classes in `src/main`.
- The server listens only on 127.0.0.1, needs a session token, accepts one controller at a
  time, and runs only in a claimed dataset world.
- A student connection never receives absolute position, compass heading, honey position
  or target metadata. Only a privileged connection does.
- The world never advances between steps, whatever the caller's speed.
- Teacher, student, clips and the M7 macros use one motion model and one shared,
  recorded settings object.
- Starting limits (configurable, recorded):
  - turning: at most 9° per tick, reaching full rate in 5 ticks, so the rate changes by
    at most 1.8° per tick;
  - pitch: at most 6° per tick, reaching full rate in 5 ticks, so 1.2° per tick.
- `forward` and `strafe` lie in [−1, 1] and go through the player's own input vector.
  Pitch is clamped to [−90, 90]. Yaw wraps to [−180, 180).
- `delay_ticks` exists on the wire and in the command type; only 0 is accepted in M11.
- M1–M10 datasets, configuration hashes and replay are unchanged.
- Conventional Commits scoped `honey-factory` or `honey-factory-tools`, ending with
  `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- Fixtures stay per suite; no shared `tests/conftest.py`.
- Gametests cannot run while a production batch uses the game from `../MineSophila-aim`.
  Run them from this checkout as a second instance, and never run Gradle in that worktree.

## Review Focus

1. **The player drifts between steps.** Vanilla tick freeze exempts players, so gravity,
   momentum or input could move the body while the caller thinks. Expect no pose change at
   all between steps. Task 1's test waits 40 client ticks between steps and asserts the
   pose is unchanged.
2. **Animated textures break replay.** Water, lava and fire animate on a client-global
   counter that a reset does not restore. Expect the exit test scene to contain none, and
   expect replay to compare masks and true state even where RGB could differ. Task 9 uses
   a level-1 platform scene and asserts it has no animated blocks.
3. **The client disconnects mid-step or sends garbage.** Expect the server to finish or
   abandon the step cleanly, release inputs, restore the user's auto-jump and accept a new
   connection. Task 7 tests a disconnect after `step` is sent, and a malformed frame.
4. **Commands outside the limits:** NaN, infinity, `forward` 1.5, `turn_rate` 40,
   `delay_ticks` 1. Expect a protocol error naming the field, with no tick advanced.
   Task 2 tests the limits and Task 6 tests the error reply.
5. **Stepping before reset, after leaving the arena, or in an unclaimed world.** Expect
   clear errors (`NOT_RESET`, `OUT_OF_ARENA`, `WORLD_NOT_CLAIMED`) and no world change.
   Task 4 tests the first two; Task 7 the third.

---

### Task 1: Tick stepper, proving exactly one frozen tick per step

The riskiest assumption comes first. If it fails, stop and report: the rest of the plan
depends on it.

**Files:**
- Create: `packages/honey-factory/src/client/java/io/github/thedeadbolt/honeyfactory/client/runtime/TickStepper.java`
- Create (if needed): a mixin under `client/mixin/` that skips the local player's tick while stepping is held, registered in the client mixin config
- Test: `tests/honey-factory/gametest/java/io/github/thedeadbolt/honeyfactory/gametest/M11TickTest.java`, registered in `tests/honey-factory/gametest/resources/fabric.mod.json`

**Interfaces:**
- Produces:
  - `final class TickStepper` with `void hold(MinecraftServer server)`, which freezes the
    server tick and stops the local player ticking;
  - `CompletableFuture<Tick> advance(Consumer<LocalPlayer> beforeTick)`, which runs
    exactly one server tick and one local-player tick and calls `beforeTick` just before
    the player tick, then completes when the client has rendered that tick;
  - `void release(MinecraftServer server)`;
  - `record Tick(long serverTick, long clientTick)`.

- [ ] **Step 1: Write `M11TickTest`.** In a claimed dataset world with a level-1 scene and
  the player standing on the floor:
  - `hold`, record the pose, wait 40 client ticks, and assert the pose is equal (Review
    Focus 1);
  - call `advance` 10 times with forward held, and assert `serverTick` increases by
    exactly 1 per call and the player moved forward;
  - drop the player 3 blocks and call `advance` once: assert the fall covered exactly one
    tick of gravity, compared with a vanilla reference;
  - `release` restores normal ticking.
- [ ] **Step 2: Run it and see it fail.**
  `cd packages/honey-factory && ./gradlew runClientGameTest -Dhoneyfactory.gametests=M11Tick`
  Expected: FAIL, because `TickStepper` is missing.
- [ ] **Step 3: Implement `TickStepper`:**
  - use the server's `tickRateManager()` freeze plus a single-tick step;
  - gate the local player's tick with a flag that the mixin consults;
  - complete the future after the client's next rendered frame for that tick, reusing
    the M7 readiness checks (sections compiled, frame rendered).
- [ ] **Step 4: Run it and see it pass.** Same command. Expected: PASS.
- [ ] **Step 5: Commit** with message `feat(honey-factory): step the world and the player one frozen tick at a time`.

### Task 2: Motor model

**Files:**
- Create: `packages/honey-factory/src/main/java/io/github/thedeadbolt/honeyfactory/control/MotorCommand.java`
- Create: `packages/honey-factory/src/main/java/io/github/thedeadbolt/honeyfactory/control/MotorLimits.java`
- Create: `packages/honey-factory/src/main/java/io/github/thedeadbolt/honeyfactory/control/MotorState.java`
- Test: `tests/honey-factory/java/io/github/thedeadbolt/honeyfactory/control/MotorStateTest.java`

**Interfaces:**
- Produces:
  - `record MotorCommand(double forward, double strafe, double turnRate, double pitchRate, boolean jump, int delayTicks)`;
    `MotorCommand.IDLE` is all zeros;
  - `record MotorLimits(double maxTurnRate, double maxTurnAccel, double maxPitchRate, double maxPitchAccel)`
    with `DEFAULT = (9.0, 1.8, 6.0, 1.2)` and `void check(MotorCommand)`, which throws
    `IllegalArgumentException` naming the field;
  - `record MotorState(double yawRate, double pitchRate)` with `ZERO`;
  - `Achieved apply(MotorCommand command, MotorLimits limits, float yaw, float pitch)`,
    returning `record Achieved(MotorState state, float yaw, float pitch)`. The new rate
    moves toward the command by at most the acceleration and is capped at the maximum;
    yaw wraps; pitch clamps to [−90, 90] and a clamp zeroes the pitch rate.

- [ ] **Step 1: Write `MotorStateTest`:**
  - from `ZERO`, `turnRate` 9 gives rates 1.8, 3.6, 5.4, 7.2, 9.0, 9.0 over 6 ticks;
  - reversing from 9 to −9 takes 10 ticks;
  - yaw 179 plus rate 2 wraps to −179;
  - pitch at 89.5 with rate 6 clamps to 90 and the pitch rate becomes 0;
  - `check` rejects `forward` 1.5, NaN, infinity, `turnRate` 9.01 and `delayTicks` 1,
    each message naming its field (Review Focus 4);
  - the same inputs always give bit-identical outputs.
- [ ] **Step 2: Run it and see it fail.**
  `./gradlew test --tests '*MotorStateTest'`. Expected: FAIL to compile.
- [ ] **Step 3: Implement the three records.** Use linear slew. Round angles to float only
  at the output, as Minecraft stores them.
- [ ] **Step 4: Run it and see it pass.** Expected: PASS.
- [ ] **Step 5: Commit** with message `feat(honey-factory): add the rate-limited motor model`.

### Task 3: Senses, true state and step records

**Files:**
- Create: `packages/honey-factory/src/main/java/io/github/thedeadbolt/honeyfactory/control/BodySenses.java`
- Create: `packages/honey-factory/src/main/java/io/github/thedeadbolt/honeyfactory/control/TrueState.java`
- Create: `packages/honey-factory/src/main/java/io/github/thedeadbolt/honeyfactory/control/ControlStep.java`
- Test: `tests/honey-factory/java/io/github/thedeadbolt/honeyfactory/control/ControlStepTest.java`

**Interfaces:**
- Consumes: `MotorCommand` and `MotorState` (Task 2); `SampleRecord.Target` and `CameraPose` (existing).
- Produces:
  - `record BodySenses(double forwardSpeed, double strafeSpeed, double verticalSpeed, double yawRate, double pitchRate, float pitch, double[] down, boolean onGround, boolean bumped, boolean inWater, MotorCommand lastCommand)`.
    Speeds are in blocks per tick in the body frame; `down` is the unit vector of world
    down in camera coordinates.
  - `record TrueState(CameraPose pose, double[] velocity, String layoutSha256, long seed, long serverTick, SampleRecord.Target target, boolean targetTouchesEdge)`.
  - `record ControlStep(int index, @Nullable MotorCommand student, @Nullable MotorCommand teacher, Executed executed, MotorState achieved, BodySenses senses, TrueState truth)`,
    with `enum Executed { STUDENT, TEACHER }`.
  - `JsonObject sensesJson()` and `JsonObject truthJson()` on `ControlStep`. These are the
    only serialisations; the server uses `sensesJson` alone for students.

- [ ] **Step 1: Write `ControlStepTest`:**
  - a step's `sensesJson()` contains none of the keys `position`, `yaw`, `target`,
    `layout_sha256` or `velocity`;
  - `truthJson()` contains them;
  - `BodySenses.of(...)` turns world velocity (1, 0, 0) at yaw 90 into a forward speed of
    −1 (yaw 90 faces −X);
  - `down` is (0, −1, 0) at pitch 0 and (0, 0, 1) at pitch 90 in right, up, forward order;
  - a target partly out of frame gives `targetTouchesEdge` true and is still a valid record.
- [ ] **Step 2: Run it and see it fail.** `./gradlew test --tests '*ControlStepTest'`.
- [ ] **Step 3: Implement the records and the factory**
  `static BodySenses of(CameraPose pose, double[] worldVelocity, MotorState achieved, boolean onGround, boolean bumped, boolean inWater, MotorCommand last)`.
- [ ] **Step 4: Run it and see it pass.**
- [ ] **Step 5: Commit** with message `feat(honey-factory): add body senses and true-state records`.

### Task 4: The control loop in the runtime

**Files:**
- Modify: `packages/honey-factory/src/client/java/io/github/thedeadbolt/honeyfactory/client/runtime/EnvironmentRuntime.java`
  (add the control operations; keep the class's operation-coordinator pattern)
- Modify: `packages/honey-factory/src/main/java/io/github/thedeadbolt/honeyfactory/environment/EnvironmentSettings.java`
  (add `MotorLimits motor`, defaulting to `MotorLimits.DEFAULT`)
- Modify: `packages/honey-factory/src/main/java/io/github/thedeadbolt/honeyfactory/environment/OperationKind.java`
  (add `CONTROL_RESET`, `CONTROL_STEP`)
- Test: `tests/honey-factory/gametest/java/io/github/thedeadbolt/honeyfactory/gametest/M11ControlLoopTest.java`

**Interfaces:**
- Consumes: `TickStepper` (Task 1); `MotorCommand`, `MotorLimits`, `MotorState` (Task 2);
  `ControlStep`, `BodySenses`, `TrueState` (Task 3).
- Produces, on `EnvironmentRuntime`:
  - `CompletableFuture<ControlStep> controlReset(ArenaLayout layout, ScenePlan plan, CameraPose start, CaptureProjection projection, long seed)`,
    which returns step 0;
  - `CompletableFuture<ControlStep> controlStep(@Nullable MotorCommand student, @Nullable MotorCommand teacher, ControlStep.Executed executed)`,
    which fails with `EnvironmentException` reason `NOT_RESET` before a reset;
  - `ControlStep lastControlStep()`;
  - `void controlEnd()`, which releases the stepper, restores input and restores the
    user's auto-jump.
  - `EnvironmentException.Reason` gains `NOT_RESET`.

- [ ] **Step 1: Write `M11ControlLoopTest`:**
  - `controlStep` before a reset fails with `NOT_RESET` (Review Focus 5);
  - after a reset, 20 steps of `turnRate` 9 give achieved yaw rates 1.8, 3.6, 5.4, 7.2,
    then 9 each, and the pose's yaw changes by exactly those amounts;
  - `forward` 1 for 20 ticks moves the player along its facing, and the speed rises over
    the first ticks;
  - a step whose executed command is `TEACHER` moves by the teacher's command, while both
    commands are recorded;
  - walking into a wall sets `bumped`;
  - walking out of the arena fails with `OUT_OF_ARENA`;
  - `senses.lastCommand` equals the previous executed command;
  - the frame and mask come back at the projection's size.
- [ ] **Step 2: Run it and see it fail.** `./gradlew runClientGameTest -Dhoneyfactory.gametests=M11ControlLoop`.
- [ ] **Step 3: Implement the operations:**
  - reset reuses the existing reset, then `hold`s;
  - each step checks the command, computes `MotorState.apply`, and in `advance`'s callback
    sets yaw, pitch and the input vector (`forward`, `strafe`, jump) on the player;
  - then capture, as `observe` does, and build the record.
- [ ] **Step 4: Run it and see it pass.**
- [ ] **Step 5: Commit** with message `feat(honey-factory): add the lock-step control loop`.

### Task 5: M7 actions as a macro layer

**Files:**
- Modify: `EnvironmentRuntime.java` (`step(Action, CaptureProjection)` expands into control steps)
- Create: `packages/honey-factory/src/main/java/io/github/thedeadbolt/honeyfactory/control/ActionMacros.java`
- Modify: the M7 gametests that assert instant turns (find them with `grep -rn "Turn(" tests/honey-factory/gametest`)
- Create: `docs/adr/0018-lock-step-motor-control.md`; update `docs/adr/README.md`
- Test: `tests/honey-factory/java/io/github/thedeadbolt/honeyfactory/control/ActionMacrosTest.java`

**Interfaces:**
- Consumes: `MotorCommand` and `MotorLimits` (Task 2).
- Produces: `static List<MotorCommand> expand(Action action, MotorLimits limits)`.
  - `Turn(d)` ramps to the largest reachable rate and back, ending at exactly `d`; the
    final tick carries the remainder.
  - `Look` works the same way.
  - `MoveForward(t)` is `t` ticks of `forward` 1.
  - `Wait(t)` is `t` ticks of `IDLE`.
  - `Stop` is an empty list.
  - `Mine` throws, as before.

- [ ] **Step 1: Write `ActionMacrosTest`:**
  - `Turn(90)` sums to 90.0 within 1e-6, never exceeds 9° per tick, and no two neighbouring
    rates differ by more than 1.8;
  - `Turn(1)` is short and still exact;
  - `MoveForward(5)` gives 5 commands;
  - `Stop` gives none.
- [ ] **Step 2: Run it and see it fail.**
- [ ] **Step 3: Implement `expand`.** A symmetric trapezoid or triangle rate profile,
  computed analytically.
- [ ] **Step 4: Update the M7 gametests** that expect instant turns: a turn now completes
  over several ticks with the same final yaw. Then run `-Dhoneyfactory.gametests=M7` and
  see it pass.
- [ ] **Step 5: Write ADR 0018.** It records: lock-step control, rate limits, the senses
  and true-state split, M7 as macros, and the loopback protocol superseding the spec's
  newline-JSON proposal.
- [ ] **Step 6: Commit** with message `feat(honey-factory): run the M7 actions as macros over control ticks`.

### Task 6: Wire codec

**Files:**
- Create: `packages/honey-factory/src/main/java/io/github/thedeadbolt/honeyfactory/control/ControlProtocol.java`
- Create: `packages/honey-factory/docs/control-protocol.md`
- Test: `tests/honey-factory/java/io/github/thedeadbolt/honeyfactory/control/ControlProtocolTest.java`

**Interfaces:**
- Produces:
  - **Frame layout:** a 4-byte big-endian header length, the UTF-8 JSON header, then the
    binary blobs whose lengths the header lists under `"blobs": [n, ...]`.
  - `record Message(JsonObject header, List<byte[]> blobs)` with
    `static Message read(InputStream in, int maxBytes)` and `void write(OutputStream out)`.
  - **Header `type` values:**
    - `hello`, carrying `version` and `token`, answered by `hello` with `version`,
      `settings` and `privileged`;
    - `reset`, carrying `scene` (a recipe JSON), `seed`, `capture` (`mode`, `width`,
      `height`) and `start`;
    - `step`, carrying `student`, `teacher` and `executed`;
    - `observation`, carrying `index`, `senses`, optionally `truth`, and blobs
      `[rgb, mask]`;
    - `close`;
    - `error`, carrying `code` and `message`.
  - `PROTOCOL_VERSION = 1`.
  - `static MotorCommand command(JsonObject)`, which throws `ContractException` naming the
    field.

- [ ] **Step 1: Write `ControlProtocolTest`:**
  - an `observation` with two blobs round-trips byte for byte;
  - a header larger than `maxBytes` is refused;
  - a truncated stream throws `EOFException`;
  - an unknown `type` and a command with `"turn_rate": "fast"` give errors naming the field
    (Review Focus 4).
- [ ] **Step 2: Run it and see it fail.**
- [ ] **Step 3: Implement the codec.** Write `control-protocol.md` so a client in another
  language could follow it alone: framing, every message and its fields with units,
  error codes, ordering and versioning.
- [ ] **Step 4: Run it and see it pass.**
- [ ] **Step 5: Commit** with message `feat(honey-factory): define the control wire protocol`.

### Task 7: Loopback control server

**Files:**
- Create: `packages/honey-factory/src/client/java/io/github/thedeadbolt/honeyfactory/client/control/ControlServer.java`
- Modify: `packages/honey-factory/src/client/java/io/github/thedeadbolt/honeyfactory/client/runtime/EnvironmentCommands.java`
  (add `/honeyfactory_control start [privileged] | stop | status`)
- Test: `tests/honey-factory/gametest/java/io/github/thedeadbolt/honeyfactory/gametest/M11ServerTest.java`

**Interfaces:**
- Consumes: `ControlProtocol` (Task 6); the runtime's control operations (Task 4).
- Produces:
  - `ControlServer.start(int port, boolean privileged)`. Port 0 picks a free port. It binds
    `InetAddress.getLoopbackAddress()` only.
  - It writes `honey-factory-control.json` (`port`, `token`, `privileged`) into the game
    directory with owner-only permissions, and deletes it on stop.
  - `ControlServer.stop()`.
  - One connection at a time; a second gets `error` `BUSY`.

- [ ] **Step 1: Write `M11ServerTest`**, using a Java socket client in the test:
  - the wrong token gives `error` `UNAUTHORISED`;
  - a second connection gives `BUSY`;
  - a student connection's observations have no `truth`, and a privileged one's do;
  - disconnecting right after sending `step` leaves the runtime idle, with inputs released
    and auto-jump restored, and a new connection can reset (Review Focus 3);
  - a malformed frame closes only that connection;
  - `start` in an unclaimed world fails with `WORLD_NOT_CLAIMED` (Review Focus 5).
- [ ] **Step 2: Run it and see it fail.**
- [ ] **Step 3: Implement the server.** One daemon thread accepts and reads. Requests run
  through the runtime's futures one at a time, and the server answers in order.
- [ ] **Step 4: Run it and see it pass.**
- [ ] **Step 5: Commit** with message `feat(honey-factory): serve the control loop on loopback`.

### Task 8: Python reference client

**Files:**
- Create: `packages/honey-factory-tools/src/honey_factory_tools/control.py`
- Modify: `packages/honey-factory-tools/src/honey_factory_tools/cli.py`
  (add `control-demo --connection FILE --steps N --log FILE --seed S [--client-delay-ms MS]`, the delay slept before each step to prove timing does not matter)
- Test: `tests/honey-factory-tools/test_control.py`

**Interfaces:**
- Consumes: the protocol in `packages/honey-factory/docs/control-protocol.md` (Task 6).
- Produces:
  - `@dataclass(frozen=True) class Command(forward=0.0, strafe=0.0, turn_rate=0.0, pitch_rate=0.0, jump=False, delay_ticks=0)`;
  - `@dataclass(frozen=True) class Observation(index: int, senses: dict[str, Any], truth: dict[str, Any] | None, rgb: bytes, mask: bytes, width: int, height: int)`;
  - `class ControlClient` with `connect(path: Path) -> ControlClient`, a context manager,
    `reset(scene: dict, seed: int, capture: dict, start: dict) -> Observation`,
    `step(student: Command | None = None, teacher: Command | None = None, executed: str = "student") -> Observation`
    and `close()`; errors raise `ControlError(code, message)`;
  - `control-demo` drives a deterministic command schedule from `--seed` (ramped turns,
    start-stop walks, pitch sweeps, jumps). It writes a JSONL log: per step, the executed
    command, plus the SHA-256 of the RGB and mask and the senses.

- [ ] **Step 1: Write `test_control.py`** against a fake server thread in the test, which
  speaks the framing:
  - `reset` then `step` decode senses and blobs;
  - an `error` reply raises `ControlError` with its code;
  - a `Command` with `forward=2` is refused locally before sending;
  - the demo schedule for seed 1 is identical across two runs;
  - the log has one line per step.
- [ ] **Step 2: Run it and see it fail.**
  `uv run --locked --isolated --package honey-factory-tools pytest tests/honey-factory-tools/test_control.py`.
- [ ] **Step 3: Implement `control.py` and the CLI command.** Standard library only.
- [ ] **Step 4: Run it and see it pass.** Also run `uv run ruff check . && uv run ruff format --check . && uv run pyright`.
- [ ] **Step 5: Commit** with message `feat(honey-factory-tools): add the control client and demo`.

### Task 9: Exit test, Python-driven determinism

**Files:**
- Create: `tests/honey-factory/gametest/java/io/github/thedeadbolt/honeyfactory/gametest/M11ExitTest.java`
- Modify: `packages/honey-factory/build.gradle` (forward `honeyfactory.controlSteps`, default 300)

**Interfaces:**
- Consumes: everything above.

- [ ] **Step 1: Write `M11ExitTest`:**
  1. Claim a dataset world and assert the level-1 test scene has no water, lava or fire
     (Review Focus 2).
  2. Start the server in privileged mode, then run
     `uv run honey-factory-tools control-demo --connection <game dir>/honey-factory-control.json --steps N --seed 11 --log run1.jsonl`
     as a subprocess from the repository root. Wait for it to exit with status 0.
  3. Run it again with `--client-delay-ms 50` to `run2.jsonl`.
  4. Assert both logs have identical frame and mask hashes and identical truth at every step.
  5. Replay `run1`'s executed commands in-process through `controlStep` and assert the
     same hashes.
- [ ] **Step 2: Run it.**
  `./gradlew runClientGameTest -Dhoneyfactory.gametests=M11Exit -Dhoneyfactory.controlSteps=1000`.
  Expected: PASS. Record the wall time and the steps per second.
- [ ] **Step 3: Run the full gametest suite** (`./gradlew build runClientGameTest`); it
  runs 300 steps. Expected: PASS.
- [ ] **Step 4: Commit** with message `test(honey-factory): drive the control loop from Python and replay it exactly`.

### Task 10: Documentation and findings

**Files:**
- Modify: `packages/honey-factory/docs/spec.md`:
  - Environment control: the control loop, the senses and true-state split, the server
    replacing the newline-JSON proposal;
  - Initial action model: M7 actions as macros, with turns taking ticks.
- Modify: `packages/honey-factory/docs/roadmap.md` (M11 passed; M14 keeps live speed, DAgger tooling and evaluation, since the basic adapter moved to M11)
- Modify: `docs/architecture.md` (the control API and protocol)
- Modify: `packages/honey-factory/docs/operating.md` (a "Drive the player from Python" section)
- Create: `packages/honey-factory/docs/m11-animal-like-control.md` (findings: limits, the exit run with steps per second, determinism results, anything that diverged)

- [ ] **Step 1: Write the documents.** Use the numbers measured in Task 9.
- [ ] **Step 2: Run all the checks.** `./gradlew build`, `uv run ruff check .`, `uv run pyright`,
  `uv run --locked --isolated --package honey-factory-tools pytest tests/honey-factory-tools`,
  `uv run --locked pytest tests/integration tests/e2e`. Expected: all pass.
- [ ] **Step 3: Commit** with message `docs(honey-factory): record M11 and pass the exit gate`.
