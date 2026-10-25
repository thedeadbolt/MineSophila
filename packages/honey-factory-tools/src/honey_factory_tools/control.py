"""Reference client for the honey-factory control server (protocol version 1).

The protocol is specified in ``packages/honey-factory/docs/control-protocol.md``: each
message is a 4-byte big-endian header length, a UTF-8 JSON header, then binary blobs whose
lengths the header lists under ``blobs``. Requests and replies alternate strictly, and the
world stays frozen while the client thinks.
"""

import hashlib
import json
import math
import random
import socket
import struct
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from types import TracebackType
from typing import Any, Self, cast

PROTOCOL_VERSION = 1
MAX_MESSAGE_BYTES = 64 << 20

type Json = dict[str, Any]


class ControlError(Exception):
    """An ``error`` reply from the server."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message


@dataclass(frozen=True)
class Command:
    """One tick's motor command; rates are degrees per tick, positive right and down."""

    forward: float = 0.0
    strafe: float = 0.0
    turn_rate: float = 0.0
    pitch_rate: float = 0.0
    jump: bool = False
    delay_ticks: int = 0

    def __post_init__(self) -> None:
        for name in ("forward", "strafe", "turn_rate", "pitch_rate"):
            value = cast(float, getattr(self, name))
            if not math.isfinite(value):
                raise ValueError(f"{name} must be finite, got {value}")
        for name in ("forward", "strafe"):
            value = cast(float, getattr(self, name))
            if abs(value) > 1.0:
                raise ValueError(f"{name} must lie within [-1, 1], got {value}")
        if self.delay_ticks != 0:
            raise ValueError(f"delay_ticks must be 0 in protocol version 1, got {self.delay_ticks}")

    def to_json(self) -> Json:
        return asdict(self)


@dataclass(frozen=True)
class Observation:
    """One step as the server sent it. ``truth``, ``commands`` and the masks are present
    only on a privileged connection."""

    index: int
    width: int
    height: int
    senses: Json
    rgb: bytes
    truth: Json | None = None
    commands: Json | None = None
    mask: bytes | None = None
    unoccluded_mask: bytes | None = None


