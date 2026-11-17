# Connectors: phase A fixes and phase B additions (2026-11-15)

Phase A made the connectors already built read their boards fully or stop for a stated reason, and
gave every spec an offline test. Phase B added the Phase 0 yellow platforms that public, structured
or fixed-template data supports under the crawl rules (robots.txt only, no replayed session tokens, no
undocumented internal hosts). Result: **54 of the 78 platforms have a working connector**; the other
24 are listed below with the reason each cannot run.

## Phase A: fixes

| Problem (Nov 14) | Cause | Fix |
| --- | --- | --- |
| Workday CVS Health stopped at 9,991 of 18,528 (PARTIAL) | The engine's own `max_pages` default (500 pages of 20). The search serves every result: offset 18,040 returns the last 9 | `max_pages: 2500` for Workday (50,000 jobs). The Nov 14 doc's "at most 2,000 results" was wrong and is corrected |
| Phenom CVS Health FAILED at page 400 ("items is not a list") | Phenom's search serves at most 10,000 results whatever `totalHits` says; past that the page holds `totalHits: 0` and no job list | New `pagination.max_results`: paging stops at the window and the board is PARTIAL with "its search serves at most 10000 results", never FAILED. CVS now reads 7,574 distinct jobs of 17,999 (the window, less jobs that shift between pages) |
| Eightfold Netflix FAILED with 429 at 1 and at 0.5 requests a second (still not read; see below) | Netflix allows a burst of about five requests, then answers 429 for a few seconds with no Retry-After; three short jittered retries cannot outlast it | Adaptive throttle in the fetcher: a 429 sets that host's spacing to twice the platform's (at least 2 s), doubling on each further 429 up to 60 s, and a 429 without Retry-After waits at least that spacing before retrying. After 50 successes in a row the spacing shrinks by a quarter and the throttle ends below 2 s. Per host, so one tenant's limit does not slow the others |
| A live run hung for 18 hours on one request | The JDK HTTP client's timeout stops at the response headers; a server that stalls mid-body (a 2.4 MB Zoho page) blocks the thread forever. In a pod that is a crawler worker lost without an error | The transport now cancels any exchange not complete within ten times the request timeout (5 minutes; slow servers take 80 s for a 2.4 MB page) and reports a retryable network error |
| Live runs timed out on Netflix while curl answered in 0.5 s | JDK 11's HTTP/2 client stalls on long-lived connections | The transport uses HTTP/1.1; a polite crawler gains nothing from multiplexing |
| Jobvite pilgrims and ziffdavis FAILED: "no job data on any of 223 detail pages" | Their JSON-LD escapes the apostrophe as `\'` (not legal JSON), so every JobPosting was rejected | The JSON-LD reader accepts any backslash escape, as browsers do |
| Manatal (2 tenants) and Oracle ejwl FAILED on single job requests on Nov 14 | Transient: all three answer normally on Nov 16 | None beyond the throttle; confirmed in the next full refresh |
| Hireology job pages answered 502 for every tenant | Hireology's outage | None needed: the spec reads burnhamnationwide completely again (Nov 15) |
| No per-platform tests beyond the engine's | | Recorded fixtures, below |

Two Phenom routes past the window were checked and rejected: URL parameters do not filter the
embedded search (every parameter tried returns the same 17,999), and the filtered search call needs
the page's CSRF token, which the rules do not replay. The job sitemaps list fewer jobs than the
search (CVS: 13,068 URLs against 17,999), so they do not close the gap either. Phenom tenants above
10,000 jobs therefore stay PARTIAL: their jobs are indexed, but closure never runs on a partial
snapshot.

### Recorded fixtures

`tests/record_fixtures.sh` records one list page and up to three job pages from a live tenant of each
platform (production fetcher: robots.txt, rate limits) into
`crawler/src/test/resources/fixtures/recorded/<platform>.json.gz` (4.7 MB for all). Requests past
those are recorded as 404 without being sent. `RecordedSpecsTest` replays every shipped spec against
its recording: it must ask for exactly the recorded requests and map at least one job with a title,
an absolute URL, an id and a company. A spec without a recording fails the build.

The replay found three real gaps on its first run:

