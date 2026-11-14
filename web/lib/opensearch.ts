// Server-side OpenSearch access. The browser never talks to OpenSearch: every request is built
// here from validated filters (query.ts) and only the `jobs` alias is read.
import "server-only";

import { groupEmployment, type EmploymentBucket } from "./employment";
import { buildSearch, type Filters } from "./query";

const URL_BASE = (process.env.OPENSEARCH_URL ?? "http://localhost:9200").replace(/\/$/, "");
const INDEX = process.env.JOBS_INDEX ?? "jobs";

export interface SourceLink {
  url?: string;
  application_url?: string;
  platform?: string;
  board_id?: string;
  last_seen?: string;
  state?: string;
  gone_at?: string;
}

export interface Job {
  job_key: string;
  title?: string;
  company?: string;
  description?: string;
  location?: string;
  countries?: string[];
  remote?: boolean | null;
  employment_type?: string | null;
  requisition_id?: string | null;
  posted_at?: string | null;
  first_seen?: string;
  last_checked?: string;
  last_changed?: string;
  closed_at?: string | null;
  status?: string;
  source_links?: SourceLink[];
}

export interface Bucket {
  key: string;
  count: number;
}

export interface SearchResult {
  total: number;
  hits: { job: Job; titleHl?: string; snippet?: string }[];
  facets: {
    country: Bucket[];
    company: Bucket[];
    platform: Bucket[];
    board: Bucket[];
    type: Bucket[];
    remote: Bucket[];
  };
}

export class SearchUnavailable extends Error {}

async function os<T>(path: string, body?: unknown): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`${URL_BASE}/${path}`, {
      method: body === undefined ? "GET" : "POST",
      headers: { "content-type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
      cache: "no-store",
      signal: AbortSignal.timeout(8_000),
    });
  } catch (e) {
    throw new SearchUnavailable(`search is unreachable: ${(e as Error).message}`);
  }
  if (!res.ok) {
    throw new SearchUnavailable(`search returned HTTP ${res.status}: ${(await res.text()).slice(0, 300)}`);
  }
  return (await res.json()) as T;
}

type AggBuckets = { buckets: { key: string | number; key_as_string?: string; doc_count: number }[] };

// The raw employment_type values change only when a new board appears; refresh every 5 minutes.
let employmentCache: { at: number; map: Map<EmploymentBucket, { count: number; raw: string[] }> } | null = null;

async function employmentRaw() {
  if (employmentCache && Date.now() - employmentCache.at < 300_000) return employmentCache.map;
  const r = await os<{ aggregations: { t: AggBuckets } }>(`${INDEX}/_search`, {
    size: 0,
    aggs: { t: { terms: { field: "employment_type", size: 1000 } } },
  });
  const map = groupEmployment(r.aggregations.t.buckets.map((b) => ({ key: String(b.key), doc_count: b.doc_count })));
  employmentCache = { at: Date.now(), map };
  return map;
}

const buckets = (a: { v: AggBuckets }): Bucket[] =>
  a.v.buckets.map((b) => ({ key: b.key_as_string ?? String(b.key), count: b.doc_count }));

export async function searchJobs(f: Filters): Promise<SearchResult> {
  const grouped = await employmentRaw();
  const raw = new Map([...grouped].map(([k, v]) => [k, v.raw] as const));
  const body = buildSearch(f, { employmentRaw: raw });
  const r = await os<{
    hits: {
      total: { value: number };
      hits: { _source: Job; highlight?: { title?: string[]; description?: string[] } }[];
    };
    aggregations: Record<string, { v: AggBuckets }>;
  }>(`${INDEX}/_search`, body);

  // Facet counts for employment buckets: sum the raw values the current filters leave.
  const typeCounts = new Map<string, number>();
  for (const b of buckets(r.aggregations.type)) {
    for (const [name, v] of grouped) {
      if (v.raw.includes(b.key)) typeCounts.set(name, (typeCounts.get(name) ?? 0) + b.count);
    }
  }

  return {
    total: r.hits.total.value,
    hits: r.hits.hits.map((h) => ({
      job: h._source,
      titleHl: h.highlight?.title?.[0],
      snippet: h.highlight?.description?.[0],
    })),
    facets: {
      country: buckets(r.aggregations.country),
      company: buckets(r.aggregations.company),
      platform: buckets(r.aggregations.platform),
      board: buckets(r.aggregations.board),
      type: [...typeCounts].map(([key, count]) => ({ key, count })).sort((a, b) => b.count - a.count),
      remote: buckets(r.aggregations.remote),
    },
  };
}

