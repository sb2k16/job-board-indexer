# jbi-tools: one-off operations task (infra: ToolsTask). Today: apply the database migrations.
# Build context (docker/build-aws.sh): db/ and migrate.sh.
FROM postgres:16-alpine
COPY db/ /db/
COPY migrate.sh /usr/local/bin/jbi-migrate
USER postgres
ENTRYPOINT ["/usr/local/bin/jbi-migrate"]
