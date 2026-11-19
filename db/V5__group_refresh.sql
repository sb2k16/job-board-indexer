-- When each refresh group last started a full refresh (docs/aws-deploy-plan-2026-11-17.md, "OpenSearch
-- write rejections"). Several groups now share one pipeline, so the leader partition's single
-- last-poll time cannot hold their cadences; the leader keeps one row per group here instead. Kept in
-- Postgres, not in memory, so a new leader (a deploy, a reclaimed Spot task) does not refresh every
-- group at once. Idempotent: safe to run on every start.
CREATE TABLE IF NOT EXISTS group_refresh (
    group_name   TEXT PRIMARY KEY,
    refreshed_at TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
