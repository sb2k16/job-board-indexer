import { HL_POST, HL_PRE } from "./query.ts";

export function ago(iso: string | null | undefined, now = Date.now()): string {
  if (!iso) return "";
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return "";
  const s = Math.max(0, Math.round((now - t) / 1000));
  if (s < 60) return "just now";
  const m = Math.round(s / 60);
  if (m < 60) return `${m} min ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h} h ago`;
  const d = Math.round(h / 24);
  if (d < 31) return `${d} d ago`;
  const mo = Math.round(d / 30.4);
  if (mo < 12) return `${mo} mo ago`;
  return `${Math.round(mo / 12)} y ago`;
}

export function dateLabel(iso: string | null | undefined): string {
  if (!iso) return "—";
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return "—";
  return new Date(t).toLocaleString("en-US", {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    timeZone: "UTC",
    timeZoneName: "short",
  });
}

export const num = (n: number) => n.toLocaleString("en-US");

/** Highlighted text as segments; `hit` segments are rendered as <mark>. Never parsed as HTML. */
export function segments(text: string): { text: string; hit: boolean }[] {
  const out: { text: string; hit: boolean }[] = [];
  let rest = text;
  while (rest.length) {
    const a = rest.indexOf(HL_PRE);
    if (a < 0) {
      out.push({ text: rest, hit: false });
      break;
    }
    if (a > 0) out.push({ text: rest.slice(0, a), hit: false });
    const b = rest.indexOf(HL_POST, a + 1);
    const end = b < 0 ? rest.length : b;
    out.push({ text: rest.slice(a + 1, end), hit: true });
    rest = b < 0 ? "" : rest.slice(b + 1);
  }
  return out.map((s) => ({ ...s, text: s.text.replace(/\s+/g, " ") }));
}

/** Plain-text description → paragraphs. Short lines ending without a full stop read as headings. */
export function paragraphs(text: string): { text: string; heading: boolean }[] {
  return text
    .split(/\n\s*\n|\n/)
    .map((p) => p.trim())
    .filter(Boolean)
    .map((p) => ({ text: p, heading: p.length <= 60 && !/[.,;:!?]$/.test(p) && !/^[-•*]/.test(p) }));
}

/** A posting's age as job sites put it: "3 hours ago", "Yesterday", "4 days ago", "Nov 2". */
export function postedAgo(iso: string | null | undefined, now = Date.now()): string {
  if (!iso) return "";
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return "";
  const h = Math.max(0, (now - t) / 3_600_000);
  if (h < 1) return "Just now";
  if (h < 24) return `${Math.floor(h)} ${Math.floor(h) === 1 ? "hour" : "hours"} ago`;
  if (h < 48) return "Yesterday";
  const d = Math.floor(h / 24);
  if (d < 31) return `${d} days ago`;
  return shortDate(iso, new Date(now).getUTCFullYear());
}

/** "Nov 14", or "Nov 14, 2025" in another year. */
export function shortDate(iso: string | null | undefined, thisYear = new Date().getUTCFullYear()): string {
  if (!iso) return "";
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return "";
  const d = new Date(t);
  return d.toLocaleDateString("en-US", { month: "short", day: "numeric", timeZone: "UTC", ...(d.getUTCFullYear() === thisYear ? {} : { year: "numeric" }) });
}

/** Posted (or, without a post date, first found) in the last 24 hours. */
export const isNew = (iso: string | null | undefined, now = Date.now()) => !!iso && now - Date.parse(iso) < 86_400_000;

export type Block = { kind: "h"; text: string } | { kind: "p"; text: string } | { kind: "ul"; items: string[] };

/** Plain-text description → headings, paragraphs and bullet lists ("-", "•", "*" lines). */
export function blocks(text: string): Block[] {
  const out: Block[] = [];
  for (const p of paragraphs(text)) {
    const bullet = /^[-•*·]\s*(.+)$/.exec(p.text);
    if (bullet) {
      const last = out.at(-1);
      if (last?.kind === "ul") last.items.push(bullet[1]);
      else out.push({ kind: "ul", items: [bullet[1]] });
    } else out.push(p.heading ? { kind: "h", text: p.text } : { kind: "p", text: p.text });
  }
  return out;
}
