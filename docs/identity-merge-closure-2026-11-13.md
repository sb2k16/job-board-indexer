# Same job in many places, merging, change and closure (2026-11-13)

Requirement: recognise when the same job appears in multiple places, preserve useful information,
and detect when a listing changes or disappears. An audit of the code and the live index on
Nov 13 found the first only partly handled, the second and fourth not at all, and the third broken
once a job had two sources. This note records what was built. The fourth part is plan task T2.5.

## 1. Identity: which postings are one job

A **posting** is one id on one board (`platform`, `board_id`, the platform's job id or else the URL).

| Rule | Why |
| --- | --- |
| A posting keeps its `job_key` for good (`job_posting` table) | An edited title or location is the same job, not a new one |
| Two postings of the same board are never one job | A board listing two ids lists two openings. The old key merged 34 such pairs in the live index (for example two Stripe "Account Executive, Commercial (Hunter)" postings in Chicago) |
| A new posting joins an existing job when one of its aliases is taken (`job_alias`): company + requisition id, or company + title + canonical location | Phase 0: the requisition id is what matches across platforms (CVS Health and Dollar Tree on Phenom and Workday; CoStar on Eightfold and Workday), and location strings never do. Both rules apply, so a board with requisition ids and one without still merge, which the old per-board choice of key never did |
| Otherwise the posting starts a job keyed by its strongest alias | Two pods finding the same new job at once compute the same key |

`identity/IdentityResolver.java` holds the rules over an `IdentityStore`: Postgres
(`JdbcIdentityStore`, db/V3, sharing the board_state pool) in the crawler, memory in tests and in
runs without a database. Binding is insert-if-absent and reads back the winner, so concurrent pods agree.

## 2. Merging: one document, several sources

`config/upsert.painless` (the sink's scripted upsert):

- Each source is one link in `source_links`, keeping its own title, location, posted date,
  employment type, remote flag, countries and content hash.
- `field_sources` (stored, not indexed) records which link supplied each top-level field.
- A source keeps and may update the fields it supplied. Another source fills a field that is empty,
  or whose supplier is no longer live. No source blanks a field another source supplied.
- Description: the longest wins. Posted date: the earliest wins. Remote: true if any live link says
  so. Countries: the union of the live links.

## 3. Change detection

- Per link: `content_hash` (title, company, location, description, type, remote) and `last_changed`.
- The document's `last_changed` moves when any link's hash changes, a link is added, or a link comes
  back. Two sources with different text no longer flip it on every crawl, because each is compared
  only with its own previous version.

## 4. Closure (T2.5)

`closure/ClosureSweeper.java` runs for one board right after a complete snapshot of it is confirmed
by the sink, before the board's partition completes, so it runs under that board's lease.

- **A link goes** when the last N complete snapshots of its board all missed it: its `last_seen` is
  older than the oldest of them (`snapshot_history[N-1]`). Failed and partial crawls never enter the
  history, so they cannot close anything.
- **A job closes** when all its links are gone (`status: closed`, `closed_at`), and **reopens** when
  any source lists it again: the upsert sets the link live and the job open, and counts that as a change.
- **Mass-closure guard:** a run that would close more than `mass_closure_ratio` (0.3) of the board's
  jobs, and at least `mass_closure_min_jobs` (10), is held. The board shows "Closures held for
  review" on the site, and the reason is in `board_state.closure_paused_reason`. It releases by itself
  once the jobs return. An operator who confirms the closures are real releases it with
  `UPDATE board_state SET closure_override_until = now() + interval '1 day' WHERE platform = ... AND board_id = ...`.
- **Retirement:** after `retire_after` (3) consecutive crawls confirm the board is gone (404/410),
  every live link of the board goes, with no guard, and the board is marked retired. A later
  successful crawl un-retires it.
- The script is `closure.painless` in the crawler jar, run with `_update_by_query`
  (`conflicts=proceed`). Links are plain objects, not nested, so the query can match on fields of two
  different links; the script re-checks each link, so such a match is a no-op and the guard's count
  errs high, never low. A failed sweep closes nothing and runs again after the next snapshot.

Configuration is the source's `closure` block (rendered from the environment file):
`opensearch_url`, `index`, `snapshots` (production 3, local 2), `mass_closure_ratio`,
`mass_closure_min_jobs`, `retire_after`. Without it nothing is ever closed, and the crawler says so
at start-up. The sweeper calls OpenSearch directly, so on AWS it needs SigV4 signing (T1.5).

## Tests

| Test | Covers |
| --- | --- |
| `IdentityResolverTest` (7) | Stable key on edit; same-board postings stay apart; merge by requisition id with different titles and locations; merge by title and location when only one board has requisition ids; different cities stay apart; two pods agree |
| `JdbcIdentityStoreIT` (2) | The rules on Postgres; four concurrent pods binding the same board agree |
| `ClosureSweeperTest` (7) | No closure before N snapshots; cutoff; the guard; the minimum; operator override; retirement after M |
| `tests/t25_merge_and_closure_test.py` (31 checks) | Both Painless scripts on a real OpenSearch: merge rules, per-link change detection, no churn, closure, takeover, reopening, retirement |
| `tests/t25_closure_e2e.py` | The live stack with a fixture board crawled every minute: closure after 2 snapshots, the guard, reopening, same-board separation |

## Rollout

The identity change changes keys, so the local index was rebuilt from a fresh crawl (crawlers
stopped, `jobs-v1` and the coordination rows dropped, crawlers started). On a shared environment the
same change goes to a new index (`jobs-v2`) and the `jobs` alias moves when it is full. Note that the
template attaches the `jobs` alias to every `jobs-*` index, so the old index must leave the alias, or
be deleted, at the swap.

After the rebuild: 4,028 postings, 4,028 jobs, no same-board merges (34 before). There are no
cross-board merges yet, because none of the 23 live boards shares an employer with another; the
platforms Phase 0 found shared employers on (Phenom, Workday, Eightfold) are not built yet (T3.5).
