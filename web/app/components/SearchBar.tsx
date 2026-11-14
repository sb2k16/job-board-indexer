"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { toSearch, type Filters } from "@/lib/query";

/** Keyword + location. Submitting keeps every other filter and goes back to page 1. */
export function SearchBar({ filters, className = "search-bar" }: { filters: Filters; className?: string }) {
  const router = useRouter();
  const [q, setQ] = useState(filters.q);
  const [location, setLocation] = useState(filters.location);
  useEffect(() => {
    setQ(filters.q);
    setLocation(filters.location);
  }, [filters.q, filters.location]);

  return (
    <form
      className={className}
      role="search"
      onSubmit={(e) => {
        e.preventDefault();
        const next = { ...filters, q: q.trim(), location: location.trim(), page: 1 };
        // A new keyword resets an explicit sort back to the default for that mode.
        if (next.q !== filters.q) next.sort = next.q ? "relevance" : "posted";
        router.push(`/jobs${toSearch(next)}`);
      }}
    >
      <input className="input" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Title, skill or company" aria-label="Keywords" />
      <input className="input" value={location} onChange={(e) => setLocation(e.target.value)} placeholder="City, region or country" aria-label="Location" />
      <button className="btn" type="submit">
        Search
      </button>
    </form>
  );
}
