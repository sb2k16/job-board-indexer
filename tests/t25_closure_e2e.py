# Copyright OpenSearch Contributors
# SPDX-License-Identifier: Apache-2.0
#
# The OpenSearch Contributors require contributions made to
# this file be licensed under the Apache-2.0 license or a
# compatible open source license.
"""T2.5 end to end on the local live stack: closure, the mass-closure guard and reopening.

    python3 tests/t25_closure_e2e.py

Needs the live overlay running with the g99-local-fixture group (local/live/env.toml: closure
snapshots = 2, mass_closure_min_jobs = 10, ratio 0.3). Edits the fixture board
local/fixture-board/boards/closure-demo.json between crawls and waits for complete snapshots in
board_state. Takes about 12 minutes (the fixture group re-crawls every minute)."""
import json, pathlib, subprocess, sys, time, urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
BOARD = ROOT / "local/fixture-board/boards/closure-demo.json"
OS = "http://localhost:9200"
FAILS = []


def check(name, ok, detail=""):
    print(("PASS " if ok else "FAIL ") + name + (f"  ({detail})" if detail else ""), flush=True)
    if not ok:
        FAILS.append(name)


def psql(sql):
    return subprocess.run(["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "jbi", "-d", "jbi", "-At", "-c", sql],
                          cwd=ROOT / "local", check=True, capture_output=True, text=True).stdout.strip()


def write_board(ids):
    jobs = [{"id": i, "title": "Shared Title" if i in (20, 21) else f"Closure Demo Role {i}", "company_name": "Closure Demo",
             "location": {"name": "Austin, Texas, United States"}, "absolute_url": f"http://fixture/closure-demo/{i}",
             "first_published": "2026-11-01T00:00:00Z", "content": f"&lt;p&gt;Role {i}&lt;/p&gt;"} for i in ids]
    BOARD.write_text(json.dumps({"jobs": jobs, "meta": {"total": len(jobs)}}))


def history():
    out = psql("SELECT cardinality(snapshot_history) || '|' || coalesce(to_char(snapshot_history[1], 'HH24:MI:SS.US'), '')"
               " FROM board_state WHERE platform='fixture' AND board_id='closure-demo'")
    return out or "0|"


def wait_snapshots(n, timeout=600):
    """Waits for n new complete snapshots of the board, then for the sweep that follows each one."""
    seen = history()
    got = 0
    deadline = time.time() + timeout
    while got < n:
        if time.time() > deadline:
            raise SystemExit(f"timed out waiting for snapshots (last {seen})")
        time.sleep(5)
        h = history()
        if h != seen:
            got += 1
            seen = h
            print(f"  snapshot {got}/{n} ({h})", flush=True)
    time.sleep(5)  # the sweep runs right after the snapshot commit; let it land


def jobs():
    body = {"size": 100, "query": {"term": {"source_links.platform": "fixture"}}, "_source": ["status", "source_links", "job_key", "title"]}
    req = urllib.request.Request(f"{OS}/jobs-v1/_search", data=json.dumps(body).encode(), headers={"content-type": "application/json"})
    urllib.request.urlopen(urllib.request.Request(f"{OS}/jobs-v1/_refresh", method="POST"))
    hits = json.load(urllib.request.urlopen(req))["hits"]["hits"]
    out = {}
    seen = []
    for h in hits:
        link = next(l for l in h["_source"]["source_links"] if l["platform"] == "fixture")
        seen.append(int(link["posting_id"]))
        out[int(link["posting_id"])] = {"status": h["_source"]["status"], "state": link["state"], "key": h["_id"]}
    out["duplicates"] = len(seen) - len(set(seen))
    return out


def main():
    print("phase 0: board lists 1-12, 20, 21 (20 and 21 share title and city)")
    write_board(list(range(1, 13)) + [20, 21])
    wait_snapshots(2)
    j = jobs()
    original_key = j[1]["key"]
    check("all 14 postings indexed and open", len(j) - 1 == 14 and all(v["status"] == "open" for k, v in j.items() if k != "duplicates"),
          f"{len(j) - 1} docs")
    check("same title and city on one board: two jobs", 20 in j and 21 in j and j[20]["key"] != j[21]["key"])

    print("phase 1: jobs 1 and 2 disappear")
    write_board(list(range(3, 13)) + [20, 21])
    wait_snapshots(1)
    j = jobs()
    check("after 1 snapshot without them: still open (N=2)", j[1]["status"] == "open" and j[2]["status"] == "open")
    wait_snapshots(1)
    j = jobs()
    check("after 2 snapshots without them: closed", j[1]["status"] == "closed" and j[2]["status"] == "closed",
          f"1={j[1]} 2={j[2]}")
    check("their links are gone", j[1]["state"] == "gone" and j[2]["state"] == "gone")
    check("the other 12 stay open", all(j[i]["status"] == "open" for i in list(range(3, 13)) + [20, 21]))
    check("board_state records the run", psql("SELECT last_closed_count FROM board_state WHERE platform='fixture'") == "2")

    print("phase 2: 10 of the 12 remaining disappear at once (mass closure)")
    write_board([20, 21])
    wait_snapshots(2)
    j = jobs()
    check("guard: the 10 stay open", all(j[i]["status"] == "open" for i in range(3, 13)))
    paused = psql("SELECT coalesce(closure_paused_reason, '') FROM board_state WHERE platform='fixture'")
    check("guard: board_state says paused, with the reason", "would close 10 of 12" in paused, paused)

    print("phase 3: the platform recovers; job 1 also comes back")
    write_board([1] + list(range(3, 13)) + [20, 21])
    wait_snapshots(1)
    j = jobs()
    check("job 1 reopened, link live", j[1]["status"] == "open" and j[1]["state"] == "live")
    check("job 1 came back as the same document, not a new one", j[1]["key"] == original_key and j["duplicates"] == 0)
    check("job 2 stays closed", j[2]["status"] == "closed")
    check("guard released once nothing is missing",
          psql("SELECT coalesce(closure_paused_reason, '') FROM board_state WHERE platform='fixture'") == "")
    print(f"\n{len(FAILS)} failure(s)")
    return 1 if FAILS else 0


if __name__ == "__main__":
    sys.exit(main())
