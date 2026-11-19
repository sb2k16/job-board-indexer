# UI mocks v0.2: copy review (2026-11-18)

Figma: https://www.figma.com/design/cZXe45aphPx36DsS2lk61g. Pages: "Public — v0.2" (Foundations,
Home, Search results, Job detail, How it works, Mobile), "Internal / Admin" (Crawl health),
"Archive — v0.1". Supersedes the copy in ui-redesign-2026-11-17.md.

## Decisions

- **Crawl health is internal.** It moves to `/admin` behind a login, out of the public nav.
  Platforms, crawls, retries and rate limits mean nothing to a job seeker, make the product look
  fragile, and tell anyone watching where we are being rate-limited.
- **Public replacement: "How it works"**: straight from the company, checked several times a day,
  gone when it's gone, you apply with the employer. Plain words, no mechanism.
- **No machinery in public copy.** Removed: crawl, index, re-checked, verified N min, via
  Greenhouse, also on Workday, connectors, boards, robots.txt, ATS, source platforms, the Source
  filter and the "Recently verified" sort.
- **Freshness in the applicant's words**: rows show the posted age ("3 days ago", "New"); the job
  page says "Still listed on Stripe's careers site" and "Posted Nov 14 · updated Nov 15". The
  accent colour marks only "New" and "Still listed".
- **Apply on the employer, not the platform**: "Apply on Stripe's site", not "Apply on Greenhouse".
- Filter labels follow common job-site wording: Date posted (Past 24 hours / week / month), Job
  type, Company, Location; sort Most relevant / Most recent. Remote is "Any / Remote only" because
  the index stores remote as yes/no (no hybrid or on-site).
- Home: "Sources" becomes "Companies hiring now"; figures are open jobs, companies hiring, new
  today, closed jobs removed since yesterday.

## Reference: how others word it

Google Jobs: "Apply on [site]", "via [site]", "3 days ago". LinkedIn: "Actively reviewing
applicants" (recruiter reviewed applications in the past two weeks). Indeed: "Urgently hiring".
None expose how listings are collected.
