"""Exercise the Android upload contract and dashboard reads without a cloud account."""

import base64
import io
import json
import os
import tempfile
import unittest
import uuid
from unittest.mock import patch
from backend.server import app, ASSETS


class SurveyRoundTrip(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        env = patch.dict(
            os.environ,
            {
                "FIELD_TOKEN": "pilot-test",
                "FIELD_BUCKET": "",
                "FIELD_DB": temp.name + "/survey.db",
            },
        )
        env.start()
        self.addCleanup(env.stop)
        self.key = str(uuid.uuid4())
        self.record = dict(
            id=self.key,
            patch=str(uuid.uuid4()),
            at=1,
            phase="Baseline",
            source="Camera sweep",
            model="pilot",
            frames=18,
            review=True,
            cover=34,
            height=None,
            lat=12,
            lon=77,
            accuracy=8,
            photo=base64.b64encode((ASSETS / "shrub.jpg").read_bytes()).decode(),
            mask="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a57sAAAAASUVORK5CYII=",
        )

    def request(self, method, path, v=None, token="pilot-test"):
        body = json.dumps(v).encode() if v is not None else b""
        env = {
            "PATH_INFO": path,
            "REQUEST_METHOD": method,
            "wsgi.input": io.BytesIO(body),
            "CONTENT_LENGTH": str(len(body)),
            "HTTP_AUTHORIZATION": "Bearer " + token,
        }
        status = []
        body = b"".join(app(env, lambda s, h: status.append(int(s[:3]))))
        return status[0], body

    def test_sync(self):
        path = "/visits/" + self.key
        self.assertEqual(self.request("PUT", path, self.record, token="wrong")[0], 401)
        for _ in range(2):
            self.assertEqual(self.request("PUT", path, self.record)[0], 204)
        self.assertEqual(self.request("PUT", path, dict(self.record, cover=12))[0], 409)
        self.assertEqual(json.loads(self.request("GET", path)[1]), self.record)
        items = json.loads(self.request("GET", "/visits")[1])
        self.assertEqual(len(items), 1)
        self.assertNotIn("photo", items[0])
        self.assertEqual(self.request("PUT", path, dict(self.record, cover=-1))[0], 400)


if __name__ == "__main__":
    unittest.main()
