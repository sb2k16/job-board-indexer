# Infrastructure (AWS CDK, TypeScript)

Option B of `docs/aws-deploy-plan-2026-11-17.md`: ECS on Fargate with every crawler task on Spot,
RDS Postgres, one OpenSearch node, no NAT gateway. Account 803817393850, us-east-1, every resource
tagged `project=jbi`. Always pass `--profile jbi`.

| Stack | Holds | Changes |
| --- | --- | --- |
| `JbiData` | VPC (public + isolated subnets, 2 zones), RDS Postgres 16 `db.t4g.micro`, OpenSearch 2.19 `t3.small.search` (`jbi-jobs`), ECR repositories `jbi-crawler`, `jbi-web`, `jbi-tools`, the database and search security groups, the OpenSearch and ECS service-linked roles | Rarely |
| `JbiApp` | ECS cluster `jbi`; services `jbi-crawler` (2 tasks) and `jbi-crawler-heavy` (1), both Fargate Spot only; `jbi-web` (1, regular Fargate) behind a public load balancer; the one-off `jbi-tools` task; task roles; log groups `/jbi/*` | Every deploy |

The data stack never depends on the app stack, so a failed app deploy cannot roll back the database
or the index.

## Commands

```sh
npm ci
npm test                                   # assertion tests of the decisions above
npx cdk synth --profile jbi                # templates in cdk.out/
npx cdk bootstrap --profile jbi            # once per account and region
npx cdk deploy JbiData --profile jbi       # OpenSearch takes 15 to 20 minutes
TAG=$(git rev-parse --short HEAD)
../docker/build-aws.sh "$TAG"              # jbi-crawler, jbi-web, jbi-tools for linux/amd64 (clean tree only)
scripts/push-images.sh "$TAG"              # to the stack's ECR repositories
scripts/deploy-app.sh "$TAG"               # crawlers to 0, cdk deploy JbiApp, crawlers back
scripts/migrate.sh                         # one-off jbi-tools task: db/V*.sql (idempotent)
```

The crawler image carries the pipelines rendered from `config/aws/env.toml` (one file per
Deployment) with `@@NAME@@` placeholders; `docker/entrypoint.sh` fills them at start from the
environment ECS injects (the database password from Secrets Manager), so nothing secret is in the
image. Locally the entrypoint does nothing.

Crawler deploys go through `scripts/deploy-app.sh`: on db.t4g.micro (the free account plan refuses
larger classes) old and new crawler tasks do not fit the database's connections at once, and ECS
starts a replacement before the old task has exited. Crawling pauses for a few minutes.

Context (`cdk.json`, override with `-c`): `imageTag`, `crawlerCpu` (1024), `crawlerMemoryMiB`
(4096), `crawlerTasks` (2), `crawlerHeavyTasks` (1). Task counts must match `[replicas]` in the AWS
env file for `config/render_groups.py`, which divides each platform's rate limit across tasks.

`cdk destroy` deletes the database and the domain with their data (a demo stack); the two
service-linked roles are retained.
