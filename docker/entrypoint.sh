#!/bin/sh
# Crawler entrypoint. Locally (no JBI_DEPLOYMENT) it changes nothing: docker compose mounts the
# pipelines and config. On ECS (infra/lib/app-stack.ts) it installs this Deployment's rendered
# pipelines and the AWS server config, fills in the @@NAME@@ values from the environment ECS
# injects, then runs Data Prepper. Replacement is literal (no regex, no shell expansion), so any
# character in the generated database password is safe. (The values land inside YAML double quotes;
# the CDK-generated RDS password excludes " ' \ and other YAML/shell metacharacters.)
set -eu
if [ -n "${JBI_DEPLOYMENT:-}" ]; then
  src="/etc/jbi/aws/pipelines/${JBI_DEPLOYMENT}.yaml"
  [ -f "$src" ] || { echo "entrypoint: no rendered pipelines for deployment '${JBI_DEPLOYMENT}'" >&2; exit 64; }
  for v in JBI_DB_URL JBI_DB_USER JBI_DB_PASSWORD JBI_OPENSEARCH_URL; do
    eval "val=\${$v:-}"
    [ -n "$val" ] || { echo "entrypoint: $v is not set" >&2; exit 64; }
  done
  fill() {
    awk '
      function rep(s, tok, val,   out, i) {
        out = ""
        while ((i = index(s, tok)) > 0) { out = out substr(s, 1, i - 1) val; s = substr(s, i + length(tok)) }
        return out s
      }
      {
        line = $0
        line = rep(line, "@@JBI_DB_URL@@", ENVIRON["JBI_DB_URL"])
        line = rep(line, "@@JBI_DB_USER@@", ENVIRON["JBI_DB_USER"])
        line = rep(line, "@@JBI_DB_PASSWORD@@", ENVIRON["JBI_DB_PASSWORD"])
        line = rep(line, "@@JBI_OPENSEARCH_URL@@", ENVIRON["JBI_OPENSEARCH_URL"])
        print line
      }' "$1" > "$2"
    if grep -q '@@JBI_' "$2"; then echo "entrypoint: unfilled value in $2" >&2; exit 64; fi
  }
  rm -f /usr/share/data-prepper/pipelines/*.yaml
  fill "$src" /usr/share/data-prepper/pipelines/pipelines.yaml
  fill /etc/jbi/aws/data-prepper-config.yaml /usr/share/data-prepper/config/data-prepper-config.yaml
  echo "entrypoint: deployment ${JBI_DEPLOYMENT}: $(grep -c '^g[0-9]*-' /usr/share/data-prepper/pipelines/pipelines.yaml) pipeline(s)"
fi
exec "$@"
