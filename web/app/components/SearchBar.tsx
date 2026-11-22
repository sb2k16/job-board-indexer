"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { toSearch, type Filters } from "@/lib/query";
import { SearchIcon } from "./Nav";

/** Keywords and location. Submitting keeps the other filters and goes back to page 1. */
export function SearchBar({ filters }: { filters: Filters }) {
  const router = useRouter();
  const [q, setQ] = useState(filters.q);
  const [location, setLocation] = useState(filters.location);
  useEffect(() => {
    setQ(filters.q);
    setLocation(filters.location);
  }, [filters.q, filters.location]);

  return (
    <form
      className="qline"
      role="search"
      action="/jobs"
      onSubmit={(e) => {
        e.preventDefault();
        const next = { ...filters, q: q.trim(), location: location.trim(), page: 1 };
        // A new keyword resets the sort to the default for that mode.
        if (next.q !== filters.q) next.sort = next.q ? "relevance" : "posted";
        router.push(`/jobs${toSearch(next)}`);
      }}
    >
      <div className="qbox">
        <SearchIcon />
        <input name="q" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Title, skill or company" aria-label="What" />
        <input
          name="location"
          className="loc"
          value={location}
          onChange={(e) => setLocation(e.target.value)}
          placeholder="Anywhere"
          aria-label="Where"
        />
      </div>
      <button className="btn" type="submit">
        Search
      </button>
    </form>
  );
}
