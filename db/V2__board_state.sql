-- Application-owned per-board state (design: board_state). Data Prepper never touches it.
-- snapshot_history: crawl-start times of the last N complete, sink-confirmed snapshots, newest first.
-- Only those count for closure (T2.5); failed and partial crawls update last_attempt and the failure
-- streak but never the history. T2.5 adds the closure, retirement and mass-closure columns.
CREATE TABLE IF NOT EXISTS board_state (
    platform             VARCHAR(100)  NOT NULL,
    board_id             VARCHAR(400)  NOT NULL,
    status               VARCHAR(20)   NOT NULL DEFAULT 'active',
    last_attempt         TIMESTAMPTZ,
    last_crawl_started   TIMESTAMPTZ,
    last_outcome         VARCHAR(20),
    last_error           VARCHAR(1000),
    last_confirmed       TIMESTAMPTZ,
    snapshot_history     TIMESTAMPTZ[]  NOT NULL DEFAULT '{}',
    consecutive_failures INTEGER       NOT NULL DEFAULT 0,
    first_failure_at     TIMESTAMPTZ,
    last_job_count       INTEGER,
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (platform, board_id)
);

CREATE INDEX IF NOT EXISTS idx_board_state_failures ON board_state (consecutive_failures) WHERE consecutive_failures > 0;
