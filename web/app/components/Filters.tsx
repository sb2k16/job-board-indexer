"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import type { Bucket } from "@/lib/opensearch";
import { toSearch, type Filters as F, type Posted } from "@/lib/query";
import { EMPLOYMENT_BUCKETS } from "@/lib/employment";
import { num } from "@/lib/format";
import { countryName } from "@/lib/countries";

interface Props {
  filters: F;
  facets: { country: Bucket[]; company: Bucket[]; type: Bucket[]; remote: Bucket[] };
  active: number;
}

const POSTED_LABELS: Record<Posted, string> = { "1d": "Past 24 hours", "7d": "Past week", "30d": "Past month" };

function toggle<T>(list: T[], v: T): T[] {
  return list.includes(v) ? list.filter((x) => x !== v) : [...list, v];
}

/** Keeps the checked values visible even when the current results no longer contain them. */
function withSelected(buckets: Bucket[], selected: string[]): Bucket[] {
  const have = new Set(buckets.map((b) => b.key));
  return [...buckets, ...selected.filter((s) => !have.has(s)).map((key) => ({ key, count: 0 }))];
}

function Option({ on, label, count, max, onClick, title }: { on: boolean; label: string; count?: number; max?: number; onClick: () => void; title?: string }) {
  return (
    <button type="button" className="opt" role="checkbox" aria-checked={on} onClick={onClick} title={title}>
      <span className={`cb${on ? " on" : ""}`} aria-hidden />
      <span className="name">{label}</span>
      {count !== undefined && <span className="n">{num(count)}</span>}
      {max ? (
        <span className="meter" aria-hidden>
          <b style={{ width: `${Math.round((100 * (count ?? 0)) / max)}%` }} />
        </span>
      ) : null}
    </button>
  );
}

function Searchable({ title, placeholder, buckets, selected, label, onToggle, moreLabel }: {
  title: string;
  placeholder: string;
  buckets: Bucket[];
  selected: string[];
  label: (key: string) => string;
  onToggle: (key: string) => void;
  moreLabel: string;
}) {
  const [query, setQuery] = useState("");
  const [all, setAll] = useState(false);
  const list = withSelected(buckets, selected).filter((b) => `${label(b.key)} ${b.key}`.toLowerCase().includes(query.toLowerCase()));
  const shown = all || query ? list.slice(0, 200) : list.slice(0, 5);
  return (
    <div className="fg">
      <h4>{title}</h4>
      <input className="fsearch" value={query} onChange={(e) => setQuery(e.target.value)} placeholder={placeholder} aria-label={placeholder} />
      {shown.map((b) => (
        <Option key={b.key} on={selected.includes(b.key)} label={label(b.key)} count={b.count} onClick={() => onToggle(b.key)} title={label(b.key)} />
      ))}
      {!query && list.length > 5 && (
        <button type="button" className="more" onClick={() => setAll(!all)}>
          {all ? "Show fewer" : moreLabel}
        </button>
      )}
      {query && list.length === 0 && <p className="snip">No match.</p>}
    </div>
  );
}

export function Filters({ filters, facets, active }: Props) {
  const router = useRouter();
  const [pending, start] = useTransition();
  const [open, setOpen] = useState(false);
  const go = (next: Partial<F>) => start(() => router.push(`/jobs${toSearch({ ...filters, ...next, page: 1 })}`, { scroll: false }));

  const typeCounts = new Map(facets.type.map((b) => [b.key, b.count]));
  const types = EMPLOYMENT_BUCKETS.filter((t) => t !== "Other" && (typeCounts.has(t) || filters.types.includes(t)));
  const maxType = Math.max(1, ...types.map((t) => typeCounts.get(t) ?? 0));

  return (
    <>
      <button type="button" className="btn ghost filters-toggle" aria-expanded={open} aria-controls="filters" onClick={() => setOpen(!open)}>
        {open ? "Hide filters" : `Filters${active ? ` · ${active}` : ""}`}
      </button>
      <aside id="filters" className={`rail${open ? " open" : ""}`} aria-label="Filters" aria-busy={pending}>
        <div className="fg">
          <h4>Remote</h4>
          <div className="seg" role="group" aria-label="Remote">
            <button type="button" aria-pressed={filters.remote !== true} onClick={() => go({ remote: null })}>
              Any
            </button>
            <button type="button" aria-pressed={filters.remote === true} onClick={() => go({ remote: true })}>
              Remote only
            </button>
          </div>
        </div>

        <div className="fg">
          <h4>Date posted</h4>
          {(Object.keys(POSTED_LABELS) as Posted[]).map((p) => (
            <Option key={p} on={filters.posted === p} label={POSTED_LABELS[p]} onClick={() => go({ posted: filters.posted === p ? null : p })} />
          ))}
        </div>

        {types.length > 0 && (
          <div className="fg">
            <h4>Job type</h4>
            {types.map((t) => (
              <Option
                key={t}
                on={filters.types.includes(t)}
                label={t}
                count={typeCounts.get(t) ?? 0}
                max={maxType}
                onClick={() => go({ types: toggle(filters.types, t) })}
              />
            ))}
          </div>
        )}

        <Searchable
          title="Company"
          placeholder="Find a company"
          buckets={facets.company}
          selected={filters.companies}
          label={(k) => k}
          onToggle={(k) => go({ companies: toggle(filters.companies, k) })}
          moreLabel="Show all companies"
        />

        <Searchable
          title="Location"
          placeholder="Find a country"
          buckets={facets.country}
          selected={filters.countries}
          label={countryName}
          onToggle={(k) => go({ countries: toggle(filters.countries, k) })}
          moreLabel="Show all countries"
        />
      </aside>
    </>
  );
}
