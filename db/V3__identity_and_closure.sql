-- Job identity (docs/identity-merge-closure-2026-11-13.md) and closure bookkeeping (T2.5).
-- Idempotent: safe to run on every start.

-- Every posting (one id on one board) is bound to one job_key for good. A posting keeps its key
-- even when its title or location is edited, and two postings of the same board never share one.
CREATE TABLE IF NOT EXISTS job_posting (
    platform    VARCHAR(100) NOT NULL,
    board_id    VARCHAR(400) NOT NULL,
    posting_id  VARCHAR(1000) NOT NULL,
    job_key     VARCHAR(64)  NOT NULL,
    first_seen  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (platform, board_id, posting_id)
);
CREATE INDEX IF NOT EXISTS idx_job_posting_key ON job_posting (job_key);

-- Merge rules: a posting on another board joins an existing job when one of its aliases is taken.
-- Aliases: company + requisition id, company + title + canonical location. First writer wins.
CREATE TABLE IF NOT EXISTS job_alias (
    alias       VARCHAR(64) PRIMARY KEY,
    job_key     VARCHAR(64) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Closure: consecutive confirmed "board gone" crawls (retirement), the last closure run, and the
-- mass-closure guard. closure_override_until lets an operator release a paused board:
--   UPDATE board_state SET closure_override_until = now() + interval '1 day' WHERE ...;
ALTER TABLE board_state ADD COLUMN IF NOT EXISTS gone_streak            INTEGER NOT NULL DEFAULT 0;
ALTER TABLE board_state ADD COLUMN IF NOT EXISTS last_closure_at        TIMESTAMPTZ;
ALTER TABLE board_state ADD COLUMN IF NOT EXISTS last_closed_count      INTEGER;
ALTER TABLE board_state ADD COLUMN IF NOT EXISTS closure_paused_at      TIMESTAMPTZ;
ALTER TABLE board_state ADD COLUMN IF NOT EXISTS closure_paused_reason  VARCHAR(500);
ALTER TABLE board_state ADD COLUMN IF NOT EXISTS closure_override_until TIMESTAMPTZ;
ALTER TABLE board_state ADD COLUMN IF NOT EXISTS retired_at             TIMESTAMPTZ;
