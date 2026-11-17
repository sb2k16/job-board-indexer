# Sooner retry of failed crawls (2026-11-17)

## Problem

A crawl whose requests still fail after the fetcher's own retries (three attempts with backoff,
`Retry-After` honoured up to 60 s, the per-host 429 throttle) was recorded as FAILED and its board
was crawled again only at the group's next refresh: 2 hours for group 1, 12 hours for groups 5, 6
and 8. One bad minute of 429s could leave a board stale for half a day.

## Rule

| Outcome of the crawl | Retry |
| --- | --- |
| FAILED, retryable: 429, 5xx, timeout, network error, circuit open, robots.txt unavailable | 15, 30, then 60 minutes after the failure (60 thereafter), unless that is at or after the group's next refresh |
| FAILED, not retryable: board marker missing, 403 and other 4xx, a response that is not the expected format | None; the next refresh |
| GONE (404/410), BLOCKED (robots.txt disallows) | None; retirement and the robots decision have their own rules |
| COMPLETE or PARTIAL | None, and any pending retry and the retry count are cleared. PARTIAL is the platform's own limit (a result window, a crawl delay), which an early retry does not change |

Retryability is the fetcher's existing classification (`BoardFetchException.isRetryable`).

## How it works

- **Worker** (`JobBoardCrawler.scheduleOrClearRetry`): after each attempt, either sets the board's
  `retry_at` and increments `retry_count`, or clears both. Each partition carries its refresh's time
  (`refresh_started`), so a retry is never scheduled at or after the next refresh: the refresh
  crawls every board anyway.
- **Leader** (`JobBoardCrawler.queueDueRetries`): the framework calls the leader every minute.
  Between refreshes it claims due retries with one statement,
  `UPDATE board_state SET retry_at = NULL WHERE platform = ? AND retry_at <= ? RETURNING board_id`,
  and creates a partition for each (`platform|board|time|retry`). The claim clears `retry_at` in the
  same statement, so a board is queued once even if two leaders race (tested against Postgres).
  Any pod's worker then picks the partition up as usual.
- **Refresh**: before creating the refresh's partitions the leader drops every pending retry of the
  pipeline's platforms.
- A board removed from its tenant list is not retried. A failure to read or write retry state is
  logged and never fails the crawl; the next refresh covers the board.

The host circuit breaker is per pod and opens for 5 minutes; the first retry comes 15 minutes later,
so it does not hit a host whose breaker is still open on the pod that saw the failures.

## Schema and metrics

`db/V4__board_retry.sql`: `board_state.retry_at`, `board_state.retry_count`, and a partial index on
`(platform, retry_at)`. Counters: `boardRetriesScheduled`, `boardRetriesQueued`.

## Tests

`JobBoardCrawlerTest`: the 15/30/60 schedule and its cap; no retry for non-retryable outcomes or
past the next refresh; a successful read clears the retry and its count; the leader queues a due
retry once and skips one not yet due; a full refresh drops pending retries.
`JdbcBoardStateStoreIT` (Postgres): schedule, claim once under a two-thread race, clear, clear per
platform; the migration applied twice.
