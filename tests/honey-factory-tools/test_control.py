import json
import socket
import struct
import threading
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pytest

from honey_factory_tools import control
from honey_factory_tools.cli import main

TOKEN = "a" * 64


def _send(
    connection: socket.socket, header: dict[str, Any], blobs: list[bytes] | None = None
) -> None:
    blobs = blobs or []
    if blobs:
        header = {**header, "blobs": [len(blob) for blob in blobs]}
    text = json.dumps(header).encode()
    connection.sendall(struct.pack(">I", len(text)) + text + b"".join(blobs))


def _receive(connection: socket.socket) -> dict[str, Any] | None:
    size = connection.recv(4, socket.MSG_WAITALL)
    if len(size) < 4:
        return None
    (length,) = struct.unpack(">I", size)
    return json.loads(connection.recv(length, socket.MSG_WAITALL))


class FakeServer:
    """Answers hello, reset and step like a student control server with 2x2 frames."""

    def __init__(self, privileged: bool = False) -> None:
        self.privileged = privileged
        self.listener = socket.create_server(("127.0.0.1", 0))
        self.port = self.listener.getsockname()[1]
        self.requests: list[dict[str, Any]] = []
        self.thread = threading.Thread(target=self._serve, daemon=True)
        self.thread.start()

    def _serve(self) -> None:
        connection, _ = self.listener.accept()
        with connection:
            index = 0
            while (request := _receive(connection)) is not None:
                self.requests.append(request)
                kind = request["type"]
                if kind == "hello":
                    if request["token"] != TOKEN:
                        _send(
                            connection, {"type": "error", "code": "UNAUTHORISED", "message": "no"}
                        )
                        continue
                    _send(
                        connection,
                        {
                            "type": "hello",
                            "version": 1,
                            "privileged": self.privileged,
                            "settings": {
                                "motor": {
                                    "max_turn_rate": 9.0,
                                    "max_turn_accel": 1.8,
                                    "max_pitch_rate": 6.0,
                                    "max_pitch_accel": 1.2,
                                },
                                "auto_jump": True,
                                "eye_height_blocks": 1.62,
                            },
                        },
                    )
                elif kind == "close":
                    _send(connection, {"type": "close"})
                    return
                elif kind == "step" and request["student"].get("turn_rate", 0) < 0:
                    _send(
                        connection, {"type": "error", "code": "OUT_OF_ARENA", "message": "fell off"}
                    )
                else:
                    index = 0 if kind == "reset" else index + 1
                    header = {
                        "type": "observation",
                        "index": index,
                        "width": 2,
                        "height": 2,
                        "senses": {
                            "forward_speed": 0.1 * index,
                            "last_command": request.get("student"),
                        },
                    }
                    blobs = [bytes([index] * 12)]
                    if self.privileged:
                        header |= {"truth": {"yaw": 0.0}, "commands": {"executed": "student"}}
                        blobs += [bytes(4), bytes(4)]
                    _send(connection, header, blobs)

    def connection_file(self, tmp_path: Path) -> Path:
        path = tmp_path / "honey-factory-control.json"
        path.write_text(
            json.dumps({"port": self.port, "token": TOKEN, "privileged": self.privileged})
        )
        return path


@pytest.fixture
def server() -> Iterator[FakeServer]:
    fake = FakeServer()
    yield fake
    fake.listener.close()


SCENE = {"preset": "level1"}
CAPTURE = {"mode": "PERSPECTIVE_90", "width": 2, "height": 2}


def test_reset_and_step_decode_senses_and_frames(server: FakeServer, tmp_path: Path) -> None:
    with control.ControlClient.connect(server.connection_file(tmp_path)) as client:
        assert client.settings["motor"]["max_turn_rate"] == 9.0
        first = client.reset(SCENE, seed=3, capture=CAPTURE)
        step = client.step(control.Command(forward=1.0, turn_rate=2.0))
    assert (first.index, step.index) == (0, 1)
    assert step.rgb == bytes([1] * 12)
    assert (step.width, step.height) == (2, 2)
    assert step.mask is None, "a student sees no mask"
    assert step.truth is None, "a student sees no truth"
    assert step.senses["last_command"] == {
        "forward": 1.0,
        "strafe": 0.0,
        "turn_rate": 2.0,
        "pitch_rate": 0.0,
        "jump": False,
        "delay_ticks": 0,
    }
    assert server.requests[-1]["type"] == "close"


def test_privileged_observations_carry_masks_and_truth(tmp_path: Path) -> None:
    fake = FakeServer(privileged=True)
    with control.ControlClient.connect(fake.connection_file(tmp_path)) as client:
        observation = client.reset(SCENE, seed=3, capture=CAPTURE)
    assert observation.truth == {"yaw": 0.0}
    assert observation.mask == bytes(4)
    assert observation.unoccluded_mask == bytes(4)


def test_errors_raise_control_error_with_their_code(server: FakeServer, tmp_path: Path) -> None:
    with control.ControlClient.connect(server.connection_file(tmp_path)) as client:
        client.reset(SCENE, seed=3, capture=CAPTURE)
        with pytest.raises(control.ControlError) as raised:
            client.step(control.Command(turn_rate=-1.0))
    assert raised.value.code == "OUT_OF_ARENA"


def test_commands_outside_their_ranges_are_refused_locally() -> None:
    with pytest.raises(ValueError, match="forward"):
        control.Command(forward=2.0)
    with pytest.raises(ValueError, match="strafe"):
        control.Command(strafe=float("nan"))
    with pytest.raises(ValueError, match="delay_ticks"):
        control.Command(delay_ticks=1)


def test_turn_rates_beyond_the_server_limits_are_refused_before_sending(
    server: FakeServer, tmp_path: Path
) -> None:
    with control.ControlClient.connect(server.connection_file(tmp_path)) as client:
        client.reset(SCENE, seed=3, capture=CAPTURE)
        sent = len(server.requests)
        with pytest.raises(ValueError, match="turn_rate"):
            client.step(control.Command(turn_rate=9.5))
        assert len(server.requests) == sent


def test_demo_schedule_is_deterministic_and_within_limits() -> None:
    first = control.demo_schedule(seed=1, steps=500, max_turn_rate=9.0, max_pitch_rate=6.0)
    assert first == control.demo_schedule(seed=1, steps=500, max_turn_rate=9.0, max_pitch_rate=6.0)
    assert first != control.demo_schedule(seed=2, steps=500, max_turn_rate=9.0, max_pitch_rate=6.0)
    assert len(first) == 500
    assert all(abs(c.turn_rate) <= 9.0 and abs(c.pitch_rate) <= 6.0 for c in first)
    assert {c.jump for c in first} == {True, False}, "the schedule jumps sometimes"
    assert any(c.forward < 0 for c in first)
    assert any(c.forward > 0 for c in first)


def test_control_demo_writes_one_log_line_per_step(tmp_path: Path) -> None:
    fake = FakeServer()
    log = tmp_path / "run.jsonl"
    status = main(
        [
            "control-demo",
            "--connection",
            str(fake.connection_file(tmp_path)),
            "--steps",
            "4",
            "--seed",
            "1",
            "--log",
            str(log),
            "--width",
            "2",
            "--height",
            "2",
        ]
    )
    assert status == 0
    lines = [json.loads(line) for line in log.read_text().splitlines()]
    assert [line["index"] for line in lines] == [0, 1, 2, 3, 4]
    assert lines[1]["rgb_sha256"] != lines[0]["rgb_sha256"]
    assert lines[0]["command"] is None
    assert lines[1]["command"]["delay_ticks"] == 0
