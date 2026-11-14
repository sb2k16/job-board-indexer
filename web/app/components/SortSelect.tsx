"use client";

import { useRouter } from "next/navigation";
import { toSearch, type Filters, type Sort } from "@/lib/query";

export function SortSelect({ filters }: { filters: Filters }) {
  const router = useRouter();
  return (
    <label style={{ display: "flex", alignItems: "center", gap: 8, color: "var(--muted)", fontSize: 14 }}>
      Sort
      <select
        className="select"
        value={filters.sort}
        onChange={(e) => router.push(`/jobs${toSearch({ ...filters, sort: e.target.value as Sort, page: 1 })}`)}
      >
        {filters.q && <option value="relevance">Best match</option>}
        <option value="posted">Newest posted</option>
        <option value="first_seen">Recently indexed</option>
      </select>
    </label>
  );
}
