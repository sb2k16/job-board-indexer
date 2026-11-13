# Decision: build the crawler outside Data Prepper (2026-11-13)

## Decision

The crawler moves out of the Data Prepper fork (`sb2k16/data-prepper`, branch `jbi`) into this repo. It is a
Data Prepper plugin compiled against the published 2.17.0 jars (`compileOnly`) and shipped as a layer on the
stock `opensearchproject/data-prepper:2.17.0` image.

## Why it works

- Everything the plugin uses is on Maven Central at 2.17.0: `data-prepper-api`,
  `org.opensearch.dataprepper.plugins.source.source_crawler:source-crawler` and `test-common`.
- Data Prepper finds plugins in any jar on its classpath that has `META-INF/data-prepper.plugins.properties`;
  the package lists of all such files are merged. Ours names `io.github.sb2k16.jobboard`.
- The stock image already contains the JDBC coordination store (upstream PR #6757, first released in 2.17.0),
  HikariCP, the Postgres driver and the OpenSearch sink.

## Verified (local stack, 3 replicas, stock image + plugin jar)

- 28/28 unit tests against the 2.17.0 jars; plugin jar is about 30 KB.
- 1,373 jobs emitted and written with 0 errors, 1,364 documents, each board fetched once, work split 15/6/12:
  identical to the fork build.

## Coordination store

The store is Data Prepper's `jdbc` plugin, configured in `data-prepper-config.yaml`. It claims partitions with
a version-checked UPDATE (optimistic locking, not `SKIP LOCKED`) and computes leases from each pod's clock.

Data Prepper 2.17.0 has a start-up race: replicas that run `CREATE TABLE IF NOT EXISTS` at the same moment can fail
on a Postgres catalog unique index, and the loser's source never starts (2 of 3 replicas in one run). This repo
avoids it by creating the table once (`db/V1__source_coordination.sql`) and running the crawlers with
`skip_table_creation: true`. The retry fix and its tests stay in the fork as a candidate upstream PR; once released,
the migration step becomes optional.

## Costs

- The source-crawler framework is not a stable public API; pin `dataPrepperVersion` and re-run the build, tests
  and end-to-end run before each upgrade.
- Changes inside Data Prepper itself need the fork or an upstream PR.

## Earlier fixes carried over

- `JobBoardCrawler` needs `@Inject` on its public constructor; `JobBoardPluginWiringTest` builds the plugin's
  beans through Spring the way Data Prepper does.
- `JobIdentity.locationToken`: a comma-less location ("Dublin") counts as the city. Before the fix, the same title
  in different cities merged (37 wrong merges on Stripe's board).
