# Status update for Vaya (2026-11-22)

Draft of the update to post on the challenge page. Numbers are from the live AWS deployment on
Nov 22.

---

## Job Board Indexer: status update (Nov 22)

**Live:** http://JbiApp-WebLB-3yU5Ai3sJ70l-789774040.us-east-1.elb.amazonaws.com
(an AWS address for now, plain HTTP until it has a domain)
**Code:** https://github.com/sb2k16/job-board-indexer

### Where it stands

- **54 of the 78 platforms** have a working connector and run in production, with 142 boards
  across them. Of the other 24, each has a written reason it can't be indexed within the crawl
  rules: no public or structured job data, login walls, or data reachable only through replayed
  session tokens or undocumented internal hosts.
- **84,453 open jobs from 4,780 companies** are searchable now. Every job links back to the page
  where it was posted.
- **The crawl rules hold everywhere:** robots.txt is obeyed, there are per-host rate limits that
  back off on 429, the crawler never reuses a page's session tokens, and it calls only documented
  public endpoints.

### What we built

- **Crawler.** A source plugin for OpenSearch Data Prepper. It runs in the stock Data Prepper
  image; we didn't fork Data Prepper.
  - Data Prepper provides leader and worker scheduling, coordination through Postgres, and the
    OpenSearch sink.
  - Platforms are YAML specs run by one engine, and each spec has an offline test against
    recorded responses.
- **Job identity and closure.** The same job posted on two platforms (CoStar, CVS) merges into one
  record. A job missing from a fully read board is marked closed. When a crawl would close more
  than 30% of a board's jobs, the closures are held for review rather than applied.
- **Honest board state.** Each crawl ends as complete, partial (the platform capped the results,
  e.g. a search that serves at most 10,000) or failed, with the reason. A failed board retries
  sooner than its group's normal refresh.
- **Refresh groups.** Platforms are grouped by how often they change, every 6 to 12 hours.
- **Web app (Next.js).**
  - Search with filters for remote, date posted, job type, company and location.
  - Job pages show where the job is still listed.
  - A company directory and a plain-language "How it works" page.
  - A password-protected crawl health page shows every board's last outcome and why it may be
    behind.
- **AWS, all in CDK.**
  - Crawler tasks on ECS Fargate Spot.
  - RDS Postgres (db.t4g.micro).
  - One OpenSearch node (t3.small.search).
  - A load-balanced web task.
  - No NAT gateway.

### Problems found and fixed in production

- **OpenSearch rejected writes (HTTP 429).** A sustained load test showed that the small
  domain's limit is on concurrent bulk requests, not megabytes:

  | Writers | Rejections |
  |---|---|
  | 3 | 0 (about 12 MiB/s accepted) |
  | 6 | about 2,200 × 429 |

  We had one pipeline per refresh group, so there were 13 sinks. The groups moved inside a single
  pipeline per crawler task, which makes 3 writers in total. Since then there have been 0 sink
  failures, and Postgres connections fell from 45 to 15.
- **Crawlers outgrew the database's connections** on db.t4g.micro during deploys. Deploys now
  drain the crawlers first, which pauses crawling for a few minutes.
- **Smaller fixes:**
  - Workday and Phenom result caps are now reported as partial instead of failed.
  - An adaptive per-host throttle handles hosts that send a 429 without Retry-After.
  - A transport timeout catches servers that stall partway through a response.
  - Malformed JSON-LD on Jobvite tenants is now read.

### Known issue we're fixing now

The crawl health page shows **104 of 142 boards failing** their latest crawl. Almost every failure
reads "robots.txt is unavailable". The crawler follows RFC 9309: if robots.txt can't be fetched,
it treats the site as disallowed and skips it. But those same robots.txt files load normally
outside AWS (Greenhouse, Lever and Workable answer 200), and RFC 9309 treats a 4xx answer as
"crawl freely". So this is our bug, not the sites' rules. Until it's fixed, most boards show jobs
from their last good crawl, and few jobs are counted as new. Fixing it is the next task.

### Cost today and what scale-out would need

**Today:** about **$135 a month** (estimated from list prices):

| Item | Per month |
|---|---|
| 3 Fargate Spot crawler tasks | $39 |
| OpenSearch | $27 |
| Public IPv4 addresses | $22 |
| Load balancer | $18 |
| RDS | $14 |
| Web task | $9 |
| Logs and image registry | $5 |

Choosing ECS over EKS (no $73 control-plane fee), Spot crawlers, RDS over Aurora and public
subnets over a NAT gateway cut the first design's roughly $400 a month to about a third.

**What limits growth, from what we measured:**

1. **OpenSearch** is the first wall. It handles about 3 concurrent writers and needs headroom for
   search. More boards first means more writers, and later more storage.
2. **Postgres connections:** every crawler task holds coordination connections, and db.t4g.micro
   is already the limit at deploy time.
3. **Crawler tasks** scale with the number of distinct hosts, not CPU. Politeness caps each host,
   so platforms that serve every tenant from one shared API host (Greenhouse, Lever, Ashby) don't
   get faster with more tasks. Tenant-subdomain platforms do.
4. **Finding boards**, not infrastructure, is the real gap to "every board". We crawl 142 known
   tenants. Reaching thousands needs tenant discovery (sitemaps, search, public directories),
   which is product work.

**Scale-out at about 10,000 boards (roughly 1 to 2 million jobs)**, estimated at about
**$450 to $550 a month**:

| Item | Per month |
|---|---|
| OpenSearch: 2 × m7g.large.search with about 100 GB of storage | about $220 |
| 10 to 15 Spot crawler tasks | $130 to $200 |
| RDS db.t4g.small or medium | $25 to $50 |
| NAT gateway in place of per-task public IPv4 (cheaper past about 10 tasks) | about $40 |
| A second web task | about $10 |
| Load balancer | $18 |
| Logs | about $15 |

**Ways to keep that cost down:**

- One sink per task, regardless of how many groups it runs.
- Adaptive refresh, so boards that rarely change are crawled less often.
- Conditional requests (ETag and If-Modified-Since) where platforms support them.
- Spot capacity for every crawler. A lost task just hands its boards back through Postgres.
- One OpenSearch replica only, after the move to two nodes.

**Next steps:** fix robots.txt handling, measure the real daily cost against the estimate, and run
a kill-a-task test on AWS to confirm boards move to other tasks.
