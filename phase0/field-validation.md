# Phase 0 field validation (Nov 11, 2026)

Procedure step 3 for the Green platforms: which of the required fields (title, company, description, location, source URL, application URL) and optional fields (salary, employment type, remote, posted date) the structured source carries, and where a detail fetch is needed. Evidence is the first record of the saved samples (`validate_fields.py`, batches 1–19); "detail" means a second request per job.

Conventions that apply everywhere and are not listed as gaps:
- **Company from the tenant registry.** Single-employer ATS feeds (Ashby, Gem, Lever, Oracle, Workday, Eightfold, ADP, HireHive, Pinpoint, Workable, Rippling, Phenom, Teamtailor, Personio, Avature, Zoho) carry no company field; the tenant record supplies it. Multi-employer boards must carry it per job, and all the Green boards do.
- **Application URL = source URL** when the source gives one URL and the page hosts the apply form (JSON-LD `directApply: true`, or the ATS's own hosted page).
- **Constructed URLs** are built from an id and the platform's URL template; they are noted because a template change breaks them silently.

## Class A: public JSON APIs

| Platform | In the listing | Detail fetch | Gaps / notes |
| --- | --- | --- | --- |
| ADP | title, postDate, locations (city, region, postal), salary range, work level (employment type) | `job-requisitions/{itemID}?cid=` → requisitionDescription | Source URL constructed (`recruitment.html?cid=&jobId=`); no remote flag |
| Ashby | title, descriptionHtml/Plain, location + postal address, employmentType, isRemote, workplaceType, publishedAt, jobUrl, applyUrl | none | Complete; no salary field |
| Breezy | name, url, published_date, type (employment), location (+is_remote), company.name, salary (string, often empty) | position page JSON-LD → description | Description only via detail |
| Eightfold | name, location(s), t_create, canonicalPositionUrl, work_location_option | `/api/apply/v2/jobs/{id}?domain=` → job_description, apply_redirect_url | job_description is empty in the list; employment type not seen; no salary |
| Gem | absolute_url, content (+plain), first_published_at, employment_type, location.name, location_type | none | Complete; no salary |
| Greenhouse | absolute_url, title, company_name, content, location.name, first_published, updated_at | none | No employment type, remote or salary (sometimes in `metadata`) |
| HireHive | title, description (html + text), location + country, salary, type (employment), publishedDate, hostedUrl | none | Complete |
| Lever | text (title), description, categories.location/commitment, workplaceType, createdAt, hostedUrl, applyUrl | none | Complete; salaryRange only on some postings |
| Oracle | Title, PostedDate, PrimaryLocation, Id, WorkplaceType | `recruitingCEJobRequisitionDetails?finder=ById;Id=` → ExternalDescriptionStr, ExternalPostedEndDate, workLocation | Source URL constructed (`/job/{Id}`); JobType often null |
| Pinpoint | title, description, url, employment_type, workplace_type, compensation min/max/currency, location (city, province, country), department | none | Posted date not in the record (deadline_at only) |
| Recruitee | title, description, requirements, careers_url, careers_apply_url, published_at, city/country/locations, remote, salary min/max/currency, company_name | none | Employment type only as min/max hours |
| Workable | title, url, application_url, published_on, country/city, telecommuting, employment_type; account name at the top level | `/api/v2/accounts/{acct}/jobs/{shortcode}` → description, requirements, benefits, workplace | Description only via detail |
| Workday | title, externalPath, locationsText, postedOn (relative text: "Posted Today"), req id | `/wday/cxs/{t}/{site}/job/{path}` → jobDescription, location, startDate (real date), timeType, externalUrl, country | Posted date reliable only from detail; no salary |

## Class A: embedded JSON in the page

| Platform | In the listing | Detail fetch | Gaps / notes |
| --- | --- | --- | --- |
| Comeet | name, department, location (city, country, is_remote), employment_type, uid, url_active_page, time_updated, company_name, workplace_type, description in custom_fields.details | none | Complete; time_updated is an update time, not a posted date |
| Phenom | title, city/state/country, postedDate, type, remote, applyUrl, jobSeqNo, descriptionTeaser | job page JSON-LD → description, hiringOrganization, jobLocation | Description only via detail |
| Rippling | name, url, department, locations (city, state, country, workplaceType) | job page embeds the posting (keys body/content/description seen; not isolated) | Description and posted date not confirmed from the list; detail needed |
| Jobiqo | title, organization, address, published, expiration, salaryRange, remoteOptions, url | detail page not probed (my URL extraction failed; instance was down) | Description unconfirmed |
| Zoho Recruit | Posting_Title, City, Country, Job_Type, Remote_Job, id; Job_Description and Date_Opened on 1 of 3 tenants only | detail pages are script shells | **Description missing on most tenants** and no structured detail: fails the required set, regraded Yellow |

## Class A: XML and RSS feeds

| Platform | In the feed | Detail fetch | Gaps / notes |
| --- | --- | --- | --- |
| Avature | title, link, pubDate; description is only the requisition id | detail page has no JSON-LD | **No description or location** without an HTML parser: fails the required set, regraded Yellow |
| HireBridge | title, link, pubDate, description, employer, location (csv), job_type, expiration_date, id | detail page JSON-LD also available | Complete |
| JobBoard.io | title, link, description, pubDate | detail JSON-LD → hiringOrganization, jobLocation, baseSalary, validThrough | Company and location structured only via detail |
| Jobboardly | title, link, description, city/state/region/country, pubDate, guid | detail JSON-LD → hiringOrganization, employmentType, baseSalary | Company structured only via detail |
| Madgex | title (employer-prefixed), description teaser, link, pubDate | detail JSON-LD → full description, hiringOrganization, jobLocation, employmentType, validThrough | Description only via detail |
| Personio | id, name, subcompany, office, department, employmentType, schedule, createdAt, jobDescriptions (populated on stark, empty on desotec) | detail page JSON-LD → description | Source URL constructed (`/job/{id}`); use detail when the XML description is empty |
| Teamtailor | title, description, pubDate, link, remoteStatus, locations, department, role | detail JSON-LD available | Complete; no salary |
| WP Job Manager | title, link, pubDate, description, location, company | none | No employment type or salary |
| Paylocity | title, date, jobtype, requisitionid, url, company, city/state/country, description, remotetype | none | Complete |

## Class B: JSON-LD on detail pages

All 21 carry `title`, `description`, `datePosted`, `hiringOrganization.name` and `jobLocation`; the list page or sitemap gives the URLs and every job needs one detail fetch by design. Differences:

| Platform | url in JSON-LD | employmentType | salary | validThrough | Notes |
| --- | --- | --- | --- | --- | --- |
| ApplicantPro, iSolvedHire | yes | yes | yes | yes | Also geo; identical markup |
| CareerPlug | no (page URL) | yes | yes | no | directApply |
| Deel | yes | yes | no | no | Board page ItemList gives the URLs |
| FirstStage | directApply holds the URL | no | yes | no | jobLocation.address is a plain string |
| Freshteam | yes | yes | no | no | `remote` flag |
| GoHire | yes | yes | yes | yes | |
| Hireology | no (page URL) | yes | no | yes | |
| HiringThing | no (page URL) | no | no | no | |
| iCIMS | yes | yes | no | yes | address fields may read UNAVAILABLE |
| JazzHR | yes | yes | no | yes | |
| Jobvite | no (page URL) | free text ("FT - Full Time") | present but empty | no | hiringOrganization is a string |
| JOIN | yes | yes | yes | no | |
| Kula | no (page URL) | yes | yes | no | |
| Manatal | no (page URL) | yes | yes | yes | |
| Recooty, Trakstar | no (page URL) | yes | — | — | JSON-LD contains raw CR/LF inside strings; parse leniently |
| Taleo TBE | yes | free text | no | no | Sample description is "<p>.</p>" and datePosted 2021: tenants can leave the description empty |
| JBoard | no (page URL) | yes | no | yes | |
| SmartJobBoard | yes | yes | present but empty | yes | occupationalCategory |
| jobBoardHQ | no (page URL) | present but empty | no | yes | |

## Regrades from this step

- **Avature → Yellow:** the feed has title, link and date only, and the detail page has no JSON-LD.
- **Zoho Recruit → Yellow:** description is absent on most tenants and the detail pages are script shells.

Both would need the HTML-parser tier, which was declined on Nov 11.

## Open items

- Jobiqo and Rippling: confirm description on the detail page (one probe each).
- Posted date is weak on Pinpoint (absent), Workday list (relative text; detail has startDate) and Comeet (update time only).
- Company for single-employer feeds comes from the tenant registry, so tenant discovery must record the employer name, not only the slug.
