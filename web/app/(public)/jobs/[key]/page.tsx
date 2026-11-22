import Link from "next/link";
import { notFound } from "next/navigation";
import type { Metadata } from "next";
import { Mark } from "../../../components/JobRow";
import { Share } from "../../../components/Share";
import { getJob, moreFromCompany, SearchUnavailable, type Job } from "@/lib/opensearch";
import { employmentBucket } from "@/lib/employment";
import { ago, blocks, num, shortDate } from "@/lib/format";
import { countryName } from "@/lib/countries";
import { applyLabel, applyUrl, isUnconfirmed, lastSeen, primaryLink, whereListed } from "@/lib/source";

export const dynamic = "force-dynamic";

export async function generateMetadata({ params }: { params: Promise<{ key: string }> }): Promise<Metadata> {
  try {
    const job = await getJob((await params).key);
    if (!job) return { title: "Job not found" };
    return { title: `${job.title} at ${job.company ?? "unknown company"}`, description: job.description?.slice(0, 160) };
  } catch {
    return { title: "Job" };
  }
}

/** The facts a job seeker scans first; a cell appears only when the source gives it. */
function spec(job: Job): { label: string; value: string }[] {
  const out: { label: string; value: string }[] = [];
  if (job.remote) out.push({ label: "Workplace", value: "Remote" });
  if (job.employment_type) {
    const b = employmentBucket(job.employment_type);
    out.push({ label: "Job type", value: b === "Other" ? job.employment_type : b });
  }
  if (job.countries?.length) out.push({ label: job.countries.length > 1 ? "Countries" : "Country", value: job.countries.map(countryName).join(", ") });
  out.push(job.posted_at ? { label: "Posted", value: shortDate(job.posted_at) } : { label: "Found", value: shortDate(job.first_seen) });
  return out;
}

function Description({ text }: { text?: string }) {
  if (!text) return <p className="none">The employer’s listing has no description here. The full posting is on their site.</p>;
  return (
    <>
      {blocks(text).map((b, i) =>
        b.kind === "h" ? (
          <h3 key={i}>{b.text}</h3>
        ) : b.kind === "ul" ? (
          <ul key={i}>
            {b.items.map((t, j) => (
              <li key={j}>{t}</li>
            ))}
          </ul>
        ) : (
          <p key={i}>{b.text}</p>
        ),
      )}
    </>
  );
}

export default async function JobPage({ params }: { params: Promise<{ key: string }> }) {
  const { key } = await params;
  let job: Job | null;
  let more: { jobs: Job[]; total: number } = { jobs: [], total: 0 };
  try {
    job = await getJob(key);
    if (job?.company) more = await moreFromCompany(job.company, job.job_key);
  } catch (e) {
    if (!(e instanceof SearchUnavailable)) throw e;
    return (
      <div className="wrap">
        <div className="notice">This job can’t be shown right now. Please try again in a minute.</div>
      </div>
    );
  }
  if (!job) notFound();

  const link = primaryLink(job.source_links);
  const apply = applyUrl(job.source_links);
  const closed = job.status === "closed";
  const cells = spec(job);
  const updated = job.last_changed && shortDate(job.last_changed) !== shortDate(job.posted_at ?? job.first_seen) ? shortDate(job.last_changed) : null;
  const dates = [job.posted_at ? `Posted ${shortDate(job.posted_at)}` : `Found ${shortDate(job.first_seen)}`, updated && `updated ${updated}`]
    .filter(Boolean)
    .join(" · ");
  const seen = lastSeen(link, job.last_checked);
  const unconfirmed = isUnconfirmed(seen);
  const companyHref = job.company ? `/jobs?company=${encodeURIComponent(job.company)}` : "/jobs";

  return (
    <div className="wrap">
      <div className="crumb">
        <Link href="/jobs">← All jobs</Link>
      </div>
      <div className="detail">
        <article>
          <div className="dh">
            <Mark name={job.company} />
            <div>
              <h1>{job.title}</h1>
              <div className="who">
                {job.company && (
                  <Link href={companyHref}>
                    <b>{job.company}</b>
                  </Link>
                )}
                {job.company && job.location && " · "}
                {job.location}
              </div>
            </div>
          </div>
          <div className="spec" style={{ gridTemplateColumns: `repeat(${Math.max(cells.length, 2)}, minmax(0, 1fr))` }}>
            {cells.map((c) => (
              <div key={c.label}>
                <small>{c.label}</small>
                <b>{c.value}</b>
              </div>
            ))}
          </div>
          <div className="body">
            <Description text={job.description} />
          </div>
        </article>

        <aside className="aside">
          <div className="apply">
            {closed ? (
              <>
                <div className="still closed">
                  <i aria-hidden />
                  No longer listed
                </div>
                <p>The employer took this job down{job.closed_at ? ` on ${shortDate(job.closed_at)}` : ""}.</p>
                <Link className="btn" href={companyHref}>
                  {job.company ? `Open jobs at ${job.company}` : "Search open jobs"}
                </Link>
              </>
            ) : (
              <>
                <div className={unconfirmed ? "still unconfirmed" : "still"}>
                  <i aria-hidden />
                  {unconfirmed
                    ? `Last seen on ${whereListed(link, job.company)} ${ago(seen)}`
                    : `Still listed on ${whereListed(link, job.company)}`}
                </div>
                <p>
                  {dates}
                  {seen && !unconfirmed && ` · checked ${ago(seen)}`}
                </p>
                {unconfirmed && (
                  <p>We haven’t been able to recheck this listing since then, so it may have closed. The link goes to the original posting.</p>
                )}
                {apply ? (
                  <a className="btn" href={apply} target="_blank" rel="noopener noreferrer nofollow">
                    {applyLabel(link, job.company)} <span aria-hidden>↗</span>
                  </a>
                ) : (
                  <span className="btn ghost">No application link</span>
                )}
              </>
            )}
            <Share title={`${job.title}${job.company ? ` at ${job.company}` : ""}`} />
          </div>

          {more.jobs.length > 0 && (
            <div className="box">
              <h4>
                More jobs at {job.company} ({num(more.total)})
              </h4>
              {more.jobs.map((m) => (
                <Link key={m.job_key} className="r" href={`/jobs/${m.job_key}`}>
                  <b>{m.title}</b>
                  <span>{m.location}</span>
                </Link>
              ))}
              <Link className="all ulink" href={companyHref}>
                See all {num(more.total)} →
              </Link>
            </div>
          )}
        </aside>
      </div>
    </div>
  );
}
