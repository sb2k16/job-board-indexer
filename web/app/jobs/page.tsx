import Link from "next/link";
import type { Metadata } from "next";
import { Filters } from "../components/Filters";
import { JobCard } from "../components/JobCard";
import { SearchBar } from "../components/SearchBar";
import { SortSelect } from "../components/SortSelect";
import { overview, searchJobs, SearchUnavailable } from "@/lib/opensearch";
import { MAX_PAGE, PAGE_SIZE, parseFilters, toSearch, type Filters as F, type Params } from "@/lib/query";
import { platformInfo } from "@/lib/platforms";
import { num } from "@/lib/format";
import { countryName } from "@/lib/countries";

export const dynamic = "force-dynamic";

export async function generateMetadata({ searchParams }: { searchParams: Promise<Params> }): Promise<Metadata> {
  const f = parseFilters(await searchParams);
  const bits = [f.q, f.companies.join(", "), f.location, f.countries.map(countryName).join(", ")].filter(Boolean);
  return { title: bits.length ? `${bits.join(" · ")} jobs` : "Jobs" };
}

function ActiveFilters({ f }: { f: F }) {
  const chips: { label: string; next: Partial<F> }[] = [];
  if (f.q) chips.push({ label: `“${f.q}”`, next: { q: "", sort: "posted" } });
  if (f.location) chips.push({ label: `Near ${f.location}`, next: { location: "" } });
  for (const c of f.countries) chips.push({ label: countryName(c), next: { countries: f.countries.filter((x) => x !== c) } });
  for (const c of f.companies) chips.push({ label: c, next: { companies: f.companies.filter((x) => x !== c) } });
  for (const p of f.platforms) chips.push({ label: platformInfo(p).label, next: { platforms: f.platforms.filter((x) => x !== p) } });
  for (const b of f.boards) chips.push({ label: `Board: ${b}`, next: { boards: f.boards.filter((x) => x !== b) } });
  for (const t of f.types) chips.push({ label: t, next: { types: f.types.filter((x) => x !== t) } });
  if (f.remote !== null) chips.push({ label: f.remote ? "Remote" : "On-site / hybrid", next: { remote: null } });
  if (f.posted) chips.push({ label: { "1d": "Last 24 hours", "7d": "Last 7 days", "30d": "Last 30 days" }[f.posted], next: { posted: null } });
  if (f.status !== "open") chips.push({ label: f.status === "closed" ? "Closed jobs" : "Open and closed", next: { status: "open" } });
  if (!chips.length) return null;
  return (
    <div className="active">
      {chips.map((c) => (
        <Link key={c.label} className="chip on" href={`/jobs${toSearch({ ...f, ...c.next, page: 1 })}`} aria-label={`Remove filter ${c.label}`}>
          {c.label} <span aria-hidden>×</span>
        </Link>
      ))}
      <Link className="chip" href="/jobs">
        Clear all
      </Link>
    </div>
  );
}

function Pager({ f, total }: { f: F; total: number }) {
  const pages = Math.min(Math.ceil(total / PAGE_SIZE), MAX_PAGE);
  if (pages <= 1) return null;
  const want = new Set([1, pages, f.page - 2, f.page - 1, f.page, f.page + 1, f.page + 2]);
  const list = [...want].filter((p) => p >= 1 && p <= pages).sort((a, b) => a - b);
  const href = (p: number) => `/jobs${toSearch({ ...f, page: p })}`;
  const out: React.ReactNode[] = [];
  list.forEach((p, i) => {
    if (i > 0 && p - list[i - 1] > 1) out.push(<span key={`gap${p}`} className="gap">…</span>);
    out.push(
      p === f.page ? (
        <span key={p} className="cur" aria-current="page">
          {p}
        </span>
      ) : (
        <Link key={p} href={href(p)}>
          {p}
        </Link>
      ),
    );
  });
  return (
    <nav className="pager" aria-label="Pages">
      {f.page > 1 ? <Link href={href(f.page - 1)}>← Prev</Link> : <span className="off">← Prev</span>}
      {out}
      {f.page < pages ? <Link href={href(f.page + 1)}>Next →</Link> : <span className="off">Next →</span>}
    </nav>
  );
}

export default async function JobsPage({ searchParams }: { searchParams: Promise<Params> }) {
  const f = parseFilters(await searchParams);
  let result, boards;
  try {
    [result, boards] = await Promise.all([searchJobs(f), overview()]);
  } catch (e) {
    if (!(e instanceof SearchUnavailable)) throw e;
    return (
      <div className="wrap">
        <div className="notice">The job index is unavailable right now. {e.message}</div>
      </div>
    );
  }
  const boardPlatforms = Object.fromEntries(boards.boards.map((b) => [b.board, b.platform]));
  const from = (f.page - 1) * PAGE_SIZE + 1;
  const to = Math.min(f.page * PAGE_SIZE, result.total);

  return (
    <div className="wrap search-layout">
      <Filters filters={f} facets={result.facets} boardPlatforms={boardPlatforms} />
      <section aria-label="Results">
        <SearchBar filters={f} />
        <ActiveFilters f={f} />
        <div className="results-head">
          <div className="count">
            {result.total === 0 ? (
              "No jobs match"
            ) : (
              <>
                <b>{num(result.total)}</b> {result.total === 1 ? "job" : "jobs"}
                {result.total > PAGE_SIZE && (
                  <>
                    {" "}
                    · showing {num(from)}–{num(to)}
                  </>
                )}
              </>
            )}
          </div>
          <SortSelect filters={f} />
        </div>
        {result.hits.length === 0 ? (
          <div className="empty">
            <p style={{ margin: "0 0 12px" }}>No jobs match these filters.</p>
            <Link className="btn ghost" href="/jobs">
              Clear filters
            </Link>
          </div>
        ) : (
          <div className="list">
            {result.hits.map((h) => (
              <JobCard key={h.job.job_key} job={h.job} titleHl={h.titleHl} snippet={h.snippet} />
            ))}
          </div>
        )}
        <Pager f={f} total={result.total} />
        {result.total > MAX_PAGE * PAGE_SIZE && f.page === MAX_PAGE && (
          <p style={{ textAlign: "center", color: "var(--muted)", fontSize: 14 }}>
            Showing the first {num(MAX_PAGE * PAGE_SIZE)} results. Add a filter to narrow them down.
          </p>
        )}
      </section>
    </div>
  );
}
