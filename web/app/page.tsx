import Link from "next/link";
import { JobCard } from "./components/JobCard";
import { HomeSearch } from "./components/HomeSearch";
import { overview, searchJobs, SearchUnavailable } from "@/lib/opensearch";
import { parseFilters } from "@/lib/query";
import { platformInfo } from "@/lib/platforms";
import { ago, num } from "@/lib/format";

export const dynamic = "force-dynamic";

export default async function Home() {
  let ov, latest;
  try {
    [ov, latest] = await Promise.all([overview(), searchJobs(parseFilters({ sort: "first_seen" }))]);
  } catch (e) {
    if (!(e instanceof SearchUnavailable)) throw e;
    return (
      <div className="wrap">
        <div className="notice">The job index is unavailable right now. {e.message}</div>
      </div>
    );
  }
  const ats = ov.platforms.filter((p) => platformInfo(p.key).kind === "ATS").length;
  const boards = ov.platforms.length - ats;

  return (
    <div className="wrap">
      <section className="hero">
        <h1>Every open job, straight from the source.</h1>
        <p>
          A live index of public postings, read directly from employers&apos; applicant tracking systems and from job
          boards, and re-checked continuously, so what you find is still open.
        </p>
        <HomeSearch />
        <div className="quick">
          <Link className="chip" href="/jobs?remote=true">
            Remote
          </Link>
          <Link className="chip" href="/jobs?type=Internship">
            Internships
          </Link>
          <Link className="chip" href="/jobs?posted=1d">
            Posted today
          </Link>
          <Link className="chip" href="/jobs?q=engineer">
            Engineering
          </Link>
          <Link className="chip" href="/jobs?q=design">
            Design
          </Link>
          <Link className="chip" href="/jobs?q=sales">
            Sales
          </Link>
        </div>
      </section>

      <div className="stats">
        <div className="stat">
          <b>{num(ov.open)}</b>
          <span>open jobs</span>
        </div>
        <div className="stat">
          <b>{num(ov.companies)}</b>
          <span>employers</span>
        </div>
        <div className="stat">
          <b>{num(ov.boards.length)}</b>
          <span>
            boards on {ats} ATS + {boards} job-board platforms
          </span>
        </div>
        <div className="stat">
          <b>{ov.lastChecked ? ago(ov.lastChecked) : "—"}</b>
          <span>last re-checked · {num(ov.newToday)} added in 24 h</span>
        </div>
      </div>

      <section className="section">
        <div className="section-head">
          <h2>Browse by source</h2>
          <Link href="/boards">All boards →</Link>
        </div>
        <div className="tiles">
          {ov.platforms.map((p) => {
            const info = platformInfo(p.key);
            return (
              <Link key={p.key} className="tile" href={`/jobs?platform=${encodeURIComponent(p.key)}`}>
                <div className="k">{info.kind}</div>
                <b>{info.label}</b>
                <div className="c">{num(p.count)} open jobs</div>
              </Link>
            );
          })}
        </div>
      </section>

      <section className="section">
        <div className="section-head">
          <h2>Top employers</h2>
          <Link href="/companies">All companies →</Link>
        </div>
        <div className="tiles">
          {ov.topCompanies.slice(0, 8).map((c) => (
            <Link key={c.key} className="tile" href={`/jobs?company=${encodeURIComponent(c.key)}`}>
              <b>{c.key}</b>
              <div className="c">{num(c.count)} open jobs</div>
            </Link>
          ))}
        </div>
      </section>

      <section className="section">
        <div className="section-head">
          <h2>Recently indexed</h2>
          <Link href="/jobs?sort=first_seen">See all →</Link>
        </div>
        <div className="list">
          {latest.hits.slice(0, 8).map((h) => (
            <JobCard key={h.job.job_key} job={h.job} />
          ))}
        </div>
      </section>
    </div>
  );
}
