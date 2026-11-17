import { CfnOutput, Duration, RemovalPolicy, Stack, StackProps } from "aws-cdk-lib";
import * as ec2 from "aws-cdk-lib/aws-ec2";
import * as ecs from "aws-cdk-lib/aws-ecs";
import * as ecsPatterns from "aws-cdk-lib/aws-ecs-patterns";
import * as iam from "aws-cdk-lib/aws-iam";
import * as logs from "aws-cdk-lib/aws-logs";
import { Construct } from "constructs";
import { DataStack } from "./data-stack";

export interface AppStackProps extends StackProps {
  readonly data: DataStack;
  /** Image tag (the git commit) for jbi-crawler, jbi-web and jbi-tools. */
  readonly imageTag: string;
  readonly crawlerCpu: number;
  readonly crawlerMemoryMiB: number;
  /** Task counts per crawler Deployment; must match [replicas] in config/aws/env.toml. */
  readonly crawlerTasks: number;
  readonly crawlerHeavyTasks: number;
}

/**
 * What changes on every deploy: the ECS cluster, the two crawler services (all tasks on Fargate
 * Spot), the web service behind a load balancer (on regular Fargate, for a stable URL) and the
 * one-off tools task that applies db/V*.sql.
 *
 * Crawler container contract (read by the image's entrypoint, docker/entrypoint.sh):
 *   JBI_DEPLOYMENT      which rendered pipelines file to run (crawler | crawler-heavy)
 *   JBI_DB_URL          JDBC URL of the coordination store and board_state (sslmode=require)
 *   JBI_DB_USER / JBI_DB_PASSWORD   from the database secret
 *   JBI_OPENSEARCH_URL  https endpoint of the domain; requests are signed with the task role
 */
