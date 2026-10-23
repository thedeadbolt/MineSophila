# 0007: Dataset world ownership, arena reset and environment coordinator

- **Status:** Accepted
- **Date:** 2026-10-23

## Context

Milestone M2 needs a safe, reproducible world and the skeleton of the synchronous
environment API before bulk generation. The specification requires generation to run
only in dataset-owned worlds, a void arena with a staging platform, template-then-delta
resets, an explicit baseline with restoration, and one operation at a time with
readiness conditions instead of fixed sleeps.

## Decision

- **Dataset worlds come from a world preset.** The mod ships `honey_factory:dataset_world`:
  a normal Overworld (the future level-5 source) plus a void `honey_factory:arena`
  dimension. Presets only apply when a world is created with one, so personal saves never
  gain the dimension. A datapack dimension would have been added to every world.
- **Ownership is an explicit claim.** `/honeyfactory world claim <world name>` requires the
  arena dimension and the world's exact name, then stores a `SavedData` record (world ID,
  name, owned bounds, staging platform, pending baseline). `WorldGuard` re-verifies it
  before every mutation and refuses writes outside the owned bounds.
- **Fixed arena geometry.** The arena core starts at (0, 64, 0). The reset volume adds the
  configured margin on the four horizontal sides and above the core, not below its floor.
  The owned region (-64..319 horizontally, 64..319 vertically) holds every valid
  configuration; the barrier staging platform is outside it.
- **Scenes are pure plans.** A `ScenePlan` is a versioned `Template` plus an ordered block
  delta and environment; its expected `layout-v1` hash covers the reset volume. The
  executor stages players, removes non-player entities, clears the reset volume, restores
  the template, applies the delta with update flags that keep exact states, sets time and
  weather, and compares the read-back hash with the plan's.
- **Baseline with crash-safe restore.** Game rules, the default clock, weather and each
  player's mode and position are snapshotted into the ownership record once per run;
  `/honeyfactory world release` restores them. Players capture in spectator mode.
- **One client-side coordinator.** `EnvironmentRuntime` is the only implementation of
  `reset`, `setViewpoint`, `observe`, `status` and `step`; commands and tests are adapters.
  A pure `OperationCoordinator` enforces the specified states and one operation at a time.
  Server work runs on the server thread; readiness is then polled per client tick with
  settling minimums and timeouts. A reset or viewpoint is ready only when the client's own
  block view hashes to the expected layout, the player is at the pose and the section
  compile queue has stayed empty for two rendered frames. Block updates reach that queue
  only during the next frame, so a single empty check was observed to capture stale
  geometry.
- **Failure handling.** Any failed operation leaves the coordinator `FAILED`; only a reset
  leaves it. Disconnecting fails a running operation and returns to `IDLE`.

## Consequences

- Dataset worlds must be created with the Honey Factory Dataset world type and claimed
  once; renaming the world requires a new claim.
- The arena is reused: its chunks stay force-loaded while the world is used for datasets.
- `observe` still delegates to the M0 capture fixture and `step` rejects every action until
  M3 and M7 replace them.
- Cancellation remains undesigned; a failed operation is recovered by a reset.