export async function getJob(key: string): Promise<Job | null> {
  if (!/^[A-Za-z0-9_-]{1,128}$/.test(key)) return null;
  const r = await os<{ hits: { hits: { _source: Job }[] } }>(`${INDEX}/_search`, {
    size: 1,
    query: { term: { job_key: key } },
  });
  return r.hits.hits[0]?._source ?? null;
}

export async function moreFromCompany(company: string, except: string): Promise<Job[]> {
  const r = await os<{ hits: { hits: { _source: Job }[] } }>(`${INDEX}/_search`, {
    size: 6,
    query: {
      bool: {
        filter: [{ term: { "company.keyword": company } }, { term: { status: "open" } }],
        must_not: [{ term: { job_key: except } }],
      },
    },
    sort: [{ posted_at: { order: "desc", missing: "_last" } }, { first_seen: "desc" }],
    _source: { excludes: ["description", "content_hash"] },
  });
  return r.hits.hits.map((h) => h._source);
}

export interface Overview {
  open: number;
  closed: number;
  companies: number;
  newToday: number;
  lastChecked: string | null;
  platforms: Bucket[];
  boards: { platform: string; board: string; count: number; companies: Bucket[] }[];
  topCompanies: Bucket[];
}

export async function overview(): Promise<Overview> {
  const since = new Date(Date.now() - 86_400_000).toISOString();
  const r = await os<{
    aggregations: {
      status: AggBuckets;
      companies: { value: number };
      newToday: { doc_count: number };
      lastChecked: { value_as_string?: string };
      open: {
        platforms: AggBuckets;
        topCompanies: AggBuckets;
        boards: { buckets: { key: string; doc_count: number; platform: AggBuckets; companies: AggBuckets }[] };
      };
    };
  }>(`${INDEX}/_search`, {
    size: 0,
    aggs: {
      status: { terms: { field: "status" } },
      companies: { cardinality: { field: "company.keyword" } },
      newToday: { filter: { range: { first_seen: { gte: since } } } },
      lastChecked: { max: { field: "last_checked" } },
      open: {
        filter: { term: { status: "open" } },
        aggs: {
          platforms: { terms: { field: "source_links.platform", size: 50 } },
          topCompanies: { terms: { field: "company.keyword", size: 1000 } },
          boards: {
            terms: { field: "source_links.board_id", size: 500 },
            aggs: {
              platform: { terms: { field: "source_links.platform", size: 1 } },
              companies: { terms: { field: "company.keyword", size: 3 } },
            },
          },
        },
      },
    },
  });
  const a = r.aggregations;
  const status = new Map(a.status.buckets.map((b) => [String(b.key), b.doc_count]));
  const toB = (x: AggBuckets): Bucket[] => x.buckets.map((b) => ({ key: String(b.key), count: b.doc_count }));
  return {
    open: status.get("open") ?? 0,
    closed: status.get("closed") ?? 0,
    companies: a.companies.value,
    newToday: a.newToday.doc_count,
    lastChecked: a.lastChecked.value_as_string ?? null,
    platforms: toB(a.open.platforms),
    topCompanies: toB(a.open.topCompanies),
    boards: a.open.boards.buckets.map((b) => ({
      board: b.key,
      platform: String(b.platform.buckets[0]?.key ?? ""),
      count: b.doc_count,
      companies: toB(b.companies),
    })),
  };
}
