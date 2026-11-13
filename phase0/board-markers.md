# Board markers per platform (Phase 0, Nov 11, 2026)

Rule (Nov 10): each platform spec names a marker that proves a response is a real board. A response without the marker is a failed fetch, never zero jobs. "Zero jobs" is the shape a genuine empty board takes, so the connector can tell it from a soft 404. Markers come from the saved samples; "fixture" names the batch where the marker was seen.

## Class A: JSON

| Platform | Listing marker | Zero jobs looks like | Soft 404 seen | Fixture |
| --- | --- | --- | --- | --- |
| ADP | object with `jobRequisitions` array and `meta.totalNumber` | `jobRequisitions: []`, `totalNumber: 0` | bogus cid returns HTML (no JSON) | batch04 |
| Ashby | `apiVersion` and `jobs` keys | `jobs: []` | 404 | batch01 |
| Breezy | top-level array; items have `friendly_id` and `company.friendly_id` | `[]` | HTML page | batch09 |
| Eightfold | `positions` array and `count` key | `count: 0` | 403/HTML | batch05 |
| Gem | top-level array; items have `absolute_url` on `jobs.gem.com` | `[]` | — | batch09 |
| Greenhouse | `jobs` array and `meta.total` | `meta.total: 0` | 404 | batch01 |
| HireHive | `publishedJobsCount` key | `publishedJobsCount: 0` | — | batch09 |
| Lever | top-level array; items have `hostedUrl` on `jobs.lever.co` | `[]` | 404 | batch01 |
| Oracle | `items[0].requisitionList` and `items[0].TotalJobsCount` | `TotalJobsCount: 0` | HTML | batch09 |
| Pinpoint | `data` array; items have `url` on `pinpointhq.com` | `data: []` | — | batch09 |
| Recruitee | `offers` array; items have `careers_url` | `offers: []` | — | batch09 |
| Workable | `name` (account) and `jobs` array | `jobs: []` | — | batch09 |
| Workday | `jobPostings` array and `total` | `total: 0` | HTML | batch02 |

## Class A: embedded JSON in HTML

| Platform | Marker | Zero jobs | Fixture |
| --- | --- | --- | --- |
| Comeet | script assigning `COMPANY_POSITIONS_DATA = [` | `COMPANY_POSITIONS_DATA = []` | batch03 |
| Phenom | `phApp.ddo` object with `eagerLoadRefineSearch.totalHits` and `jobs` | `totalHits: 0` | batch06 |
| Rippling | page JSON with `"items":[` where items carry `url` on `ats.rippling.com`, plus `totalItems` | `totalItems: 0` | batch09 |
| Jobiqo | `__NEXT_DATA__` with `props.pageProps.data.jobs.pages` and `totalCount`; `<meta name="owner" content="jobiqo">` | `totalCount: 0` | batch13 |

## Class A: XML and RSS

| Platform | Marker | Zero jobs | Fixture |
| --- | --- | --- | --- |
| Paylocity | `<job>` elements with `<requisitionid>`; root is the Paylocity feed | root element with no `<job>` | batch05 |
| Personio | root `<workzag-jobs>` | `<workzag-jobs/>` | batch15 |
| Teamtailor | RSS with `<remoteStatus>` in items; channel link on the tenant host | channel with no `<item>` | batch09 |
| HireBridge | RSS with `g:` namespace tags (`g:employer`, `g:expiration_date`) | channel with no item | batch15 |
| WP Job Manager | feed: `<content:encoded>` and `<dc:creator>` with job_listing links; AJAX: JSON with `found_jobs` and `max_num_pages` | `found_jobs: false` | batch06 |
| Jobboardly | RSS items with `<city>`, `<state>`, `<country>` | channel with no item | batch01 |
| JobBoard.io | RSS `<generator>` naming JobBoard.io; item links `/jobs/{id}-` | channel with no item | batch08 |
| Madgex | RSS item links `/job/{id}/`; page fingerprint `Madgex` in the HTML | channel with no item | batch07 |

## Class B: HTML list plus JSON-LD detail

List marker is the platform fingerprint on the list page; detail marker is a `JobPosting` JSON-LD object. A list page without the fingerprint is a failed fetch (this is what catches the soft 404s on JazzHR, Jobvite, HiringThing, BambooHR, which return 200 with the vendor's generic page).

| Platform | List fingerprint | Zero jobs | Fixture |
| --- | --- | --- | --- |
| ApplicantPro, iSolvedHire | `/job_site_map.xml` is a `<urlset>`; pages reference `applicantpro.com` or `isolvedhire.com` assets | empty `<urlset>` | batch12 |
| Hireology | `careers.hireology.com` sitemap index | sitemap with no job URLs | batch12 |
| iCIMS | page contains `icims` asset host and `iCIMS_` class names | list with "no jobs" container | batch10 |
| CareerPlug | `careerplug` asset host | — | batch10 |
| HiringThing | `hiringthing` asset host; unknown tenant shows the vendor landing (soft 404) | — | batch10 |
| JazzHR | `applytojob.com` host with `resumator` assets; unknown tenant shows the vendor page (soft 404) | — | batch10 |
| Jobvite | `jobvite.com` host; `jv-` class names; unknown tenant shows the vendor page (soft 404) | — | batch10 |
| Freshteam | `freshteam.com` host and `freshteam` assets | — | batch10 |
| GoHire | `jobs.gohire.io` host and `gohire-avatars` assets | — | batch10 |
| JOIN | `join.com/companies/` URL and `cdn.join.com` | — | batch10 |
| Kula | `careers.kula.ai` host and `assets.kula.ai` | — | batch10 |
| Manatal | `careers-page.com` host and `manatal` assets | — | batch10 |
| Recooty | `jobs.recooty.com` host | — | batch10 |
| Trakstar | `trakstar` or `hire.trakstar.com` assets | — | batch10 |
| Deel | board page ItemList JSON-LD; `jobs.deel.com` host | ItemList with no elements | batch15 |
| FirstStage | title suffix `| First`; `firststage.co` host | list page with no `/jobs/{id}/view` links | batch17 |
| Taleo TBE | `/ats/careers/v2/searchResults` page title "Career Center/Centre" and `viewRequisition` links | page with no `viewRequisition` link | batch16 |
| JBoard.io | `jboard` assets | — | batch10 |
| SmartJobBoard | `Server: sjb-nginx` header and `smartjobboard` assets | — | batch10 |
| jobBoardHQ | `jobboardhq` assets; JSON-LD identifier name `jobKey` | — | batch10 |

Where "zero jobs" is "—", the empty shape was not observed in Phase 0; the connector should treat an empty list as complete only when the fingerprint is present and record it for review (two consecutive identical empty crawls before closing jobs, per the completeness rule).
