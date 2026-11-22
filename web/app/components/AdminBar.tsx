import Link from "next/link";
import { Wordmark } from "./Wordmark";

/** The bar on the crawl health page (/health): open to anyone for now, not linked from the public site. */
export function AdminBar() {
  return (
    <header className="bar adminbar">
      <div className="wrap">
        <Wordmark href="/" />
        <span className="badge">Internal</span>
        <nav className="nav" aria-label="Internal">
          <Link href="/health" aria-current="page">
            Crawl health
          </Link>
        </nav>
        <div className="grow" />
        <div className="live">
          <Link href="/">
            <span>Public site ↗</span>
          </Link>
        </div>
      </div>
    </header>
  );
}
