# The 24 unsupported platforms: research and options (2026-11-22)

Re-examined against the crawl rules, which stay fixed: obey robots.txt, never replay a page's
embedded session tokens, never use undocumented internal hosts. Evidence comes from vendor
documentation and from live probes on Nov 22 with the crawler's user agent, about one request
every 1.5 s.

## What changed since Phase 0 (Nov 6 to 11)

| Platform | Then | Now |
|---|---|---|
| **Niceboard** | robots.txt disallowed `/api/`; the sitemap had no job URLs | robots.txt now allows the board's own `/api/jobs` list. The sitemap route was tried first and dropped: 206 of 209 sitemap URLs answered 404. **Built** (`fbf8eb3`) on `/api/jobs`: avajobboard 209/209 in 7 requests |
| **Paycom** | robots.txt disallowed the jobs list | robots.txt now explicitly allows `/v4/ats/sitemap.php`, `/api/ats/job-postings/`, `/api/ats/portal-settings` and `/career-portal/sprawl.json`. The Phase 0 portal key's sitemap answers 404 (the key may be stale), so a live portal key is still needed to confirm |
| **BambooHR** | no tenants found | Real tenants answer `{t}.bamboohr.com/careers/list` with JSON (trimgroup: 46 jobs) and `/careers/{id}/detail` with the description, datePosted, compensation and locationType. robots.txt: `Disallow:` with nothing after it (allows all). These are the requests the public careers page itself makes. Still blocked on **finding tenants** (search engines do not surface tenant subdomains; the Common Crawl index answered 504 on Nov 22) |

## Recommendation by platform

### A. Buildable now within the rules (spec only, no engine work)

