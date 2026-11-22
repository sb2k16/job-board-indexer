import Link from "next/link";
import { BarSearch, Nav } from "../components/Nav";
import { Wordmark } from "../components/Wordmark";
import { REPO_URL } from "@/lib/site";

export default function PublicLayout({ children }: { children: React.ReactNode }) {
  return (
    <>
      <header className="bar">
        <div className="wrap">
          <Wordmark />
          <Nav />
          <div className="grow" />
          <BarSearch />
        </div>
      </header>
      <main>{children}</main>
      <footer className="foot">
        <div className="wrap">
          <span>Every job links to the page where it was posted, so you apply there.</span>
          <nav aria-label="Footer">
            <Link href="/how-it-works">How it works</Link>
            <a href={REPO_URL}>Source code</a>
          </nav>
        </div>
      </footer>
    </>
  );
}
