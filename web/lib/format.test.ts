import { test } from "node:test";
import assert from "node:assert/strict";
import { isNew, postedAgo, shortDate } from "./format.ts";

const now = Date.parse("2026-11-19T12:00:00Z");
const before = (h: number) => new Date(now - h * 3_600_000).toISOString();

test("posted ages read like a job site's", () => {
  assert.equal(postedAgo(before(0.2), now), "Just now");
  assert.equal(postedAgo(before(1.5), now), "1 hour ago");
  assert.equal(postedAgo(before(5), now), "5 hours ago");
  assert.equal(postedAgo(before(30), now), "Yesterday");
  assert.equal(postedAgo(before(24 * 4 + 1), now), "4 days ago");
  assert.equal(postedAgo("2026-09-02T00:00:00Z", now), "Sep 2");
  assert.equal(postedAgo("2025-09-02T00:00:00Z", now), "Sep 2, 2025");
  assert.equal(postedAgo(undefined, now), "");
});

test("new means the last 24 hours", () => {
  assert.equal(isNew(before(23), now), true);
  assert.equal(isNew(before(25), now), false);
  assert.equal(shortDate("2026-11-14T08:00:00Z", 2026), "Nov 14");
});

import { blocks } from "./format.ts";

test("descriptions become headings, paragraphs and lists", () => {
  assert.deepEqual(blocks("We build tools.\n\nWhat you’ll do\n- Run CI\n• Cut build times\nWho you are\nYou like fast builds."), [
    { kind: "p", text: "We build tools." },
    { kind: "h", text: "What you’ll do" },
    { kind: "ul", items: ["Run CI", "Cut build times"] },
    { kind: "h", text: "Who you are" },
    { kind: "p", text: "You like fast builds." },
  ]);
});
