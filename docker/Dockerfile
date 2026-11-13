# Job Board Indexer crawler image: the stock Data Prepper release plus our plugin jar.
# Data Prepper already ships the crawler framework, the jdbc coordination store, the Postgres driver
# and the OpenSearch sink, so nothing of Data Prepper is rebuilt here.
# Build context (docker/build.sh): job-board-crawler-*.jar, jobs-template.json, specs/ (platform specs)
# and log4j2-rolling.properties.
ARG DATA_PREPPER_VERSION=2.17.0
FROM opensearchproject/data-prepper:${DATA_PREPPER_VERSION}
COPY job-board-crawler-*.jar /usr/share/data-prepper/lib/
COPY jobs-template.json /etc/jbi/jobs-template.json
COPY specs/ /etc/jbi/specs/
# Stock logging config plus INFO for io.github.sb2k16.jobboard (the stock root level is WARN).
COPY --chown=data_prepper:data_prepper log4j2-rolling.properties /usr/share/data-prepper/config/log4j2-rolling.properties
