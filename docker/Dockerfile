# Job Board Indexer crawler image: the stock Data Prepper release plus our plugin jar.
# Data Prepper already ships the crawler framework, the jdbc coordination store, the Postgres driver
# and the OpenSearch sink, so nothing of Data Prepper is rebuilt here.
# Build context (docker/build.sh): job-board-crawler-*.jar and jobs-template.json.
ARG DATA_PREPPER_VERSION=2.17.0
FROM opensearchproject/data-prepper:${DATA_PREPPER_VERSION}
COPY job-board-crawler-*.jar /usr/share/data-prepper/lib/
COPY jobs-template.json /etc/jbi/jobs-template.json
