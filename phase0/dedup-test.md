# Phase 0 dedup test (Nov 11, 2026)

Design-fit check: find at least 3 employers that post the same job through two platforms, and confirm that `job_key` matches and `source_links` would merge. `job_key` is defined in the document model as a fingerprint of normalised title, company and location.

## Pairs found

All four came from the saved samples: Phenom and Eightfold career sites hand the application to the employer's Workday tenant, and the apply URL carries the Workday job path. The Workday side was fetched with the keyless `/wday/cxs/{tenant}/{site}/job/{path}` call (batch 21).

| Employer | Platform A | Platform B | Title (both) | Location on A | Location on B | Requisition id (both) | Posted (both) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| CVS Health | Phenom (jobs.cvshealth.com) | Workday (cvshealth.wd1) | Pharmacy Intern - Grad | Ashburn, Virginia, United States | 10563 - Virginia CVS Pharmacy, L.L.C. | R0996880 | 2026-10-12 |
| Dollar Tree | Phenom (careers.dollartree.com) | Workday (dollartree.wd5) | Customer Service Associate | Ashburn, Virginia, United States of America | VA-Ashburn | R-256821 | 2026-11-07 |
| CoStar | Eightfold (costar.eightfold.ai) | Workday (costar.wd1) | Director, Pre-Sales Engineering | Irvine, California, United States of America | Irvine (US) | R40220 | 2026-10-28 |
| O'Reilly Auto | Phenom (careers.oreillyauto.com) | Workday (oreillyauto.wd1) | Delivery Specialist | Independence, Missouri, USA | not compared: the Workday call returned 403 | R39470 | — |

## Result

- **Title and company match on all 3 compared pairs** after lower-casing, ASCII folding and punctuation stripping.
- **Location never matches as a string.** Workday's `location` is the tenant's internal location name ("10563 - Virginia CVS Pharmacy, L.L.C.", "VA-Ashburn", "Irvine (US)"); Phenom and Eightfold give "City, State, Country". A fingerprint over the raw location string gives a different `job_key` on every pair, so **the design's `job_key` as specified does not dedup any of the 3 pairs.**
- **The requisition id matches on all 4 pairs** (Workday `jobReqId`, Phenom `reqId`, Eightfold `ats_job_id`), and the Phenom/Eightfold apply URL names the Workday job path, which also contains the id.
- Posted dates agree on the 3 compared pairs (Workday `startDate` vs Phenom `postedDate`; Eightfold `t_create`).

## Design change

1. **Location in the fingerprint must be a canonical token, not the source string.** Use city plus region code when the source gives structured parts (Ashby, Breezy, Recruitee, Pinpoint, JSON-LD `addressLocality` + `addressRegion`, Phenom `city`/`state`), and a best-effort parse for free text. Where the parse yields nothing (Workday's internal names), fall back to country, or leave location out of the key for that record and rely on rule 2.
2. **Add a second merge rule on (company, requisition id).** Many ATS records expose the employer's requisition id (Workday `jobReqId`, Oracle `Id`/requisition number, Eightfold `ats_job_id`, Phenom `reqId`, Greenhouse `requisition_id`, Pinpoint `job.requisition_id`, Paylocity `requisitionid`, ADP `clientRequisitionID`, Lever and Ashby through the detail). When two records share company and requisition id, merge them regardless of location.
3. **A third hint: the application URL.** When a record's `application_url` points at another indexed platform's job (Phenom → Workday, Eightfold → Workday), the target is the same job; record it as a `source_links` entry with `state: redirect` rather than a second document.
4. Spike S2 / task T1.4 should test the three rules together with these four pairs as fixtures (`samples/batch21`, `samples/batch05`, `samples/batch06`, `samples/batch19`).

## What was not found

No duplicate across a job board and an ATS was confirmed: the Green boards (Jobboardly, JobBoard.io, SmartJobBoard, JBoard, Madgex) link to their own apply pages, not to the employer's ATS, so cross-matching them needs the title/company/location rule, which is exactly the one that needs canonical locations.
