"""The local visual inspector: a small HTTP server and a browser page, standard library only.

The server listens on the loopback interface only and serves one dataset read-only. Every
request must name this host and port, and API requests must carry the session token that
the page is served with, so other web pages cannot read the dataset or record reviews
through the browser. Images are streamed from the dataset only after the sample passes its
integrity check; overlays and zoom are drawn by the page and never stored.

API (JSON unless noted):

    GET  /api/dataset               manifest summary, index and validation errors, options
    GET  /api/samples?...           a filtered selection (see ``selection``); paged
    GET  /api/samples/<id>          one sample with its scene, errors, reviews and neighbours
    GET  /api/scenes/<id>           one scene with all of its samples
    GET  /api/reviews               the review summary and every review of this manifest
    POST /api/reviews               record a review: kind, id, outcome, reason, note, selection
    GET  /image/<sample id>/rgb|mask   the stored PNG, or 409 with its integrity errors
"""

import json
import secrets
import threading
from dataclasses import asdict
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from importlib import resources
from typing import Any, cast
from urllib.parse import parse_qs, unquote, urlsplit

from honey_factory_tools import __version__, selection
from honey_factory_tools.catalog import Catalog, IntegrityError
from honey_factory_tools.reviews import OUTCOMES, REASONS, ReviewError, ReviewStore

type Record = dict[str, Any]

STATUSES = (
    "absent",
    "out_of_view",
    "subpixel",
    "fully_occluded",
    "partially_visible",
    "fully_visible",
)
SPLITS = ("train", "validation", "test")
PAGE_LIMIT = 500
_STATIC = {
    "/": ("index.html", "text/html; charset=utf-8"),
    "/static/app.js": ("app.js", "text/javascript; charset=utf-8"),
    "/static/app.css": ("app.css", "text/css; charset=utf-8"),
}
_INT_FILTERS = ("viewpoint", "min_pixels", "max_pixels")
_FLOAT_FILTERS = ("min_ratio", "max_ratio", "min_distance", "max_distance")
_TEXT_FILTERS = ("split", "status", "scene_id", "sample_id")