- **JobBoard.io**: employers with their own parentheses ("Texas Tech University Health Sciences
  Center (Faculty)") lost the company. The title regex now splits on the last " at ".
- **Oracle** tenant edyo had no company: the API names none. Its job texts name Pike (Pike
  Electric), now in the tenant list.
- **ADP** tenant e5360e53 has no company and nothing public names it. ADP is the one platform whose
  data never names the employer, so its company has to come from the tenant list; the test allows
  that for ADP alone.

Workable is recorded (Nov 16). On Nov 15 its `/api/v2/.../jobs/{shortcode}` call answered the
crawler 429 on every attempt across three accounts, while curl from the same machine got 200; the
same calls ran 170 in a row on Nov 14. It is most likely Cloudflare limiting the Java client after
the day's repeated recording runs; on Nov 16 the same calls answered normally. The crawler did not
change its client to get past it.

**Netflix (Eightfold) is not read.** With the throttle the crawl got through the whole list (420
jobs) and into the job calls, then Netflix stopped answering: on Nov 16 even a single curl request
from this machine got no response for 10 minutes. Its edge blocks this IP after a few hundred
requests. The tenant is commented out of `local/live/tenants/eightfold.csv` so refreshes stop
knocking; re-enable it once it answers, at 0.5 requests a second. Eightfold itself works (CoStar
379 of 379).

## Phase B: new connectors

All checked live on Nov 15 against the tenants in `local/live/tenants/`.

| Platform | Group | How it is read | Live check |
| --- | --- | --- | --- |
| SmartRecruiters | 6 | `careers.smartrecruiters.com/{Company}/api/more?page=N` (HTML fragment, ungrouped, empty body past the last page) and each job page, which answers JSON to `Accept: application/json` (`api.smartrecruiters.com` is disallowed and not used) | Playtech 107 of 107, complete |
| SAP SuccessFactors | 5 | The search page `/search/?startrow=N` sorted by date (25 a page, total in the label) and one vendor template on every job page (microdata title, date, employer, address; `span.jobdescription`). jobs.sap.com answers this client 403 and is not a tenant | BNSF 99 of 99, complete |
| WeRecruit | 4 | `window.allOffers` on the tenant page, then the JobPosting on each offer page (its script type is written `ld&#x2B;json`, now accepted) | VACOA 67 of 67, complete |
| Zoho Recruit | 4 | HTML-escaped JSON in `<input id="jobs">`; the job page's `var jobs = JSON.parse('...')` (a JS string literal) only when the list lacks the description | Talenture 7 of 7, Jamp Pharma 60 of 60, GateSource HR 215 of 215, all complete (GateSource from the list alone; the others fetch job pages of 1.7 MB that stream slowly, so Jamp Pharma takes 32 minutes) |
| PageUp | 6 | List-only: the listing table's rows give title, location and a summary paragraph, 100 a page. Hosts throttle after 2 to 4 requests (HTTP 202, empty body, about 5 minutes), now seen on listing pages too, so one request per 150 s | U. Delaware 90, complete (one page) |
| TriNet Hire | 6 | Company job list, then the job page template (`h3.job-name`, `ul.job-meta`, `div.job-descr`); no posted date anywhere | RocketRide 2 of 2, complete; Academic Engagement Network has no openings (0, read from the page's own empty state) |
| Avature | 6 | The search page `/SearchJobs/?jobRecordsPerPage=12&jobOffset=N` (12 a page; the total in its legend), then the job page's labelled fields: the rich ones are the description; location under one of several labels, absent on some tenants. The RSS feed is not used: it serves the same 20 newest jobs at every offset | DFI Retail 115 of 115, complete (52 minutes: the host answers slowly) |
| JobKit | 8 | `/jobs.rss` with full descriptions, then each job's JobPosting for location and employer | Rails job board 15 of 15, complete (location on 1: most jobs are remote) |

Engine additions for these, each documented in `config/specs/README.md`: `pagination.end_on_empty`,
`pagination.max_results`, `list.extract_decode` / `detail.extract_decode` (`html`, `js`),
`detail.unless`, totals with thousands separators, `java.util.Date` text dates, and the encoded
`ld&#x2B;json` script type. And a guard for every paged spec: a page whose jobs were all seen
already stops paging and makes the board PARTIAL, never complete (Avature's feed would otherwise
have looped, and read as complete). Group 9 (HTML-parser tier) is removed: its seven platforms run in
groups 4, 5 and 6.

## The 24 platforms without a connector

| Platform | Kind | Reason |
| --- | --- | --- |
| CSOD | ATS | The only listing call needs the page's embedded session token (rule: not replayed) |
| Dover | ATS | robots.txt disallows `/api/`; job pages are JavaScript shells |
| Paycom | ATS | robots.txt disallows the jobs list |
| Paycor | ATS | robots.txt disallows `/career/CareerHome.action` |
| UltiPro | ATS | robots.txt disallows `*/JobBoardView` (the search); board pages embed only featured jobs |
| Niceboard | Job board | robots.txt disallows `/api/jobs`; the sitemap has no job URLs |
| Dayforce | ATS | The search POST depends on browser session state (403 without it) |
| BrassRing | ATS | Jobs load by the Angular app's own POST, not reproducible without its session |
| HiBob | ATS | Every path returns the same JavaScript shell; no public listing source |
| Paradox | ATS | Jobs load by script; no feed or embedded data |
| Polymer | ATS | The API answers 422; the board's JSON path sits behind a Cloudflare challenge |
| talentReef | ATS | Only through an undocumented internal host (`prod-kong.internal.talentreef.com`; rule: not used) |
| JobBoardFire | Job board | Sitemaps list pages and companies only; job data loads by script |
| JobBoard.com | Job board | Data is structured (sitemaps plus JobPosting), but list pages sit behind a browser check and its one customer board lists about 340,000 URLs, each a detail fetch: not refreshable at a polite rate |
| BambooHR | ATS | No tenant found in three Common Crawl crawls |
| Rival | ATS | No Rival-hosted career site found (SilkRoad's rebrand) |
| Talenetic | Job board | No live board found |
| JobMount | Job board | No customer board found |
| CareerSiteCloud | Job board | No customer board found |
| Artha | Job board | No customer board found |
| jobboardsoftware.com | Job board | The one board found returns 500 |
| Nodex | Job board | No board found |
| eJobBoard | Job board | No board found |
| ejobsitesoftware.com | Job board | Named customers are gone or serve empty pages |

The first six are what the robots.txt rule decides; the next six need browser session state, a
script runtime or an internal host; JobBoard.com is a volume limit; the last ten have nothing live to
crawl. A headless-browser tier would reach at most Paradox, JobBoardFire and perhaps HiBob, and only
where robots.txt allows the pages.

## Not done

- Netflix on Eightfold (see above).
- Ingestion of the eight new connectors through the pipeline: their crawls are verified with the
  production fetcher, but they have not yet run in the local stack into the index.
- Descriptions for Rippling and Phenom (list fields only).
- Jobiqo boards above about 1,400 jobs stay PARTIAL under the 10 s crawl delay; jobboardly's and
  Jobiqo's first tenants had robots.txt unavailable on Nov 15, so the crawler skips them
  (RFC 9309: unavailable means disallow-all) and their recordings use the next tenant.
