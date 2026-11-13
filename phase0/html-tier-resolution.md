# Resolution of the seven "HTML-parser tier" platforms (Nov 11, 2026, batches 22–25)

Question: for each of the seven Yellow platforms that Phase 0 filed under "server HTML without feed or JSON-LD" (SAP SuccessFactors, SmartRecruiters, PageUp, TriNet, WeRecruit, Avature, Zoho Recruit), what does the live page actually give, and can the gap be closed individually? Every claim below is from a saved response in `samples/batch22`–`batch25` (fetched from a t3.micro in us-east-1, stdlib probe, robots honoured) or from earlier batches named inline. Nothing is inferred from vendor documentation.

## Why Phase 0 misgraded them

Three defects in the Phase 0 method, found by re-reading the samples:

1. The validator only looked for JSON-LD in `<script type="application/ld+json">`. It missed schema.org **microdata** (SmartRecruiters) and an ld+json tag whose `+` is HTML-encoded as `&#x2B;` (WeRecruit).
2. Several "detail page" samples were **expired postings**: BNSF and Illinois on SuccessFactors ("Sorry, this position has been filled"), AbbVie and ServiceNow on SmartRecruiters (`jobad--empty-state`), Jamp Pharma and Recrute Action on Zoho ("Cette offre d'emploi n'est plus disponible"). An expired page has no description, which was read as "no description".
3. Two WeRecruit "detail" samples were the spontaneous-application form, not a job.

Batch 22 re-fetched live jobs chosen from current list pages, sitemaps and feeds.

## Per-platform result

| Platform | Tenants checked live | Title | Description | Location | Posted date | Enumeration | Verdict |
| --- | --- | --- | --- | --- | --- | --- | --- |
| SmartRecruiters | AbbVie ×2, Playtech ×2, SmartRecruiters ×1 (b22, b23, b09) | microdata `title` | microdata `description` (also `responsibilities`, `qualifications`) | `addressLocality`/`addressRegion`/`addressCountry` | `datePosted`, `validThrough` | list page 51 items; `careers.smartrecruiters.com/{Company}/api/more?page=N` returns the next 24 `<li class="opening-job">` as an HTML fragment; grouped boards use `/api/groups?page=N` with `data-groups-pages` on the list | **Green.** Structured (schema.org microdata), not an HTML parser. |
| SAP SuccessFactors | ASSA ABLOY ×2, BNSF, State of Illinois (b22) | `h1` / `data-careersite-propertyid="title"` | `span.jobdescription` (`propertyid="description"`) | `span.jobGeoLocation` ("Centralia, IL, US, 62801") | `<meta itemprop="datePosted">` on every page; sitemap `<lastmod>` per URL | sitemap (ASSA ABLOY 1,037 URLs) or `/search/` list, 50 `jobTitle-link` per page | **Green with one fixed vendor template.** Same ids and classes on all 4 tenants. |
| WeRecruit | VACOA, myJob Intérim, Groupe Bodemer (b22) | JSON-LD `title` | JSON-LD `description` (551–2,270 chars) | JSON-LD `PostalAddress` with locality, region, postal code, country | JSON-LD `datePosted`; list `PublicationStartDate` | list page embeds `window.allOffers = [...]` (67, 97, 93 offers) with `Url`, `Title`, `Address_City`, `Address_Region`, `Company_Name`, `PublicationStartDate`, `Id` | **Green.** Embedded JSON list plus JobPosting JSON-LD detail (also `employmentType`, `baseSalary` where set). |
| Zoho Recruit | GateSource HR, Jamp Pharma, Talenture, TGO Corp (b22) | record `Posting_Title` | record `Job_Description` (5,362–18,088 chars) on all 4 detail pages, including the two whose list page omits it | `City`, `State`, `Country`, `Zip_Code` | `Date_Opened` | list page hidden input `id="jobs"` (7–215 records with `id`); detail at `/jobs/{site}/{id}/{slug}` embeds `var jobs = JSON.parse('…')` with the full record | **Green.** Embedded JSON on both pages; the earlier "script shell" finding came from expired jobs. The JS string needs a one-pass unescape (`\x22`, `\/`, `\-`) before JSON parsing. |
| PageUp | ufl (b17), Simonds 895, Monash 513, Aston Martin ×2, Michigan Tech ×2, Delaware ×2 (b22–b24) | `#job-content h2` | `#job-details` table or `#job-content` body | `.location` | `.open-date time[datetime]`, `.close-date` | list table rows (`a.job-link`, `.location`, `.close-date`, `tr.summary`) | **Green with a per-host rate budget** (burst of 2–4 trips a 202 throttle; clears within 300 s). See below. |
| TriNet | Academic Engagement Network, RocketRide (detail pages on app.trinethire.com, both with `job-name`, `job-meta`, `job-descr`) | `h3.job-name` | `div.job-descr` | first `ul.job-meta li` | none on the page | list page links | **Green for required fields, no posted date** (optional in the model). |
| Avature | Bloomberg, Koch, DFI Retail, Siemens Energy (b15, b22) | `og:title` | longest `article__content__view__field__value` (4,501–18,473 chars) | labelled field on 3 of 4 tenants, under three different labels: "Location", "Location(s)", "Country/Region"; Siemens Energy has **no location field** (the word appears only as a heading inside the description) | RSS `pubDate` on 3 tenants; labelled "Job Posting date" on 1 | RSS `/SearchJobs/feed/` (b15) | **Yellow, tenant-specific.** Title, description and date are vendor-wide; location needs a per-tenant label map and is absent on one of four tenants. |

