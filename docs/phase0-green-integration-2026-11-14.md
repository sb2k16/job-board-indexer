# Integrating every Phase 0 green platform (2026-11-14)

Phase 0 graded 46 platforms green. Nine ran on Nov 13 (Greenhouse, Lever, Ashby, Recruitee,
Pinpoint, Gem, HireBridge, WP Job Manager, Madgex). This adds the other 37, each a spec file plus
a tenant list, and the engine features they needed. Every spec was run against a live tenant with
the production fetcher (robots.txt, rate limits, retries) through `tests/live_spec.sh`.

## Engine features added

| Feature | For |
| --- | --- |
| `format: html`: list items, markers, totals and next links are regexes over the page; `{board}` in a regex is the quoted tenant | The 18 HTML-list platforms and the sitemap ones |
| `detail` block: one request per job, JSON, HTML or `jsonld` (the page's schema.org JobPosting, with a standard mapping) | Workable, ADP, Eightfold, Oracle, Workday, Breezy, and every JSON-LD platform |
| `list.extract`: a regex that pulls embedded JSON out of a page before parsing | Comeet, Rippling, Phenom, Jobiqo |
| `list.method: POST` and `list.body`, with `{offset}`/`{page}` in the URL or body | Workday, Oracle |
| `{board_raw}`, `{board_1}`, `{board_2}`...: a board made of several parts (`host\|tenant\|site`) | Workday, Oracle, Eightfold, Taleo, Comeet |
| `pagination.until_missing`: read numbered files until a 404 | Hireology's cross-tenant sitemaps |
| Dates `+0000` (no colon) and `YYYY-MM-DD HH:MM:SS` | Phenom, ApplicantPro, iSolvedHire, Recooty |

A detail page that is gone (404/410) or no longer holds a JobPosting is dropped as an expired job; if
every detail page lacks it the board fails, so a redesigned platform cannot read as "every job
closed". Completeness is judged on the list, so a job expiring mid-crawl does not make a long crawl
partial forever.

## Live results

| Platform | Group | Tenant checked | Jobs | Complete | Notes |
| --- | --- | --- | --- | --- | --- |
| Breezy HR | 1 | vetsez | 68 | yes | descriptions from each job's JobPosting |
| HireHive | 1 | irish-wheelchair-association | 30 of 30 | yes | |
| Workable | 1 | luxasia | 170 | yes | descriptions from the v2 job call; 1 request/s |
| Workday | 2 | CoStar | 373 of 373 | yes | POST search plus a detail call per job; the search serves at most 2,000 results |
| Oracle Recruiting | 2 | edyo (CX) | 887 of 887 | yes | 15 minutes at 1 request/s |
| Eightfold | 2 | CoStar | 379 of 379 | yes | |
| ADP | 2 | e5360e53 | 36 of 36 | yes | `$top=20` returns 19, so paging steps by what came back |
| Paylocity | 3 | 27577 | 2 | yes | |
| Personio | 3 | desotec | 38 | yes | this tenant's feed has no descriptions |
| Teamtailor | 3 | vardaga | 100 | yes | |
| Taleo Business Edition | 3 | AlMansoori (lde01) | 295 | yes | the RSS feed, not the HTML list (whose paging is a session-bound "next") |
| Comeet | 4 | silverfort | 33 | yes | |
| Rippling | 4 | nocd | 86 | yes | totalItems counts a job once per location, so it is not compared |
| Phenom | 4 | O'Reilly | 20 (2 pages, capped) | n/a | 19,200 jobs, 10 a page: extraction checked on two pages; a full read is about 1,900 requests and runs in the heavy group |
| ApplicantPro | 5 | opc | 22 | yes | job sitemap; the sitemap's bare /jobs/ entry is excluded |
| iSolvedHire | 5 | directviz | 32 | yes | |
| iCIMS | 5 | careers-actslife | 488 | yes | 10 list pages via rel=next |
| Hireology | 5 | burnhamnationwide | — | — | every job page answered 502 on Nov 14, for every tenant tried; the spec stands, and each crawl is recorded FAILED (retryable) until the pages return |
| CareerPlug | 6 | lil-kickers-careers | 55 | yes | |
| Deel | 6 | airbase | 11 | yes | |
| FirstStage | 6 | quantumbase | 3 | yes | its JobPosting has no location |
| Freshteam | 6 | talent360 | 23 | yes | |
| GoHire | 6 | taisan | 10 | yes | |
| HiringThing | 6 | oppco | 2 | yes | |
| JazzHR | 6 | instinctscience | 6 | yes | |
| Jobvite | 6 | egnyte | 27 | yes | |
| JOIN | 6 | 100marketing | 1 | yes | |
| Kula | 6 | shinkei | 18 | yes | |
| Manatal | 6 | ffiadvisory | 23 | yes | answered 429 at 2 requests/s; now 0.5 requests/s |
| Recooty | 6 | gururo | 10 | yes | |
| Trakstar | 6 | bennington | 8 | yes | |
| JobBoard.io | 7 | hireheroesusa | 50 | yes | |
| Jobboardly | 7 | medicalcourierjobs | 316 | yes | |
| Jobiqo | 7 | miamijobs | 20 (2 pages, capped) | n/a | 10 s crawl delay: a board over ~1,400 jobs cannot be re-read within the 4 h refresh (miamijobs has 20,055). mediajobcenter's robots.txt was unavailable, which RFC 9309 treats as disallow-all, so the crawler does not fetch it |
| JBoard.io | 8 | jobs.nuclearinst.com | 435 | yes | 11 minutes: a detail page per job |
| SmartJobBoard | 8 | psychologicalscience | 74 of 74 | yes | |
| jobBoardHQ | 8 | jbcjobs | 20 | yes | the site lists "only the top 500" |

Summary: 43 of the 46 green platforms read a live board completely. Phenom and Jobiqo extract
correctly but their large boards were checked on two pages only. Hireology could not be checked
because its job pages were down.

## Not done

- Descriptions for Rippling and Phenom (list fields only; a detail call per job would be
  hundreds to 19,000 requests a refresh).
- Recorded fixtures and unit tests per new platform (the spec README asks for them); the engine
  features have unit tests (`HtmlAndDetailTest`, `DateFormsTest`), and the live runs above are the
  platform checks.
- Phase 0 regrades of the yellow platforms (SmartRecruiters and others found structured on Nov 11).
