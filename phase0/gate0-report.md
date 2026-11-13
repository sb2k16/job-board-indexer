# Gate 0 report (Nov 11, 2026)

Question for the gate: can each of the 78 named platforms be indexed the way the design assumes, within the politeness rules? Measured from AWS egress (t3.micro, us-east-1) with the stdlib probe, 21 batches, about 600 requests, Nov 6–11.

## Headline

| | ATS (58) | Job boards (20) | Total (78) |
| --- | --- | --- | --- |
| Green: structured source, 2+ tenants, required fields present | 38 | 8 | **46** |
| Yellow: reachable, no keyless structured path under the current rules | 13 | 3 | 16 |
| Blocked: robots.txt forbids the listing path, or it needs a session token | 5 | 1 | 6 |
| Ungraded: no live customer board found | 2 | 8 | 10 |

Green means: a public JSON or XML source, or an HTML list whose detail pages carry schema.org JobPosting JSON-LD, returning real jobs on at least two tenants, with title, company, description, location and URLs obtainable within the rules (`coverage-matrix.md`, `field-validation.md`).

## Decisions taken during Phase 0

1. Index only what robots.txt allows, under RFC 9309 longest-match; a platform with no allowed listing path stays unclaimed (Nov 10).
2. Every platform spec names a board marker; a response without it is a failed fetch, never zero jobs (Nov 10; `board-markers.md`).
3. Do not replay a page's embedded session token (CSOD) and do not rely on undocumented `internal` hosts (talentReef) (Nov 11).
4. No HTML-parser tier for platforms that serve server HTML without a feed or JSON-LD (Nov 11). This keeps SAP SuccessFactors, SmartRecruiters, PageUp, TriNet, WeRecruit, Avature and Zoho Recruit at Yellow.

## Design-fit findings

- **One adapter interface holds.** Three adapter kinds cover all 46 Green platforms: JSON list (13 documented or internal APIs, 4 embedded), feed (8), and HTML list plus JSON-LD detail (21). The JSON-LD adapter is shared; per-platform differences are list templates and fingerprints.
- **Completeness signals exist** on every Green platform: a total (Greenhouse, Workday, Oracle, Eightfold, ADP, HireHive, Phenom, Rippling, Jobiqo), a last-page or `hasMore` flag (Oracle, Workable paging, Madgex 20-item pages), or a full feed/sitemap in one or a few requests (the rest).
- **Dedup needs a design change** (`dedup-test.md`). On 3 confirmed cross-platform pairs (CVS Health, Dollar Tree and CoStar, each on Phenom or Eightfold and on Workday) title and company match but the location string never does, so the specified `job_key` (title, company, location) merges none of them. The requisition id matches on all pairs. Add a canonical location token to the key and a second merge rule on (company, requisition id).
- **Tenant discovery works through Common Crawl's URL index** (Athena, under a cent per domain query), which found the second and third tenants for about 30 platforms. BambooHR tenant subdomains are absent from it.
- **Aliases:** iSolved Hire and ApplicantPro share markup and sitemaps and are one platform in practice. JBoard.io and JobBoard.io are distinct products.
- **Field gaps worth knowing:** company on single-employer feeds comes from the tenant registry; posted date is absent on Pinpoint and only a relative string on Workday lists (the detail call has the date); description needs a per-job detail call on 9 class A platforms and on every class B platform.

## The 32 non-Green platforms and their fallbacks

| Set | Platforms | Fallback |
| --- | --- | --- |
| HTML-parser tier (declined) | SAP SuccessFactors, SmartRecruiters, PageUp, TriNet, WeRecruit, Avature, Zoho Recruit | Reversible: enabling group 9 in the groups file plus one parser per platform (M each); all seven have 2+ live tenants and allowed list pages |
| No keyless path | Dayforce (search POST returns 403 to direct requests), HiBob (every path is the same shell), BrassRing (jobs via the app's own POST), Paradox, Polymer (API 422), JobBoardFire | Would need browser session emulation (group 10). Not planned; ask the organisers whether these count |
| Policy | CSOD (token), talentReef (internal host) | Ask the vendors or organisers for a documented feed; otherwise unclaimed |
| robots.txt | Dover, Niceboard, Paycom, Paycor, UltiPro | Unclaimed; the exact disallow rule is recorded in the matrix |
| One board only | JobKit (Rails board), JobBoard.com (itjobstech) | Green as soon as a second board is found; connectors are S |
| No live customer found | BambooHR, Rival, Talenetic, JobMount, CareerSiteCloud, Artha, jobboardsoftware.com (Exenzo), Nodex, eJobBoard, ejobsitesoftware.com | Ask the organisers for a reference site per platform; several appear to have no public boards at all |

## Questions for the organisers (send now)

1. Does coverage count platforms, or employers reachable through them? Six platforms have no public listing path that respects robots.txt or avoids session tokens; do they count against the entry?
2. Ten platforms have no live customer board we could find (list above). Can you name a reference site for each, or confirm they are out of scope?
3. Is a platform "supported" when the connector works against its JSON-LD or feed on 2+ tenants, or do you expect a per-platform integration with the vendor's API?
4. iSolved Hire and ApplicantPro share one backend; JBoard.io and JobBoard.io do not. Do shared-backend platforms count once or twice?
5. Is a crawler that declines robots-disallowed paths and session-token replay the expected behaviour, or do you accept browser-based crawling?

## Re-scope decision at this gate

46 Green platforms is above the "about 50 with a cut" planning assumption once the one-board platforms (2) and the declined HTML tier (7, reversible) are counted as reachable. Recommendation: build waves for the 46 Green platforms in the group order of `groups.md` (groups 1, 3, 6 and 7 first, since they are S-effort and cover 32 platforms), keep group 9 as the first stretch, and put the organiser answers on the critical path for the remaining 23.

## Phase 0 cost

Common Crawl via Athena: about 94 GB scanned, about $0.47. EC2: four t3.micro sessions, under $0.10. S3 and SSM: negligible.

## Artefacts

`coverage-matrix.md/.csv`, `field-validation.md`, `dedup-test.md`, `groups.md`, `board-markers.md`, `probes/batch01–21.json`, `samples/batch01–21/` (raw responses, the future test fixtures), `probe.py`, `validate_fields.py`, `build_matrix.py`.

## Open items

- Confirm the description on Jobiqo and Rippling detail pages (one probe each; the Jobiqo URL I guessed was a 404).
- Find a second board for JobKit and JobBoard.com.
- The organiser questions above are drafted here, not yet sent.
