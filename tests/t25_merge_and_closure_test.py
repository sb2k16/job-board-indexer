# Copyright OpenSearch Contributors
# SPDX-License-Identifier: Apache-2.0
#
# The OpenSearch Contributors require contributions made to
# this file be licensed under the Apache-2.0 license or a
# compatible open source license.
"""Merge, change detection and closure scripts against a real OpenSearch.

    python3 tests/t25_merge_and_closure_test.py [http://localhost:9200]

Sends exactly what the Data Prepper sink sends (scripted_upsert with the document as the upsert
body and params.doc) using config/upsert.painless, and runs the crawler's closure.painless through
_update_by_query as ClosureSweeper does. Uses a throwaway index built from config/jobs-template.json
(not matching jobs-*, so the site never sees it) and deletes it afterwards."""
import json, pathlib, sys, urllib.request, uuid

ROOT = pathlib.Path(__file__).resolve().parent.parent
OS = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:9200").rstrip("/")
UPSERT = (ROOT / "config/upsert.painless").read_text()
CLOSURE = (ROOT / "crawler/src/main/resources/io/github/sb2k16/jobboard/closure/closure.painless").read_text()
INDEX = "t25-" + uuid.uuid4().hex[:8]
FAILS = []


def call(method, path, body=None):
    req = urllib.request.Request(OS + path, method=method, data=None if body is None else json.dumps(body).encode(),
                                 headers={"content-type": "application/json"})
    try:
        with urllib.request.urlopen(req) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        raise SystemExit(f"{method} {path}: HTTP {e.code} {e.read().decode()[:600]}")


def check(name, ok, detail=""):
    print(("PASS " if ok else "FAIL ") + name + (f"  ({detail})" if detail and not ok else ""))
    if not ok:
        FAILS.append(name)


def doc(source, crawl, *, title="Pharmacist", description=None, posted=None, remote=None, countries=("US",),
        hash_="h1", location="Austin, TX", company="CVS Health"):
    d = {"job_key": "k1", "title": title, "company": company, "description": description, "location": location,
         "countries": list(countries), "content_hash": hash_, "crawl_started": crawl, "last_checked": crawl}
    if posted:
        d["posted_at"] = posted
    if remote is not None:
        d["remote"] = remote
    link = {"url": f"https://{source}/job/1", "application_url": f"https://{source}/apply/1", "platform": source,
            "board_id": "cvs", "posting_id": "1", "last_seen": crawl, "state": "live", "content_hash": hash_,
            "title": title, "location": location}
    if posted:
        link["posted_at"] = posted
    if remote is not None:
        link["remote"] = remote
    d["source_link"] = link
    return d


def upsert(d):
    call("POST", f"/{INDEX}/_update/k1?refresh=true", {
        "scripted_upsert": True, "upsert": d, "script": {"lang": "painless", "source": UPSERT, "params": {"doc": d}}})


def close(platform, cutoff, now):
    q = {"bool": {"filter": [{"term": {"source_links.platform": platform}}, {"term": {"source_links.board_id": "cvs"}},
                             {"term": {"source_links.state": "live"}}]
                  + ([{"range": {"source_links.last_seen": {"lt": cutoff}}}] if cutoff else [])}}
    r = call("POST", f"/{INDEX}/_update_by_query?conflicts=proceed&refresh=true", {
        "query": q, "script": {"lang": "painless", "source": CLOSURE,
                               "params": {"platform": platform, "board": "cvs", "cutoff": cutoff, "now": now}}})
    return r["updated"]


def get():
    return call("GET", f"/{INDEX}/_doc/k1")["_source"]


def link(s, platform):
    return next(l for l in s["source_links"] if l["platform"] == platform)


def main():
    tpl = json.loads((ROOT / "config/jobs-template.json").read_text())["template"]
    call("PUT", f"/{INDEX}", {"settings": {"number_of_shards": 1, "number_of_replicas": 0}, "mappings": tpl["mappings"]})
    try:
        run()
    finally:
        call("DELETE", f"/{INDEX}")
    print(f"\n{len(FAILS)} failure(s)")
    return 1 if FAILS else 0


