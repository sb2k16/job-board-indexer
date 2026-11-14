# Pipeline groups and Deployments (2026-11-13)

Question: one pipeline for 58 ATS and 20 job-board platforms, or split them? And how does the
split map onto EKS? Implements plan task T2.8 (groups file and renderer) and the Deployment split.

## Decision

**Ten group pipelines (the design's option C), run by two Deployments.**

| Deployment | Groups | Why |
| --- | --- | --- |
| `crawler` (3 pods on EKS) | 1 hosted ATS JSON, 3 ATS feeds, 6 JSON-LD by page, 7 boards with feeds, 8 boards with JSON-LD | One list call or a cheap detail fetch per job; many small boards |
| `crawler-heavy` (2 pods) | 2 enterprise suites (Workday class), 4 job data embedded in pages, 5 JSON-LD by sitemap | Tenants of 2,000+ jobs, a detail call per job, crawls that run for hours; separate pods so their memory and threads cannot starve the light groups |
| none | 9 HTML-parser tier, 10 session tier | Declined Nov 11; `active = false` keeps them one line away |

Considered and rejected:

- **One pipeline for everything** (what the local stack ran until today): one buffer and one set of
  workers, so a Workday crawl that takes hours sits in the same queue as a two-minute Greenhouse
  refresh.
- **One Deployment per group** (10 Deployments): best isolation, but each needs at least one pod,
  and two for availability. At about $42 per pod-month (1 vCPU, 4 GB) that is $420 to $840 a month
  against a $90 budget, for groups that are mostly idle between refreshes.

## How it works

- `config/groups.toml` is the single source of truth: every group's platforms, Deployment, refresh
  interval and worker count, plus a default replica count per Deployment.
- `config/render_groups.py ENV.toml OUT_DIR` writes `OUT_DIR/<deployment>.yaml`, one pipeline per
  active group (`g<id>-<name>`). A platform is rendered only when its spec and tenant list exist, so
  the full Phase 0 assignment lives in the file while connectors are being built. It refuses a
  platform in two groups, an unknown Deployment or a duplicate id, and deletes a stale file for a
  Deployment left with nothing to run.
- The environment file (`local/live/env.toml` locally, one per EKS environment later) holds what
  differs: OpenSearch, the database, in-container paths and replica overrides. The renderer writes
  each Deployment's replica count into `politeness.replicas`, so every pod takes the right share of
  a platform's rate budget. A platform belongs to exactly one group, so the split never multiplies
  a platform's request rate.
- Inside a Deployment every pod runs all its pipelines; boards are leased partitions, so any free
  pod takes the next board of any group and a lost pod's boards return when its lease expires.
  Each pipeline has its own leader, partitions, buffer and sink. `board_state` is keyed by
  platform and board, not by pipeline, so moving a platform to another group keeps its history.

## Connection pools (spike S7, partly answered)

- Data Prepper builds one coordination store, and with it one connection pool, **per pipeline**
  (`SourceCoordinatorFactory` calls `loadPlugin` for every source; default pool size 5). That is
  Data Prepper core, which this project does not rebuild.
- `board_state` now uses one reference-counted pool per database per process
  (`JdbcBoardStateStore`), shared by every pipeline in the pod (2 connections).
- **Shrinking the coordination pool does not work.** `max_pool_size: 2` cut idle connections from
  21 to 12 per pod, but a full re-crawl starved it: worker and acknowledgement threads waited on
  connections (`total=2, active=2, waiting=6`) and partition updates after acknowledgement timed out.
  No job was lost (the partitions return when their leases expire), but boards were crawled twice.
  Back to the default 5.
- Budget at full scale with the default: 3 light pods with 5 pipelines (5 × 5 + 2 = 27 each) and
  2 heavy pods with 3 pipelines (3 × 5 + 2 = 17 each) is about 115 connections. Aurora Serverless v2
  sets `max_connections` from the maximum ACU; size the cluster's maximum so it allows at least 150,
  and measure in S7.
- A shared store across pipelines is an upstream Data Prepper change; it joins the JDBC
  table-creation race fix as a candidate PR.

## Verified locally (2026-11-13)

- Groups 1, 3 and 7 render to three pipelines in `crawler`; `crawler-heavy` correctly has nothing to
  run (no heavy platform built yet) and stays behind a compose profile (`--profile heavy`).
- Both pods start all three pipelines; each pipeline has its own leader and its own board partitions,
  spread over both pods.
- Recreating the crawlers mid-crawl left boards leased to the old containers; they were taken over
  when the leases expired (the kill-a-pod path).

## Still open

- **S7 at full size:** memory and threads with 8 to 10 pipelines per pod, and what happens when one
  pipeline fails to build. Needs more platforms built, or synthetic groups.
- **EKS manifests (T1.5, T2.9):** two Deployments from the same image, each mounting its rendered
  file from a ConfigMap; a rolling restart applies a groups change.
- Phase 0 regrades (SmartRecruiters and others found structured on Nov 11) are not reflected in the
  group assignment yet; Personio sits in group 3 although Phase 0 found it robots-blocked.
