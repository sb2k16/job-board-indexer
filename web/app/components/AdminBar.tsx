import Link from "next/link";
import { Wordmark } from "./Wordmark";
import { SignOutButton } from "./AdminForms";

export function AdminBar({ signedIn }: { signedIn: boolean }) {
  return (
    <header className="bar adminbar">
      <div className="wrap">
        <Wordmark href={signedIn ? "/admin" : "/"} />
        <span className="badge">Internal</span>
        {signedIn && (
          <nav className="nav" aria-label="Admin">
            <Link href="/admin" aria-current="page">
              Crawl health
            </Link>
          </nav>
        )}
        <div className="grow" />
        <div className="live">
          <Link href="/">
            <span>Public site ↗</span>
          </Link>
          {signedIn && (
            <SignOutButton />
          )}
        </div>
      </div>
    </header>
  );
}