class Inspector:
    """The inspector's state: one catalog, optional reviews and the session token."""

    def __init__(self, catalog: Catalog, reviews: ReviewStore | None) -> None:
        self.catalog = catalog
        self.reviews = reviews
        self.token = secrets.token_urlsafe(24)

    def dataset(self) -> Record:
        manifest = self.catalog.manifest
        validation = self.catalog.validation
        return {
            "dataset_id": self.catalog.dataset_id,
            "directory": str(self.catalog.root),
            "manifest_sha256": self.catalog.manifest_sha256,
            "inspector_version": __version__,
            "lifecycle": manifest["lifecycle"],
            "difficulty": manifest["difficulty"],
            "capture": {
                key: manifest["capture"][key] for key in ("mode", "width", "height", "projection")
            },
            "target_block": manifest["target"]["block_id"],
            "counts": manifest["counts"],
            "splits": manifest["splits"],
            "software": manifest["software"],
            "render_environment": manifest["render_environment"],
            "scene_count": len(self.catalog.scenes),
            "sample_count": len(self.catalog.samples),
            "index_errors": self.catalog.index_errors,
            "validation": {
                "state": validation.state,
                "dataset_errors": validation.dataset_errors,
                "error_count": validation.error_count,
                "subjects_with_errors": sorted(validation.subject_errors),
            },
            "reviews": None
            if self.reviews is None
            else {"path": str(self.reviews.path), "reviewer": self.reviews.reviewer},
            "options": {
                "statuses": STATUSES,
                "splits": SPLITS,
                "modes": selection.MODES,
                "strata": selection.STRATA,
                "outcomes": OUTCOMES,
                "reasons": REASONS,
            },
        }

    def samples(self, query: dict[str, list[str]]) -> Record:
        chosen = parse_selection(query)
        picked = selection.select(self.catalog.manifest, self.catalog.samples, chosen)
        offset = _int(query, "offset") or 0
        limit = min(_int(query, "limit") or PAGE_LIMIT, PAGE_LIMIT)
        seams = selection.seam_columns(self.catalog.manifest)
        latest = self.reviews.latest() if self.reviews else {}
        return {
            "selection": chosen.to_json(),
            "total": len(picked),
            "offset": offset,
            "samples": [
                self._summary(sample, seams, latest, chosen.low_pixels)
                for sample in picked[offset : offset + limit]
            ],
        }

    def sample(self, sample_id: str) -> Record | None:
        sample = self.catalog.by_sample.get(sample_id)
        if sample is None:
            return None
        order = self.catalog.samples
        index = order.index(sample)
        reviews = self._reviews("sample", sample_id)
        return {
            "sample": sample,
            "scene": self.catalog.scenes.get(sample["scene_id"]),
            "errors": self.catalog.sample_errors(sample_id),
            "integrity": self.catalog.integrity(sample_id),
            "previous": order[index - 1]["sample_id"] if index > 0 else None,
            "next": order[index + 1]["sample_id"] if index + 1 < len(order) else None,
            "reviews": reviews,
            "replay_command": self._replay(sample_id),
        }

    def scene(self, scene_id: str) -> Record | None:
        scene = self.catalog.scenes.get(scene_id)
        if scene is None:
            return None
        ids = self.catalog.scene_ids
        index = ids.index(scene_id)
        seams = selection.seam_columns(self.catalog.manifest)
        latest = self.reviews.latest() if self.reviews else {}
        return {
            "scene": scene,
            "samples": [
                self._summary(sample, seams, latest, selection.DEFAULT_LOW_PIXELS)
                for sample in self.catalog.scene_samples.get(scene_id, [])
            ],
            "errors": self.catalog.validation.subject_errors.get(scene_id, []),
            "previous": ids[index - 1] if index > 0 else None,
            "next": ids[index + 1] if index + 1 < len(ids) else None,
            "reviews": self._reviews("scene", scene_id),
            "replay_command": self._replay(scene_id),
        }

    def record_review(self, body: Record) -> Record:
        if self.reviews is None:
            raise ReviewError("reviews are disabled; start the inspector with --reviews DIR")
        note = body.get("note", "")
        chosen = body.get("selection")
        if not isinstance(note, str) or (chosen is not None and not isinstance(chosen, dict)):
            raise ReviewError("note must be a string and selection an object")
        return self.reviews.record(
            str(body.get("kind")),
            str(body.get("id")),
            str(body.get("outcome")),
            str(body.get("reason")),
            note,
            cast(Record | None, chosen),
        )

    def review_report(self) -> Record:
        if self.reviews is None:
            return {"enabled": False}
        return {
            "enabled": True,
            "path": str(self.reviews.path),
            "summary": asdict(self.reviews.summary()),
            "reviews": self.reviews.all(),
        }

    def _summary(
        self,
        sample: Record,
        seams: list[float],
        latest: dict[tuple[str, str], Record],
        low_pixels: int,
    ) -> Record:
        target: Record = sample["target"]
        review = latest.get(("sample", sample["sample_id"]))
        return {
            "sample_id": sample["sample_id"],
            "scene_id": sample["scene_id"],
            "split": sample["split"],
            "viewpoint_index": sample["viewpoint_index"],
            "target_status": target["target_status"],
            "visibility_ratio": target["visibility_ratio"],
            "visible_pixels": target["visible_pixels"],
            "distance_blocks": target.get("distance_blocks"),
            "stratum": selection.stratum(sample, seams, low_pixels),
            "integrity": self.catalog.integrity(sample["sample_id"]),
            "review": None if review is None else review["outcome"],
        }

    def _reviews(self, kind: str, subject_id: str) -> list[Record]:
        if self.reviews is None:
            return []
        return [
            review
            for review in self.reviews.all()
            if review["subject"]["kind"] == kind and review["subject"]["id"] == subject_id
        ]

    def _replay(self, subject_id: str) -> str:
        return f"/honeyfactory_replay load {subject_id} {self.catalog.root}"


def parse_selection(query: dict[str, list[str]]) -> selection.Selection:
    """A ``Selection`` from query parameters; raises ``ValueError`` for a malformed one."""
    filters: dict[str, Any] = {}
    for name in _TEXT_FILTERS:
        if value := _text(query, name):
            filters[name] = value
    for name in _INT_FILTERS:
        if (value := _int(query, name)) is not None:
            filters[name] = value
    for name in _FLOAT_FILTERS:
        if (value := _float(query, name)) is not None:
            filters[name] = value
    if present := _text(query, "present"):
        if present not in ("true", "false"):
            raise ValueError("present must be true or false")
        filters["present"] = present == "true"
    return selection.Selection(
        mode=_text(query, "mode") or "random",
        seed=_int(query, "seed") or 0,
        count=_int(query, "count"),
        filters=selection.Filters(**filters),
        low_pixels=_int(query, "low_pixels") or selection.DEFAULT_LOW_PIXELS,
    )


def _text(query: dict[str, list[str]], name: str) -> str | None:
    values = query.get(name)
    return values[-1].strip() or None if values else None


def _int(query: dict[str, list[str]], name: str) -> int | None:
    value = _text(query, name)
    if value is None:
        return None
    try:
        return int(value)
    except ValueError:
        raise ValueError(f"{name} must be a whole number") from None


def _float(query: dict[str, list[str]], name: str) -> float | None:
    value = _text(query, name)
    if value is None:
        return None
    try:
        return float(value)
    except ValueError:
        raise ValueError(f"{name} must be a number") from None


