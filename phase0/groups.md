# Pipeline groups (Phase 0 assignment, Nov 11, 2026)

Ten grouped pipelines, each running the multiplexing `job_board_crawler` source for its subset. Grouping is by resource class and cadence, so that a slow or detail-heavy family cannot starve a cheap one. Settings are starting points for T3.4; S/M/L is the Phase 3 connector effort.

| # | Group | Platforms | Why together | Refresh | Workers | Effort |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | Hosted ATS, documented JSON | Greenhouse, Lever, Ashby, Workable, Recruitee, Pinpoint, Gem, HireHive, Breezy | One list call per tenant, ETag/304 on most, full records (Workable and Breezy need a detail call) | 2 h | 4 | S each |
| 2 | Enterprise suites, internal JSON | Workday, Oracle Recruiting, Eightfold, ADP | Undocumented endpoints, paging by offset, a detail call per job, largest tenants (Workday 2,000+ per tenant) | 6 h | 4 | M each |
| 3 | ATS feeds | Paylocity, Personio, Teamtailor, HireBridge | XML/RSS, one request per tenant or client id, cheap | 3 h | 2 | S each |
| 4 | ATS with embedded JSON | Comeet, Phenom, Rippling | Job data embedded in the HTML page; Phenom and Rippling need a detail fetch for the description; Phenom tenants are large | 6 h | 3 | M each |
| 5 | JSON-LD ATS, sitemap-listed | ApplicantPro, iSolvedHire, Hireology, iCIMS | Sitemaps give the job URLs (Hireology's is cross-tenant, 50,000 URLs per file); every job is a detail fetch | 12 h | 4 | S each, Hireology M |
| 6 | JSON-LD ATS, page-listed | CareerPlug, HiringThing, JazzHR, Jobvite, Freshteam, GoHire, JOIN, Kula, Manatal, Recooty, Trakstar, Deel, FirstStage, Taleo TBE | HTML list page plus one detail fetch per job; one shared JSON-LD adapter, per-platform list templates and soft-404 markers | 12 h | 4 | S each |
| 7 | Job boards with feeds | WP Job Manager, Jobboardly, JobBoard.io, Madgex, Jobiqo | RSS or embedded JSON; 20-item feeds on Madgex need paging; Jobiqo has a 10 s crawl delay | 4 h | 2 | S each, Jobiqo M |
| 8 | Job boards with JSON-LD | JBoard.io, SmartJobBoard, jobBoardHQ, JobBoard.com (when a 2nd board is found), JobKit (same) | HTML list plus detail fetch; multi-employer, so company comes from the record | 12 h | 2 | S each |
| 9 | HTML-parser tier (inactive) | SAP SuccessFactors, SmartRecruiters, PageUp, TriNet, WeRecruit, Avature, Zoho Recruit | Server HTML without feeds or JSON-LD; declined on Nov 11, kept as a group so enabling it later is a groups-file change | — | — | M each |
| 10 | Session or browser tier (inactive) | Dayforce, HiBob, BrassRing, Paradox, Polymer, JobBoardFire, talentReef | Need browser session state, an internal host or a token; nothing runs here under the current rules | — | — | L each |

Notes
- Groups 9 and 10 hold the 14 Yellow platforms (JobBoard.com and JobKit sit in group 8 pending a second board). Blocked (CSOD, Dover, Niceboard, Paycom, Paycor, UltiPro) and Ungraded platforms are not assigned.
- Group sizes are uneven on purpose: group 6 has 14 cheap platforms behind one adapter; group 2 has 4 heavy ones.
- Spike S7 decides whether ten pipelines fit one process; if not, groups 2, 4 and 5 are the ones to split out first.
