import { App, Tags } from "aws-cdk-lib";
import { Match, Template } from "aws-cdk-lib/assertions";
import assert from "node:assert/strict";
import { test } from "node:test";
import { AppStack } from "../lib/app-stack";
import { DataStack } from "../lib/data-stack";

// The decisions of docs/aws-deploy-plan-2026-11-17.md (option B, all crawlers on Spot), as checks.
const env = { account: "803817393850", region: "us-east-1" };
const app = new App({ context: { "availability-zones:account=803817393850:region=us-east-1": ["us-east-1a", "us-east-1b"] } });
const dataStack = new DataStack(app, "JbiData", { env });
const appStack = new AppStack(app, "JbiApp", {
  env,
  data: dataStack,
  imageTag: "abc1234",
  crawlerCpu: 1024,
  crawlerMemoryMiB: 4096,
  crawlerTasks: 2,
  crawlerHeavyTasks: 1,
});
Tags.of(app).add("project", "jbi");
const data = Template.fromStack(dataStack);
const svc = Template.fromStack(appStack);

test("no NAT gateway: tasks reach the internet from public subnets", () => {
  data.resourceCountIs("AWS::EC2::NatGateway", 0);
  for (const name of ["jbi-crawler", "jbi-crawler-heavy", "jbi-web"]) {
    svc.hasResourceProperties("AWS::ECS::Service", {
      ServiceName: name,
      NetworkConfiguration: { AwsvpcConfiguration: { AssignPublicIp: "ENABLED" } },
    });
  }
});

test("every crawler task runs on Fargate Spot, the web task on regular Fargate", () => {
  const services = svc.findResources("AWS::ECS::Service");
  const byName = Object.fromEntries(Object.values(services).map((r) => [r.Properties.ServiceName, r.Properties]));
  for (const name of ["jbi-crawler", "jbi-crawler-heavy"]) {
    assert.deepEqual(byName[name].CapacityProviderStrategy, [{ CapacityProvider: "FARGATE_SPOT", Weight: 1 }]);
    assert.equal(byName[name].LaunchType, undefined);
  }
  assert.equal(byName["jbi-crawler"].DesiredCount, 2);
  assert.equal(byName["jbi-crawler-heavy"].DesiredCount, 1);
  assert.deepEqual(byName["jbi-web"].CapacityProviderStrategy, [{ CapacityProvider: "FARGATE", Weight: 1 }]);
});

test("Postgres and OpenSearch are private and encrypted", () => {
  data.hasResourceProperties("AWS::RDS::DBInstance", {
    PubliclyAccessible: false,
    StorageEncrypted: true,
    Engine: "postgres",
    DBInstanceClass: "db.t4g.micro",
  });
  data.hasResourceProperties("AWS::OpenSearchService::Domain", {
    DomainName: "jbi-jobs",
    VPCOptions: Match.objectLike({ SubnetIds: Match.anyValue() }),
    EncryptionAtRestOptions: { Enabled: true },
    NodeToNodeEncryptionOptions: { Enabled: true },
    DomainEndpointOptions: Match.objectLike({ EnforceHTTPS: true }),
    ClusterConfig: Match.objectLike({ InstanceCount: 1, InstanceType: "t3.small.search" }),
  });
});

test("only the task security groups reach Postgres and OpenSearch", () => {
  const ingress = [
    ...Object.values(data.findResources("AWS::EC2::SecurityGroupIngress")),
    ...Object.values(svc.findResources("AWS::EC2::SecurityGroupIngress")),
  ].map((r) => r.Properties);
  for (const port of [5432, 443]) {
    const rules = ingress.filter((p) => p.FromPort === port);
    assert.equal(rules.length, 2, `two groups may reach port ${port}`);
    for (const r of rules) {
      assert.equal(r.CidrIp, undefined, `port ${port} is never open to an address range`);
      assert.ok(r.SourceSecurityGroupId, `port ${port} is opened to a security group`);
    }
  }
});

test("the web role may only search the jobs alias", () => {
  const policies = svc.findResources("AWS::IAM::Policy", {
    Properties: { Roles: [{ Ref: Match.stringLikeRegexp("WebTaskRole") }] },
  });
  const statements = Object.values(policies).flatMap((p) => p.Properties.PolicyDocument.Statement);
  assert.equal(statements.length, 1);
  assert.deepEqual(statements[0].Action, ["es:ESHttpGet", "es:ESHttpPost"]);
  assert.match(JSON.stringify(statements[0].Resource), /\/jobs\/_search/);
});

test("crawler containers get the database password from Secrets Manager, never as plain text", () => {
  const tasks = Object.values(svc.findResources("AWS::ECS::TaskDefinition")).map((t) => t.Properties);
  const crawlers = tasks.filter((t) => String(t.Family).startsWith("jbi-crawler"));
  assert.equal(crawlers.length, 2);
  for (const t of crawlers) {
    const c = t.ContainerDefinitions[0];
    const env = Object.fromEntries(c.Environment.map((e: { Name: string; Value: unknown }) => [e.Name, e.Value]));
    assert.equal(env.JBI_DB_PASSWORD, undefined);
    assert.ok(c.Secrets.some((s: { Name: string }) => s.Name === "JBI_DB_PASSWORD"));
    assert.match(JSON.stringify(env.JBI_DB_URL), /sslmode=require/);
    assert.equal(t.RequiresCompatibilities[0], "FARGATE");
    assert.equal(t.RuntimePlatform.CpuArchitecture, "X86_64"); // Fargate Spot is x86 only
  }
});

test("every taggable resource carries project=jbi", () => {
  for (const tpl of [data, svc]) {
    for (const type of ["AWS::EC2::VPC", "AWS::RDS::DBInstance", "AWS::OpenSearchService::Domain", "AWS::ECS::Service",
      "AWS::ECS::Cluster", "AWS::ECR::Repository", "AWS::ElasticLoadBalancingV2::LoadBalancer", "AWS::Logs::LogGroup"]) {
      for (const r of Object.values(tpl.findResources(type))) {
        const tags = r.Properties.Tags ?? r.Properties.TagList ?? [];
        assert.ok(tags.some((t: { Key: string; Value: string }) => t.Key === "project" && t.Value === "jbi"), `${type} tagged`);
      }
    }
  }
});

test("the data stack never depends on the app stack", () => {
  assert.equal(dataStack.dependencies.length, 0);
  assert.deepEqual(appStack.dependencies.map((s) => s.stackName), ["JbiData"]);
});

test("task counts match the replicas the pipelines divide rate limits by (config/aws/env.toml)", async () => {
  const { readFileSync } = await import("node:fs");
  const toml = readFileSync(new URL("../../config/aws/env.toml", `file://${__filename}`), "utf8");
  const section = toml.split("[replicas]")[1].split("\n[")[0];
  const replicas = (key: string) => Number(new RegExp(`^${key}\\s*=\\s*(\\d+)`, "m").exec(section)![1]);
  const ctx = JSON.parse(readFileSync(new URL("../cdk.json", `file://${__filename}`), "utf8")).context;
  assert.equal(replicas("crawler"), ctx.crawlerTasks);
  assert.equal(replicas("crawler-heavy"), ctx.crawlerHeavyTasks);
});
