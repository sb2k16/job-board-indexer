// Where a job is listed, in the applicant's words: an employer's own careers site ("Stripe's
// careers site") or the job board it was posted on ("jobs.economist.com"). No platform names.
import { isJobBoard } from "./platforms.ts";

export interface LinkLike {
  url?: string;
  application_url?: string;
  platform?: string;
  board_id?: string;
  state?: string;
  last_seen?: string;
}

/** Only http(s) links from the index become hrefs. */
export function safeUrl(u?: string): string | null {
  if (!u) return null;
  try {
    const url = new URL(u);
    return url.protocol === "https:" || url.protocol === "http:" ? url.toString() : null;
  } catch {
    return null;
  }
}

function host(u?: string): string | null {
  const s = safeUrl(u);
  return s ? new URL(s).hostname.replace(/^www\./, "") : null;
}

const possessive = (name: string) => (/s$/i.test(name) ? `${name}’` : `${name}’s`);

/** The live link to show: still listed, newest first; else the first one. */
export function primaryLink<T extends LinkLike>(links: T[] | undefined): T | undefined {
  const live = (links ?? []).filter((l) => l.state !== "gone");
  live.sort((a, b) => (b.last_seen ?? "").localeCompare(a.last_seen ?? ""));
  return live[0] ?? links?.[0];
}

/** "Stripe’s careers site" or the job board's host. */
export function whereListed(link: LinkLike | undefined, company: string | undefined): string {
  if (link && isJobBoard(link.platform)) return host(link.url) ?? link.board_id ?? "the job board";
  return company ? `${possessive(company)} careers site` : "the employer’s careers site";
}

/** The apply button: "Apply on Stripe’s site" or "Apply on jobs.economist.com". */
export function applyLabel(link: LinkLike | undefined, company: string | undefined): string {
  if (link && isJobBoard(link.platform)) return `Apply on ${host(link.application_url ?? link.url) ?? "the job board"}`;
  return company ? `Apply on ${possessive(company)} site` : "Apply on the employer’s site";
}

export function applyUrl(links: LinkLike[] | undefined): string | null {
  const l = primaryLink(links);
  return safeUrl(l?.application_url) ?? safeUrl(l?.url);
}
