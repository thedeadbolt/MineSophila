# Control protocol, version 1

This document specifies how an external program drives the honey-factory player one tick
at a time, as described in ADR 0018. It is enough on its own to write a client in any
language. The reference client is `honey_factory_tools.control` (Python).

## Connection

- The server runs inside the Minecraft client and is started in a claimed dataset world
  with `/honeyfactory_control start` (student) or `/honeyfactory_control start privileged`.
- It listens only on 127.0.0.1 and accepts one connection at a time; a second connection
  is answered with an `error` (`BUSY`) and closed.
- On start it writes `honey-factory-control.json` into the game directory:

  ```json
  {"port": 50123, "token": "9f2c…", "privileged": false}
  ```

  The file is readable only by its owner and is deleted when the server stops. The token
  changes every time the server starts.

## Framing

Every message, in either direction, is:

| Bytes | Content |
|---|---|
| 4 | header length `n`, unsigned big-endian |
| `n` | header: one UTF-8 JSON object |
| … | binary blobs, back to back, whose byte lengths the header lists in `blobs` |

- A header without `blobs` has no binary part.
- Every header has a string `type`.
- The server refuses messages over 64 MiB and headers over 1 MiB; it closes the connection
  after a framing error.

## Order

Requests and replies alternate strictly. The client sends one request and reads exactly
one reply before sending the next. The world is frozen between requests, so a client may
take as long as it likes.

1. `hello` → `hello`, or `error`. It must come first.
2. `reset` → `observation` (step 0), or `error`.
3. `step` → `observation` (the next step), or `error`. This repeats.
4. `close` → `close`. The server then ends the control session, unfreezes the world and
   closes the connection.

An `error` leaves the connection open unless the error was in the framing. A failed
`step` (for example `OUT_OF_ARENA`) needs a new `reset`: until then further steps fail with
`INVALID_STATE`.

## Messages

### `hello` (client → server)

| Field | Type | Meaning |
|---|---|---|
| `version` | integer | protocol version, `1` |
| `token` | string | the token from `honey-factory-control.json` |

### `hello` (server → client)

| Field | Type | Meaning |
|---|---|---|
| `version` | integer | `1` |
| `privileged` | boolean | whether observations carry the true state |
| `settings` | object | `motor`: `max_turn_rate`, `max_turn_accel`, `max_pitch_rate`, `max_pitch_accel` (degrees per tick and per tick²); `auto_jump` (boolean); `eye_height_blocks` |

### `reset` (client → server)

| Field | Type | Meaning |
|---|---|---|
| `scene` | object | a scene recipe, as in a dataset configuration's `scene` section (for example `{"preset": "level1"}`); platform bases only |
| `seed` | integer | dataset seed, 0 to 2⁵³−1 |
| `scene_index` | integer | which scene of that seed, default 0; the target is always present |
| `capture` | object | `mode` (`PERSPECTIVE_90` or `PANORAMA_310`), `width`, `height`, and `cube_face_size` for panoramas |
| `start` | object, optional | player feet `x`, `y`, `z` in blocks relative to the arena core's minimum corner, `yaw` and `pitch` in degrees; default: the scene's first viewpoint |

### `step` (client → server)

| Field | Type | Meaning |
|---|---|---|
| `student` | command or null | the student's command |
| `teacher` | command or null | the teacher's command; privileged connections only |
| `executed` | string | `"student"` (default) or `"teacher"`: whose command runs |

A **command** is an object. Absent fields are 0 or false:

| Field | Range | Unit | Meaning |
|---|---|---|---|
| `forward` | [−1, 1] | — | desired forward (+) or backward speed, through the player's input |
| `strafe` | [−1, 1] | — | desired sideways speed, + to the right |
| `turn_rate` | ±`max_turn_rate` | degrees per tick | desired yaw rate, + turns right |
| `pitch_rate` | ±`max_pitch_rate` | degrees per tick | desired pitch rate, + looks down |
| `jump` | boolean | — | press jump this tick |
| `delay_ticks` | 0 | ticks | reserved for live prediction; must be 0 |

The body's actual rates move toward the commanded rates by at most `max_*_accel` per tick.
Walking follows Minecraft's own acceleration and friction.

### `observation` (server → client)

| Field | Type | Meaning |
|---|---|---|
| `index` | integer | step number, 0 after a reset |
| `width`, `height` | integer | frame size in pixels |
| `senses` | object | below |
| `commands` | object | privileged only: `student`, `teacher`, `executed`, and `achieved` (`yaw_rate`, `pitch_rate`) |
| `truth` | object | privileged only: below |

**Blobs.** A student connection receives `[rgb]`. A privileged connection receives
`[rgb, visible_mask, unoccluded_mask]`.

- `rgb` is `width × height × 3` bytes, row-major, top row first, R G B.
- Each mask is `width × height` bytes, 255 where the target is and 0 elsewhere.

A student never receives masks, because a mask shows where the honey is.

**`senses`** is what the body feels:

| Field | Unit | Meaning |
|---|---|---|
| `forward_speed`, `strafe_speed` | blocks per tick | motion along the body's horizontal facing and to its right |
| `vertical_speed` | blocks per tick | upward motion |
| `yaw_rate`, `pitch_rate` | degrees per tick | turning and looking rates |
| `pitch` | degrees | head pitch, + down |
| `down` | unit vector | world down in camera coordinates (right, up, forward) |
| `on_ground`, `bumped`, `in_water` | boolean | contact this tick |
| `last_command` | command | the command executed last step |

**`truth`** is the world as it is:

| Field | Meaning |
|---|---|
| `position` | player feet position, world blocks |
| `yaw`, `pitch` | degrees |
| `velocity` | blocks per tick |
| `layout_sha256`, `seed`, `server_tick` | the scene and the time |
| `target` | the dataset contract's target object (presence, pixel counts, status, box, centroid, distance) |
| `target_touches_edge` | whether the target's mask reaches the frame edge |

### `close`

Either direction, no fields. The server replies `close` and ends the session.

### `error` (server → client)

| Field | Meaning |
|---|---|
| `code` | below |
| `message` | a human-readable explanation |

| Code | When |
|---|---|
| `BAD_MESSAGE` | unknown `type`, a missing or mistyped field, or a request out of order |
| `UNAUTHORISED` | wrong token or version |
| `BUSY` | another connection is active |
| `NOT_RESET` | `step` before a successful `reset` |
| `INVALID_STATE` | `step` after a failed step, before the next `reset` |
| `INVALID_REQUEST` | a command outside the limits, or a teacher command from a student connection |
| `OUT_OF_ARENA` | the step took the player out of the arena |
| `UNSUPPORTED` | a natural-terrain scene, or another feature this version lacks |
| `TIMEOUT` | the game did not become ready in time |
| `NOT_OWNED` | the world stopped being a claimed dataset world |
| `BUSY` (after `hello`) | the game is running another environment operation |
| `INTERNAL` | anything else |

## Versioning

The server rejects a `hello` whose `version` differs from its own. A new version is
introduced only for incompatible changes. New optional fields may appear in replies within
a version, and clients must ignore fields they do not know.
