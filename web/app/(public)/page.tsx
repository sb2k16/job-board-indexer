import Link from "next/link";
import { JobRow, Mark } from "../components/JobRow";
import { overview, searchJobs, SearchUnavailable } from "@/lib/opensearch";
import { parseFilters } from "@/lib/query";
import { num } from "@/lib/format";

export const dynamic = "force-dynamic";

const PATHS = [
  { label: "Remote engineering", href: "/jobs?q=engineer&remote=true" },
  { label: "Posted today", href: "/jobs?posted=1d" },
  { label: "Internships", href: "/jobs?type=Internship" },
  { label: "Design", href: "/jobs?q=design" },
  { label: "Sales", href: "/jobs?q=sales" },
];

export default async function Home() {
  let ov, latest;
  try {
    [ov, latest] = await Promise.all([overview(), searchJobs(parseFilters({}))]);
  } catch (e) {
    if (!(e instanceof SearchUnavailable)) throw e;
    return (
      <div className="wrap">
        <div className="notice">Jobs can’t be shown right now. Please try again in a minute.</div>
      </div>
    );
  }

  return (
    <>
      <section className="mast">
        <div className="wrap">
          <div>
            <div className="kicker">Open jobs at {num(ov.companies)} companies</div>
            <h1>
              Real jobs, straight from the <em>companies hiring</em>.
            </h1>
            <p className="lede">
              Read from employers’ careers sites and the job boards they post on, and checked again through the day. When a
              job comes down, it comes down here too.
            </p>
            <form className="search" action="/jobs" role="search">
              <label className="f w">
                <small>What</small>
                <input name="q" placeholder="Title, skill or company" />
              </label>
              <label className="f">
                <small>Where</small>
                <input name="location" placeholder="City or country" />
              </label>
              <label className="tog">
                <input type="checkbox" name="remote" value="true" />
                <span className="sw" aria-hidden />
                Remote only
              </label>
              <button type="submit">Search</button>
            </form>
            <div className="paths">
              <span>Popular</span>
              {PATHS.map((p) => (
                <Link key={p.href} href={p.href}>
                  {p.label}
                </Link>
              ))}
            </div>
          </div>
          <div className="ledger" aria-label="Today in numbers">
            <div className="fig">
              <div className="n">{num(ov.open)}</div>
              <div className="l">open jobs</div>
            </div>
            <div className="rule" />
            <div className="fig sm">
              <div className="n">{num(ov.companies)}</div>
              <div className="l">companies hiring</div>
            </div>
            <div className="fig sm">
              <div className="n">{num(ov.newToday)}</div>
              <div className="l">new in the last 24 hours</div>
            </div>
            <div className="fig sm">
              <div className="n">{num(ov.closedToday)}</div>
              <div className="l">taken down since yesterday</div>
            </div>
          </div>
        </div>
      </section>

      <div className="wrap cols">
        <section aria-labelledby="new-jobs">
          <div className="h2">
            <h2 id="new-jobs">New jobs</h2>
            <Link href="/jobs">All new jobs →</Link>
          </div>
          {latest.hits.slice(0, 6).map((h) => (
            <JobRow key={h.job.job_key} job={h.job} />
          ))}
        </section>
        <section aria-labelledby="hiring">
          <div className="h2">
            <h2 id="hiring">Companies hiring now</h2>
            <Link href="/companies">All companies →</Link>
          </div>
          {ov.topCompanies.slice(0, 8).map((c) => (
            <Link key={c.key} className="co" href={`/jobs?company=${encodeURIComponent(c.key)}`}>
              <Mark name={c.key} />
              <b>{c.key}</b>
              <span className="c">{num(c.count)} jobs</span>
            </Link>
          ))}
        </section>
      </div>
    </>
  );
}
