# Closures through the sink, and job page verification (2026-11-22)

## 1. Every index write goes through the sink

**Before.** After each complete snapshot, `ClosureSweeper` called
`_update_by_query?conflicts=proceed&refresh=true` with `closure.painless`, writing straight to
OpenSearch from the source. That was the only write that bypassed the pipeline, and it cost three
things:

- **The write budget.** The t3.small domain takes about 3 concurrent bulk writers (load test,
  Nov 18), and the design runs exactly one sink worker per task to stay inside that. Update by
  query was a further writer that nothing limited, and an expensive one: a scroll, internal bulk
  requests and a forced refresh.
- **The sink's machinery.** No back-pressure, no retry with backoff, no dead-letter path, none of
  the sink's metrics.
- **Coupling.** The source had its own OpenSearch write client and credentials.

**Now.** The sweeper and the verifier read the index to decide (a count, then the matching job
keys), and write decisions as **command events** into the same buffer as the jobs:

```
{ "job_key": "…", "_op": "close",  "platform": "…", "board_id": "…", "cutoff": "…" | null, "now": "…" }
{ "job_key": "…", "_op": "verify", "platform": "…", "board_id": "…", "urls": ["…"], "verdict": "gone" | "alive", "now": "…" }
```

The sink sends every event as a scripted upsert through `config/upsert.painless`. That script
now branches on `_op`:

- **`close`:** live links of the board last seen before the cutoff go. With no cutoff (a retired
  board), all of them go. A job whose last live link goes is closed. This is what
  `closure.painless` did; that file is deleted.
- **`verify`:** the checked link goes (`gone`), or is seen now (`alive`: `last_seen` and
  `last_checked`).
- **No `_op`:** the job upsert, unchanged.
- **A command for a job not in the index** sets `ctx.op = 'noop'`. The sink sends
  `scripted_upsert: true` with the event as the upsert body, and OpenSearch 2.19 then creates
  nothing (tested).

**Acknowledgements and leases.** Commands join the board's acknowledgement set, so the partition
completes only after the sink has written the closures too.

- A sweep started by the snapshot acknowledgement runs on a dedicated `jbi-closure` thread. The
  buffer may make it wait, and it must never block the acknowledgement executor.
- If a command write fails, the board's set is cancelled and the partition returns to its lease,
  the same as a failed job write.

**Latency.** Closures are now eventually consistent, like job writes. The sink flushes a small
batch after its `flush_timeout` (60 s by default), so a sweep's closures can reach the index up to
about a minute after the sweep. In the end-to-end test the sweep emitted at 13:45:38 and the jobs
were closed before 13:46:38. `board_state.last_closed_count` is written at the sweep, not when
the sink confirms.

**Ordering.** Commands and jobs share one buffer and one sink worker, but nothing relies on
their order.

- A `close` changes only links last seen before its cutoff. If a newer crawl has already seen the
  link again, the close is a no-op.
- A job event re-opens a link a command closed.

**Still direct, and why.** The sweeper still reads the index: `_refresh`, `_count` and `_search`
for job keys.

- Reads are not the problem. They add no write load and are bounded: one count and one search of
  at most 10,000 keys per sweep.
- `_refresh` makes the snapshot's own writes visible before the count. It is the one remaining
  call that is not a pure read.
- Removing the read dependency would mean keeping the links ledger (`last_seen` per link) in
  Postgres. Not done.

## 2. Job page verification

The sweeper closes a link only after 3 **complete** snapshots miss it. A board that is never read
completely (capped lists: Workday CVS, Phenom past 10,000, JobBoard.io's 50, JobBoardHQ's 20)
therefore never closed a removed job. Closing by age alone would be wrong: an unseen job on a
capped or failing board is usually still open.

`closure/JobVerifier` runs after each **partial** crawl, in the crawl worker, before the
partition's acknowledgement set completes:

1. **Select.** Up to `verify_max_per_crawl` (100) of the board's open jobs with a live link unseen
   for `verify_after` (2 days), oldest `last_checked` first. `source_links` is not nested, so the
   exact link is re-checked in Java.
2. **Fetch.** Each job's own page, through the platform's fetcher (robots.txt, rate limits,
   breaker; a 404 counts as the host answering).
3. **Judge.**

   | What the page shows | Verdict |
   |---|---|
   | 404 or 410 | gone |
   | Gone wording ("no longer available", "position has been filled", "job not found", …) | gone |
   | The job's title, with no gone wording | alive |
   | Anything else: errors, robots, a JavaScript shell | unknown, nothing changes |

   The run stops after 5 unreadable pages in a row.
4. **Guard.** If at least `mass_closure_min_jobs` pages read as gone and that is more than
   `verify_gone_ratio` (50%) of those checked, no gone verdicts are applied. A template we misread
   is likelier than a purge. Alive verdicts still apply.
5. **Write.** `verify` commands through the buffer, as above.

Platforms whose job pages are JavaScript shells (Workday's human pages, for one) will mostly read
as unknown. The verifier's log line gives gone, alive and unknown counts per board, so coverage
per platform can be measured before choosing a structured detail source for those.

## Tests

- **Crawler:** 246 unit tests. Among them, `JobVerifierTest` (6), and `ClosureSweeperTest`
  asserting commands, never index writes.
- **Script on OpenSearch 2.19:** `tests/t25_merge_and_closure_test.py` (38 checks) sends close and
  verify commands exactly as the sink does, including a command for a job not in the index.
  `tests/t14_upsert_test.py` (19 checks) is unchanged.
- **End to end:** `tests/t25_closure_e2e.py` runs on the local stack, with the real Data Prepper
  sink carrying command events.

## Deploy note

On Nov 22 a deploy ran with `push-images.sh … | tail -2 && deploy-app.sh`. The pipe hid the push's
exit code, so a network timeout left `jbi-web:0df630f` missing from ECR. ECS could not place the
new web task (the old one kept serving), and CloudFormation waited on it until the image was
pushed. Run the deploy steps without pipes, or with `set -o pipefail`.