| Platform | Route | Notes |
|---|---|---|
| Niceboard | `/api/jobs` (the board page's own list call, allowed by robots.txt) | **Built** (`fbf8eb3`). More tenant boards (`*.niceboard.co` plus custom domains) is discovery work |
| BambooHR | `/careers/list`, then `/careers/{id}/detail` | **Built** (Nov 23): trimgroup 46/46, nycballet 3/3 complete. Only 3 tenants so far; tenant discovery is open (section E) |

### B. Possible with more work, still within the rules

| Platform | Route | Work |
|---|---|---|
| **JobBoard.com** | Sitemaps list every job, and detail pages carry `JobPosting` | Too large for a full refresh (about 340,000 URLs on one board), but `lastmod` makes it incremental: fetch only new or changed URLs. About 4 days for the first pass at 1 request a second, then a fraction of that per day. Needs a "sitemap delta" mode in the spec engine |
| **Paradox** (McDonald's) | `jobs.mchire.com/sitemap.xml` (about 1,000 sub-sitemaps, ~90 jobs each) lists job URLs; robots.txt allows all | Job pages answer HTTP 202 with a 2 KB holding page, a bot check. Not something to get around. Ask Paradox or the employer for a feed, or leave it out |

### C. Only with a headless browser, and a rules decision first

Dayforce, HiBob and Polymer (and BrassRing, CSOD, Paradox as alternatives) load jobs only by
running the page's own JavaScript.

- **A headless browser (Playwright)** would render them. Cost: about 0.5 to 1 GB of memory per
  browser and much slower crawls, so a separate "render" Deployment.
- **It is your rules decision, not a technical one.** The browser obtains its session the normal
  way, which is not replaying a token we copied. But the page's scripts then make the same
  requests the rule was written about.
- **It does not unlock everything:**
  - **Dover:** the page's scripts call `/api/`, which robots.txt disallows, so a browser does not
    make Dover allowed.
  - **Polymer:** sits behind a Cloudflare challenge. Getting past bot challenges is out of bounds.

| Platform | With a headless browser | Without |
|---|---|---|
| Dayforce | The search page renders the job list; detail pages already embed the full posting | No public list. Its documented job-feed service needs a login issued by each customer |
| HiBob | Renders | The official Hiring API needs a token issued by each customer |
| CSOD | Renders without us copying the token | Its listing call needs the page's session token: excluded on Nov 11 |
| Polymer | Cloudflare challenge: no | No |

### D. Stays out under the current rules

| Platform | Why |
|---|---|
| Paycom | Rechecked Nov 23. The allowed `/api/ats/job-postings/` calls only work with the `sessionJWT` the portal page embeds; replaying it breaks the token rule. The allowed `/v4/ats/sitemap.php` answers 404 for live portal keys too. Its Indeed feed is private |
| BrassRing (IBM Kenexa) | Rechecked Nov 23. IBM's Talent Gateway SearchAPI needs an API user each customer creates in BrassRing admin; it is not a public endpoint. The RSS output of the job search loads through the same session-bound page |
| UltiPro (UKG) | robots.txt still disallows `*/JobBoardView`. Both the list and the detail data load from it, and the board page embeds no jobs (`initialFeaturedOpportunities: []`). UKG's Indeed feed is pushed privately |
| Dover | robots.txt disallows `/api/`, the only source of job data. The sitemap (10,124 job URLs) points at JavaScript shells |
| Paycor | robots.txt disallows `/career/CareerHome.action`. Paycor documents an ATOM feed for customers' own careers pages, but its URL could not be confirmed (the setup PDF's text is unreadable) |
| talentReef | Data only through an undocumented internal host. Its Indeed feed is private |

### E. Discovery, not access: the 10 with no boards found

BambooHR (now proven accessible), Rival, Talenetic, JobMount, CareerSiteCloud, Artha,
jobboardsoftware.com, Nodex, eJobBoard, ejobsitesoftware.com.

- **BambooHR:** worth real discovery work.
  - Mine careers links (`*.bamboohr.com/careers`) from company websites, such as the YC list.
  - Use the Common Crawl index once it answers.
  - Look through customer case studies.
- **The other nine:** small or defunct job-board vendors. Phase 0 found no live customer for any
  of them. Recommend recording them as "no live customers found" and stopping, unless one turns
  up through "powered by" footer searches.

## Summary (corrected Nov 23)

| Outcome | Count | Platforms |
|---|---|---|
| Built since this research | 2 | Niceboard (`fbf8eb3`), BambooHR |
| More work, within the rules | 2 | JobBoard.com (sitemap deltas), Paradox (only via a feed from the vendor) |
| Needs a headless browser plus a rules decision | 3 | Dayforce, HiBob, CSOD |
| Out under the current rules | 7 | UltiPro, Dover, Paycor, talentReef, Polymer, Paycom, BrassRing |
| No live customers found | 9 | Rival, Talenetic, JobMount, CareerSiteCloud, Artha, jobboardsoftware.com, Nodex, eJobBoard, ejobsitesoftware.com |

Correction: the first version of this doc counted Paycom as buildable and BrassRing as "more work". Both
turned out to need either a page-embedded session token (Paycom) or a customer-created API user (BrassRing).

**Realistic ceiling within the current rules:** 54 + 2 built + 1 (JobBoard.com) = **57 of 78**. With a
headless browser and your approval: up to **60**. "All 78" is not reachable without changing the rules or
finding customers for the 9 defunct vendors.

## Sources

- HiBob Hiring API (needs a service-user token): https://apidocs.hibob.com/docs/how-to-use-hiring-api-careers-page
- Dayforce job feeds (basic auth): https://help.dayforce.com/r/documents/Dayforce-RESTful-Web-Services-Developer-Guide/RESTful-Get-Job-Postings
- UKG Indeed integration (private push): https://library.ukg.com/prebuilt/newgen/Recruiting/Content/Indeed_Job_Postings.html
- IBM Talent Gateway jobs API (BrassRing): https://www.ibm.com/docs/SSEUFV/2_Configuration/11_IntegrationsAndAPIs/4d_TGGWandCandidateAPIRefs.html
- BrassRing SearchAPI reference: https://cdn.us.document360.io/4bb62c4c-9973-48ec-ab93-23d4b1755503/Images/Documentation/SearchAPI.pdf
- Niceboard sitemap for Google for Jobs: https://help.niceboard.co/article/56-how-to-automatically-get-your-jobs-on-google-for-jobs
- Paradox job feed guide: https://paradox.helpjuice.com/en_US/job-feed-technical-guide
- Paycor ATOM feed setup: https://static.helpjuice.com/helpjuice_production/uploads/upload/image/16049/4054098/1715627444854-Paycor_ATOM%2BFeed%2B%2B%2BIframe%2BSetup.pdf
- Cornerstone (no public feed): https://jobspipe.dev/sources/cornerstone.md
- BambooHR careers JSON as used by scrapers: https://dev.to/devil_scrapes/bamboohr-jobs-scraper-pull-every-open-role-from-any-bamboohr-careers-site-1oja
