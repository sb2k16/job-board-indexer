#!/usr/bin/env bash
# Deploys JbiApp with a new image tag without overlapping two generations of crawler tasks on the
# database. db.t4g.micro has about 73 usable connections and the running crawlers hold most of them;
# ECS starts a replacement before the old task has exited, so a rolling deploy runs out (Nov 17).
# Sequence: scale both crawler services to 0, wait until their tasks have stopped (connections
# closed), deploy, then restore the task counts from cdk.json. The web service deploys as usual.
# Usage: infra/scripts/deploy-app.sh <tag>
set -euo pipefail
TAG="${1:?image tag}"
PROFILE="${AWS_PROFILE:-jbi}"
cd "$(dirname "$0")/.."
a() { aws --profile "$PROFILE" --region us-east-1 "$@"; }
ctx() { node -e "console.log(require('./cdk.json').context.$1)"; }
CRAWLER=$(ctx crawlerTasks)
HEAVY=$(ctx crawlerHeavyTasks)

echo "scaling crawlers to 0"
a ecs update-service --cluster jbi --service jbi-crawler --desired-count 0 >/dev/null
a ecs update-service --cluster jbi --service jbi-crawler-heavy --desired-count 0 >/dev/null
a ecs wait services-stable --cluster jbi --services jbi-crawler jbi-crawler-heavy
echo "crawler tasks stopped"

npx cdk deploy JbiApp --profile "$PROFILE" --require-approval never -c imageTag="$TAG"

# CloudFormation leaves DesiredCount alone when the template's value has not changed, so restore it.
a ecs update-service --cluster jbi --service jbi-crawler --desired-count "$CRAWLER" >/dev/null
a ecs update-service --cluster jbi --service jbi-crawler-heavy --desired-count "$HEAVY" >/dev/null
a ecs wait services-stable --cluster jbi --services jbi-crawler jbi-crawler-heavy
a ecs describe-services --cluster jbi --services jbi-crawler jbi-crawler-heavy jbi-web \
  --query 'services[].[serviceName,taskDefinition,runningCount,desiredCount]' --output text
