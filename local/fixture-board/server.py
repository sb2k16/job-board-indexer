# Copyright OpenSearch Contributors
# SPDX-License-Identifier: Apache-2.0
#
# The OpenSearch Contributors require contributions made to
# this file be licensed under the Apache-2.0 license or a
# compatible open source license.
"""Fake board server: serves the Greenhouse Job Board API shape from files in ./boards/{board}.json.
GET /v1/boards/{board}/jobs?content=true  -> the file, or 404 like the real API.
GET /v1/boards/synth-NN/jobs              -> SYNTH_JOBS generated jobs after SYNTH_DELAY seconds, so a
                                             crawl lasts long enough to spread over replicas (T1.3).
GET /_hits                                 -> request counts per board (for tests)."""
import json, os, pathlib, re, collections, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BOARDS = pathlib.Path(__file__).parent / "boards"
HITS = collections.Counter()
ROUTE = re.compile(r"^/v1/boards/([^/]+)/jobs")
SYNTH = re.compile(r"^synth-(\d+)$")
SYNTH_JOBS = int(os.environ.get("SYNTH_JOBS", "20"))
SYNTH_DELAY = float(os.environ.get("SYNTH_DELAY", "3"))


def synth_board(n):
    jobs = [{
        "id": n * 1000 + i,
        "title": f"Synthetic Role {i}",
        "company_name": f"Synth {n}",
        "location": {"name": f"City {i}, CA"},
        "absolute_url": f"http://fixture:8080/synth-{n}/jobs/{i}",
        "updated_at": "2026-11-01T00:00:00Z",
        "content": f"&lt;p&gt;Synthetic job {i} for board {n}.&lt;/p&gt;",
        "requisition_id": None,
        "metadata": [],
        "offices": [],
    } for i in range(SYNTH_JOBS)]
    return json.dumps({"jobs": jobs, "meta": {"total": len(jobs)}})

class H(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/_hits":
            return self._send(200, json.dumps(HITS))
        m = ROUTE.match(self.path)
        if not m:
            return self._send(404, '{"status":404,"error":"Not found"}')
        board = m.group(1)
        HITS[board] += 1
        synth = SYNTH.match(board)
        if synth:
            time.sleep(SYNTH_DELAY)
            return self._send(200, synth_board(int(synth.group(1))))
        f = BOARDS / f"{board}.json"
        if not f.exists():
            return self._send(404, '{"status":404,"error":"Job not found"}')
        self._send(200, f.read_text())

    def _send(self, code, body):
        b = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def log_message(self, *a):
        pass

ThreadingHTTPServer(("0.0.0.0", 8080), H).serve_forever()
