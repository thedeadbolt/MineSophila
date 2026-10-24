# 0013: Dataset throughput: batched viewpoints, parallel writes and patch preparation

- **Status:** Accepted
- **Date:** 2026-10-24

## Context

A per-phase profile of 8-scene datasets at the default sizes showed most of the time spent
waiting, not rendering: 0.42–0.88 s per image, of which moving the camera through the
server and letting the client settle took about 0.2 s, waiting for the next tick before a
capture up to 0.1 s, PNG encoding 0.06–0.14 s, and preparing a natural patch 13 s, during
which the server thread generated 676 chunks one at a time.

## Decision

- **Batched viewpoints.** A scene captures all its viewpoints in one operation
  (`observeAll`). For each pose the client moves its own player, the server following as
  it does any spectator, and the game's frame loop renders until the pose is reached and
  every section is compiled for two frames; the capture then runs between frames. Rejected
  viewpoints are re-drawn and captured in further rounds, so each viewpoint ends on the
  same draw as before. Rendering frames by hand from a moved camera was tried and rejected:
  parts of the renderer stay anchored to the player's real position.
- **Equivalence is tested**: batched captures match captures after a server-side move byte
  for byte in a level-4 scene; on natural terrain the masks match and RGB differs only as
  two ordinary captures a few ticks apart do, from animated textures.
- **Parallel image writes.** Accepted images are encoded and written on a small worker pool
  while later rounds capture; a scene is appended only once all its writes finish.
- **Patch preparation off the server thread.** Each candidate's chunks get a
  `honey_factory:source_patch` ticket and are requested together from a loader thread, so
  they generate in parallel; Minecraft's own temporary ticket expires after one tick and
  dropped chunks still generating. Candidates are screened with noise heights first, and
  the next patch is prepared in the background while the current one captures.
- **Timings are recorded** per phase and logged at the end of every run.

## Consequences

- Per image at the default sizes: level 4 0.09 s (perspective) and 0.21 s (panorama),
  level 5 0.26 s and 0.40 s, against 0.42–0.88 s before.
- During a batched capture the server's player follows the client, so the world is no
  longer frozen between viewpoints of one scene; this changes nothing visible on platforms
  and only animated textures on terrain.
- Running several game instances on separate saves would scale further, since the GPU is
  mostly idle; that needs dataset sharding and is left for later.
