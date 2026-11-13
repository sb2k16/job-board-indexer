# Phase 0 coverage matrix (pass 5, Nov 11, 2026)

Green 46, Yellow 16, Blocked 6, Ungraded 10.

Grades: Green means real jobs from a structured source on at least 2 tenants; an HTML job list counts when its detail pages carry JobPosting JSON-LD. Yellow means reachable but needs effort or has only 1 tenant, and many Yellow rows have not had fields validated. Blocked means robots.txt disallows the path needed to list jobs (RFC 9309 longest match), or the only listing call needs the page's session token, which is not replayed (decision of Nov 11). Ungraded means no usable tenant or evidence yet, often because a guessed URL was wrong, so it says nothing about the platform itself.

| # | Platform | Kind | Grade | Tenants with results | Notes |
| --- | --- | --- | --- | --- | --- |
| 1 | ADP | ATS | Green | workforcenow cid e5360e53 (37 total), 56385b92 (3), 7a599ddd (1) | Keyless JSON job-requisitions API with totalNumber and paging; tenants found in Common Crawl (Nov 10). myjobs.adp.com is a separate JavaScript shell |
| 2 | ApplicantPro | ATS | Green | opc 16, cayugahealthsystem (job sitemaps) | Per-tenant /job_site_map.xml plus a vendor index of tenant sitemaps; JobPosting JSON-LD on detail pages (checked on opc) |
| 3 | Ashby | ATS | Green | ramp 170; notion 150; openai 822 | JSON API, ETag and 304; bogus tenant 404; robots.txt returns 401 (treated as no rule); no company field |
| 4 | Avature | ATS | Yellow | bloomberg 20, dfiretailgroup 20, Siemens Energy 20 | RSS at /SearchJobs/feed/ on 3 tenants, but it carries only title, link and date (the description field is the requisition id) and the detail page has no JSON-LD, so description and location need an HTML parser (field validation, Nov 11); robots.txt disallows missionpethealth |
| 5 | BambooHR | ATS | Ungraded |  | No tenant hosts in 3 Common Crawl crawls (Aug to Oct 2026); guessed slugs, a bogus tenant and a slug taken from a scraper's sample output (cascadedental) all return the vendor page (soft 404) |
| 6 | BrassRing | ATS | Yellow | Hobby Lobby 25879/5295, GardaWorld 26003/5325, BAE Systems 25771/5403, Cardiff University 30011/5460 | partnerid/siteid pairs from Common Crawl; pages load with an empty preload JSON and the Angular app fetches jobs with its own POST to /TGnewUI/Search/Home, which was not replicated; no robots.txt |
| 7 | Breezy HR | ATS | Green | vetsez 69, german-american-chambers 39, mindoula 30, breezy 3 | Keyless /json with name, location, published_date, salary; 304 supported |
| 8 | CareerPlug | ATS | Green | i9-sports-careers, lil-kickers-careers, uptowncheapskate | HTML list plus JobPosting JSON-LD on detail pages (2 tenants checked), with salary and identifier |
| 9 | Comeet | ATS | Green | silverfort 28, cyera 251, quantummachines | Page embeds COMPANY_POSITIONS_DATA JSON with uid, name, location, department, time_updated; pages branded Spark Hire Recruit |
| 10 | CSOD | ATS | Blocked | turnerconstruction, worldbankgroup, illinois | The only listing call needs the page's embedded session token; user decided on Nov 11 not to replay it. Crawl delay 10s |
| 11 | Dayforce | ATS | Yellow | wheeler, wfad, twh, hajoca, dbi, ohmc | /api/geo/{ns}/jobposting/search is a POST: a direct GET gets 405 and a direct POST with the page's body gets 403, so the call depends on browser session state. Detail pages embed the full posting in a dehydrated query cache; no sitemap. No keyless path found |
| 12 | Deel | ATS | Green | airbase, evident-insights, klarna, BW Epic Kosan | Board page carries an ItemList JSON-LD of job-detail URLs; detail pages carry JobPosting JSON-LD (3 tenants checked); tenant slugs from Common Crawl |
| 13 | Dover | ATS | Blocked | jobs.dover.com sitemap 9,738 URLs | robots.txt disallows /api/ and detail pages are JavaScript shells (424 characters), so the cross-tenant sitemap gives URLs but no job data |
| 14 | Eightfold AI | ATS | Green | costar 363, netflix 404 | Keyless JSON /api/apply/v2/jobs with count; robots.txt allows /careers and /api/apply. bostonscientific and morganstanley returned 403. Pass 1 wrongly marked this disallowed (robots parser bug) |
| 15 | FirstStage | ATS | Green | quantumbase, intersect-mbo (JSON-LD), agility-gwg 81 links | Server HTML job lists; JobPosting JSON-LD on detail pages /jobs/{id}/view (2 tenants); wayve moved (404) |
| 16 | Freshteam | ATS | Green | talent360 20, littlelives-talent 11 | HTML list plus JobPosting JSON-LD on detail pages (JSON-LD checked on talent360) |
| 17 | Gem.com | ATS | Green | the-boring-company 134, firestorm 65, function-health 33 | Keyless JSON job_board API with title, location, content, first_published_at |
| 18 | GoHire | ATS | Green | shri-educare, taisan, wellnessliving | HTML list plus JobPosting JSON-LD on detail pages, with salary and validThrough |
| 19 | Greenhouse | ATS | Green | stripe 768; airbnb 200; figma 143 | JSON API, ETag and 304; bogus tenant 404 |
| 20 | HiBob | ATS | Yellow | credo, interwell, ustwo, ecologi, zodiamarkets | Every path tried, including detail pages, sitemap.xml and /api/jobs, returns the same 94-character JavaScript shell; no public listing source found |
| 21 | HireBridge | ATS | Green | rss 6741 (71 items), 6284 (10), 5559 (1) | Per-client RSS at rss.hirebridge.com/{cid}.rss with title, link, pubDate and description; cids from alert and jobseeker2 URLs in Common Crawl; career pages are script-rendered |
| 22 | HireHive | ATS | Green | irish-wheelchair-association 30, jsc-bank-of-georgia 30, voltedge 8 | Keyless JSON /api/v1/jobs with title, location, publishedDate, description |
| 23 | Hireology | ATS | Green | careers.hireology.com sitemaps (50,000 URLs in one file) | Cross-tenant job sitemaps; JobPosting JSON-LD on detail pages on 2 tenants |
| 24 | HiringThing | ATS | Green | wealthcounsel-llc, oppco, pacmin-inc | HTML list plus JobPosting JSON-LD on detail pages (2 tenants); soft 404 on unknown tenants |
| 25 | iCIMS | ATS | Green | actslife 50, us-erac 30, covenanthealth 20 | HTML list plus JobPosting JSON-LD on detail pages (2 tenants), with validThrough |
| 26 | iSolvedHire | ATS | Green | directviz 34, midwestcityok 28 (job sitemaps) | Same markup and job_site_map.xml as ApplicantPro, so likely the same platform; JobPosting JSON-LD on detail pages (checked on directviz) |
| 27 | JazzHR | ATS | Green | fincons 62, afterschoolmatters 12, instinctscience 8 | HTML list plus JobPosting JSON-LD on detail pages (2 tenants); soft 404 on unknown tenants |
| 28 | Jobvite | ATS | Green | medvet 232 links, pilgrims, ziffdavis, egnyte | HTML list plus JobPosting JSON-LD on detail pages (2 tenants), with salary; soft 404 on unknown tenants |
| 29 | JOIN.com | ATS | Green | join, 100marketing | HTML list plus JobPosting JSON-LD on detail pages (2 tenants) |
| 30 | Kula.ai | ATS | Green | avantstay 54, 10xgenomics 36, shinkei 17 | HTML list plus JobPosting JSON-LD on detail pages (2 tenants) |
| 31 | Lever.co | ATS | Green | spotify 87; palantir 309 | JSON API, ETag and 304; crawl delay 1s; bogus tenant 404; no company field; responses not gzipped |
| 32 | Manatal | ATS | Green | ffiadvisory, the-pss-group, yoailabs | HTML list on careers-page.com; JobPosting JSON-LD on 2 of 3 detail pages checked |
| 33 | Oracle Recruiting | ATS | Green | oracle 2832 total, jpmc, edyo | Keyless recruitingCEJobRequisitions JSON with totals and paging; undocumented, so may change |
| 34 | PageUp | ATS | Yellow | ufl 21 links, unicef 42 links, pageuppeople 895 | Server HTML lists on custom domains (fingerprint /cw/en-us/listing/); the ufl detail page has no JSON-LD; astonmartin, mtu and unicef detail pages answer 202 with an empty body (a bot gate); needs an HTML parser |
| 35 | Paradox | ATS | Yellow | jobs.mchire.com (McDonald's) | One Paradox-run career site loads; jobs load by script |
| 36 | Paycom | ATS | Blocked | portals 0013BFED4F70…, 005B8FC0D5FA… | Real portals from Common Crawl: career-page is allowed but a script shell; the jobs list /v4/ats/web.php/jobs is disallowed; detail pages carry JobPosting JSON-LD but there is no allowed listing or sitemap |
| 37 | Paycor | ATS | Blocked | clientId 8a7883d0843a56f5… | Rechecked with a real clientId from Common Crawl: /career/CareerHome.action is disallowed |
| 38 | Paylocity | ATS | Green | xml 27577 (2 jobs), xml 26333 (2 jobs) | Public XML feed at recruiting.paylocity.com/recruiting/jobs/xml?id= with title, date, requisition id, URL; tenant ids from Common Crawl. Listing pages are script-rendered |
| 39 | Personio | ATS | Green | stark 7, desotec 38 (XML) | Public XML feed at {tenant}.jobs.personio.com/xml with id, name, office, department, createdAt and descriptions; 304 works. Tenants on jobs.personio.de were Disallow: / in pass 1; the .com tenants allow crawling |
| 40 | Phenom People | ATS | Green | cvshealth 18799, oreillyauto 19142, dollartree 18692 | Search-results page embeds job JSON with totalHits and jobSeqNo; vendor confirmed by page fingerprint; tenants from the /us/en/job/ URL pattern in Common Crawl |
| 41 | Pinpoint | ATS | Green | indrive 170, forterro 29, arrowglobal 16, workwithus 6 | Keyless /postings.json with compensation fields; 304 supported |
| 42 | Polymer | ATS | Yellow | polymer | API /v1/hire/organizations/{slug}/jobs returns 422 with and without Accept and paging params; no jobs.polymer.co hosts in 3 crawls; the board's JSON path hits a Cloudflare challenge |
| 43 | Recooty | ATS | Green | gururo, matthew-weiner, stand-by-personnel | HTML list plus JobPosting JSON-LD on detail pages (2 tenants) |
| 44 | Recruitee | ATS | Green | arsipa 111, 60secondstonapoli 78, puregymswiss 35, bunq 23 | Keyless JSON /api/offers with company_name |
| 45 | Rippling | ATS | Green | popstroke 161, nocd 99, rippling | Next.js page embeds the job-posts query with totalItems and paging |
| 46 | Rival | ATS | Ungraded |  | SilkRoad rebranded to Rival in 2023; its case-study customer (Van Andel Institute) now runs Workday; no Rival-hosted career site found |
| 47 | SAP SuccessFactors | ATS | Yellow | jobs.sap.com, assaabloy, illinois, bnsf (jobs2web.com) | Server HTML lists and job sitemaps (assaabloy 1,037 URLs) on 4 tenants with one template, but detail pages carry no JSON-LD, so it needs one HTML parser. Siemens Energy, counted here in pass 1, is Avature |
| 48 | SmartRecruiters | ATS | Yellow | AbbVie, Bosch, Playtech, SmartRecruiters, Visa | api.smartrecruiters.com is disallowed; jobs.smartrecruiters.com/{Company} has no robots.txt and serves job lists as HTML, detail pages carry no JSON-LD and there is no sitemap, so it needs an HTML parser |
| 49 | talentReef | ATS | Yellow | all clients (190,683 postings) via an internal host | User chose on Nov 11 not to rely on the career site's undocumented Elasticsearch proxy (prod-kong.internal.talentreef.com); no documented public source found yet |
| 50 | Taleo | ATS | Green | TBE: lde ALMANSSP 34 links, tre BAA9G6 33 links | Taleo Business Edition career centres (/ats/careers/v2/searchResults) serve HTML lists and JobPosting JSON-LD on detail pages (2 tenants). Taleo Enterprise careersection sites (weyerhaeuser, watercorp, who, baptisthlth load; cognizant, uhg, vulcanmat unavailable) render their lists by script, so Enterprise tenants are not covered |
| 51 | Teamtailor | ATS | Green | vardaga 100, dfdsprofessionals 31, coffeedesk 9, career 16 | RSS /jobs.rss works on these tenants; it returned 404 on 7 others, so it may be a per-tenant setting |
| 52 | Trakstar | ATS | Green | bracinternational, bennington, whiteoak | HTML list plus JobPosting JSON-LD on detail pages (2 tenants) |
| 53 | TriNet | ATS | Yellow | academic-engagement-network, rocketride | List and detail pages are server HTML without JSON-LD; needs an HTML parser |
| 54 | UltiPro | ATS | Blocked | CHE1023CRRY, CRE1007CREHR, EOS1000EOSI, MEN1002MENZI, GEO1010GEODI | robots.txt allows */JobBoard/ pages but disallows */JobBoardView, the search API; board pages embed only a featured subset (initialFeaturedOpportunities). Pass 1 reason was wrong (parser bug) |
| 55 | WeRecruit | ATS | Yellow | vacoa 68, myjob-interim 98, groupe-bodemer 94 | Server HTML with /offres/ links; JSON-LD present but not JobPosting |
| 56 | Workable | ATS | Green | valsoft-corp 246, tiger-analytics 238, luxasia 162, mercari 140 | Keyless widget JSON with locations and dates; descriptions need the detail call |
| 57 | Workday | ATS | Green | nvidia 2000 total, intel, salesforce | Keyless internal POST with totals and paging; detail fetch needed; undocumented |
| 58 | Zoho Recruit | ATS | Yellow | talenture 7, tgocorp 21, jamppharma 60, gatesourcehrus 215, recruteaction 39 | /jobs/Careers embeds the Job_Openings records (Posting_Title, City, Country, Job_Type, Remote_Job, id) in a hidden input, but descriptions and dates appear on 1 of 3 tenants checked and detail pages are script shells, so the required description is missing (field validation, Nov 11); sitemap disallowed |
| 59 | Niceboard | Job board | Blocked | avajobboard, wisconsinliteracyjobs | Jobs load from /api/jobs, which robots.txt disallows; sitemap has no job URLs |
| 60 | WP Job Manager | Job board | Green | lathire 10, oavt 10, employmentoptions (19 pages) | RSS ?feed=job_feed on 2 sites and /jm-ajax/get_listings/ JSON with max_num_pages on a third; sites from the post_type=job_listing URL pattern in Common Crawl; ymcaneo robots.txt disallows the feed |
| 61 | JobKit | Job board | Yellow | jobs.rubyonrails.org 14 (RSS) | RSS, sitemap and WebSite JSON-LD on the Rails board, which rubyonrails.org says is built on JobKit; no second board found |
| 62 | Jobboardly | Job board | Green | medicalcourierjobs 343; jobsindevon 25 | RSS feed and 304; boards named by vendor testimonials, platform not independently confirmed |
| 63 | Jobiqo | Job board | Green | miamijobs 132, mediajobcenter 153 | Next.js pages embed job JSON with totalCount; meta owner=jobiqo; crawl delay 10s; newsday and startus leads did not resolve |
| 64 | JBoard.io | Job board | Green | golf-jobs 50, charityjobs.ie 30, usa.jobs 30, nuclearinst 30 | HTML list plus JobPosting JSON-LD on detail pages (2 boards) |
| 65 | JobBoardFire | Job board | Yellow | productmarketing | Sitemaps list pages and companies only; /jobs and /rss return 404; job data loads by script |
| 66 | SmartJobBoard | Job board | Green | academiccareers 20, hireveterans 20, psychologicalscience 20 | HTML list plus JobPosting JSON-LD on detail pages (2 boards) |
| 67 | JobBoard.io | Job board | Green | hireheroesusa 50, cityandstateny 13, nynmedia 4 | RSS at /jobs.rss; vendor confirmed by page fingerprint; boards named in vendor case studies |
| 68 | Wiley Madgex | Job board | Green | insidehighered 20, sciencecareers 20, chronicle 20, economist 20 | RSS at /jobsrss/ (20 items per page); Madgex confirmed by page fingerprint on all four; bmj feed works but vendor not confirmed; pharmiweb and getafinancejob failed |
| 69 | Talenetic | Job board | Ungraded |  | britishmedicaljobs.com is now a casino site; thejobnetwork.com, named as a Talenetic customer on a competitor's site, is a PandoLogic board; no Talenetic board found |
| 70 | JobMount | Job board | Ungraded |  | No customer board found; directory listings only |
| 71 | CareerSiteCloud | Job board | Ungraded |  | Vendor site returned 403; no customer board found; the vendor's own case study names none |
| 72 | JobBoard.com | Job board | Yellow | itjobstech (sitemaps of 10,000 URLs per file, JSON-LD on detail) | Detail pages carry JobPosting JSON-LD and sitemaps list the jobs, but list pages sit behind a browser check and only one customer board was found (preview.jobboard.com is the vendor demo) |
| 73 | Artha | Job board | Ungraded |  | Vendor docs name Nasscom and Orbit Indonesia as customers without URLs; no customer board found |
| 74 | jobboardsoftware.com | Job board | Ungraded |  | Site identifies as Exenzo; the one Exenzo-hosted board found (marketingfacts.frontend.exenzo.com) returns 500 |
| 75 | jobBoardHQ | Job board | Green | grandforksmfrcjobs, jbcjobs, vandenberg | HTML list plus JobPosting JSON-LD on detail pages (2 boards) |
| 76 | Nodex | Job board | Ungraded |  | Agency site only; its portfolio page returns 403; no board found |
| 77 | eJobBoard | Job board | Ungraded |  | Guessed host did not resolve |
| 78 | ejobsitesoftware.com | Job board | Ungraded |  | justoracle.com is no longer a job board; jobs.marketron.com (the other named customer) has a mismatched TLS certificate and serves an empty page over HTTP |
