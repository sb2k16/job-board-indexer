// Crawl health for the internal admin page: pure functions over board_state rows, so they are
// unit-tested directly. Wording is for the people running the crawler, not for job seekers.
import type { BoardState } from "./boardState";

export type Tone = "ok" | "warn" | "bad" | "none";

export const OUTCOMES: Record<string, { label: string; tone: Tone }> = {
  COMPLETE: { label: "Complete", tone: "ok" },
  PARTIAL: { label: "Partial", tone: "warn" },
  FAILED: { label: "Failed", tone: "bad" },
  GONE: { label: "Board gone", tone: "bad" },
  BLOCKED: { label: "Blocked by robots.txt", tone: "warn" },
};

export function outcome(s: BoardState): { label: string; tone: Tone } {
  return (s.last_outcome && OUTCOMES[s.last_outcome]) || { label: "Not crawled yet", tone: "none" };
}

/** Anything short of a clean, complete last crawl. */
export function needsAttention(s: BoardState): boolean {
  return s.last_outcome !== "COMPLETE" || s.consecutive_failures > 0 || !!s.closure_paused_reason || !!s.retry_at || s.retired;
}

/** A crawler error for a person: request URLs shortened to their host, ending as a sentence. */
export function readable(error: string): string {
  const text = error
    .replace(/https?:\/\/([^/\s]+)\S*/g, (_m, host: string) => host)
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 200);
  return /[.!?]$/.test(text) ? text : `${text}.`;
}

/** Why the board is behind, in one or two sentences; null when nothing is wrong. */
export function why(s: BoardState): string | null {
  const parts: string[] = [];
  if (s.retired) parts.push("Retired: the board was gone on several crawls in a row.");
  if (s.closure_paused_reason) parts.push(readable(`Closures held for review: ${s.closure_paused_reason}`));
  if (s.last_outcome !== "COMPLETE" && s.last_error) parts.push(readable(s.last_error));
  if (s.consecutive_failures > 1) parts.push(`${s.consecutive_failures} crawls in a row without a complete read.`);
  return parts.length ? parts.join(" ") : null;
}

export interface Summary {
  boards: number;
  platforms: number;
  complete: number;
  partial: number;
  failed: number;
  retrying: number;
  held: number;
  attention: number;
  retired: number;
}

export function summarize(states: BoardState[]): Summary {
  const by = (o: string) => states.filter((s) => s.last_outcome === o).length;
  return {
    boards: states.length,
    platforms: new Set(states.map((s) => s.platform)).size,
    complete: by("COMPLETE"),
    partial: by("PARTIAL"),
    failed: by("FAILED") + by("GONE"),
    retrying: states.filter((s) => s.retry_at).length,
    held: states.filter((s) => s.closure_paused_reason).length,
    attention: states.filter(needsAttention).length,
    retired: states.filter((s) => s.retired).length,
  };
}

export type Tab = "attention" | "all" | "retired";

export function select(states: BoardState[], tab: Tab, query: string): BoardState[] {
  const q = query.trim().toLowerCase();
  return states
    .filter((s) => (tab === "attention" ? needsAttention(s) : tab === "retired" ? s.retired : true))
    .filter((s) => !q || `${s.platform} ${s.board_id}`.toLowerCase().includes(q))
    .sort((a, b) => b.consecutive_failures - a.consecutive_failures || (b.last_attempt ?? "").localeCompare(a.last_attempt ?? ""));
}

/** "in 18 min", "due now", or "" with no retry pending. */
export function nextRetry(retryAt: string | null, now = Date.now()): string {
  if (!retryAt) return "";
  const m = Math.round((Date.parse(retryAt) - now) / 60_000);
  if (m <= 0) return "due now";
  return m < 60 ? `in ${m} min` : `in ${Math.floor(m / 60)} h ${m % 60} min`;
}
