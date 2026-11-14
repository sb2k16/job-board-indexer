import Link from "next/link";
import type { Job } from "@/lib/opensearch";
import { employmentBucket } from "@/lib/employment";
import { ago, segments } from "@/lib/format";
import { platformInfo } from "@/lib/platforms";

export function Highlighted({ text }: { text: string }) {
  return (
    <>
      {segments(text).map((s, i) => (s.hit ? <mark key={i}>{s.text}</mark> : <span key={i}>{s.text}</span>))}
    </>
  );
}

export function Logo({ name, className = "logo" }: { name?: string; className?: string }) {
  const letter = (name ?? "?").replace(/[^\p{L}\p{N}]/gu, "").charAt(0) || "?";
  return (
    <div className={className} aria-hidden>
      {letter}
    </div>
  );
}

export function JobCard({ job, titleHl, snippet }: { job: Job; titleHl?: string; snippet?: string }) {
  const link = job.source_links?.[0];
  const posted = job.posted_at ?? job.first_seen;
  // "New" follows the date shown: posted in the last 3 days (or first indexed, when no post date).
  const isNew = !!posted && Date.now() - Date.parse(posted) < 3 * 86_400_000;
  return (
    <article className="card">
      <Logo name={job.company} />
      <div style={{ minWidth: 0 }}>
        <h3>
          <Link href={`/jobs/${job.job_key}`}>{titleHl ? <Highlighted text={titleHl} /> : job.title}</Link>
        </h3>
        <div className="meta">
          {job.company && (
            <Link className="co" href={`/jobs?company=${encodeURIComponent(job.company)}`}>
              {job.company}
            </Link>
          )}
          {job.location && <span>{job.location}</span>}
        </div>
        {snippet && (
          <p className="snippet">
            <Highlighted text={snippet} />
          </p>
        )}
        <div className="tags">
          {job.remote && <span className="tag accent">Remote</span>}
          {job.employment_type && <span className="tag">{employmentBucket(job.employment_type)}</span>}
          {link?.platform && <span className="tag">via {platformInfo(link.platform).label}</span>}
          {job.status === "closed" && <span className="tag bad">Closed</span>}
        </div>
      </div>
      <div className="side">
        <span title={job.posted_at ? "Posted" : "First seen by the crawler"}>{ago(posted)}</span>
        {isNew && <span className="tag ok">New</span>}
      </div>
    </article>
  );
}
