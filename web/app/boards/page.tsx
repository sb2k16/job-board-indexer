import Link from "next/link";
import type { Metadata } from "next";
import { overview, SearchUnavailable } from "@/lib/opensearch";
import { boardStates } from "@/lib/boardState";
import { platformInfo } from "@/lib/platforms";
import { ago, dateLabel, num } from "@/lib/format";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Boards" };

const OUTCOME_CLASS: Record<string, string> = { COMPLETE: "ok", PARTIAL: "warn", FAILED: "bad", GONE: "bad", BLOCKED: "warn" };

export default async function Boards() {
  let ov;
  try {
    ov = await overview();
  } catch (e) {
    if (!(e instanceof SearchUnavailable)) throw e;
    return (
      <div className="wrap">
        <div className="notice">The job index is unavailable right now. {e.message}</div>
      </div>
    );
  }
  const states = await boardStates();
  const stateOf = new Map((states ?? []).map((s) => [`${s.platform}/${s.board_id}`, s]));
  const counts = new Map(ov.boards.map((b) => [`${b.platform}/${b.board}`, b]));

  // Every board the crawler knows (board_state), plus any with jobs but no state row.
  const keys = new Set([...stateOf.keys(), ...counts.keys()]);
  const rows = [...keys]
    .map((k) => {
      const [platform, ...rest] = k.split("/");
      const board = rest.join("/");
      return { key: k, platform, board, jobs: counts.get(k)?.count ?? 0, employer: counts.get(k)?.companies, state: stateOf.get(k) };
    })
    .sort((a, b) => {
      const ka = platformInfo(a.platform).kind, kb = platformInfo(b.platform).kind;
      return ka === kb ? b.jobs - a.jobs : ka === "ATS" ? -1 : 1;
    });

  return (
    <div className="wrap" style={{ paddingBottom: 60 }}>
      <div className="page-head">
        <h1>Boards</h1>
        <p>
          Each board is one employer&apos;s careers page on an applicant tracking system, or one public job board. The crawler
          re-reads every board on a schedule; a board&apos;s jobs are only counted as checked when the whole board was read.
        </p>
      </div>
      {states === null && (
        <p style={{ color: "var(--muted)", fontSize: 14 }}>Crawl health is not shown: the crawler database is not configured for this site.</p>
      )}
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Board</th>
              <th>Platform</th>
              <th>Employer</th>
              <th className="num">Open jobs</th>
              {states !== null && (
                <>
                  <th>Last crawl</th>
                  <th>Outcome</th>
                  <th className="num">Snapshots</th>
                </>
              )}
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => {
              const info = platformInfo(r.platform);
              const multi = (r.employer?.length ?? 0) > 1;
              return (
                <tr key={r.key}>
                  <td>
                    <Link href={`/jobs?board=${encodeURIComponent(r.board)}`} style={{ fontWeight: 500 }}>
                      {r.board}
                    </Link>
                  </td>
                  <td>
                    {info.label} <span className="tag">{info.kind}</span>
                  </td>
                  <td>{multi ? <span style={{ color: "var(--muted)" }}>Many employers</span> : r.employer?.[0]?.key ?? "—"}</td>
                  <td className="num">{num(r.jobs)}</td>
                  {states !== null && (
                    <>
                      <td title={dateLabel(r.state?.last_attempt)}>{r.state?.last_attempt ? ago(r.state.last_attempt) : "—"}</td>
                      <td>
                        {r.state?.last_outcome ? (
                          <span className={`tag ${OUTCOME_CLASS[r.state.last_outcome] ?? ""}`}>{r.state.last_outcome.toLowerCase()}</span>
                        ) : (
                          "—"
                        )}
                        {r.state?.retired && <span className="tag bad" style={{ marginLeft: 6 }}>retired</span>}
                        {r.state?.closure_paused_reason && (
                          <span className="err">Closures held for review: {r.state.closure_paused_reason}</span>
                        )}
                        {r.state && r.state.consecutive_failures > 0 && (
                          <span className="err">
                            {r.state.consecutive_failures} failed in a row{r.state.last_error ? `: ${r.state.last_error.slice(0, 140)}` : ""}
                          </span>
                        )}
                      </td>
                      <td className="num">{r.state ? num(r.state.snapshots) : "—"}</td>
                    </>
                  )}
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}
