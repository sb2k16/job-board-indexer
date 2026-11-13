# Copyright OpenSearch Contributors
# SPDX-License-Identifier: Apache-2.0
#
# The OpenSearch Contributors require contributions made to
# this file be licensed under the Apache-2.0 license or a
# compatible open source license.
"""T2.4 acceptance: snapshot_history does not advance after 500s, 429s, timeouts or truncated pages.

Runs against the local compose stack (local/) after at least two refresh cycles:
    python3 tests/t24_fault_injection_test.py [--min-cycles 2]
Reads board_state through `docker compose exec postgres psql`, and the fixture's /_hits counter."""
import argparse, json, pathlib, subprocess, sys, urllib.request

LOCAL = pathlib.Path(__file__).resolve().parent.parent / "local"
FAILS = []


def check(name, ok, detail=""):
    print(("PASS " if ok else "FAIL ") + name + (f"  ({detail})" if detail else ""))
    if not ok:
        FAILS.append(name)


def board_state():
    sql = ("SELECT platform, board_id, last_outcome, consecutive_failures, cardinality(snapshot_history),"
           " coalesce(last_error, '') FROM board_state ORDER BY 1, 2")
    out = subprocess.run(["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "jbi", "-d", "jbi", "-At", "-F", "\t", "-c", sql],
                         cwd=LOCAL, check=True, capture_output=True, text=True).stdout
    rows = {}
    for line in out.strip().splitlines():
        p, b, outcome, fails, hist, err = line.split("\t")
        rows[(p, b)] = {"outcome": outcome, "failures": int(fails), "history": int(hist), "error": err}
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--min-cycles", type=int, default=2)
    args = ap.parse_args()
    state = board_state()
    hits = json.load(urllib.request.urlopen("http://localhost:8080/_hits"))
    cycles = state[("greenhouse", "stripe")]["history"]
    print(f"refresh cycles completed (stripe history): {cycles}")
    if cycles < args.min_cycles:
        print(f"need at least {args.min_cycles} cycles; wait and re-run")
        return 2

    expected_failures = {
        ("greenhouse", "fault-500"): ("FAILED", "HTTP 500"),
        ("greenhouse", "fault-429"): ("FAILED", "HTTP 429"),
        ("greenhouse", "fault-timeout"): ("FAILED", "timed out"),
        ("greenhouse", "fault-truncated"): ("FAILED", "not json"),
        ("greenhouse", "fault-html"): ("FAILED", "not json"),
        ("greenhouse", "fault-short"): ("PARTIAL", "platform reports 20"),
        ("greenhouse", "blocked-board"): ("BLOCKED", "robots.txt"),
        ("greenhouse", "missing-board"): ("GONE", "HTTP 404"),
        ("lever", "trunc"): ("FAILED", "page 2"),
        ("lever", "missing-lever"): ("GONE", "HTTP 404"),
        ("hirebridge", "9999999"): ("GONE", "HTTP 404"),
    }
    for key, (outcome, err) in expected_failures.items():
        row = state.get(key)
        if row is None:
            check(f"{key[0]}/{key[1]} recorded", False, "no board_state row")
            continue
        check(f"{key[0]}/{key[1]}: history never advances", row["history"] == 0, f"history={row['history']}")
        check(f"{key[0]}/{key[1]}: outcome {outcome}", row["outcome"] == outcome, row["outcome"])
        check(f"{key[0]}/{key[1]}: streak counts every crawl", row["failures"] >= cycles, f"failures={row['failures']}")
        check(f"{key[0]}/{key[1]}: error names the cause", err.lower() in row["error"].lower(), row["error"][:120])

    healthy = [k for k in state if k not in expected_failures]
    short = [f"{p}/{b}={state[(p, b)]['history']}" for p, b in healthy
             if state[(p, b)]["history"] < cycles or state[(p, b)]["outcome"] != "COMPLETE" or state[(p, b)]["failures"] != 0]
    check(f"{len(healthy)} healthy boards each gained a snapshot per cycle", not short, ", ".join(short[:10]))
    check("fault-flaky recovered by retry and is COMPLETE", state[("greenhouse", "fault-flaky")]["outcome"] == "COMPLETE")

    # Politeness evidence from the fixture's side.
    check("robots.txt read (cached per host, re-read at most once per pod per TTL)", 1 <= hits.get("robots.txt", 0) <= 6,
          f"hits={hits.get('robots.txt')}")
    check("blocked-board never requested", hits.get("blocked-board", 0) == 0, f"hits={hits.get('blocked-board', 0)}")
    check("fault-500 retried 3 times per crawl, not more", hits.get("fault-500", 0) <= 3 * (cycles + 1),
          f"hits={hits.get('fault-500')} for {cycles} cycles")
    check("missing-board (404) not retried", hits.get("missing-board", 0) <= cycles + 1, f"hits={hits.get('missing-board')}")
    print(f"\n{len(FAILS)} failure(s)")
    return 1 if FAILS else 0


if __name__ == "__main__":
    sys.exit(main())
