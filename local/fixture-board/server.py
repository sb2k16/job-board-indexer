# Copyright OpenSearch Contributors
# SPDX-License-Identifier: Apache-2.0
#
# The OpenSearch Contributors require contributions made to
# this file be licensed under the Apache-2.0 license or a
# compatible open source license.
"""Fake board server for the local stack. Files under ./boards stand in for each platform.
GET /v1/boards/{board}/jobs?content=true  -> Greenhouse: boards/{board}.json, or 404 like the real API.
GET /v0/postings/{board}?skip=&limit=     -> Lever: a skip/limit slice of boards/lever/{board}.json.
GET /{cid}.rss                             -> HireBridge: boards/hirebridge/{cid}.rss.
GET /v1/boards/synth-NN/jobs              -> SYNTH_JOBS generated jobs after SYNTH_DELAY seconds, so a
                                             crawl lasts long enough to spread over replicas (T1.3).
GET /robots.txt                            -> disallows blocked-board (T2.4).
Fault boards (Greenhouse route, T2.4 fault injection):
  fault-500 always 500; fault-429 always 429 with Retry-After: 1; fault-timeout answers after
  FAULT_DELAY seconds (beyond the crawler's request_timeout); fault-truncated sends half a body;
  fault-short reports 20 jobs and sends 10; fault-html is a 200 maintenance page; fault-flaky
  fails every other request with 503, so a retry succeeds.
Lever board "trunc" is palantir with its second page cut in half.
GET /_hits                                 -> request counts per board (for tests)."""
import json, os, pathlib, re, collections, time
from urllib.parse import urlsplit, parse_qs
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BOARDS = pathlib.Path(__file__).parent / "boards"
HITS = collections.Counter()
ROUTE = re.compile(r"^/v1/boards/([^/]+)/jobs")
LEVER = re.compile(r"^/v0/postings/([^/?]+)")
HIREBRIDGE = re.compile(r"^/(\d+)\.rss$")
SYNTH = re.compile(r"^synth-(\d+)$")
SYNTH_JOBS = int(os.environ.get("SYNTH_JOBS", "20"))
SYNTH_DELAY = float(os.environ.get("SYNTH_DELAY", "3"))
FAULT_DELAY = float(os.environ.get("FAULT_DELAY", "8"))
ROBOTS = "User-agent: *\nDisallow: /v1/boards/blocked-board/\n"


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
        if self.path == "/robots.txt":
            HITS["robots.txt"] += 1
            return self._send(200, ROBOTS, "text/plain")
        url = urlsplit(self.path)
        lever = LEVER.match(url.path)
        if lever:
            return self._lever(lever.group(1), parse_qs(url.query))
        hb = HIREBRIDGE.match(url.path)
        if hb:
            HITS["hirebridge/" + hb.group(1)] += 1
            f = BOARDS / "hirebridge" / f"{hb.group(1)}.rss"
            if not f.exists():
                return self._send(404, "The resource you are looking for has been removed.", "text/html")
            return self._send(200, f.read_text(), "text/xml")
        m = ROUTE.match(self.path)
        if not m:
            return self._send(404, '{"status":404,"error":"Not found"}')
        board = m.group(1)
        HITS[board] += 1
        if board.startswith("fault-") or board == "blocked-board":
            return self._fault(board)
        synth = SYNTH.match(board)
        if synth:
            time.sleep(SYNTH_DELAY)
            return self._send(200, synth_board(int(synth.group(1))))
        f = BOARDS / f"{board}.json"
        if not f.exists():
            return self._send(404, '{"status":404,"error":"Job not found"}')
        self._send(200, f.read_text())

    def _fault(self, board):
        jobs = json.loads(synth_board(99))["jobs"][:10]
        full = json.dumps({"jobs": jobs, "meta": {"total": len(jobs)}})
        if board == "fault-500":
            return self._send(500, '{"error":"internal"}')
        if board == "fault-429":
            return self._send(429, '{"error":"slow down"}', headers={"Retry-After": "1"})
        if board == "fault-timeout":
            time.sleep(FAULT_DELAY)
            return self._send(200, full)
        if board == "fault-truncated":
            return self._send(200, full[: len(full) // 2])
        if board == "fault-short":
            return self._send(200, json.dumps({"jobs": jobs, "meta": {"total": 20}}))
        if board == "fault-html":
            return self._send(200, "<html><body>Down for maintenance</body></html>", "text/html")
        if board == "fault-flaky":
            if HITS[board] % 2 == 1:
                return self._send(503, '{"error":"try again"}')
            return self._send(200, full)
        return self._send(200, full)  # blocked-board: only reachable by ignoring robots.txt

    def _lever(self, board, q):
        HITS["lever/" + board] += 1
        f = BOARDS / "lever" / f"{'palantir' if board == 'trunc' else board}.json"
        if not f.exists():
            return self._send(404, '{"ok":false,"error":"Document not found"}')
        jobs = json.loads(f.read_text())
        skip = int(q.get("skip", ["0"])[0])
        limit = int(q.get("limit", [str(len(jobs))])[0])
        body = json.dumps(jobs[skip:skip + limit])
        if board == "trunc" and skip > 0:
            body = body[: len(body) // 2]
        self._send(200, body)

    def _send(self, code, body, ctype="application/json", headers=None):
        b = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def log_message(self, *a):
        pass

ThreadingHTTPServer(("0.0.0.0", 8080), H).serve_forever()
