#!/usr/bin/env bash
# Pushes jbi-crawler, jbi-web and jbi-tools:<tag> (from docker/build-aws.sh) to the stack's ECR
# repositories. Usage: infra/scripts/push-images.sh <tag>
set -euo pipefail
TAG="${1:?image tag (the git commit)}"
PROFILE="${AWS_PROFILE:-jbi}"
REGISTRY="803817393850.dkr.ecr.us-east-1.amazonaws.com"
aws --profile "$PROFILE" --region us-east-1 ecr get-login-password | docker login --username AWS --password-stdin "$REGISTRY"
for image in jbi-crawler jbi-web jbi-tools; do
  docker tag "$image:$TAG" "$REGISTRY/$image:$TAG"
  docker push "$REGISTRY/$image:$TAG"
done
