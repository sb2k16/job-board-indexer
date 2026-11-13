# Copyright OpenSearch Contributors
# SPDX-License-Identifier: Apache-2.0
#
# The OpenSearch Contributors require contributions made to
# this file be licensed under the Apache-2.0 license or a
# compatible open source license.
"""T1.4 done-when, against a real OpenSearch.

Sends bulk update requests shaped exactly like the sink's BulkOperationFactory
(script + upsert body + scripted_upsert=true, params.doc = the event) and checks:
  1. a job from two platforms becomes one document with two source_links and the original first_seen;
  2. an edited description updates last_changed;
  3. an identical re-crawl does not;
  4. a document with an unmapped field is rejected (strict mapping -> DLQ path).
Usage: python3 t14_upsert_test.py http://localhost:9200
"""
import json, sys, time, urllib.request, pathlib

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:9200"
HERE = pathlib.Path(__file__).resolve().parent.parent / "config"
SCRIPT = (HERE / "upsert.painless").read_text()
INDEX = "jobs-t14"

def req(method, path, body=None, ndjson=False):
    data = None
    if body is not None:
        data = body.encode() if isinstance(body, str) else json.dumps(body).encode()
    r = urllib.request.Request(BASE + path, data=data, method=method,
                               headers={"Content-Type": "application/x-ndjson" if ndjson else "application/json"})
    try:
        with urllib.request.urlopen(r) as resp:
            return json.loads(resp.read() or b"{}")
    except urllib.error.HTTPError as e:
        return json.loads(e.read() or b"{}")

def doc(key, url, platform, board, desc, crawl, checked):
    return {"job_key": key, "title": "Pharmacy Intern - Grad", "company": "CVS Health", "description": desc,
            "location": "Ashburn, Virginia, United States", "requisition_id": "R0996880",
            "content_hash": str(abs(hash(desc))), "crawl_started": crawl, "last_checked": checked,
            "source_link": {"url": url, "application_url": url, "platform": platform, "board_id": board,
                            "last_seen": crawl, "state": "live"}}

def upsert(d):
    action = {"update": {"_index": INDEX, "_id": d["job_key"], "retry_on_conflict": 3}}
    body = {"script": {"source": SCRIPT, "lang": "painless", "params": {"doc": d}}, "upsert": d, "scripted_upsert": True}
    out = req("POST", "/_bulk?refresh=true", json.dumps(action) + "\n" + json.dumps(body) + "\n", ndjson=True)
    item = out["items"][0]["update"]
    return item

def get():
    return req("GET", f"/{INDEX}/_doc/K1")["_source"]

fails = []
def check(name, cond, detail=""):
    print(("PASS " if cond else "FAIL ") + name + (f"  [{detail}]" if detail and not cond else ""))
    if not cond: fails.append(name)

tpl = json.loads((HERE / "jobs-template.json").read_text())
# Scope the template to the test index: the crawler's sink may already have installed a template for jobs-v1,
# and OpenSearch rejects a second template whose patterns overlap at the same priority.
tpl["index_patterns"] = [INDEX]
tpl["priority"] = 100
req("DELETE", f"/{INDEX}")
put = req("PUT", f"/_index_template/{INDEX}", tpl)
check("template applied", put.get("acknowledged") is True, put)

t1, t2, t3, t4 = "2026-11-13T00:00:00Z", "2026-11-13T06:00:00Z", "2026-11-13T12:00:00Z", "2026-11-13T18:00:00Z"
r = upsert(doc("K1", "https://jobs.cvshealth.com/R0996880", "phenom", "cvshealth", "Desc A", t1, t1))
check("insert ok", r.get("status") in (200, 201), r)
s = get()
check("insert: first_seen set", s.get("first_seen") == t1, s.get("first_seen"))
check("insert: last_changed set", s.get("last_changed") == t1, s.get("last_changed"))
check("insert: one source_link", len(s.get("source_links", [])) == 1)
check("insert: raw source_link removed", "source_link" not in s)
check("insert: status open", s.get("status") == "open")
# Search, not GET by id: the alias may span several indices (here also the crawler's jobs-v1), and
# OpenSearch only allows GET-by-id through an alias that points at a single index.
hits = req("POST", "/jobs/_search", {"query": {"ids": {"values": ["K1"]}}})["hits"]["hits"]
check("alias jobs resolves", any(h["_index"] == INDEX for h in hits), hits)

upsert(doc("K1", "https://cvshealth.wd1.myworkdayjobs.com/x/job/R0996880", "workday", "cvshealth", "Desc A", t2, t2))
s = get()
check("two platforms: one document, two source_links", len(s["source_links"]) == 2, s["source_links"])
check("two platforms: first_seen kept", s["first_seen"] == t1)
check("same content from 2nd platform: last_changed kept", s["last_changed"] == t1)

upsert(doc("K1", "https://jobs.cvshealth.com/R0996880", "phenom", "cvshealth", "Desc A", t3, t3))
s = get()
check("identical re-crawl: last_changed unchanged", s["last_changed"] == t1, s["last_changed"])
check("identical re-crawl: link last_seen advanced", [l for l in s["source_links"] if l["platform"] == "phenom"][0]["last_seen"] == t3)
check("identical re-crawl: still two links", len(s["source_links"]) == 2)
check("identical re-crawl: last_checked advanced", s["last_checked"] == t3)

upsert(doc("K1", "https://jobs.cvshealth.com/R0996880", "phenom", "cvshealth", "Desc B (edited)", t4, t4))
s = get()
check("edited description: last_changed updated", s["last_changed"] == t4, s["last_changed"])
check("edited description: first_seen kept", s["first_seen"] == t1)
check("edited description: description replaced", s["description"] == "Desc B (edited)")

bad = doc("K2", "u", "p", "b", "x", t1, t1); bad["unexpected_field"] = 1
r = upsert(bad)
check("unmapped field rejected (strict mapping)", r.get("status", 0) >= 400, r)

print(f"\n{len(fails)} failure(s)")
sys.exit(1 if fails else 0)
