import Link from "next/link";
import type { Metadata } from "next";
import { Filters } from "../../components/Filters";
import { JobRow } from "../../components/JobRow";
import { SearchBar } from "../../components/SearchBar";
import { searchJobs, SearchUnavailable } from "@/lib/opensearch";
import { MAX_PAGE, PAGE_SIZE, parseFilters, toSearch, type Filters as F, type Params } from "@/lib/query";
import { num } from "@/lib/format";
import { countryName } from "@/lib/countries";

export const dynamic = "force-dynamic";

export async function generateMetadata({ searchParams }: { searchParams: Promise<Params> }): Promise<Metadata> {
  const f = parseFilters(await searchParams);
  const bits = [f.q, f.companies.join(", "), f.location, f.countries.map(countryName).join(", ")].filter(Boolean);
  return { title: bits.length ? `${bits.join(" · ")} jobs` : "Jobs" };
}

const POSTED = { "1d": "Past 24 hours", "7d": "Past week", "30d": "Past month" } as const;

/** The applied filters as removable chips (keywords and location live in the search box). */
function chips(f: F): { label: string; next: Partial<F> }[] {
  const out: { label: string; next: Partial<F> }[] = [];
  if (f.remote === true) out.push({ label: "Remote only", next: { remote: null } });
  if (f.remote === false) out.push({ label: "Not remote", next: { remote: null } });
  for (const t of f.types) out.push({ label: t, next: { types: f.types.filter((x) => x !== t) } });
  if (f.posted) out.push({ label: POSTED[f.posted], next: { posted: null } });
  for (const c of f.companies) out.push({ label: c, next: { companies: f.companies.filter((x) => x !== c) } });
  for (const c of f.countries) out.push({ label: countryName(c), next: { countries: f.countries.filter((x) => x !== c) } });
  // Not offered as filters on the public site, but links may carry them.
  for (const b of f.boards) out.push({ label: `Listed on ${b}`, next: { boards: f.boards.filter((x) => x !== b) } });
  for (const p of f.platforms) out.push({ label: `Source: ${p}`, next: { platforms: f.platforms.filter((x) => x !== p) } });
  if (f.status !== "open") out.push({ label: f.status === "closed" ? "No longer listed" : "Including jobs no longer listed", next: { status: "open" } });
  return out;
}

function Pager({ f, total }: { f: F; total: number }) {
  const pages = Math.min(Math.ceil(total / PAGE_SIZE), MAX_PAGE);
  const from = (f.page - 1) * PAGE_SIZE + 1;
  const to = Math.min(f.page * PAGE_SIZE, total);
  const href = (p: number) => `/jobs${toSearch({ ...f, page: p })}`;
  const want = [...new Set([1, pages, f.page - 1, f.page, f.page + 1])].filter((p) => p >= 1 && p <= pages).sort((a, b) => a - b);
  const items: React.ReactNode[] = [];
  want.forEach((p, i) => {
    if (i > 0 && p - want[i - 1] > 1) items.push(<span key={`gap${p}`}>…</span>);
    items.push(
      p === f.page ? (
        <span key={p} aria-current="page">
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
      <span>
        Showing {num(from)}–{num(to)} of {num(total)}
      </span>
      {pages > 1 && <div className="pg">{items}</div>}
      <span>
        {f.page > 1 && (
          <Link href={href(f.page - 1)} style={{ marginRight: 18 }}>
            ← Previous
          </Link>
        )}
        {f.page < pages ? <Link href={href(f.page + 1)}>Next →</Link> : <span className="off">Next →</span>}
      </span>
    </nav>
  );
}

export default async function JobsPage({ searchParams }: { searchParams: Promise<Params> }) {
  const f = parseFilters(await searchParams);
  let result;
  try {
    result = await searchJobs(f);
  } catch (e) {
    if (!(e instanceof SearchUnavailable)) throw e;
    return (
      <div className="wrap">
        <div className="notice">Jobs can’t be shown right now. Please try again in a minute.</div>
      </div>
    );
  }
  const applied = chips(f);
  const sortHref = (sort: F["sort"]) => `/jobs${toSearch({ ...f, sort, page: 1 })}`;

  return (
    <>
      <div className="qhead">
        <div className="wrap">
          <SearchBar filters={f} />
          <div className="active">
            {applied.length > 0 && <span>Filters</span>}
            {applied.map((c) => (
              <Link key={c.label} className="chip" href={`/jobs${toSearch({ ...f, ...c.next, page: 1 })}`} aria-label={`Remove filter: ${c.label}`}>
                {c.label}
                <span className="x" aria-hidden>
                  ×
                </span>
              </Link>
            ))}
            {applied.length > 1 && (
              <Link className="clear" href={`/jobs${toSearch({ q: f.q, location: f.location })}`}>
                Clear all
              </Link>
            )}
          </div>
        </div>
      </div>
      <div className="wrap res">
        <Filters filters={f} facets={result.facets} active={applied.length} />
        <section aria-label="Results">
          <div className="reshead">
            <h1 className="count">{result.total === 0 ? "No jobs" : `${num(result.total)} ${result.total === 1 ? "job" : "jobs"}`}</h1>
            <div className="sort">
              <span style={{ color: "var(--ink-3)" }}>Sort</span>
              {f.q && (
                <Link href={sortHref("relevance")} aria-current={f.sort === "relevance"}>
                  Most relevant
                </Link>
              )}
              <Link href={sortHref("posted")} aria-current={f.sort !== "relevance"}>
                Most recent
              </Link>
            </div>
          </div>
          {result.hits.length === 0 ? (
            <div className="empty">
              <h2>No jobs match</h2>
              <p>Try fewer filters, a broader keyword, or another place.</p>
              <Link className="btn ghost" href="/jobs">
                Clear search
              </Link>
            </div>
          ) : (
            <>
              {result.hits.map((h) => (
                <JobRow key={h.job.job_key} job={h.job} titleHl={h.titleHl} snippet={h.snippet} />
              ))}
              <Pager f={f} total={result.total} />
              {result.total > MAX_PAGE * PAGE_SIZE && f.page === MAX_PAGE && (
                <p className="snip" style={{ textAlign: "center", margin: "20px auto 0" }}>
                  That’s the first {num(MAX_PAGE * PAGE_SIZE)}. Add a filter to see others.
                </p>
              )}
            </>
          )}
        </section>
      </div>
    </>
  );
}
