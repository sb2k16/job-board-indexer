// URL search parameters → validated filters → OpenSearch request body. Pure functions, no I/O, so
// they are unit-tested directly. Nothing from the URL reaches OpenSearch except through these
// whitelisted shapes: free text goes into simple_query_string (which cannot raise syntax errors),
// and everything else is matched against fixed fields.

import { EMPLOYMENT_BUCKETS, type EmploymentBucket } from "./employment.ts";
import { isCountryCode, parseCountry } from "./countries.ts";

export const PAGE_SIZE = 20;
export const MAX_PAGE = 50; // 1,000 results; deeper paging is what search_after is for, not a UI
export const SORTS = ["relevance", "posted", "first_seen"] as const;
export const POSTED = ["1d", "7d", "30d"] as const;
export const STATUSES = ["open", "closed", "all"] as const;

export type Sort = (typeof SORTS)[number];
export type Posted = (typeof POSTED)[number];
export type Status = (typeof STATUSES)[number];

export interface Filters {
  q: string;
  location: string;
  countries: string[];
  companies: string[];
  platforms: string[];
  boards: string[];
  types: EmploymentBucket[];
  remote: boolean | null;
  posted: Posted | null;
  status: Status;
  sort: Sort;
  page: number;
}

export type Params = Record<string, string | string[] | undefined>;

function all(params: Params, key: string): string[] {
  const v = params[key];
  const list = v === undefined ? [] : Array.isArray(v) ? v : [v];
  return [...new Set(list.map((s) => s.trim()).filter((s) => s.length > 0 && s.length <= 200))].slice(0, 50);
}

function one(params: Params, key: string, max = 200): string {
  return (all(params, key)[0] ?? "").slice(0, max);
}

function pick<T extends string>(value: string, allowed: readonly T[], fallback: T): T {
  return (allowed as readonly string[]).includes(value) ? (value as T) : fallback;
}

export function parseFilters(params: Params): Filters {
  const q = one(params, "q");
  const remote = one(params, "remote");
  const posted = one(params, "posted");
  const page = Number.parseInt(one(params, "page"), 10);
  const countries = all(params, "country").map((c) => c.toUpperCase()).filter(isCountryCode);
  // A location box holding just a country ("US", "Germany") filters by country, not by text.
  let location = one(params, "location");
  const asCountry = parseCountry(location);
  if (asCountry) {
    if (!countries.includes(asCountry)) countries.push(asCountry);
    location = "";
  }
  return {
    q,
    location,
    countries,
    companies: all(params, "company"),
    platforms: all(params, "platform"),
    boards: all(params, "board"),
    types: all(params, "type").filter((t): t is EmploymentBucket =>
      (EMPLOYMENT_BUCKETS as readonly string[]).includes(t),
    ),
    remote: remote === "true" ? true : remote === "false" ? false : null,
    posted: (POSTED as readonly string[]).includes(posted) ? (posted as Posted) : null,
    status: pick(one(params, "status"), STATUSES, "open"),
    sort: pick(one(params, "sort"), SORTS, q ? "relevance" : "posted"),
    page: Number.isFinite(page) ? Math.min(Math.max(page, 1), MAX_PAGE) : 1,
  };
}

/** Filters back to a query string; defaults are left out so URLs stay short. */
export function toSearch(f: Partial<Filters>): string {
  const p = new URLSearchParams();
  if (f.q) p.set("q", f.q);
  if (f.location) p.set("location", f.location);
  for (const c of f.countries ?? []) p.append("country", c);
  for (const c of f.companies ?? []) p.append("company", c);
  for (const c of f.platforms ?? []) p.append("platform", c);
  for (const c of f.boards ?? []) p.append("board", c);
  for (const c of f.types ?? []) p.append("type", c);
  if (f.remote !== undefined && f.remote !== null) p.set("remote", String(f.remote));
  if (f.posted) p.set("posted", f.posted);
  if (f.status && f.status !== "open") p.set("status", f.status);
  if (f.sort && f.sort !== (f.q ? "relevance" : "posted")) p.set("sort", f.sort);
  if (f.page && f.page > 1) p.set("page", String(f.page));
  const s = p.toString();
  return s ? `?${s}` : "";
}

type Clause = Record<string, unknown>;

/**
 * Markers the highlighter wraps matches in; the UI splits on them instead of rendering HTML.
 * Private-use code points: control characters would be lost, since OpenSearch trims fragments with
 * Java's String.trim(), which strips everything up to U+0020.
 */
