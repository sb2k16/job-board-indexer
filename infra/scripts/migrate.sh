#!/usr/bin/env bash
# Runs the one-off jbi-tools task (applies db/V*.sql) and waits for it; prints its log and exit code.
# Usage: infra/scripts/migrate.sh        (after `cdk deploy JbiApp`)
set -euo pipefail
PROFILE="${AWS_PROFILE:-jbi}"
aws_() { aws --profile "$PROFILE" --region us-east-1 "$@"; }
out() { aws_ cloudformation describe-stacks --stack-name JbiApp --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue" --output text; }
CLUSTER="$(out ClusterName)"
TASKDEF="$(out ToolsTaskDefinition)"
SUBNETS="$(out TaskSubnets)"
SG="$(out TaskSecurityGroup)"
ARN="$(aws_ ecs run-task --cluster "$CLUSTER" --task-definition "$TASKDEF" --launch-type FARGATE \
  --network-configuration "awsvpcConfiguration={subnets=[$SUBNETS],securityGroups=[$SG],assignPublicIp=ENABLED}" \
  --query 'tasks[0].taskArn' --output text)"
echo "migration task: $ARN"
aws_ ecs wait tasks-stopped --cluster "$CLUSTER" --tasks "$ARN"
CODE="$(aws_ ecs describe-tasks --cluster "$CLUSTER" --tasks "$ARN" --query 'tasks[0].containers[0].exitCode' --output text)"
aws_ logs tail /jbi/Tools --since 30m --format short | tail -20
echo "exit code: $CODE"
[ "$CODE" = "0" ]
