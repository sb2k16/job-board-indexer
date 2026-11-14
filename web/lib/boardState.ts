// Crawl health per board, read from the crawler's board_state table. Optional: without
// DATABASE_URL the boards page shows job counts only.
import "server-only";

import { Pool } from "pg";

export interface BoardState {
  platform: string;
  board_id: string;
  status: string;
  last_outcome: string | null;
  last_attempt: string | null;
  last_confirmed: string | null;
  last_error: string | null;
  consecutive_failures: number;
  snapshots: number;
  last_job_count: number | null;
  last_closed_count: number | null;
  closure_paused_reason: string | null;
  retired: boolean;
}

let pool: Pool | null = null;

function db(): Pool | null {
  const url = process.env.DATABASE_URL;
  if (!url) return null;
  pool ??= new Pool({ connectionString: url, max: 3, idleTimeoutMillis: 30_000, connectionTimeoutMillis: 3_000 });
  return pool;
}

/** null when no database is configured or it is unreachable (the page then says so). */
export async function boardStates(): Promise<BoardState[] | null> {
  const p = db();
  if (!p) return null;
  try {
    const r = await p.query<BoardState>(
      `SELECT platform, board_id, status, last_outcome,
              to_char(last_attempt AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS last_attempt,
              to_char(last_confirmed AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS last_confirmed,
              last_error, consecutive_failures, cardinality(snapshot_history) AS snapshots, last_job_count,
              last_closed_count, closure_paused_reason, retired_at IS NOT NULL AS retired
         FROM board_state ORDER BY platform, board_id`,
    );
    return r.rows;
  } catch (e) {
    console.error("board_state unavailable:", (e as Error).message);
    return null;
  }
}
