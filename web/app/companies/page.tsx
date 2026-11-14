import Link from "next/link";
import type { Metadata } from "next";
import { Logo } from "../components/JobCard";
import { overview, SearchUnavailable } from "@/lib/opensearch";
import { num } from "@/lib/format";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Companies" };

export default async function Companies({ searchParams }: { searchParams: Promise<{ sort?: string }> }) {
  const { sort } = await searchParams;
  let ov;
  try {
    ov = await overview();
  } catch (e) {
    if (!(e instanceof SearchUnavailable)) throw e;
    return (
      <div className="wrap">
        <div className="notice">The job index is unavailable right now. {e.message}</div>
      </div>
    );
  }
  const byName = sort === "name";
  const list = [...ov.topCompanies].sort((a, b) => (byName ? a.key.localeCompare(b.key) : b.count - a.count));

  return (
    <div className="wrap">
      <div className="page-head">
        <h1>Companies</h1>
        <p>
          {num(ov.companies)} employers with open jobs. Jobs from employer ATS boards and from job boards, where each posting
          names its own employer.
        </p>
      </div>
      <div className="letters">
        <Link className={`chip${byName ? "" : " on"}`} href="/companies">
          Most jobs
        </Link>
        <Link className={`chip${byName ? " on" : ""}`} href="/companies?sort=name">
          A–Z
        </Link>
      </div>
      <div className="company-grid">
        {list.map((c) => (
          <Link key={c.key} className="company" href={`/jobs?company=${encodeURIComponent(c.key)}`} title={c.key}>
            <Logo name={c.key} />
            <div>
              <b>{c.key}</b>
              <span>
                {num(c.count)} open {c.count === 1 ? "job" : "jobs"}
              </span>
            </div>
          </Link>
        ))}
      </div>
    </div>
  );
}
