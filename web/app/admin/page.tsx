import Link from "next/link";
import type { Metadata } from "next";
import { AdminBar } from "../components/AdminBar";
import { requireAdmin } from "@/lib/admin";
import { boardStates, groupRefreshes } from "@/lib/boardState";
import { overview } from "@/lib/opensearch";
import { platformInfo } from "@/lib/platforms";
import { nextRetry, outcome, select, summarize, why, type Tab } from "@/lib/health";
import { ago, dateLabel, num } from "@/lib/format";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: { absolute: "Crawl health · Admin" } };

const TABS: { id: Tab; label: string }[] = [
  { id: "attention", label: "Needs attention" },
  { id: "all", label: "All boards" },
  { id: "retired", label: "Retired" },
];

export default async function CrawlHealth({ searchParams }: { searchParams: Promise<{ tab?: string; q?: string }> }) {
  await requireAdmin();
  const sp = await searchParams;
  const tab: Tab = TABS.some((t) => t.id === sp.tab) ? (sp.tab as Tab) : "attention";
  const query = (sp.q ?? "").slice(0, 100);

  const [states, groups, ov] = await Promise.all([boardStates(), groupRefreshes(), overview().catch(() => null)]);
  if (states === null) {
    return (
      <>
        <AdminBar signedIn />
        <main className="wrap">
          <div className="notice">The crawler database is not reachable from this site, so crawl health can’t be shown.</div>
        </main>
      </>
    );
  }
  const sum = summarize(states);
  const jobs = new Map((ov?.boards ?? []).map((b) => [`${b.platform}/${b.board}`, b]));
  const rows = select(states, tab, query);
  const counts: Record<Tab, number> = { attention: sum.attention, all: sum.boards, retired: sum.retired };
  const tabHref = (t: Tab) => `/admin?tab=${t}${query ? `&q=${encodeURIComponent(query)}` : ""}`;

  return (
    <>
      <AdminBar signedIn />
      <main>
        <div className="ph">
          <div className="wrap">
            <div>
              <h1>Crawl health</h1>
              <p>
                Every board the crawler reads, how its last crawl went, and why it may be behind. A board’s jobs count as checked
                only when the whole board was read.
              </p>
            </div>
          </div>
        </div>

        <div className="strip">
          <div className="wrap">
            <div>
              <div className="n">{num(sum.boards)}</div>
              <div className="l">boards on {num(sum.platforms)} platforms</div>
            </div>
            <div>
              <div className="n">{num(sum.complete)}</div>
              <div className="l">
                <i className="dot-ok" aria-hidden />
                complete
              </div>
            </div>
            <div>
              <div className="n">{num(sum.partial)}</div>
              <div className="l">
                <i className="dot-warn" aria-hidden />
                partial: capped by the platform
              </div>
            </div>
            <div>
              <div className="n">{num(sum.failed)}</div>
              <div className="l">
                <i className="dot-bad" aria-hidden />
                failed or gone · {num(sum.retrying)} retrying
              </div>
            </div>
            <div>
              <div className="n">{num(sum.held)}</div>
              <div className="l">
                <i className="dot-none" aria-hidden />
                boards with closures held
              </div>
            </div>
          </div>
        </div>

        {groups && groups.length > 0 && (
          <section className="wrap groups" aria-labelledby="groups">
            <h2 className="gh" id="groups">
              Refresh groups
            </h2>
            <div className="ggrid">
              {groups.map((g) => (
                <div key={g.group_name} className="gcard">
                  <div className="top">{g.group_name.replace(/^g(\d+)-/, "g$1 · ")}</div>
                  <div className="meta" title={dateLabel(g.refreshed_at)}>
                    last full refresh {ago(g.refreshed_at)}
                  </div>
                </div>
              ))}
            </div>
          </section>
        )}

        <section className="wrap tablewrap" aria-label="Boards">
          <div className="tools">
            <nav className="tabs" aria-label="Which boards">
              {TABS.map((t) => (
                <Link key={t.id} href={tabHref(t.id)} aria-current={tab === t.id}>
                  {t.label}
                  <i>{num(counts[t.id])}</i>
                </Link>
              ))}
            </nav>
            <form action="/admin" role="search">
              <input type="hidden" name="tab" value={tab} />
              <input name="q" defaultValue={query} placeholder="Filter boards or platforms" aria-label="Filter boards or platforms" />
            </form>
          </div>
          {rows.length === 0 ? (
            <div className="notice">{tab === "attention" && !query ? "Every board’s last crawl was complete." : "No boards match."}</div>
          ) : (
            <div className="scroll">
              <table>
                <thead>
                  <tr>
                    <th>Board</th>
                    <th>Platform</th>
                    <th className="num">Open jobs</th>
                    <th>Last crawl</th>
                    <th>Outcome</th>
                    <th className="num">Snapshots</th>
                    <th className="num">Next retry</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((s) => {
                    const o = outcome(s);
                    const j = jobs.get(`${s.platform}/${s.board_id}`);
                    const info = platformInfo(s.platform);
                    const employer = info.kind === "Job board" ? "Many employers" : j?.companies[0]?.key;
                    const reason = why(s);
                    return (
                      <tr key={`${s.platform}/${s.board_id}`}>
                        <td>
                          <Link className="b" href={`/jobs?board=${encodeURIComponent(s.board_id)}`}>
                            {s.board_id}
                          </Link>
                          {employer && <div className="sub">{employer}</div>}
                        </td>
                        <td>{info.label}</td>
                        <td className="num">{j ? num(j.count) : "—"}</td>
                        <td title={dateLabel(s.last_attempt)}>{s.last_attempt ? ago(s.last_attempt) : "—"}</td>
                        <td>
                          <span className="oc">
                            <i className={`dot-${o.tone}`} aria-hidden />
                            {o.label}
                            {s.retry_count > 0 && ` · retry ${s.retry_count}`}
                          </span>
                          {reason && <div className="why">{reason}</div>}
                        </td>
                        <td className="num">{num(s.snapshots)}</td>
                        <td className="num">{nextRetry(s.retry_at) || "—"}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </section>
      </main>
    </>
  );
}
