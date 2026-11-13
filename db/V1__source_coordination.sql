-- Coordination table for Data Prepper's jdbc store (schema copied from the 2.17.0 store's create-table.sql
-- and create-index.sql). Created once before the crawlers start; the crawlers run with
-- skip_table_creation: true, which avoids the start-up race in the released store.
CREATE TABLE IF NOT EXISTS source_coordination (
    source_identifier VARCHAR(256) NOT NULL,
    source_partition_key VARCHAR(512) NOT NULL,
    partition_owner VARCHAR(256),
    partition_progress_state TEXT,
    source_partition_status VARCHAR(20),
    partition_ownership_timeout TIMESTAMP,
    reopen_at TIMESTAMP,
    closed_count BIGINT DEFAULT 0,
    partition_priority VARCHAR(64),
    version BIGINT NOT NULL DEFAULT 0,
    expiration_time TIMESTAMP,
    PRIMARY KEY (source_identifier, source_partition_key)
);
CREATE INDEX IF NOT EXISTS idx_source_status_priority
    ON source_coordination (source_identifier, source_partition_status, partition_priority);
