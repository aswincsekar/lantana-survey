"""Fieldbook dashboard WSGI app. GCS objects are immutable and retries are idempotent."""

import base64
import binascii
import hashlib
import hmac
import json
import math
import mimetypes
import os
import sqlite3
import uuid
from pathlib import Path

ASSETS = Path(__file__).resolve().parents[1] / "mobile/app/src/main/assets"
MAX_BODY = 2_000_000


def canonical(value):
    return json.dumps(
        value, separators=(",", ":"), sort_keys=True, allow_nan=False
    ).encode()


def validate(v, key):
    if not isinstance(v, dict) or str(uuid.UUID(v["id"])) != key:
        raise ValueError("visit id mismatch")
    if str(uuid.UUID(v["patch"])) != v["patch"] or v["phase"] not in (
        "Baseline",
        "After clearance",
        "Regrowth",
    ):
        raise ValueError("invalid patch or phase")
    if (
        v["source"] not in ("Imported image", "Camera sweep")
        or type(v["frames"]) is not int
        or v["frames"] < 1
        or type(v["review"]) is not bool
        or type(v["at"]) is not int
        or v["at"] < 0
        or not isinstance(v["model"], str)
        or len(v["model"]) > 128
    ):
        raise ValueError("invalid visit metadata")
    for name, lo, hi in [
        ("cover", 0, 100),
        ("lat", -90, 90),
        ("lon", -180, 180),
        ("accuracy", 0, 1e6),
        ("height", 0, 100),
        ("heading", 0, 360),
    ]:
        val = v.get(name)
        if val is None and name != "cover":
            continue
        if (
            type(val) not in (float, int)
            or not math.isfinite(val)
            or not lo <= val <= hi
        ):
            raise ValueError(name)
    if ("lat" in v) != ("lon" in v) or ("lat" in v and "accuracy" not in v):
        raise ValueError("incomplete location")
    for name, signature in [("photo", b"\xff\xd8\xff"), ("mask", b"\x89PNG\r\n\x1a\n")]:
        if not base64.b64decode(v[name], validate=True).startswith(signature):
            raise ValueError(name)
    meta = {k: value for k, value in v.items() if k not in ("photo", "mask")}
    if len(canonical(meta)) > 4096:
        raise ValueError("metadata too large")
    return meta


class LocalStore:
    def __init__(self):
        self.path = os.environ.get("FIELD_DB", "fieldbook.sqlite")
        with sqlite3.connect(self.path) as db:
            db.execute(
                "CREATE TABLE IF NOT EXISTS visits(id TEXT PRIMARY KEY, body BLOB NOT NULL, meta BLOB NOT NULL)"
            )

    def put(self, key, body, meta):
        with sqlite3.connect(self.path) as db:
            db.execute(
                "INSERT OR IGNORE INTO visits VALUES(?,?,?)",
                (key, body, canonical(meta)),
            )
            return (
                db.execute("SELECT body FROM visits WHERE id=?", (key,)).fetchone()[0]
                == body
            )

    def get(self, key):
        with sqlite3.connect(self.path) as db:
            row = db.execute("SELECT body FROM visits WHERE id=?", (key,)).fetchone()
            return row[0] if row else None

    def list(self):
        with sqlite3.connect(self.path) as db:
            return [json.loads(r[0]) for r in db.execute("SELECT meta FROM visits")]


class CloudStore:
    def __init__(self):
        from google.cloud import storage

        self.bucket = storage.Client().bucket(os.environ["FIELD_BUCKET"])

    def put(self, key, body, meta):
        from google.api_core.exceptions import PreconditionFailed

        blob = self.bucket.blob("visits/" + key + ".json")
        digest = hashlib.sha256(body).hexdigest()
        blob.metadata = {"visit": canonical(meta).decode(), "sha256": digest}
        try:
            blob.upload_from_string(
                body, content_type="application/json", if_generation_match=0
            )
            return True
        except PreconditionFailed:
            blob.reload()
            return blob.metadata.get("sha256") == digest

    def get(self, key):
        from google.api_core.exceptions import NotFound

        try:
            return self.bucket.blob("visits/" + key + ".json").download_as_bytes()
        except NotFound:
            return None

    def list(self):
        return [
            json.loads(b.metadata["visit"])
            for b in self.bucket.list_blobs(prefix="visits/")
            if b.metadata and "visit" in b.metadata
        ]


def app(env, start):
    def response(status, body=b"", mime="application/json"):
        start(
            status,
            [
                ("Content-Type", mime),
                ("Content-Length", str(len(body))),
                ("Cache-Control", "no-store"),
                ("X-Content-Type-Options", "nosniff"),
            ],
        )
        return [body]

    path = env.get("PATH_INFO", "/")
    method = env["REQUEST_METHOD"]
    if method == "GET" and path in (
        "/",
        "/index.html",
        "/field.js",
        "/field.css",
        "/shrub.jpg",
        "/vanarekha.png",
        "/google-sans.ttf",
        "/google-sans-license.txt",
    ):
        name = path[1:] or "index.html"
        return response(
            "200 OK", (ASSETS / name).read_bytes(), mimetypes.guess_type(name)[0]
        )
    token = os.environ.get("FIELD_TOKEN", "")
    if not token or not hmac.compare_digest(
        env.get("HTTP_AUTHORIZATION", "").encode(), ("Bearer " + token).encode()
    ):
        return response("401 Unauthorized")
    store = CloudStore() if os.environ.get("FIELD_BUCKET") else LocalStore()
    if path == "/visits" and method == "GET":
        return response(
            "200 OK",
            canonical(sorted(store.list(), key=lambda v: v["at"], reverse=True)),
        )
    if not path.startswith("/visits/"):
        return response("404 Not Found")
    try:
        key = str(uuid.UUID(path[len("/visits/") :]))
        if method == "GET":
            body = store.get(key)
            return response("200 OK", body) if body else response("404 Not Found")
        if method != "PUT":
            return response("405 Method Not Allowed")
        size = int(env.get("CONTENT_LENGTH") or "0")
        if not 0 < size <= MAX_BODY:
            return response("413 Payload Too Large")
        v = json.loads(env["wsgi.input"].read(size))
        accepted = store.put(key, canonical(v), validate(v, key))
        return response("204 No Content" if accepted else "409 Conflict")
    except (
        ValueError,
        KeyError,
        TypeError,
        AttributeError,
        OverflowError,
        binascii.Error,
    ):
        return response("400 Bad Request")


if __name__ == "__main__":
    from wsgiref.simple_server import make_server

    if not os.environ.get("FIELD_TOKEN"):
        raise SystemExit("Set FIELD_TOKEN before starting the dashboard.")
    make_server("127.0.0.1", int(os.getenv("PORT", "8080")), app).serve_forever()
