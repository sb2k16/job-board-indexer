import { App, Tags } from "aws-cdk-lib";
import { AppStack } from "../lib/app-stack";
import { DataStack } from "../lib/data-stack";

// Account 803817393850, us-east-1 (project memory: profile jbi). Deploy with --profile jbi.
const env = { account: "803817393850", region: "us-east-1" };
const app = new App();
const num = (key: string) => Number(app.node.tryGetContext(key));

const data = new DataStack(app, "JbiData", { env, description: "Job Board Indexer: network, Postgres, OpenSearch, ECR" });
new AppStack(app, "JbiApp", {
  env,
  description: "Job Board Indexer: crawler services (Fargate Spot), web service, tools task",
  data,
  imageTag: String(app.node.tryGetContext("imageTag")),
  crawlerCpu: num("crawlerCpu"),
  crawlerMemoryMiB: num("crawlerMemoryMiB"),
  crawlerTasks: num("crawlerTasks"),
  crawlerHeavyTasks: num("crawlerHeavyTasks"),
});

// Every resource the stacks create carries project=jbi (project rule: keep JBI separate from Siddhanta).
Tags.of(app).add("project", "jbi");
