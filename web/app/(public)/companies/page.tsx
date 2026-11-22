import Link from "next/link";
import type { Metadata } from "next";
import { Mark } from "../../components/JobRow";
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
        <div className="notice">Companies can’t be shown right now. Please try again in a minute.</div>
      </div>
    );
  }
  const byName = sort === "name";
  const list = [...ov.topCompanies].sort((a, b) => (byName ? a.key.localeCompare(b.key) : b.count - a.count));

  return (
    <>
      <div className="ph">
        <div className="wrap">
          <div>
            <h1>Companies hiring</h1>
            <p>{num(ov.companies)} companies with open jobs right now.</p>
          </div>
          <nav className="tabs" aria-label="Sort companies">
            <Link href="/companies" aria-current={!byName}>
              Most jobs
            </Link>
            <Link href="/companies?sort=name" aria-current={byName}>
              A–Z
            </Link>
          </nav>
        </div>
      </div>
      <div className="wrap co-grid">
        {list.map((c) => (
          <Link key={c.key} className="co" href={`/jobs?company=${encodeURIComponent(c.key)}`} title={c.key}>
            <Mark name={c.key} />
            <b>{c.key}</b>
            <span className="c">
              {num(c.count)} {c.count === 1 ? "job" : "jobs"}
            </span>
          </Link>
        ))}
      </div>
    </>
  );
}
