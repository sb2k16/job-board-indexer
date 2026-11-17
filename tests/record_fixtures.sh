#!/bin/sh
# Records a replay fixture per platform from a live tenant (one list page, up to three job pages):
#   tests/record_fixtures.sh workday greenhouse:stripe     (no arguments: every shipped spec)
# Writes crawler/src/test/resources/fixtures/recorded/<platform>.json.gz; RecordedSpecsTest replays them.
set -e
cd "$(dirname "$0")/.."
if [ $# -eq 0 ]; then
  set -- $(ls config/specs/*.yaml | xargs -n1 basename | sed 's/\.yaml$//')
fi
targets=$(echo "$@" | tr ' ' ',')
rm -rf crawler/build/record
./gradlew -q :crawler:test --tests '*RecordFixtures' -Djbi.record="$targets" --rerun >/dev/null 2>&1 || true
cat crawler/build/record/*.txt