## PageUp access pattern (batches 23–25)

- Every PageUp host serves list pages (`/cw/{lang}/listing/`) to a fresh IP: ufl, unicef, udel, astonmartin, mtu, pageuppeople 513 and 895 all 200.
- Detail pages are served to a fresh IP for the first two or three requests per host, then every request on that host returns **HTTP 202 with an empty body from CloudFront**, regardless of URL, for longer than 60 s. Observed sequence on careers.udel.edu and employment.mtu.edu (b24): request 1 200, request 2 (5 s later) 200, request 3 (60 s later) 202, request 4 202. Aston Martin allowed 3 in a row (b23).
- An expired job id does not produce a 202: Aston Martin 511982 redirects to the listing with 200 (b24). So the Phase 0 202s were the throttle, not the job.
- robots.txt on these hosts has 32 rules, no `Crawl-delay`, and nothing covering `/cw/`.
- Recovery window (batch 25, fresh IP): udel served 4 detail requests in a row at 2 s spacing; mtu served 2, then 202 on requests 3 and 4. After a 300 s pause both hosts served 200 again, and again after a further 600 s. So the throttle trips on a short burst (2–4 requests, varying by host), is per host, and clears within 5 minutes. No `Retry-After` header is sent.

Design setting for PageUp: one detail request per host per 150 s, no bursts, 202-without-marker treated as a failed fetch and retried after 300 s. A 200-job tenant then takes about 8 h per full pass, which fits the 12 h refresh of groups 5 and 6. The 150 s figure is a conservative choice between the measured burst limit and the measured recovery, not a measured steady-state rate; T-phase should measure the steady-state rate on one tenant before committing a cadence.

A 202 without the board marker is already a failed fetch under the Nov 10 rule, so the connector cannot mis-record it as zero jobs. Whether PageUp is Green depends on the recovery window: if a host accepts N detail requests per window W, a tenant with J jobs needs J/N windows for a full pass, and the daily budget decides whether that fits the refresh cadence.

## Corrections to earlier Phase 0 statements

- "SmartRecruiters detail pages carry no JSON-LD" is true but irrelevant: they carry microdata.
- "SuccessFactors detail pages carry no JSON-LD, needs an HTML parser": they carry `itemprop="datePosted"` and a fixed vendor template; the parser is a handful of selectors, the same on four tenants.
- "Zoho detail pages are script shells; description on 1 of 3 tenants": the shells were expired jobs; live detail pages embed the full record on 4 of 4.
- "WeRecruit JSON-LD present but not JobPosting": the JobPosting block was missed because of `&#x2B;`.
- "PageUp detail pages answer 202 empty (a bot gate)": it is a per-host request throttle, not a per-page gate.
- No other platform among the 78 carries JobPosting microdata (full scan of all saved samples, batches 01–21): the microdata miss is specific to SmartRecruiters.

## Effect on the groups file (not yet applied)

- Group 1 or 6 (one shared adapter): SmartRecruiters (microdata adapter, sibling of the JSON-LD adapter).
- Group 4 (embedded JSON): Zoho Recruit (two-page embedded JSON), WeRecruit (embedded list + JSON-LD detail could also sit in group 6).
- Group 5/6 (sitemap or page list + fixed template): SAP SuccessFactors, TriNet, PageUp (with a per-host budget).
- Group 9 (HTML-parser tier) keeps only Avature.

## Probe changes

`probe.py` gained an optional per-target `sleep` (seconds before the request) for the timing experiments. Batches 22–25 ran on four t3.micro sessions, each terminated after its batch; about 60 requests in total.
