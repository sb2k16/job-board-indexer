import { test } from "node:test";
import assert from "node:assert/strict";
import { applyLabel, applyUrl, primaryLink, whereListed } from "./source.ts";

test("an employer's ATS reads as its careers site; a job board by its address", () => {
  const ats = { platform: "greenhouse", board_id: "stripe", url: "https://boards.greenhouse.io/stripe/jobs/1" };
  assert.equal(whereListed(ats, "Stripe"), "Stripe’s careers site");
  assert.equal(applyLabel(ats, "Stripe"), "Apply on Stripe’s site");
  assert.equal(whereListed(ats, "Pilgrims"), "Pilgrims’ careers site");
  const board = { platform: "madgex", board_id: "jobs.economist.com", url: "https://www.jobs.economist.com/job/1/x/" };
  assert.equal(whereListed(board, "Some Bank"), "jobs.economist.com");
  assert.equal(applyLabel(board, "Some Bank"), "Apply on jobs.economist.com");
});

test("the live, most recently seen link wins; only http(s) URLs are used", () => {
  const links = [
    { url: "https://a.example/1", state: "gone", last_seen: "2026-11-19" },
    { url: "https://b.example/1", state: "live", last_seen: "2026-11-17" },
    { url: "https://c.example/1", state: "live", last_seen: "2026-11-18", application_url: "javascript:alert(1)" },
  ];
  assert.equal(primaryLink(links)?.url, "https://c.example/1");
  assert.equal(applyUrl(links), "https://c.example/1");
  assert.equal(applyUrl([{ url: "ftp://x" }]), null);
});
