"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

const LINKS = [
  { href: "/jobs", label: "Jobs" },
  { href: "/companies", label: "Companies" },
  { href: "/boards", label: "Boards" },
];

export function Nav() {
  const path = usePathname();
  return (
    <nav className="nav">
      {LINKS.map((l) => (
        <Link key={l.href} href={l.href} aria-current={path.startsWith(l.href) ? "page" : undefined}>
          {l.label}
        </Link>
      ))}
    </nav>
  );
}
