# UI redesign mocks (2026-11-17)

Figma: https://www.figma.com/design/cZXe45aphPx36DsS2lk61g (page "Mocks v0.1"). Source HTML of the
mocks was generated for the import; the frames are editable layers, not images.

## Frames

| Frame | Replaces | What changes |
| --- | --- | --- |
| 00 Foundations | `web/app/globals.css` tokens | Warm paper and ink palette, one accent (deep green) reserved for "verified open"; status colours only for crawl outcomes |
| 01 Home | `app/page.tsx` | Left-aligned editorial masthead; one search bar (what, where, remote); stat tiles become a figure ledger; "Browse by source" tiles become a Sources ledger with the last 7 crawl outcomes per platform |
| 02 Search results | `app/jobs/page.tsx`, `Filters.tsx`, `JobCard.tsx` | Job cards become hairline rows; active filters as removable chips above results; facet counts with a thin meter; sort by best match, newest, recently verified |
| 03 Job detail | `app/jobs/[key]/page.tsx` | Spec row (arrangement, employment, salary, team); description set in serif; apply panel states when the posting was last confirmed open; a provenance timeline and source list |
| 04 Sources & crawl health | `app/boards/page.tsx` | Summary strip (complete, partial, failed, closures held); refresh-group cards with schedule and progress; table defaults to "Needs attention" with the reason in plain words and the last 10 outcomes |
| 05 Mobile results | (responsive) | Search, horizontally scrolling filter chips, rows with verification line |

## Decisions

- **Provenance is the product.** The index's difference from an aggregator is that every listing
  is read from the source and re-confirmed on each crawl, so "verified N min ago · via Platform" is
  on every row and is the only use of the accent colour.
- **Rows, not cards**, for scanning density. **Serif (Source Serif 4) for content** (titles,
  descriptions, figures), **IBM Plex Sans for controls**, **IBM Plex Mono for counts and labels**
  (tabular figures line up in columns).
- No gradients, illustrations, emoji or icon-per-tile; status colour appears only where it means
  something (Sources page, closed listings).
- "Openings" is a working product name in the mocks, not a decision.
- Figures in the mocks are illustrative; the shapes of the data (fields, outcomes, partial
  reasons, retry counts, refresh groups) match what the index and board_state hold today.

## Data the mocks need that the web app does not read yet

- Per-job `last_confirmed` (the "verified N min ago" line): board_state has `last_confirmed` per
  board; per job it is the board's last complete crawl that contained the job.
- Last N outcomes per board (the history bars): needs an outcome history in board_state (today:
  last outcome, consecutive failures, snapshot history).
- Refresh-group schedule and next run: from `config/groups.toml` and the leader's refresh times.
- Salary and team: only where the source provides them.
