import { test } from "node:test";
import assert from "node:assert/strict";
import { buildSearch, parseFilters, toSearch } from "./query.ts";
import { employmentBucket, groupEmployment } from "./employment.ts";
import { segments } from "./format.ts";

const raw = groupEmployment([
  { key: "FullTime", doc_count: 3 },
  { key: "Full-time", doc_count: 2 },
  { key: "Internship", doc_count: 1 },
]);
const opts = { employmentRaw: new Map([...raw].map(([k, v]) => [k, v.raw] as const)), now: new Date("2026-11-13T00:00:00Z") };

test("parseFilters whitelists values and clamps the page", () => {
  const f = parseFilters({ q: " rust ", type: ["Full-time", "bogus"], remote: "yes", sort: "evil", status: "x", page: "9999", posted: "2d" });
  assert.equal(f.q, "rust");
  assert.deepEqual(f.types, ["Full-time"]);
  assert.equal(f.remote, null);
  assert.equal(f.sort, "relevance");
  assert.equal(f.status, "open");
  assert.equal(f.page, 50);
  assert.equal(f.posted, null);
  assert.equal(parseFilters({}).sort, "posted");
});

test("toSearch round-trips and omits defaults", () => {
  const f = parseFilters({ q: "data", company: ["Stripe", "Figma"], remote: "true", page: "3" });
  const back = parseFilters(Object.fromEntries([...new URLSearchParams(toSearch(f).slice(1))].reduce((m, [k, v]) => {
    (m.get(k) ?? m.set(k, []).get(k)!).push(v);
    return m;
  }, new Map<string, string[]>())));
  assert.deepEqual(back, f);
  assert.equal(toSearch(parseFilters({})), "");
});

test("facet filters go to post_filter and each facet ignores its own filter", () => {
  const body = buildSearch(parseFilters({ company: "Stripe", platform: "lever", type: "Full-time" }), opts) as any;
  const post = body.post_filter.bool.filter;
  assert.equal(post.length, 3);
  assert.deepEqual(post.find((c: any) => c.terms?.employment_type).terms.employment_type, ["FullTime", "Full-time"]);
  const companyAgg = body.aggs.company.filter.bool.filter;
  assert.ok(!companyAgg.some((c: any) => c.terms?.["company.keyword"]));
  assert.equal(companyAgg.length, 2);
  assert.deepEqual(body.query.bool.filter, [{ term: { status: "open" } }]);
});

test("free text uses simple_query_string; posted falls back to first_seen", () => {
  const body = buildSearch(parseFilters({ q: 'title:"x" OR (', posted: "7d", status: "all" }), opts) as any;
  const inner = body.query.function_score.query;
  assert.ok(inner.bool.must[0].simple_query_string);
  const range = inner.bool.filter[0].bool.should;
  assert.equal(range[0].range.posted_at.gte, "2026-11-06T00:00:00.000Z");
  assert.equal(range[1].bool.filter.range.first_seen.gte, "2026-11-06T00:00:00.000Z");
});

test("employment buckets", () => {
  for (const [rawv, b] of [["fulltime_permanent", "Full-time"], ["parttime_permanent", "Part-time"], ["fulltime_fixed_term", "Contract / temporary"],
    ["PRN/On-Call", "Part-time"], ["intern", "Internship"], ["Permanent", "Full-time"], ["Scholarship", "Internship"], ["", "Other"]] as const) {
    assert.equal(employmentBucket(rawv), b, rawv);
  }
});

test("a highlight at the very start survives", () => {
  assert.deepEqual(segments("\uE000Design\uE001 \uE000Engineer\uE001").map((x) => x.hit), [true, false, true]);
});

test("highlight segments never carry markup", () => {
  assert.deepEqual(segments("a \uE000<b>\uE001 c"), [
    { text: "a ", hit: false },
    { text: "<b>", hit: true },
    { text: " c", hit: false },
  ]);
});

test("a location that is just a country becomes a country filter", () => {
  for (const loc of ["US", "us", "USA", "United States", "Deutschland", "germany"]) {
    const f = parseFilters({ location: loc, platform: "recruitee" });
    assert.equal(f.location, "", loc);
    assert.equal(f.countries.length, 1, loc);
  }
  assert.deepEqual(parseFilters({ location: "US" }).countries, ["US"]);
  assert.equal(parseFilters({ location: "Berlin" }).location, "Berlin");
  assert.deepEqual(parseFilters({ country: ["us", "XX", "DE"] }).countries, ["US", "DE"]);
  const body = buildSearch(parseFilters({ location: "US" }), opts) as any;
  assert.deepEqual(body.post_filter.bool.filter, [{ terms: { countries: ["US"] } }]);
  assert.ok(body.aggs.country);
});

test("relevance order sinks jobs nobody has re-confirmed, never below half weight; other orders are untouched", () => {
  const rel = buildSearch(parseFilters({ q: "nurse" }), opts) as any;
  const fs = rel.query.function_score;
  assert.equal(fs.boost_mode, "multiply");
  assert.equal(fs.score_mode, "sum");
  assert.deepEqual(fs.functions[0], { weight: 0.5 });
  assert.equal(fs.functions[1].weight, 0.5);
  assert.ok(fs.functions[1].gauss.last_checked);
  const recent = buildSearch(parseFilters({ q: "nurse", sort: "posted" }), opts) as any;
  assert.ok(recent.query.bool);
  const browse = buildSearch(parseFilters({}), opts) as any;
  assert.ok(browse.query.bool);
});