def make_server(inspector: Inspector, port: int = 0) -> ThreadingHTTPServer:
    """A server on 127.0.0.1 (``port`` 0 picks a free one); call ``serve_forever`` to run it."""

    class Handler(BaseHTTPRequestHandler):
        server_version = f"honey-factory-inspector/{__version__}"

        def log_message(self, format: str, *args: Any) -> None:
            pass

        def do_GET(self) -> None:
            if not self._host_allowed():
                return
            url = urlsplit(self.path)
            path = unquote(url.path)
            if path in _STATIC:
                name, content_type = _STATIC[path]
                text = resources.files("honey_factory_tools").joinpath("static", name).read_text()
                self._send(HTTPStatus.OK, text.replace("{{TOKEN}}", inspector.token), content_type)
                return
            if not self._token_valid():
                return
            try:
                self._get(path, parse_qs(url.query))
            except ValueError as error:
                self._json(HTTPStatus.BAD_REQUEST, {"error": str(error)})

        def _get(self, path: str, query: dict[str, list[str]]) -> None:
            parts = path.strip("/").split("/")
            if path == "/api/dataset":
                self._json(HTTPStatus.OK, inspector.dataset())
            elif path == "/api/samples":
                self._json(HTTPStatus.OK, inspector.samples(query))
            elif path == "/api/reviews":
                self._json(HTTPStatus.OK, inspector.review_report())
            elif len(parts) == 3 and parts[:2] == ["api", "samples"]:
                self._found(inspector.sample(parts[2]), f"no sample {parts[2]}")
            elif len(parts) == 3 and parts[:2] == ["api", "scenes"]:
                self._found(inspector.scene(parts[2]), f"no scene {parts[2]}")
            elif len(parts) == 3 and parts[0] == "image" and parts[2] in ("rgb", "mask"):
                if parts[1] not in inspector.catalog.by_sample:
                    self._json(HTTPStatus.NOT_FOUND, {"error": f"no sample {parts[1]}"})
                    return
                try:
                    data = inspector.catalog.image(parts[1], parts[2])
                except IntegrityError as error:
                    self._json(HTTPStatus.CONFLICT, {"errors": error.errors})
                    return
                self._send(HTTPStatus.OK, data, "image/png")
            else:
                self._json(HTTPStatus.NOT_FOUND, {"error": f"no such resource {path}"})

        def do_POST(self) -> None:
            if not self._host_allowed() or not self._token_valid():
                return
            if urlsplit(self.path).path != "/api/reviews":
                self._json(HTTPStatus.NOT_FOUND, {"error": "only /api/reviews accepts POST"})
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                body = json.loads(self.rfile.read(min(length, 65536)) or b"{}")
                if not isinstance(body, dict):
                    raise ReviewError("the body must be a JSON object")
                review = inspector.record_review(cast(Record, body))
            except (ReviewError, ValueError) as error:
                disabled = inspector.reviews is None
                status = HTTPStatus.FORBIDDEN if disabled else HTTPStatus.BAD_REQUEST
                self._json(status, {"error": str(error)})
                return
            self._json(HTTPStatus.CREATED, review)

        def _found(self, value: Record | None, missing: str) -> None:
            if value is None:
                self._json(HTTPStatus.NOT_FOUND, {"error": missing})
            else:
                self._json(HTTPStatus.OK, value)

        def _host_allowed(self) -> bool:
            port = cast(tuple[str, int], self.server.server_address)[1]
            if self.headers.get("Host") in (f"127.0.0.1:{port}", f"localhost:{port}"):
                return True
            self._json(HTTPStatus.FORBIDDEN, {"error": "unexpected Host header"})
            return False

        def _token_valid(self) -> bool:
            if secrets.compare_digest(self.headers.get("X-Inspector-Token", ""), inspector.token):
                return True
            self._json(HTTPStatus.FORBIDDEN, {"error": "missing or wrong session token"})
            return False

        def _json(self, status: HTTPStatus, value: Any) -> None:
            self._send(status, json.dumps(value, allow_nan=False), "application/json")

        def _send(self, status: HTTPStatus, body: str | bytes, content_type: str) -> None:
            data = body.encode("utf-8") if isinstance(body, str) else body
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            self.wfile.write(data)

    return ThreadingHTTPServer(("127.0.0.1", port), Handler)


def serve(inspector: Inspector, port: int = 0) -> tuple[ThreadingHTTPServer, threading.Thread]:
    """Starts the server in a daemon thread and returns it with the thread."""
    server = make_server(inspector, port)
    thread = threading.Thread(
        target=server.serve_forever, name="honey-factory-inspector", daemon=True
    )
    thread.start()
    return server, thread


def url(server: ThreadingHTTPServer) -> str:
    return f"http://127.0.0.1:{cast(tuple[str, int], server.server_address)[1]}/"
