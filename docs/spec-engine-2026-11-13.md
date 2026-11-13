# T2.2 spec engine (2026-11-13)

Task T2.2: a declarative spec engine for class A, with field mapping and pagination for JSON and
XML. Acceptance: two different class A fixtures run from spec files alone, with no new code.

## What was built

- `crawler/.../spec/`: `PlatformSpec`, `FieldSpec` (the format), `SpecLoader` (YAML plus a
  validator that lists every problem and stops start-up), `JsonPage` and `XmlPage` (path
  lookup), `FieldMapper` (sources, transforms, types) and `SpecAdapter` (paging, board marker,
  completeness).
- A pipeline platform with `spec:` runs on the engine. One without it names a coded adapter
  (class C) in `AdapterRegistry`, which is now empty.
- Greenhouse moved from `GreenhouseAdapter.java` to `config/specs/greenhouse.yaml`. The old
  class is deleted and its tests now run against the spec.
- Format reference: `config/specs/README.md`.

## Decisions

- **Specs ship in the image** under `/etc/jbi/specs/`, and tests read `config/specs` directly,
  so the specs under test are the ones that ship. T2.9 moves them to a ConfigMap (option 1).
- **Pagination styles:** none, offset, page, cursor and next_url. `max_pages` (default 500) is a
  safety stop that yields an incomplete snapshot, never a complete one. A job repeated across
  pages is kept once.
- **Board marker on every page.** A marker missing on any page fails the whole board, so a
  rate-limit body on page 3 cannot shorten a snapshot.
- **XML is parsed with external entities and DTD loading off.** A test covers XXE.
- **Dates:** an unparseable date is dropped, never guessed. US zone abbreviations (EST, PDT)
  map to fixed offsets, because Java reads "EST" as New York time and shifts summer dates by
  an hour.
- **The tenant's company wins** over the feed's employer field, as before.
- **Validation now, schema later.** The loader rejects unknown keys, fields, transforms and
  styles, and incomplete pagination settings. A JSON schema and CI validation against fixtures
  remain T2.7.

## Platforms and fixtures

| Platform | Shape | Fixture | robots.txt |
| --- | --- | --- | --- |
| Greenhouse | JSON, one response, `meta.total` | Stripe, 3 jobs | checked in Phase 0 |
| Lever | JSON array body, offset paging (`skip`/`limit` 100) | Palantir, 5 jobs (unit test); 309 (local stack) | `Allow: /`, `Crawl-delay: 1` (2026-11-13) |
| HireBridge | RSS XML with the `g:` namespace, one response | client 6284, 10 items; 6741, 71 items (local) | none (404, 2026-11-13) |

Fixtures are cut from real Phase 0 responses with descriptions trimmed. Lever paging was checked
live with two `limit=2` requests.

HireBridge finding: each item has two `pubDate` elements. The first is the feed build time,
identical on every item; the last is the posting date. The spec reads `pubDate[last()]`.

## Results

- Unit tests: 51 pass, including 15 against the shipped specs and 13 engine tests covering
  every pagination style, `max_pages`, repeats, a marker missing on a later page, totals, date
  formats and loader errors.
- Local stack (3 replicas, 3 refresh cycles):
  - Greenhouse: 1,364 docs, unchanged from before the move.
  - Lever: 309/309. Each crawl took 4 pages.
  - HireBridge: 6284 10/10, and 6741 71 items into 69 docs.
  - The 6741 shortfall is two pairs of distinct openings with identical title, company and
    location (ids 653805/655721 and 655987/656144). They merge under `job_key` as the design
    accepts.
  - The missing tenants on all three platforms (404) fail the fetch and write nothing.
  - The T1.4 upsert test passes 19/19.

## Not in T2.2

- Detail fetches (a second request per job) are not in the spec format yet. The class A
  platforms that need one for the description (Breezy, Eightfold, Workable, Personio when the XML
  field is empty, ADP, Oracle, Workday) need a `detail:` block. That block is the first addition
  wave 1 needs after Gate 2.
- `rate_limit` is parsed but enforced only once T2.4 exists.
- HireBridge titles carry the location as a suffix ("Chef - , " when empty). This is left as
  the source gives it.
