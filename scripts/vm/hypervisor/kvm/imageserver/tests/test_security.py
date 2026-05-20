# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

"""Focused security regression tests for image-server registration and HTTP auth."""

import io
import os
import sys
import tempfile
import types
import unittest
import urllib.error
import urllib.request

from imageserver.config import validate_transfer_config

if "nbd" not in sys.modules:
    fake_nbd = types.ModuleType("nbd")

    class _UnavailableNbd:
        def __init__(self, *args, **kwargs):
            raise RuntimeError("libnbd is unavailable in this focused unit test")

    fake_nbd.NBD = _UnavailableNbd
    sys.modules["nbd"] = fake_nbd

from imageserver.handler import Handler

from .test_base import (
    ImageServerTestCase,
    http_get,
    http_put,
    make_file_transfer,
    make_tmp_image,
    shutdown_image_server,
)


class _FakeRangeBackend:
    supports_range_write = True

    def __init__(self):
        self.calls = []

    def write_range(self, rfile, offset, length):
        data = rfile.read(length)
        self.calls.append((offset, length, data))
        return len(data)

    def flush(self):
        pass

    def size(self):
        return 1024


class _PutRangeHarness:
    _CONTENT_RANGE_RE = Handler._CONTENT_RANGE_RE
    _parse_content_range = Handler._parse_content_range
    _handle_put_range_with_backend = Handler._handle_put_range_with_backend

    def __init__(self, body):
        self.rfile = io.BytesIO(body)
        self.errors = []
        self.responses = []
        self.command = "PUT"
        self.path = "/images/test"

    def _send_error_json(self, status, message):
        self.errors.append((int(status), message))

    def _send_json(self, status, obj, allowed_methods=None):
        self.responses.append((int(status), obj))

    def _send_range_not_satisfiable(self, size):
        self.errors.append((416, str(size)))


class TestPutRangeValidation(unittest.TestCase):
    def test_put_content_range_rejects_short_content_length(self):
        backend = _FakeRangeBackend()
        h = _PutRangeHarness(b"x")

        h._handle_put_range_with_backend("test", backend, "bytes 0-511/*", 1, False)

        self.assertEqual(h.errors[0][0], 400)
        self.assertIn("Content-Length", h.errors[0][1])
        self.assertEqual(backend.calls, [])


class TestTransferConfigValidation(unittest.TestCase):
    def test_register_requires_non_empty_token(self):
        img = make_tmp_image()
        with self.assertRaises(ValueError):
            validate_transfer_config({"backend": "file", "file": img})
        with self.assertRaises(ValueError):
            validate_transfer_config({"backend": "file", "file": img, "token": "   "})

    def test_register_rejects_relative_and_traversal_file_paths(self):
        with self.assertRaises(ValueError):
            validate_transfer_config({"backend": "file", "file": "relative.raw", "token": "secret"})
        with self.assertRaises(ValueError):
            validate_transfer_config({"backend": "file", "file": "/tmp/../etc/passwd", "token": "secret"})
        with self.assertRaises(ValueError):
            validate_transfer_config({"backend": "nbd", "socket": "relative.sock", "token": "secret"})
        with self.assertRaises(ValueError):
            validate_transfer_config({"backend": "nbd", "socket": "/tmp/../tmp/nbd.sock", "token": "secret"})

    def test_register_rejects_sensitive_file_roots(self):
        for path in ("/etc/passwd", "/proc/self/mem", "/sys/kernel", "/dev/mem"):
            with self.subTest(path=path):
                with self.assertRaises(ValueError):
                    validate_transfer_config({"backend": "file", "file": path, "token": "secret"})
                with self.assertRaises(ValueError):
                    validate_transfer_config({"backend": "nbd", "socket": path, "token": "secret"})

    def test_register_allows_absolute_tmp_file_and_socket_paths(self):
        with tempfile.TemporaryDirectory() as tmp:
            file_path = os.path.join(tmp, "disk.raw")
            socket_path = os.path.join(tmp, "nbd.sock")
            self.assertEqual(
                validate_transfer_config({"backend": "file", "file": file_path, "token": "secret"})["file"],
                file_path,
            )
            self.assertEqual(
                validate_transfer_config({"backend": "nbd", "socket": socket_path, "token": "secret"})["socket"],
                socket_path,
            )


class TestHttpTransferToken(ImageServerTestCase):
    def test_get_rejects_missing_and_wrong_token(self):
        _tid, url, _path, cleanup = make_file_transfer()
        try:
            with self.assertRaises(urllib.error.HTTPError) as missing:
                urllib.request.urlopen(url, timeout=5)
            self.assertEqual(missing.exception.code, 401)

            with self.assertRaises(urllib.error.HTTPError) as wrong:
                urllib.request.urlopen(
                    urllib.request.Request(url, headers={"Authorization": "Bearer wrong"}),
                    timeout=5,
                )
            self.assertEqual(wrong.exception.code, 401)
        finally:
            cleanup()

    def test_get_accepts_bearer_and_transfer_token_headers(self):
        _tid, url, _path, cleanup = make_file_transfer()
        try:
            resp = http_get(url)
            self.assertEqual(resp.status, 200)
            resp.read()

            req = urllib.request.Request(url, headers={"X-CloudStack-Image-Transfer-Token": "test-token"})
            resp = urllib.request.urlopen(req, timeout=5)
            self.assertEqual(resp.status, 200)
            resp.read()
        finally:
            cleanup()

    def test_put_rejects_missing_token(self):
        _tid, url, _path, cleanup = make_file_transfer(data=b"\x00" * 4)
        try:
            req = urllib.request.Request(url, data=b"data", method="PUT")
            req.add_header("Content-Length", "4")
            with self.assertRaises(urllib.error.HTTPError) as missing:
                urllib.request.urlopen(req, timeout=5)
            self.assertEqual(missing.exception.code, 401)

            resp = http_put(url, b"data")
            self.assertEqual(resp.status, 200)
            resp.read()
        finally:
            cleanup()

    def test_head_requires_token(self):
        _tid, url, _path, cleanup = make_file_transfer()
        try:
            with self.assertRaises(urllib.error.HTTPError) as missing:
                urllib.request.urlopen(urllib.request.Request(url, method="HEAD"), timeout=5)
            self.assertEqual(missing.exception.code, 401)

            req = urllib.request.Request(
                url,
                headers={"Authorization": "Bearer test-token"},
                method="HEAD",
            )
            resp = urllib.request.urlopen(req, timeout=5)
            self.assertEqual(resp.status, 200)
            self.assertEqual(resp.read(), b"")
        finally:
            cleanup()


if __name__ == "__main__":
    try:
        unittest.main()
    finally:
        shutdown_image_server()
