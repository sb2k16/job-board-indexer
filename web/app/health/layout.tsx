import type { Metadata } from "next";

// Crawl health is open without a password for now, but kept out of search engines.
export const metadata: Metadata = { robots: { index: false, follow: false } };

export default function HealthLayout({ children }: { children: React.ReactNode }) {
  return <>{children}</>;
}
