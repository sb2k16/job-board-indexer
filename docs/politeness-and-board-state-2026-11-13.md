# T2.4 politeness and failure layer (2026-11-13)

Task T2.4: per-platform limiter, robots.txt, backoff, circuit breaker, `board_state` and the
complete-snapshot rule. Acceptance: in a fault-injection end-to-end run, `snapshot_history` does
not advance after 500s, 429s, timeouts or truncated pages.

## What was built

**Fetch layer** (`crawler/.../fetch/`). Every adapter request goes through a `PoliteFetcher`
bound to its platform, in this order:

1. **robots.txt** for the URL's origin, cached 24 h per pod. Rules follow RFC 9309:
   - the group naming `JobBoardIndexer` wins over `*`;
   - the longest matching rule wins, and a tie goes to allow;
   - `*` and `$` are supported.

   A 4xx robots.txt means allow all. A 5xx or an unreachable server means disallow, recorded as
   a retryable failure, never a reason to crawl. A disallowed URL is BLOCKED without any request.
2. **Circuit breaker** per host. After 5 consecutive failed fetches (retries already spent) the
   host is skipped for 5 minutes. 404s do not count: they concern a board, not the host.
3. **Rate limit** per platform: the spec's `rate_limit` is the whole budget, and each pod takes
   1/`replicas` of it, both requests per second and concurrency. A robots.txt `Crawl-delay`
   applies per host on top, also scaled by replicas.
4. **Retries** for 429, 5xx and network errors or timeouts: 3 attempts, exponential backoff with
   full jitter (base 1 s, cap 30 s). `Retry-After` is honoured. A `Retry-After` above 60 s ends
   the fetch, and the next refresh tries again. Other 4xx responses are not retried.

**`board_state`** (`db/V2__board_state.sql`, `crawler/.../state/`). One row per board, written
with atomic upserts:
- `last_attempt`, `last_crawl_started`, `last_outcome` and `last_error`;
- `consecutive_failures` and `first_failure_at`;
- `last_job_count` and `last_confirmed`;
- `snapshot_history`: crawl-start times of the last 10 complete snapshots, newest first and
  deduplicated, so a late or repeated acknowledgement is harmless.

The closure, retirement and mass-closure columns arrive with T2.5.

**Outcomes:**

| Outcome | Meaning |
| --- | --- |
| COMPLETE | Every reported job was read |
| PARTIAL | Fewer jobs than reported, or `max_pages` was hit |
| FAILED | 429, 5xx, timeout, missing marker, unparseable body, open circuit, or sink rejection |
| GONE | 404 or 410 on the board |
| BLOCKED | robots.txt disallows the board's URL |

Only COMPLETE adds to the history.

## Decisions

- **A snapshot is committed only after the sink confirms every job.** An event can belong to
  one acknowledgement set only, so a complete board's events go into a set the crawler creates.
  Its callback commits the snapshot and then completes the framework's set, which completes the
  partition.
  - On a sink failure, nothing is committed, the framework's set is cancelled, and the lease
    lapses so another pod retries.
  - In 2.17.0 the framework renews the lease every 2 minutes while its set is pending and times
    the set out after 2 h. Ours times out after 1 h, so it always resolves first.
  - The source passes its `AcknowledgementSetManager` to the crawler.
- **A failed fetch is recorded and its partition completed, not thrown.** In 2.17.0 a retryable
  throw keeps the partition ASSIGNED until its lease lapses and then retries it every lease;
  this was the missing-board repeat seen in T1.2. A non-retryable throw retries the partition
  daily for 30 days. The board is fetched again at the next refresh instead.
- **Superseded crawls are skipped.** A partition whose crawl start is older than the board's
  newest attempted crawl completes without fetching. This handles leftovers retried after a
  lapsed lease.
- **A `board_state` outage is retryable** at the framework level, so an outcome is never silently
  lost.
- **Logging.** Data Prepper's root logger is WARN. The image now ships the stock
  `log4j2-rolling.properties` plus INFO for `io.github.sb2k16.jobboard`, so emitted counts,
  retries and outcomes are visible.
- **Defaults:** 2 rps and concurrency 2 per platform when a spec sets nothing; Lever sets 1 rps
  (its Crawl-delay). These are starting values for spike S2.

## Not done (later tasks)

- Conditional requests (ETag, 304, "unchanged") and the daily unconditional fetch.
- Closure, retirement after M confirmed 404s, and the mass-closure guard (T2.5).
- `quarantined_urls` and an alarm on `consecutive_failures`. The column and metrics exist; the
  alarm belongs with T1.5 and T4.
- Rate limits are per pod and assume `replicas` matches the deployment. A shared limiter in
  Postgres is the alternative if autoscaling makes the count drift.

## Results

- **Unit tests:** 72 pass.
  - Robots precedence, including the Eightfold and UltiPro files from Phase 0.
  - Limiter spacing split across replicas, and Crawl-delay.
  - Retries, `Retry-After`, backoff bounds, and breaker open, closed and 404 handling.
  - Every crawler outcome, including commit only after acknowledgement, sink rejection,
    superseded crawls and a `board_state` outage.
- **Integration test** (`-Djbi.test.jdbc.url=...`, real Postgres): 4 pass. Covers history order,
  dedup and cap, a late acknowledgement, the failure streak, supersession and 40 concurrent
  writers.
- **Fault-injection run** (`tests/t24_fault_injection_test.py`, 3 replicas, 2 refresh cycles):
  50/50 checks pass.

  | Board | Outcome | History |
  | --- | --- | --- |
  | fault-500, fault-429, fault-timeout, fault-truncated, fault-html (200 HTML page) | FAILED | 0 |
  | Lever `trunc` (page 2 cut in half) | FAILED | 0 |
  | fault-short | PARTIAL | 0 |
  | blocked-board | BLOCKED | 0 |
  | Three missing boards | GONE | 0 |
  | 37 healthy boards | COMPLETE | 2 each |
  | fault-flaky | COMPLETE after a retry | 2 |

  - blocked-board was never requested.
  - robots.txt was fetched once per pod (3 requests).
  - fault-500 was requested 3 times per crawl; the 404 boards once per crawl.
  - All 96 worker partitions completed, with none left ASSIGNED.
  - The T1.4 upsert test still passes 19/19.
