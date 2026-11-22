import { test } from "node:test";
import assert from "node:assert/strict";
import { needsAttention, nextRetry, select, summarize, why } from "./health.ts";
import type { BoardState } from "./boardState.ts";

const base: BoardState = {
  platform: "greenhouse", board_id: "stripe", status: "active", last_outcome: "COMPLETE", last_attempt: "2026-11-19T10:00:00Z",
  last_confirmed: "2026-11-19T10:00:00Z", last_error: null, consecutive_failures: 0, snapshots: 3, last_job_count: 10,
  last_closed_count: 0, closure_paused_reason: null, retired: false, retry_at: null, retry_count: 0,
};
const s = (o: Partial<BoardState>): BoardState => ({ ...base, ...o });

test("a clean complete crawl needs no attention; anything else does", () => {
  assert.equal(needsAttention(base), false);
  assert.equal(needsAttention(s({ last_outcome: "PARTIAL" })), true);
  assert.equal(needsAttention(s({ retry_at: "2026-11-19T11:00:00Z" })), true);
  assert.equal(needsAttention(s({ closure_paused_reason: "40% of jobs" })), true);
  assert.equal(why(base), null);
  assert.equal(why(s({ last_outcome: "FAILED", last_error: "HTTP 429", consecutive_failures: 3 })), "HTTP 429. 3 crawls in a row without a complete read.");
  assert.equal(why(s({ last_error: "old error" })), null); // a past error after a complete crawl is not news
});

test("summary, tabs and filter", () => {
  const rows = [base, s({ board_id: "a", platform: "workable", last_outcome: "FAILED", consecutive_failures: 4, retry_at: "x" }), s({ board_id: "b", last_outcome: "PARTIAL" }), s({ board_id: "c", retired: true, last_outcome: "GONE" })];
  const sum = summarize(rows);
  assert.deepEqual([sum.boards, sum.platforms, sum.complete, sum.partial, sum.failed, sum.retrying, sum.attention, sum.retired], [4, 2, 1, 1, 2, 1, 3, 1]);
  assert.deepEqual(select(rows, "attention", "").map((r) => r.board_id), ["a", "b", "c"]);
  assert.deepEqual(select(rows, "retired", "").map((r) => r.board_id), ["c"]);
  assert.deepEqual(select(rows, "all", "WORK").map((r) => r.board_id), ["a"]);
});

test("next retry", () => {
  const now = Date.parse("2026-11-19T10:00:00Z");
  assert.equal(nextRetry(null, now), "");
  assert.equal(nextRetry("2026-11-19T09:59:00Z", now), "due now");
  assert.equal(nextRetry("2026-11-19T10:18:00Z", now), "in 18 min");
  assert.equal(nextRetry("2026-11-19T11:38:00Z", now), "in 1 h 38 min");
});

import { readable } from "./health.ts";

test("crawler errors read as sentences, with URLs cut to their host", () => {
  assert.equal(readable("GET https://apply.workable.com/api/v2/accounts/x/jobs/5C9F returned HTTP 429"), "GET apply.workable.com returned HTTP 429.");
  assert.equal(readable("parsed 30 jobs, platform reports 100"), "parsed 30 jobs, platform reports 100.");
  assert.equal(readable("Done."), "Done.");
});