export class AppStack extends Stack {
  constructor(scope: Construct, id: string, props: AppStackProps) {
    super(scope, id, props);
    const { data } = props;
    const dbSecret = data.db.secret!;
    const searchUrl = `https://${data.domain.domainEndpoint}`;
    const jdbcUrl = `jdbc:postgresql://${data.db.dbInstanceEndpointAddress}:${data.db.dbInstanceEndpointPort}/jbi?sslmode=require`;

    const cluster = new ecs.Cluster(this, "Cluster", {
      clusterName: "jbi",
      vpc: data.vpc,
      enableFargateCapacityProviders: true,
      containerInsightsV2: ecs.ContainerInsights.DISABLED,
    });

    const logGroup = (name: string) =>
      new logs.LogGroup(this, `${name}Logs`, {
        logGroupName: `/jbi/${name}`,
        retention: logs.RetentionDays.TWO_WEEKS,
        removalPolicy: RemovalPolicy.DESTROY,
      });

    // ---- crawlers: one task role for both Deployments and the tools task ----
    const crawlerRole = new iam.Role(this, "CrawlerTaskRole", {
      assumedBy: new iam.ServicePrincipal("ecs-tasks.amazonaws.com"),
      description: "Crawler: index, update-by-query (closure) and template writes on the jobs domain",
    });
    data.domain.grantReadWrite(crawlerRole);

    const dbSecrets = {
      JBI_DB_USER: ecs.Secret.fromSecretsManager(dbSecret, "username"),
      JBI_DB_PASSWORD: ecs.Secret.fromSecretsManager(dbSecret, "password"),
    };

    const crawler = (name: "crawler" | "crawler-heavy", tasks: number) => {
      const id = name === "crawler" ? "Crawler" : "CrawlerHeavy";
      const task = new ecs.FargateTaskDefinition(this, `${id}Task`, {
        family: `jbi-${name}`,
        cpu: props.crawlerCpu,
        memoryLimitMiB: props.crawlerMemoryMiB,
        taskRole: crawlerRole,
        // Fargate Spot runs x86 only; docker/build.sh builds linux/amd64 by default.
        runtimePlatform: {
          cpuArchitecture: ecs.CpuArchitecture.X86_64,
          operatingSystemFamily: ecs.OperatingSystemFamily.LINUX,
        },
      });
      task.addContainer("data-prepper", {
        image: ecs.ContainerImage.fromEcrRepository(data.crawlerRepo, props.imageTag),
        logging: ecs.LogDrivers.awsLogs({ streamPrefix: name, logGroup: logGroup(id) }),
        environment: {
          JBI_DEPLOYMENT: name,
          JBI_DB_URL: jdbcUrl,
          JBI_OPENSEARCH_URL: searchUrl,
          AWS_REGION: this.region,
          // Heap follows the task size; the rest is left for off-heap and the JVM's own use.
          JAVA_OPTS: "-XX:MaxRAMPercentage=70.0",
        },
        secrets: dbSecrets,
        // Spot gives two minutes' notice; a stop lets in-flight bulk writes finish.
        stopTimeout: Duration.seconds(110),
      });
      return new ecs.FargateService(this, `${id}Service`, {
        serviceName: `jbi-${name}`,
        cluster,
        taskDefinition: task,
        desiredCount: tasks,
        // All crawler tasks on Spot (decided 2026-11-17). A reclaimed task's boards return to the
        // pool when its leases lapse; ECS starts a replacement when capacity returns.
        capacityProviderStrategies: [{ capacityProvider: "FARGATE_SPOT", weight: 1 }],
        assignPublicIp: true, // no NAT gateway: each task reaches job boards from its own address
        vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
        securityGroups: [data.crawlerSg],
        minHealthyPercent: 100, // a rolling deploy starts the new task before stopping the old
        maxHealthyPercent: 200,
        circuitBreaker: { rollback: true },
      });
    };
    crawler("crawler", props.crawlerTasks);
    crawler("crawler-heavy", props.crawlerHeavyTasks);

    // ---- tools: one-off task that applies db/V*.sql (infra/scripts/migrate.sh) ----
    const tools = new ecs.FargateTaskDefinition(this, "ToolsTask", {
      family: "jbi-tools",
      cpu: 256,
      memoryLimitMiB: 512,
      taskRole: crawlerRole,
      runtimePlatform: { cpuArchitecture: ecs.CpuArchitecture.X86_64, operatingSystemFamily: ecs.OperatingSystemFamily.LINUX },
    });
    tools.addContainer("tools", {
      image: ecs.ContainerImage.fromEcrRepository(data.toolsRepo, props.imageTag),
      logging: ecs.LogDrivers.awsLogs({ streamPrefix: "tools", logGroup: logGroup("Tools") }),
      environment: {
        PGHOST: data.db.dbInstanceEndpointAddress,
        PGPORT: data.db.dbInstanceEndpointPort,
        PGDATABASE: "jbi",
        PGSSLMODE: "require",
      },
      secrets: {
        PGUSER: ecs.Secret.fromSecretsManager(dbSecret, "username"),
        PGPASSWORD: ecs.Secret.fromSecretsManager(dbSecret, "password"),
      },
    });

    // ---- web: regular Fargate behind a public load balancer ----
    const webRole = new iam.Role(this, "WebTaskRole", {
      assumedBy: new iam.ServicePrincipal("ecs-tasks.amazonaws.com"),
      description: "Web: search only, on the jobs alias",
    });
    // The site only searches (web/lib/opensearch.ts): POST, or GET without a body, to jobs/_search.
    webRole.addToPolicy(
      new iam.PolicyStatement({
        actions: ["es:ESHttpGet", "es:ESHttpPost"],
        resources: [`${data.domain.domainArn}/jobs/_search`],
      }),
    );
    // The web group lives here, not in the data stack: the load balancer (created here) is let into
    // it, and a rule across stacks in that direction would make the data stack depend on this one.
    // Its Postgres and OpenSearch ingress are explicit resources of this stack for the same reason.
    const webSg = new ec2.SecurityGroup(this, "WebSg", {
      vpc: data.vpc,
      description: "Web tasks: inbound from the load balancer only",
      allowAllOutbound: true,
    });
    new ec2.CfnSecurityGroupIngress(this, "DbFromWeb", {
      groupId: data.dbSg.securityGroupId,
      sourceSecurityGroupId: webSg.securityGroupId,
      ipProtocol: "tcp",
      fromPort: 5432,
      toPort: 5432,
      description: "Postgres from web tasks",
    });
    new ec2.CfnSecurityGroupIngress(this, "SearchFromWeb", {
      groupId: data.searchSg.securityGroupId,
      sourceSecurityGroupId: webSg.securityGroupId,
      ipProtocol: "tcp",
      fromPort: 443,
      toPort: 443,
      description: "OpenSearch from web tasks",
    });
    const web = new ecsPatterns.ApplicationLoadBalancedFargateService(this, "Web", {
      serviceName: "jbi-web",
      cluster,
      cpu: 256,
      memoryLimitMiB: 512,
      desiredCount: 1,
      publicLoadBalancer: true,
      assignPublicIp: true,
      taskSubnets: { subnetType: ec2.SubnetType.PUBLIC },
      securityGroups: [webSg],
      runtimePlatform: { cpuArchitecture: ecs.CpuArchitecture.X86_64, operatingSystemFamily: ecs.OperatingSystemFamily.LINUX },
      capacityProviderStrategies: [{ capacityProvider: "FARGATE", weight: 1 }],
      minHealthyPercent: 100,
      circuitBreaker: { rollback: true },
      taskImageOptions: {
        image: ecs.ContainerImage.fromEcrRepository(data.webRepo, props.imageTag),
        containerPort: 3000,
        taskRole: webRole,
        logDriver: ecs.LogDrivers.awsLogs({ streamPrefix: "web", logGroup: logGroup("Web") }),
        environment: {
          OPENSEARCH_URL: searchUrl,
          JOBS_INDEX: "jobs",
          AWS_REGION: this.region,
          PGHOST: data.db.dbInstanceEndpointAddress,
          PGPORT: data.db.dbInstanceEndpointPort,
          PGDATABASE: "jbi",
          // Encrypted; the RDS CA is not in Node's trust store, so the certificate is not verified.
          PGSSLMODE: "no-verify",
        },
        secrets: {
          PGUSER: ecs.Secret.fromSecretsManager(dbSecret, "username"),
          PGPASSWORD: ecs.Secret.fromSecretsManager(dbSecret, "password"),
        },
      },
    });
    web.targetGroup.configureHealthCheck({ path: "/api/health", healthyHttpCodes: "200" });

    new CfnOutput(this, "WebUrl", { value: `http://${web.loadBalancer.loadBalancerDnsName}` });
    new CfnOutput(this, "ClusterName", { value: cluster.clusterName });
    new CfnOutput(this, "ToolsTaskDefinition", { value: tools.taskDefinitionArn });
    new CfnOutput(this, "TaskSubnets", { value: data.vpc.publicSubnets.map((s) => s.subnetId).join(",") });
    new CfnOutput(this, "TaskSecurityGroup", { value: data.crawlerSg.securityGroupId });
  }
}
