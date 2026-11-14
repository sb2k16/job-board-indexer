"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import type { Bucket } from "@/lib/opensearch";
import { platformInfo } from "@/lib/platforms";
import { toSearch, type Filters as F, type Posted } from "@/lib/query";
import { EMPLOYMENT_BUCKETS, type EmploymentBucket } from "@/lib/employment";
import { num } from "@/lib/format";
import { countryName } from "@/lib/countries";

interface Props {
  filters: F;
  facets: { country: Bucket[]; company: Bucket[]; platform: Bucket[]; board: Bucket[]; type: Bucket[]; remote: Bucket[] };
  boardPlatforms: Record<string, string>;
}

function toggle<T>(list: T[], v: T): T[] {
  return list.includes(v) ? list.filter((x) => x !== v) : [...list, v];
}

/** Keeps the checked values visible even when the current results no longer contain them. */
function withSelected(buckets: Bucket[], selected: string[]): Bucket[] {
  const have = new Set(buckets.map((b) => b.key));
  return [...buckets, ...selected.filter((s) => !have.has(s)).map((key) => ({ key, count: 0 }))];
}

export function Filters({ filters, facets, boardPlatforms }: Props) {
  const router = useRouter();
  const [pending, start] = useTransition();
  const [companyQuery, setCompanyQuery] = useState("");
  const [showAllCompanies, setShowAllCompanies] = useState(false);
  const [countryQuery, setCountryQuery] = useState("");
  const [showAllCountries, setShowAllCountries] = useState(false);
  const [showAllBoards, setShowAllBoards] = useState(false);
  const [open, setOpen] = useState(false);

  const go = (next: Partial<F>) => {
    start(() => router.push(`/jobs${toSearch({ ...filters, ...next, page: 1 })}`, { scroll: false }));
  };

  const companies = withSelected(facets.company, filters.companies).filter((b) =>
    b.key.toLowerCase().includes(companyQuery.toLowerCase()),
  );
  const shownCompanies = showAllCompanies || companyQuery ? companies.slice(0, 200) : companies.slice(0, 8);
  const countries = withSelected(facets.country, filters.countries).filter((b) =>
    `${countryName(b.key)} ${b.key}`.toLowerCase().includes(countryQuery.toLowerCase()),
  );
  const shownCountries = showAllCountries || countryQuery ? countries : countries.slice(0, 8);
  const boards = withSelected(facets.board, filters.boards);
  const shownBoards = showAllBoards ? boards : boards.slice(0, 8);
  const typeCounts = new Map(facets.type.map((b) => [b.key, b.count]));
  const remoteCounts = new Map(facets.remote.map((b) => [b.key, b.count]));
  const platforms = withSelected(facets.platform, filters.platforms);
  const ats = platforms.filter((p) => platformInfo(p.key).kind === "ATS");
  const jobBoards = platforms.filter((p) => platformInfo(p.key).kind === "Job board");

  const platformRow = (b: Bucket) => (
    <label key={b.key}>
      <input
        type="checkbox"
        checked={filters.platforms.includes(b.key)}
        onChange={() => go({ platforms: toggle(filters.platforms, b.key) })}
      />
      <span className="name">{platformInfo(b.key).label}</span>
      <span className="n">{num(b.count)}</span>
    </label>
  );

  return (
    <>
      <button type="button" className="btn ghost filters-toggle" onClick={() => setOpen(!open)} style={{ marginBottom: 12 }}>
        {open ? "Hide filters" : "Show filters"}
      </button>
      <aside className={`sidebar${open ? " open" : ""}`} aria-busy={pending} style={{ opacity: pending ? 0.6 : 1 }}>
        <div className="facet">
          <h3>Date posted</h3>
          {([null, "1d", "7d", "30d"] as (Posted | null)[]).map((p) => (
            <label key={p ?? "any"}>
              <input type="radio" name="posted" checked={filters.posted === p} onChange={() => go({ posted: p })} />
              <span className="name">{p === null ? "Any time" : { "1d": "Last 24 hours", "7d": "Last 7 days", "30d": "Last 30 days" }[p]}</span>
            </label>
          ))}
        </div>

        <div className="facet">
          <h3>Country</h3>
          <input
            className="input"
            placeholder="Filter countries"
            value={countryQuery}
            onChange={(e) => setCountryQuery(e.target.value)}
            aria-label="Filter countries"
          />
          {shownCountries.map((b) => (
            <label key={b.key}>
              <input
                type="checkbox"
                checked={filters.countries.includes(b.key)}
                onChange={() => go({ countries: toggle(filters.countries, b.key) })}
              />
              <span className="name">{countryName(b.key)}</span>
              <span className="n">{num(b.count)}</span>
            </label>
          ))}
          {!countryQuery && countries.length > 8 && (
            <button type="button" className="more" onClick={() => setShowAllCountries(!showAllCountries)}>
              {showAllCountries ? "Show fewer" : `Show all ${num(countries.length)}`}
            </button>
          )}
        </div>

        <div className="facet">
          <h3>Work arrangement</h3>
          {([
            [null, "Any"],
            [true, "Remote"],
            [false, "On-site / hybrid"],
          ] as [boolean | null, string][]).map(([v, label]) => (
            <label key={String(v)}>
              <input type="radio" name="remote" checked={filters.remote === v} onChange={() => go({ remote: v })} />
              <span className="name">{label}</span>
              {v !== null && <span className="n">{num(remoteCounts.get(String(v)) ?? 0)}</span>}
            </label>
          ))}
        </div>

        <div className="facet">
          <h3>Employment type</h3>
          {EMPLOYMENT_BUCKETS.filter((t) => typeCounts.has(t) || filters.types.includes(t)).map((t: EmploymentBucket) => (
            <label key={t}>
              <input type="checkbox" checked={filters.types.includes(t)} onChange={() => go({ types: toggle(filters.types, t) })} />
              <span className="name">{t}</span>
              <span className="n">{num(typeCounts.get(t) ?? 0)}</span>
            </label>
          ))}
        </div>

        <div className="facet">
          <h3>Company</h3>
          <input
            className="input"
            placeholder="Filter companies"
            value={companyQuery}
            onChange={(e) => setCompanyQuery(e.target.value)}
            aria-label="Filter companies"
          />
          {shownCompanies.map((b) => (
            <label key={b.key} title={b.key}>
              <input
                type="checkbox"
                checked={filters.companies.includes(b.key)}
                onChange={() => go({ companies: toggle(filters.companies, b.key) })}
              />
              <span className="name">{b.key}</span>
              <span className="n">{num(b.count)}</span>
            </label>
          ))}
          {!companyQuery && companies.length > 8 && (
            <button type="button" className="more" onClick={() => setShowAllCompanies(!showAllCompanies)}>
              {showAllCompanies ? "Show fewer" : `Show all ${num(companies.length)}`}
            </button>
          )}
        </div>

        <div className="facet">
          <h3>Source platform</h3>
          {ats.length > 0 && <div className="sub">Applicant tracking systems</div>}
          {ats.map(platformRow)}
          {jobBoards.length > 0 && <div className="sub">Job boards</div>}
          {jobBoards.map(platformRow)}
        </div>

        <div className="facet">
          <h3>Board</h3>
          {shownBoards.map((b) => (
            <label key={b.key} title={b.key}>
              <input
                type="checkbox"
                checked={filters.boards.includes(b.key)}
                onChange={() => go({ boards: toggle(filters.boards, b.key) })}
              />
              <span className="name">
                {b.key}
                {boardPlatforms[b.key] && <span style={{ color: "var(--faint)" }}> · {platformInfo(boardPlatforms[b.key]).label}</span>}
              </span>
              <span className="n">{num(b.count)}</span>
            </label>
          ))}
          {boards.length > 8 && (
            <button type="button" className="more" onClick={() => setShowAllBoards(!showAllBoards)}>
              {showAllBoards ? "Show fewer" : `Show all ${num(boards.length)}`}
            </button>
          )}
        </div>

        <div className="facet">
          <h3>Status</h3>
          {(["open", "closed", "all"] as const).map((s) => (
            <label key={s}>
              <input type="radio" name="status" checked={filters.status === s} onChange={() => go({ status: s })} />
              <span className="name">{{ open: "Open", closed: "Closed", all: "All" }[s]}</span>
            </label>
          ))}
        </div>
      </aside>
    </>
  );
}
