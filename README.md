# Job Board Indexer

A continuously updated, searchable index of public job postings across ATS and job-board platforms.

The crawler is a source plugin for [OpenSearch Data Prepper](https://github.com/opensearch-project/data-prepper).
It is built here against the published Data Prepper jars and runs in the stock Data Prepper image; Data Prepper
itself is not forked or rebuilt. Data Prepper provides the pipeline runtime, the source-crawler framework (leader
and worker scheduling), the JDBC (Postgres) coordination store and the OpenSearch sink.

Crawling indexes only what robots.txt allows and uses public job-board endpoints.

## Layout

| Path | What |
|---|---|
| `crawler/` | `job_board_crawler` source plugin (package `io.github.sb2k16.jobboard`): platform adapters, tenant lists, job identity (`job_key`) |
| `docker/` | Image = `opensearchproject/data-prepper:<version>` + the plugin jar |
| `db/` | Migrations run once before the crawlers start: coordination table (V1), `board_state` (V2), job identity and closure (V3) |
| `config/` | OpenSearch index template, upsert script, pipeline groups (`groups.toml`) and their renderer ([notes](docs/pipeline-groups-2026-11-13.md)) |
| `config/specs/` | Platform specs: one YAML per class A platform, run by the spec engine ([format](config/specs/README.md)) |
| `web/` | The website: search jobs with filters, job pages, companies, boards and crawl health ([notes](docs/web-ui-2026-11-13.md)) |
| `local/` | Docker Compose stack: Postgres, OpenSearch, a fake board server and 3 crawler replicas |
| `tests/` | Integration tests against the local stack: upsert (T1.4), fault injection (T2.4), merge and closure scripts and closure end to end (T2.5, [notes](docs/identity-merge-closure-2026-11-13.md)) |
| `phase0/` | Platform feasibility study (coverage matrix, probes) |
| `docs/` | Design notes and decisions |

The Data Prepper version is pinned in `gradle.properties` (`dataPrepperVersion`) and used for both compiling and the image.

## Build and test

```sh
./gradlew build                                  # compile + unit tests (Java 11 toolchain)
PLATFORM=linux/arm64 docker/build.sh local       # image jbi-crawler:local (omit PLATFORM for amd64)
```

## Run locally

```sh
cd local
python3 ../config/render_pipeline.py pipelines.tmpl.yaml pipelines.yaml
docker compose up -d
curl -s localhost:9200/jobs-v1/_count
python3 ../tests/t14_upsert_test.py http://localhost:9200   # upsert/merge checks
```

That runs the fixture board server. To crawl the real platforms instead, with the pipelines rendered
from `config/groups.toml`, and the website on http://localhost:3000:

```sh
cd local
python3 ../config/render_groups.py live/env.toml live/pipelines
docker compose -f docker-compose.yml -f docker-compose.live.yml up -d --scale crawler=2
```

## License

Apache-2.0
