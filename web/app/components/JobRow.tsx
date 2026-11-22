import Link from "next/link";
import type { Job } from "@/lib/opensearch";
import { employmentBucket } from "@/lib/employment";
import { isNew, postedAgo, segments } from "@/lib/format";

export function Highlighted({ text }: { text: string }) {
  return (
    <>
      {segments(text).map((s, i) => (s.hit ? <mark key={i}>{s.text}</mark> : <span key={i}>{s.text}</span>))}
    </>
  );
}

/** The company's initial, as a placeholder for a logo (no logos are fetched). */
export function Mark({ name }: { name?: string }) {
  const letter = (name ?? "").replace(/[^\p{L}\p{N}]/gu, "").charAt(0).toUpperCase() || "·";
  return (
    <div className="mk" aria-hidden>
      {letter}
    </div>
  );
}

export function JobRow({ job, titleHl, snippet }: { job: Job; titleHl?: string; snippet?: string }) {
  const posted = job.posted_at ?? job.first_seen;
  const type = job.employment_type ? employmentBucket(job.employment_type) : null;
  return (
    <article className="row">
      <Mark name={job.company} />
      <div>
        <h3>
          <Link href={`/jobs/${job.job_key}`}>{titleHl ? <Highlighted text={titleHl} /> : job.title}</Link>
        </h3>
        <div className="who">
          {job.company && <b>{job.company}</b>}
          {job.company && job.location && <span className="dot">/</span>}
          {job.location && <span>{job.location}</span>}
        </div>
        {snippet && (
          <p className="snip">
            <Highlighted text={snippet} />
          </p>
        )}
        {(job.remote || (type && type !== "Other") || job.status === "closed") && (
          <div className="facts">
            {job.remote && <span className="t g">Remote</span>}
            {type && type !== "Other" && <span className="t">{type}</span>}
            {job.status === "closed" && <span className="t">No longer listed</span>}
          </div>
        )}
      </div>
      <div className="side">
        {isNew(posted) && <span className="new">New</span>}
        <span>{postedAgo(posted)}</span>
      </div>
    </article>
  );
}
