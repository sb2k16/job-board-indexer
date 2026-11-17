-- Sooner retry of failed crawls (docs/sooner-retry-2026-11-17.md). Idempotent: safe to run on every start.
-- A crawl that fails for a reason that may pass (429, 5xx, timeout, network, circuit open, robots.txt
-- unavailable) gets retry_at = failure + 15, 30, then 60 minutes, unless that is after the group's
-- next refresh. The pipeline's leader claims due rows (setting retry_at back to NULL) and queues a
-- crawl of each. Any other outcome clears both columns.
ALTER TABLE board_state ADD COLUMN IF NOT EXISTS retry_at    TIMESTAMPTZ;
ALTER TABLE board_state ADD COLUMN IF NOT EXISTS retry_count INTEGER NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_board_state_retry ON board_state (platform, retry_at) WHERE retry_at IS NOT NULL;
