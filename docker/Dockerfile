# Job Board Indexer crawler image: the stock Data Prepper release plus our plugin jar.
# Data Prepper already ships the crawler framework, the jdbc coordination store, the Postgres driver
# and the OpenSearch sink, so nothing of Data Prepper is rebuilt here.
# Build context (docker/build.sh): job-board-crawler-*.jar, jobs-template.json, specs/ (platform specs),
# tenants/ (tenant lists), aws/ (pipelines rendered from config/aws/env.toml and the AWS server
# config), entrypoint.sh and log4j2-rolling.properties.
ARG DATA_PREPPER_VERSION=2.17.0
FROM opensearchproject/data-prepper:${DATA_PREPPER_VERSION}
COPY job-board-crawler-*.jar /usr/share/data-prepper/lib/
COPY jobs-template.json /etc/jbi/jobs-template.json
COPY specs/ /etc/jbi/specs/
COPY tenants/ /etc/jbi/tenants/
COPY aws/ /etc/jbi/aws/
COPY entrypoint.sh /usr/local/bin/jbi-entrypoint
# Stock logging config plus INFO for io.github.sb2k16.jobboard (the stock root level is WARN).
COPY --chown=data_prepper:data_prepper log4j2-rolling.properties /usr/share/data-prepper/config/log4j2-rolling.properties
# Locally a no-op; on ECS installs the Deployment's pipelines and fills in its settings.
ENTRYPOINT ["/usr/local/bin/jbi-entrypoint"]
CMD ["bin/data-prepper"]
