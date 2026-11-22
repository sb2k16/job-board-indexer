import type { Metadata } from "next";
import { REPO_URL } from "@/lib/site";

export const metadata: Metadata = { title: "How it works" };

// Plain words, no mechanism (docs/ui-redesign-2026-11-18-review.md). Each claim matches what the
// crawler does: sources are employer careers sites and the job boards employers post on; every
// source is read at least twice a day; a job is taken down once it is missing from its source on
// several reads in a row.
const STEPS = [
  {
    title: "Straight from where it was posted",
    body: "Every job comes from the company’s own careers site, or from the job board the company posted it on. We don’t copy listings from other job aggregators.",
  },
  {
    title: "Checked again and again",
    body: "We read each careers site and job board at least twice a day, and many every couple of hours, so new jobs show up soon after they’re posted.",
  },
  {
    title: "Gone when it’s gone",
    body: "When a job disappears from the place it was posted and stays gone on our next checks, we take it down too. If you see it here, it’s still listed there.",
  },
  {
    title: "You apply with the employer",
    body: "“Apply” takes you to the page where the job was posted. We never ask for your CV and never stand between you and the employer.",
  },
];

export default function HowItWorks() {
  return (
    <div className="wrap how">
      <div>
        <div className="kicker">How it works</div>
        <h1>Only real, open jobs.</h1>
        <p className="lede">Many job sites are full of reposts and listings that closed weeks ago. Here is how we keep this one clean.</p>
      </div>
      <ol className="steps" style={{ listStyle: "none" }}>
        {STEPS.map((s, i) => (
          <li key={s.title} className="step">
            <div className="no">{i + 1}</div>
            <div>
              <h3>{s.title}</h3>
              <p>{s.body}</p>
            </div>
          </li>
        ))}
      </ol>
      <div className="faq">
        <div>
          <b>Which jobs do you list?</b>
          <p>Jobs that companies publish openly on their careers sites and on public job boards. Nothing that sits behind a login.</p>
        </div>
        <div>
          <b>I can’t find a company I know is hiring.</b>
          <p>
            We add companies all the time. Tell us which one by <a href={`${REPO_URL}/issues`}>opening an issue</a> and we’ll look at it.
          </p>
        </div>
        <div>
          <b>I’m an employer. How do I get listed?</b>
          <p>If your careers site is public, you may already be. Search for your company name.</p>
        </div>
        <div>
          <b>Why is a job I saw yesterday gone?</b>
          <p>The employer took it down. We only show jobs that are still listed where they were posted.</p>
        </div>
      </div>
    </div>
  );
}
