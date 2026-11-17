#!/usr/bin/env bash
# Builds the three images the AWS stack runs, for linux/amd64 (Fargate Spot is x86 only), tagged
# with the git commit: jbi-crawler, jbi-web, jbi-tools. Push them with infra/scripts/push-images.sh.
# Usage: docker/build-aws.sh [tag]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TAG="${1:-$(git -C "$ROOT" rev-parse --short HEAD)}"
if [[ -n "$(git -C "$ROOT" status --porcelain)" && "${ALLOW_DIRTY:-0}" != "1" ]]; then
  echo "working tree has uncommitted changes; the image tag would not describe the code (ALLOW_DIRTY=1 to override)" >&2
  exit 1
fi
export PLATFORM=linux/amd64
"$ROOT/docker/build.sh" "$TAG"
docker build --platform linux/amd64 -t "jbi-web:$TAG" "$ROOT/web"
CTX="$(mktemp -d)"
trap 'rm -rf "$CTX"' EXIT
cp -R "$ROOT/db" "$CTX/db"
cp "$ROOT/docker/migrate.sh" "$CTX/"
docker build --platform linux/amd64 -f "$ROOT/docker/tools.Dockerfile" -t "jbi-tools:$TAG" "$CTX"
echo "built jbi-crawler:$TAG jbi-web:$TAG jbi-tools:$TAG (linux/amd64)"
