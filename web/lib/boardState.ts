// Crawl health per board, read from the crawler's board_state table, for the internal admin page.
// Optional: without DATABASE_URL or PGHOST the page says the database is not configured. On AWS the standard PG* variables
// (PGHOST, PGUSER, PGPASSWORD from Secrets Manager, PGSSLMODE) configure the connection.
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
  retry_at: string | null;
  retry_count: number;
}

export interface GroupRefresh {
  group_name: string;
  refreshed_at: string;
}

let pool: Pool | null = null;

function db(): Pool | null {
  const url = process.env.DATABASE_URL;
  if (!url && !process.env.PGHOST) return null;
  // Without a URL, node-postgres reads PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD and PGSSLMODE.
  pool ??= new Pool({ ...(url ? { connectionString: url } : {}), max: 3, idleTimeoutMillis: 30_000, connectionTimeoutMillis: 3_000 });
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
              last_closed_count, closure_paused_reason, retired_at IS NOT NULL AS retired,
              to_char(retry_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS retry_at, retry_count
         FROM board_state ORDER BY platform, board_id`,
    );
    return r.rows;
  } catch (e) {
    console.error("board_state unavailable:", (e as Error).message);
    return null;
  }
}

/** When each refresh group last started a full refresh (db/V5__group_refresh.sql); null if unavailable. */
export async function groupRefreshes(): Promise<GroupRefresh[] | null> {
  const p = db();
  if (!p) return null;
  try {
    const r = await p.query<GroupRefresh>(
      `SELECT group_name, to_char(refreshed_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS refreshed_at
         FROM group_refresh ORDER BY group_name`,
    );
    return r.rows;
  } catch (e) {
    console.error("group_refresh unavailable:", (e as Error).message);
    return null;
  }
}