export const HL_PRE = "\uE000";
export const HL_POST = "\uE001";

export interface BuildOptions {
  /** Raw employment_type values per bucket, from the index (see employment.ts). */
  employmentRaw: Map<EmploymentBucket, string[]>;
  now?: Date;
}

/**
 * The search body. Facet filters (company, platform, board, type, remote) go into post_filter, and
 * each facet's aggregation applies every facet filter except its own, so a facet keeps showing the
 * counts of its other values after one is picked (multi-select facets).
 */
export function buildSearch(f: Filters, opts: BuildOptions): Clause {
  const must: Clause[] = [];
  const filter: Clause[] = [];

  if (f.q) {
    must.push({
      simple_query_string: {
        query: f.q,
        fields: ["title^4", "company^3", "location", "description"],
        default_operator: "and",
        flags: "AND|OR|NOT|PHRASE|PREFIX|WHITESPACE|PRECEDENCE",
      },
    });
  }
  if (f.location) {
    must.push({ match: { location: { query: f.location, operator: "and" } } });
  }
  if (f.status !== "all") filter.push({ term: { status: f.status } });
  if (f.posted) {
    const days = { "1d": 1, "7d": 7, "30d": 30 }[f.posted];
    const now = opts.now ?? new Date();
    const since = new Date(now.getTime() - days * 86_400_000).toISOString();
    // posted_at when the platform gives one, else when we first saw the job.
    filter.push({
      bool: {
        should: [
          { range: { posted_at: { gte: since } } },
          {
            bool: {
              must_not: { exists: { field: "posted_at" } },
              filter: { range: { first_seen: { gte: since } } },
            },
          },
        ],
        minimum_should_match: 1,
      },
    });
  }

  const facets: Record<string, Clause | null> = {
    country: f.countries.length ? { terms: { countries: f.countries } } : null,
    company: f.companies.length ? { terms: { "company.keyword": f.companies } } : null,
    platform: f.platforms.length ? { terms: { "source_links.platform": f.platforms } } : null,
    board: f.boards.length ? { terms: { "source_links.board_id": f.boards } } : null,
    type: f.types.length
      ? { terms: { employment_type: f.types.flatMap((t) => opts.employmentRaw.get(t) ?? [`__none__${t}`]) } }
      : null,
    remote: f.remote === null ? null : { term: { remote: f.remote } },
  };
  const others = (except: string): Clause =>
    ({
      bool: {
        filter: Object.entries(facets)
          .filter(([k, v]) => k !== except && v !== null)
          .map(([, v]) => v as Clause),
      },
    }) as Clause;
  const facetAgg = (name: string, inner: Clause): Clause => ({ filter: others(name), aggs: { v: inner } });

  const sort: Clause[] =
    f.sort === "relevance" && f.q
      ? [{ _score: "desc" }, { posted_at: { order: "desc", missing: "_last" } }]
      : f.sort === "first_seen"
        ? [{ first_seen: "desc" }, { job_key: "asc" }]
        : [{ posted_at: { order: "desc", missing: "_last" } }, { first_seen: "desc" }, { job_key: "asc" }];

  return {
    size: PAGE_SIZE,
    from: (f.page - 1) * PAGE_SIZE,
    track_total_hits: true,
    query: { bool: { must: must.length ? must : [{ match_all: {} }], filter } },
    post_filter: others(""),
    sort,
    _source: { excludes: ["description", "content_hash"] },
    highlight: f.q
      ? {
          pre_tags: [HL_PRE],
          post_tags: [HL_POST],
          encoder: "default",
          fields: {
            title: { number_of_fragments: 0 },
            description: { fragment_size: 180, number_of_fragments: 1, no_match_size: 180 },
          },
        }
      : { fields: { description: { fragment_size: 180, number_of_fragments: 1, no_match_size: 180 } } },
    aggs: {
      country: facetAgg("country", { terms: { field: "countries", size: 250 } }),
      company: facetAgg("company", { terms: { field: "company.keyword", size: 300 } }),
      platform: facetAgg("platform", { terms: { field: "source_links.platform", size: 50 } }),
      board: facetAgg("board", { terms: { field: "source_links.board_id", size: 200 } }),
      type: facetAgg("type", { terms: { field: "employment_type", size: 200 } }),
      remote: facetAgg("remote", { terms: { field: "remote", size: 2 } }),
    },
  };
}
