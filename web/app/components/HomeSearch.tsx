"use client";

import { SearchBar } from "./SearchBar";
import { parseFilters } from "@/lib/query";

export function HomeSearch() {
  return <SearchBar filters={parseFilters({})} className="hero-search" />;
}
