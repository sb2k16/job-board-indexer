import Link from "next/link";
import { notFound } from "next/navigation";
import type { Metadata } from "next";
import { Logo } from "../../components/JobCard";
import { getJob, moreFromCompany } from "@/lib/opensearch";
import { employmentBucket } from "@/lib/employment";
import { ago, dateLabel, paragraphs } from "@/lib/format";
import { platformInfo } from "@/lib/platforms";
import { countryName } from "@/lib/countries";

export const dynamic = "force-dynamic";

/** Only http(s) links from the index become hrefs. */
function safeUrl(u?: string): string | null {
  if (!u) return null;
  try {
    const url = new URL(u);
    return url.protocol === "https:" || url.protocol === "http:" ? url.toString() : null;
  } catch {
    return null;
  }
}

export async function generateMetadata({ params }: { params: Promise<{ key: string }> }): Promise<Metadata> {
  const job = await getJob((await params).key);
  if (!job) return { title: "Job not found" };
  return {
    title: `${job.title} at ${job.company ?? "unknown company"}`,
    description: job.description?.slice(0, 160),
  };
}

export default async function JobPage({ params }: { params: Promise<{ key: string }> }) {
  const { key } = await params;
  const job = await getJob(key);
  if (!job) notFound();
  const more = job.company ? await moreFromCompany(job.company, job.job_key) : [];
  const links = job.source_links ?? [];
  const apply = safeUrl(links.find((l) => l.application_url)?.application_url) ?? safeUrl(links[0]?.url);
  const closed = job.status === "closed";

  return (
    <div className="wrap">
      <div className="crumbs">
        <Link href="/jobs">Jobs</Link>
        {job.company && (
          <>
            {" / "}
            <Link href={`/jobs?company=${encodeURIComponent(job.company)}`}>{job.company}</Link>
          </>
        )}
      </div>
      <div className="job">
        <div>
          <div className="job-head">
            <Logo name={job.company} />
            <div>
              <h1>{job.title}</h1>
              <div className="meta">
                {job.company && <span style={{ color: "var(--text)", fontWeight: 500 }}>{job.company}</span>}
                {job.location && <span>{job.location}</span>}
                <span>{job.posted_at ? `Posted ${ago(job.posted_at)}` : `Indexed ${ago(job.first_seen)}`}</span>
              </div>
              <div className="tags" style={{ display: "flex", gap: 6, marginTop: 10, flexWrap: "wrap" }}>
                {job.remote && <span className="tag accent">Remote</span>}
                {job.employment_type && <span className="tag">{employmentBucket(job.employment_type)}</span>}
                {closed ? <span className="tag bad">Closed</span> : <span className="tag ok">Open</span>}
              </div>
            </div>
          </div>
          <div className="panel desc">
            {job.description ? (
              paragraphs(job.description).map((p, i) => (p.heading ? <h4 key={i}>{p.text}</h4> : <p key={i}>{p.text}</p>))
            ) : (
              <p style={{ color: "var(--muted)" }}>
                This board does not publish a description in its feed. The full posting is on the employer&apos;s site.
              </p>
            )}
          </div>
        </div>

        <aside className="aside">
          <div className="panel">
            {apply && !closed ? (
              <a className="btn" href={apply} target="_blank" rel="noopener noreferrer nofollow" style={{ width: "100%" }}>
                Apply on {job.company ?? "employer site"} ↗
              </a>
            ) : (
              <span className="btn ghost" style={{ width: "100%", cursor: "default" }}>
                {closed ? "No longer accepting applications" : "No application link"}
              </span>
            )}
            <dl className="facts">
              {job.employment_type && (
                <>
                  <dt>Type</dt>
                  <dd>{job.employment_type}</dd>
                </>
              )}
              {(job.countries?.length ?? 0) > 0 && (
                <>
                  <dt>{job.countries!.length > 1 ? "Countries" : "Country"}</dt>
                  <dd>{job.countries!.map(countryName).join(", ")}</dd>
                </>
              )}
              {job.requisition_id && (
                <>
                  <dt>Req. ID</dt>
                  <dd>{job.requisition_id}</dd>
                </>
              )}
              <dt>Posted</dt>
              <dd>{dateLabel(job.posted_at)}</dd>
              <dt>First indexed</dt>
              <dd>{dateLabel(job.first_seen)}</dd>
              <dt>Last checked</dt>
              <dd>{dateLabel(job.last_checked)}</dd>
              {closed && (
                <>
                  <dt>Closed</dt>
                  <dd>{dateLabel(job.closed_at)}</dd>
                </>
              )}
            </dl>
          </div>

          <div className="panel">
            <h3>Found on</h3>
            <div className="mini">
              {links.map((l, i) => {
                const href = safeUrl(l.url);
                const body = (
                  <>
                    <b>
                      {platformInfo(l.platform ?? "").label} · {l.board_id}
                    </b>
                    <span>
                      {l.state === "gone" ? `Removed from this board ${ago(l.gone_at)}` : `Listed · checked ${ago(l.last_seen)}`}
                    </span>
                  </>
                );
                return href ? (
                  <a key={i} href={href} target="_blank" rel="noopener noreferrer nofollow">
                    {body}
                  </a>
                ) : (
                  <div key={i}>{body}</div>
                );
              })}
            </div>
          </div>

          {more.length > 0 && (
            <div className="panel">
              <h3>More at {job.company}</h3>
              <div className="mini">
                {more.map((m) => (
                  <Link key={m.job_key} href={`/jobs/${m.job_key}`}>
                    <b>{m.title}</b>
                    <span>{m.location}</span>
                  </Link>
                ))}
              </div>
              <Link href={`/jobs?company=${encodeURIComponent(job.company!)}`} style={{ display: "block", marginTop: 12, color: "var(--accent)", fontSize: 14 }}>
                All {job.company} jobs →
              </Link>
            </div>
          )}
        </aside>
      </div>
    </div>
  );
}
