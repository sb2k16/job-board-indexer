"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

const PUBLIC = [
  { href: "/jobs", label: "Jobs" },
  { href: "/companies", label: "Companies" },
  { href: "/how-it-works", label: "How it works" },
];

export function Nav({ links = PUBLIC, label = "Main" }: { links?: { href: string; label: string }[]; label?: string }) {
  const path = usePathname();
  return (
    <nav className="nav" aria-label={label}>
      {links.map((l) => (
        <Link key={l.href} href={l.href} aria-current={path === l.href || path.startsWith(`${l.href}/`) ? "page" : undefined}>
          {l.label}
        </Link>
      ))}
    </nav>
  );
}

/** The small search box in the bar; hidden on the pages that have a big one. */
export function BarSearch() {
  const path = usePathname();
  if (path === "/" || path === "/jobs") return null;
  return (
    <form action="/jobs" className="mini-search" role="search">
      <SearchIcon />
      <input name="q" placeholder="Search jobs" aria-label="Search jobs" />
    </form>
  );
}

export function SearchIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden>
      <circle cx="11" cy="11" r="6.5" />
      <path d="m20 20-4.2-4.2" />
    </svg>
  );
}
