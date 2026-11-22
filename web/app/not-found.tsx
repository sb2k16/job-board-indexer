import Link from "next/link";
import { Wordmark } from "./components/Wordmark";

export default function NotFound() {
  return (
    <>
      <header className="bar">
        <div className="wrap">
          <Wordmark />
        </div>
      </header>
      <main className="wrap">
        <div className="empty">
          <h2>This page isn’t here</h2>
          <p>If it was a job, the employer has probably taken it down.</p>
          <Link className="btn" href="/jobs">
            Search open jobs
          </Link>
        </div>
      </main>
    </>
  );
}
