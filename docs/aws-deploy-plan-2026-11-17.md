# Deploying to AWS: plan and open decision (2026-11-17)

Goal: run the whole system in AWS (account 803817393850, us-east-1, profile `jbi`, every resource
tagged `project=jbi`) and verify ingestion there for all 54 connectors, instead of in the local
stack.

## Where we start

- Nothing of the pipeline exists in AWS. Tagged `project=jbi` today: the Phase 0 S3 bucket
  (`jbi-phase0-803817393850`), one security group and the probe instance profile.
- Tools: Terraform 1.17, kubectl 1.32, helm 4.3; no eksctl (not needed with Terraform).
- The code assumes the local stack in four places:
  1. The OpenSearch sink and the closure sweeper (`HttpIndexClient`) use plain HTTP; a managed
     domain needs AWS SigV4 signing. The Data Prepper `opensearch` sink signs natively
     (`aws.region`, `aws.sts_role_arn`); the sweeper and the web app need signing added.
  2. Specs ship inside the image; tenant lists are mounted from `local/live/tenants`. In AWS they
     need a home (image or S3) and the pipelines an AWS env file for `config/render_groups.py`.
  3. The image builds for linux/amd64 by default (`docker/build.sh`), which suits Fargate.
  4. The web app reads `OPENSEARCH_URL` with no auth.

## Steps

| # | Step | Done when |
| --- | --- | --- |
| 1 | Terraform: VPC (2 AZs), egress, Postgres, OpenSearch domain, ECR repositories, IAM roles, log groups, all tagged | `terraform apply` succeeds; outputs give endpoints |
| 2 | SigV4 in the closure sweeper and the web app; sink configured for SigV4 | Unit tests; a signed request to the domain from a task succeeds |
| 3 | AWS env file for the renderer (`config/aws/env.toml`): endpoints, replicas | Renders `crawler.yaml` and `crawler-heavy.yaml` for AWS |
| 4 | Images: build crawler and web for amd64, push to ECR tagged with the commit | Images in ECR |
| 5 | Database: run `db/V*.sql` migrations as a one-off task | Tables exist |
| 6 | Index: create `jobs-v1` from the template and the `jobs` alias | Index exists on the domain |
| 7 | Deploy the two crawler services and the web service | Tasks/pods healthy; logs in CloudWatch |
| 8 | Verify: a full refresh of every group, then per-platform counts from the index and board_state outcomes from Postgres, compared with the 54 connectors | Report: each platform ingesting, or the reason it is not |
| 9 | Kill-a-task test and the cross-platform merge check (CoStar, CVS) on AWS | Leases move; merges present |
| 10 | Cost: record the actual daily spend after a day | Measured cost next to the estimate |

## Open decision: the AWS shape

The design (Nov 6) specifies EKS on Fargate, Aurora Serverless v2 and a VPC-only OpenSearch
domain behind one NAT gateway. Its priced lines come to about $343 a month before the OpenSearch
domain, the load balancer and transfer, against the $90 budget noted on Nov 13. The steps above are
the same for every shape; only step 1 and the manifests in step 7 differ.

Estimates below are from public us-east-1 list prices and are unverified until the bill (step 10).

| Shape | What changes | Rough monthly |
| --- | --- | --- |
| A. Design as written | EKS control plane, Fargate pods (3 crawler, 2 API, system pods), Aurora Serverless v2, NAT gateway, OpenSearch domain, ALB | about $343 plus OpenSearch (about $25 to $50 for one small node) and ALB (about $18): roughly $390 to $410 |
| B. Lean ECS | ECS on Fargate (no control-plane fee) with crawler tasks on Fargate Spot, RDS Postgres db.t4g.micro instead of Aurora, single-node OpenSearch t3.small, tasks in public subnets with public IPs instead of a NAT gateway, ALB for the web app | roughly $90 to $110 |
| C. EKS trimmed | EKS kept for the design story, but RDS instead of Aurora, one OpenSearch node, Spot or 2 crawler pods | roughly $150 to $200 |

What B gives up, against the design: no Kubernetes (the Data Prepper side is unchanged; the
manifests become ECS task definitions), no fixed single egress IP (each task has its own public
IP, so platforms see two or three addresses instead of one), Spot interruptions (leases make a
lost task harmless: its boards return to the pool), and no multi-AZ database or domain.

Recommendation: B for the verification and the demo, since it fits the budget and exercises every
part of the pipeline the challenge asks about; the design doc's EKS shape stays the documented
production option. The choice is yours to make; nothing is created in AWS until it is made.
