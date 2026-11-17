import { CfnOutput, Duration, RemovalPolicy, Stack, StackProps } from "aws-cdk-lib";
import * as ec2 from "aws-cdk-lib/aws-ec2";
import * as ecr from "aws-cdk-lib/aws-ecr";
import * as iam from "aws-cdk-lib/aws-iam";
import * as opensearch from "aws-cdk-lib/aws-opensearchservice";
import * as rds from "aws-cdk-lib/aws-rds";
import { Construct } from "constructs";

/**
 * What changes rarely: network, Postgres (coordination store and board_state), the OpenSearch
 * domain, the image repositories and the security groups. Kept apart from the app stack so a
 * failed app deploy can never roll back the database or the index.
 *
 * Option B (docs/aws-deploy-plan-2026-11-17.md): no NAT gateway. Tasks run in public subnets with
 * public IPs; Postgres and OpenSearch sit in isolated subnets with no internet route.
 */
export class DataStack extends Stack {
  readonly vpc: ec2.Vpc;
  readonly db: rds.DatabaseInstance;
  readonly domain: opensearch.Domain;
  readonly crawlerRepo: ecr.Repository;
  readonly webRepo: ecr.Repository;
  readonly toolsRepo: ecr.Repository;
  /** Crawler tasks and the one-off tools task: reach Postgres and OpenSearch, accept nothing. */
  readonly crawlerSg: ec2.SecurityGroup;
  /** Postgres and OpenSearch groups: the app stack adds the web tasks' ingress (its own resources). */
  readonly dbSg: ec2.SecurityGroup;
  readonly searchSg: ec2.SecurityGroup;

  constructor(scope: Construct, id: string, props?: StackProps) {
    super(scope, id, props);

    this.vpc = new ec2.Vpc(this, "Vpc", {
      ipAddresses: ec2.IpAddresses.cidr("10.42.0.0/16"),
      maxAzs: 2, // RDS needs a subnet group in two zones even for a single instance
      natGateways: 0,
      subnetConfiguration: [
        { name: "public", subnetType: ec2.SubnetType.PUBLIC, cidrMask: 24 },
        { name: "data", subnetType: ec2.SubnetType.PRIVATE_ISOLATED, cidrMask: 24 },
      ],
    });

    const dbSg = (this.dbSg = new ec2.SecurityGroup(this, "DbSg", {
      vpc: this.vpc,
      description: "Postgres: crawler, tools and web tasks only",
      allowAllOutbound: false,
    }));
    const searchSg = (this.searchSg = new ec2.SecurityGroup(this, "SearchSg", {
      vpc: this.vpc,
      description: "OpenSearch: crawler, tools and web tasks only",
      allowAllOutbound: false,
    }));
    this.crawlerSg = new ec2.SecurityGroup(this, "CrawlerSg", {
      vpc: this.vpc,
      description: "Crawler and tools tasks: outbound to job boards, Postgres and OpenSearch; no inbound",
      allowAllOutbound: true,
    });
    dbSg.addIngressRule(this.crawlerSg, ec2.Port.tcp(5432), "Postgres from crawler and tools tasks");
    searchSg.addIngressRule(this.crawlerSg, ec2.Port.tcp(443), "OpenSearch from crawler and tools tasks");

    // Neither service-linked role existed in the account on 2026-11-17. A VPC domain cannot be
    // created without OpenSearch's. Retained on destroy: deleting one that is in use fails.
    const searchRole = new iam.CfnServiceLinkedRole(this, "OpenSearchServiceRole", {
      awsServiceName: "opensearchservice.amazonaws.com",
    });
    searchRole.applyRemovalPolicy(RemovalPolicy.RETAIN);
    const ecsRole = new iam.CfnServiceLinkedRole(this, "EcsServiceRole", { awsServiceName: "ecs.amazonaws.com" });
    ecsRole.applyRemovalPolicy(RemovalPolicy.RETAIN);

    // Postgres 16 (as locally). RDS enforces SSL on 16 (rds.force_ssl=1): clients use sslmode=require.
    this.db = new rds.DatabaseInstance(this, "Db", {
      engine: rds.DatabaseInstanceEngine.postgres({ version: rds.PostgresEngineVersion.VER_16 }),
      instanceType: ec2.InstanceType.of(ec2.InstanceClass.T4G, ec2.InstanceSize.MICRO),
      vpc: this.vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_ISOLATED },
      securityGroups: [dbSg],
      credentials: rds.Credentials.fromGeneratedSecret("jbi"),
      databaseName: "jbi",
      allocatedStorage: 20,
      storageType: rds.StorageType.GP3,
      storageEncrypted: true,
      multiAz: false,
      publiclyAccessible: false,
      backupRetention: Duration.days(1),
      deleteAutomatedBackups: true,
      deletionProtection: false,
      removalPolicy: RemovalPolicy.DESTROY, // a demo stack: destroy takes the database with it
    });

    // One t3.small node in one zone. Access is by IAM (SigV4): the domain policy defers to the
    // account's IAM policies, and the app stack grants each task role only what it needs.
    const domainName = "jbi-jobs";
    this.domain = new opensearch.Domain(this, "Search", {
      domainName,
      // Set at creation (not addAccessPolicies, which needs a custom-resource Lambda).
      accessPolicies: [
        new iam.PolicyStatement({
          principals: [new iam.AccountRootPrincipal()],
          actions: ["es:ESHttp*"],
          resources: [`arn:aws:es:${this.region}:${this.account}:domain/${domainName}/*`],
        }),
      ],
      version: opensearch.EngineVersion.OPENSEARCH_2_19, // as locally (2.19.2)
      capacity: { dataNodes: 1, dataNodeInstanceType: "t3.small.search", multiAzWithStandbyEnabled: false },
      ebs: { volumeSize: 10, volumeType: ec2.EbsDeviceVolumeType.GP3 },
      zoneAwareness: { enabled: false },
      vpc: this.vpc,
      vpcSubnets: [{ subnets: [this.vpc.isolatedSubnets[0]] }],
      securityGroups: [searchSg],
      enforceHttps: true,
      nodeToNodeEncryption: true,
      encryptionAtRest: { enabled: true },
      removalPolicy: RemovalPolicy.DESTROY,
    });
    this.domain.node.addDependency(searchRole);

    const repo = (id: string, name: string) =>
      new ecr.Repository(this, id, {
        repositoryName: name,
        imageScanOnPush: true,
        lifecycleRules: [{ maxImageCount: 10, description: "keep the last 10 images" }],
        removalPolicy: RemovalPolicy.DESTROY,
        emptyOnDelete: true,
      });
    this.crawlerRepo = repo("CrawlerRepo", "jbi-crawler");
    this.webRepo = repo("WebRepo", "jbi-web");
    this.toolsRepo = repo("ToolsRepo", "jbi-tools");

    new CfnOutput(this, "DbEndpoint", { value: this.db.dbInstanceEndpointAddress });
    new CfnOutput(this, "SearchEndpoint", { value: `https://${this.domain.domainEndpoint}` });
    new CfnOutput(this, "CrawlerRepoUri", { value: this.crawlerRepo.repositoryUri });
    new CfnOutput(this, "WebRepoUri", { value: this.webRepo.repositoryUri });
    new CfnOutput(this, "ToolsRepoUri", { value: this.toolsRepo.repositoryUri });
  }
}