def run():
    # 1. First source.
    upsert(doc("phenom", "2026-11-13T10:00:00Z", description="Short.", posted="2026-11-10T00:00:00Z", remote=False))
    s = get()
    check("first insert: one link, open, first_seen set", len(s["source_links"]) == 1 and s["status"] == "open"
          and s["first_seen"] == "2026-11-13T10:00:00Z")
    check("first insert: the incoming copy is not left behind", "source_link" not in s)
    check("first insert: fields recorded with their source", s["field_sources"].get("description") == "https://phenom/job/1")

    # 2. Second source, same job: richer description, earlier date, different title, remote.
    upsert(doc("workday", "2026-11-13T10:05:00Z", title="Pharmacist - Retail", description="A much longer description.",
               posted="2026-11-08T00:00:00Z", remote=True, countries=("US", "CA"), hash_="w1"))
    s = get()
    check("merge: two links", len(s["source_links"]) == 2)
    check("merge: longest description wins", s["description"] == "A much longer description.")
    check("merge: earliest posted date wins", s["posted_at"] == "2026-11-08T00:00:00Z")
    check("merge: first source keeps the title it supplied", s["title"] == "Pharmacist")
    check("merge: remote if any live source says so", s["remote"] is True)
    check("merge: countries are the union", sorted(s["countries"]) == ["CA", "US"])
    check("merge: first_seen unchanged", s["first_seen"] == "2026-11-13T10:00:00Z")
    check("merge: each link keeps its own title", link(s, "workday")["title"] == "Pharmacist - Retail")

    # 3. Identical re-crawl of the first source: nothing changed.
    lc = s["last_changed"]
    upsert(doc("phenom", "2026-11-13T10:30:00Z", description="Short.", posted="2026-11-10T00:00:00Z", remote=False))
    s = get()
    check("no change: last_changed stays", s["last_changed"] == lc)
    check("no change: last_seen moves", link(s, "phenom")["last_seen"] == "2026-11-13T10:30:00Z")

    # 4. A source with no description does not blank the one another source supplied.
    upsert(doc("phenom", "2026-11-13T11:00:00Z", description=None, posted="2026-11-10T00:00:00Z", remote=False, hash_="h2"))
    s = get()
    check("preserve: a missing description never blanks another source's", s["description"] == "A much longer description.")
    check("change: phenom's content changed, so last_changed moves", s["last_changed"] == "2026-11-13T11:00:00Z")
    check("change: only the changed link's last_changed moves",
          link(s, "phenom")["last_changed"] == "2026-11-13T11:00:00Z" and link(s, "workday")["last_changed"] == "2026-11-13T10:05:00Z")

    # 5. Two sources that differ do not churn last_changed on re-crawl.
    upsert(doc("workday", "2026-11-13T11:30:00Z", title="Pharmacist - Retail", description="A much longer description.",
               posted="2026-11-08T00:00:00Z", remote=True, countries=("US", "CA"), hash_="w1"))
    upsert(doc("phenom", "2026-11-13T11:30:00Z", description=None, posted="2026-11-10T00:00:00Z", remote=False, hash_="h2"))
    check("no churn: two differing sources re-crawled, last_changed stays", get()["last_changed"] == "2026-11-13T11:00:00Z")

    # 6. Closure: workday's board stops listing it. Cutoff after its last_seen.
    n = close("workday", "2026-11-13T12:00:00Z", "2026-11-13T12:00:05Z")
    s = get()
    check("closure: one document updated", n == 1, n)
    check("closure: the workday link is gone", link(s, "workday")["state"] == "gone" and link(s, "workday")["gone_at"])
    check("closure: job stays open while phenom lists it", s["status"] == "open")
    check("closure: remote and countries follow the live links", s["remote"] is False and s["countries"] == ["US"])
    check("closure: a link seen at the cutoff is not closed", close("phenom", "2026-11-13T11:30:00Z", "x") == 0)

    # 7. The workday supplier is gone: phenom's next crawl may take over its fields.
    upsert(doc("phenom", "2026-11-13T12:30:00Z", description="Short again.", posted="2026-11-10T00:00:00Z", remote=False, hash_="h3"))
    s = get()
    check("takeover: a live source replaces a gone source's description", s["description"] == "Short again.")
    check("takeover: and its posted date", s["posted_at"] == "2026-11-10T00:00:00Z")

    # 8. Last source gone: the job closes.
    close("phenom", "2026-11-13T13:00:00Z", "2026-11-13T13:00:05Z")
    s = get()
    check("closed: status closed with closed_at", s["status"] == "closed" and s["closed_at"] == "2026-11-13T13:00:05Z")
    check("closed: a second sweep is a no-op", close("phenom", "2026-11-13T13:00:00Z", "y") == 0)

    # 9. It comes back: reopened, link live again, gone_at cleared.
    upsert(doc("phenom", "2026-11-13T14:00:00Z", description="Short again.", posted="2026-11-10T00:00:00Z", remote=False, hash_="h3"))
    s = get()
    check("reopen: open, closed_at cleared", s["status"] == "open" and s.get("closed_at") is None)
    check("reopen: link live, gone_at cleared", link(s, "phenom")["state"] == "live" and "gone_at" not in link(s, "phenom"))
    check("reopen: counts as a change", s["last_changed"] == "2026-11-13T14:00:00Z")

    # 10. Retirement: no cutoff, every live link of the board goes.
    check("retire: closes regardless of last_seen", close("phenom", None, "2026-11-13T15:00:00Z") == 1)
    check("retire: job closed", get()["status"] == "closed")


if __name__ == "__main__":
    sys.exit(main())