class ControlClient:
    """A connection to a running control server, from its connection file."""

    def __init__(self, sock: socket.socket, token: str) -> None:
        self._socket = sock
        reply, _ = self._request({"type": "hello", "version": PROTOCOL_VERSION, "token": token})
        self.privileged = bool(reply["privileged"])
        self.settings: Json = reply["settings"]

    @classmethod
    def connect(cls, connection_file: Path, timeout: float = 300.0) -> Self:
        details = json.loads(connection_file.read_text(encoding="utf-8"))
        sock = socket.create_connection(("127.0.0.1", int(details["port"])), timeout=timeout)
        try:
            return cls(sock, str(details["token"]))
        except BaseException:
            sock.close()
            raise

    def __enter__(self) -> Self:
        return self

    def __exit__(
        self,
        kind: type[BaseException] | None,
        error: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        self.close()

    def reset(
        self, scene: Json, seed: int, capture: Json, start: Json | None = None, scene_index: int = 0
    ) -> Observation:
        request: Json = {
            "type": "reset",
            "scene": scene,
            "seed": seed,
            "scene_index": scene_index,
            "capture": capture,
        }
        if start is not None:
            request["start"] = start
        return self._observation(request)

    def step(
        self,
        student: Command | None = None,
        teacher: Command | None = None,
        executed: str = "student",
    ) -> Observation:
        motor: Json = self.settings.get("motor", {})
        for command in (student, teacher):
            if command is None:
                continue
            for name, limit in (("turn_rate", "max_turn_rate"), ("pitch_rate", "max_pitch_rate")):
                value = cast(float, getattr(command, name))
                if limit in motor and abs(value) > float(motor[limit]):
                    raise ValueError(f"{name} {value} exceeds the server's {limit} {motor[limit]}")
        return self._observation(
            {
                "type": "step",
                "student": None if student is None else student.to_json(),
                "teacher": None if teacher is None else teacher.to_json(),
                "executed": executed,
            }
        )

    def close(self) -> None:
        try:
            self._request({"type": "close"})
        except (OSError, ControlError):
            pass
        finally:
            self._socket.close()

    def _observation(self, request: Json) -> Observation:
        header, blobs = self._request(request)
        masks = blobs[1:] if len(blobs) >= 3 else [None, None]
        return Observation(
            index=int(header["index"]),
            width=int(header["width"]),
            height=int(header["height"]),
            senses=header["senses"],
            rgb=blobs[0],
            truth=header.get("truth"),
            commands=header.get("commands"),
            mask=masks[0],
            unoccluded_mask=masks[1],
        )

    def _request(self, header: Json) -> tuple[Json, list[bytes]]:
        text = json.dumps(header).encode()
        self._socket.sendall(struct.pack(">I", len(text)) + text)
        reply, blobs = self._read()
        if reply.get("type") == "error":
            raise ControlError(str(reply.get("code")), str(reply.get("message")))
        return reply, blobs

    def _read(self) -> tuple[Json, list[bytes]]:
        (length,) = struct.unpack(">I", self._exactly(4))
        if length > MAX_MESSAGE_BYTES:
            raise OSError(f"a {length}-byte header is too large")
        header: Json = json.loads(self._exactly(length))
        return header, [self._exactly(int(size)) for size in header.get("blobs", [])]

    def _exactly(self, size: int) -> bytes:
        chunks: list[bytes] = []
        while size:
            chunk = self._socket.recv(min(size, 1 << 20))
            if not chunk:
                raise OSError("the server closed the connection")
            chunks.append(chunk)
            size -= len(chunk)
        return b"".join(chunks)


def demo_schedule(
    seed: int, steps: int, max_turn_rate: float, max_pitch_rate: float
) -> list[Command]:
    """A reproducible mix of ramped turns, walks that start and stop, pitch sweeps, jumps and
    pauses. Walks forward are followed by as long a walk back, so the body stays near its start."""
    rng = random.Random(seed)
    commands: list[Command] = []
    while len(commands) < steps:
        kind = rng.choice(("turn", "walk", "look", "jump", "pause"))
        if kind == "turn":
            rate = rng.uniform(0.3, 1.0) * max_turn_rate * rng.choice((-1, 1))
            commands += [Command(turn_rate=rate)] * rng.randint(4, 15) + [Command()] * 3
        elif kind == "walk":
            ticks = rng.randint(5, 15)
            speed = rng.uniform(0.4, 1.0)
            commands += [Command(forward=speed)] * ticks + [Command(forward=-speed)] * ticks
        elif kind == "look":
            rate = rng.uniform(0.3, 1.0) * max_pitch_rate
            ticks = rng.randint(3, 8)
            commands += [Command(pitch_rate=rate)] * ticks + [Command(pitch_rate=-rate)] * ticks
        elif kind == "jump":
            commands += [Command(jump=True)] + [Command()] * 10
        else:
            commands += [Command()] * rng.randint(2, 6)
    return commands[:steps]


def run_demo(
    connection_file: Path,
    steps: int,
    seed: int,
    log: Path,
    capture: Json,
    scene: Json,
    client_delay_ms: int = 0,
) -> None:
    """Drives ``steps`` steps of the demo schedule and logs every observation as JSONL."""
    with ControlClient.connect(connection_file) as client, log.open("w", encoding="utf-8") as out:
        motor: Json = client.settings["motor"]
        schedule = demo_schedule(
            seed, steps, float(motor["max_turn_rate"]), float(motor["max_pitch_rate"])
        )
        _log(out, client.reset(scene, seed=seed, capture=capture), None)
        for command in schedule:
            if client_delay_ms:
                time.sleep(client_delay_ms / 1000)
            _log(out, client.step(command), command)


def _log(out: Any, observation: Observation, command: Command | None) -> None:
    line: Json = {
        "index": observation.index,
        "command": None if command is None else command.to_json(),
        "rgb_sha256": hashlib.sha256(observation.rgb).hexdigest(),
        "mask_sha256": None
        if observation.mask is None
        else hashlib.sha256(observation.mask).hexdigest(),
        "senses": observation.senses,
        "truth": observation.truth,
    }
    out.write(json.dumps(line, sort_keys=True) + "\n")
