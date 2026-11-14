import type { Metadata } from "next";
import Link from "next/link";
import "./globals.css";
import { Nav } from "./components/Nav";

export const metadata: Metadata = {
  title: { default: "Job Board Index", template: "%s · Job Board Index" },
  description: "A continuously updated, searchable index of public job postings across ATS and job-board platforms.",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <header className="header">
          <div className="wrap">
            <Link href="/" className="brand">
              <span className="brand-mark" aria-hidden>
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round">
                  <circle cx="11" cy="11" r="6.5" />
                  <path d="m20 20-4.2-4.2" />
                </svg>
              </span>
              Job Board Index
            </Link>
            <Nav />
            <div className="spacer" />
            <form action="/jobs" className="header-search" role="search">
              <input className="input" name="q" placeholder="Search jobs…" aria-label="Search jobs" />
            </form>
          </div>
        </header>
        <main>{children}</main>
        <footer className="footer">
          <div className="wrap">
            <span>Indexed from public job boards. The crawler only fetches what robots.txt allows.</span>
            <a href="https://github.com/sb2k16/job-board-indexer">Source on GitHub</a>
          </div>
        </footer>
      </body>
    </html>
  );
}
