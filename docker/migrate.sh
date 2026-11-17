#!/bin/sh
# Applies db/V*.sql in order (each file is idempotent: CREATE ... IF NOT EXISTS, ADD COLUMN IF NOT
# EXISTS). Connection from the standard PG* variables; on ECS the jbi-tools task sets PGHOST,
# PGPORT, PGDATABASE, PGSSLMODE=require and PGUSER/PGPASSWORD from Secrets Manager.
set -eu
export PGOPTIONS="${PGOPTIONS:-} -c client_min_messages=warning"   # no "already exists, skipping" notices
for f in /db/V*.sql; do
  echo "migrate: $(basename "$f")"
  psql -v ON_ERROR_STOP=1 -q -f "$f"
done
psql -At -c "SELECT 'migrate: tables: ' || string_agg(tablename, ', ' ORDER BY tablename) FROM pg_tables WHERE schemaname = 'public'"
