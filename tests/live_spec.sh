#!/bin/sh
# Runs platform specs against live tenants with the production fetcher and prints the summaries.
#   tests/live_spec.sh workable:tiger-analytics breezy:vetsez
set -e
cd "$(dirname "$0")/.."
targets=$(echo "$@" | tr ' ' ',')
rm -rf crawler/build/live
./gradlew -q :crawler:test --tests '*LiveSpecRun' -Djbi.live="$targets" --rerun >/dev/null 2>&1 || true
cat crawler/build/live/*.txt
