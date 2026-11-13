#!/usr/bin/env bash
# Builds the plugin jar and the crawler image.
# Usage: [PLATFORM=linux/arm64] [SKIP_GRADLE=1] docker/build.sh [tag]
# PLATFORM defaults to linux/amd64 (EKS Fargate); use linux/arm64 for local runs on Apple silicon.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TAG="${1:-$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo local)}"
PLATFORM="${PLATFORM:-linux/amd64}"
DP_VERSION="$(sed -n 's/^dataPrepperVersion=//p' "$ROOT/gradle.properties")"
if [[ "${SKIP_GRADLE:-0}" != "1" ]]; then
  (cd "$ROOT" && ./gradlew :crawler:build)
fi
CTX="$(mktemp -d)"
trap 'rm -rf "$CTX"' EXIT
cp "$ROOT"/crawler/build/libs/job-board-crawler-*.jar "$CTX/"
cp "$ROOT/config/jobs-template.json" "$CTX/"
cp -R "$ROOT/config/specs" "$CTX/specs"
docker build --platform "$PLATFORM" --build-arg "DATA_PREPPER_VERSION=$DP_VERSION" \
  -f "$ROOT/docker/Dockerfile" -t "jbi-crawler:$TAG" "$CTX"
echo "built jbi-crawler:$TAG (Data Prepper $DP_VERSION)"
