FROM eclipse-temurin:21-jre

ENV SOLR_VERSION=9.4.1
ENV SOLR_TGZ_URL=https://archive.apache.org/dist/solr/solr/${SOLR_VERSION}/solr-${SOLR_VERSION}.tgz
ENV SOLR_HOME=/opt/solr
ENV PATH=${SOLR_HOME}/bin:${PATH}

RUN apt-get update \
    && apt-get install -y curl tar procps \
    && rm -rf /var/lib/apt/lists/*

RUN curl -fSL "${SOLR_TGZ_URL}" -o /tmp/solr.tgz \
    && tar -xzf /tmp/solr.tgz -C /opt \
    && mv /opt/solr-${SOLR_VERSION} ${SOLR_HOME} \
    && rm /tmp/solr.tgz

RUN mkdir -p /var/solr/data

WORKDIR ${SOLR_HOME}
EXPOSE 8983

# This image runs Solr as root for local testing only, so Solr must be started with -force.
# Bind to 0.0.0.0 so the published Docker port is reachable from the host.
CMD ["bin/solr", "start", "-f", "-force", "-p", "8983", "-s", "/var/solr/data", "-a", "-Dsolr.jetty.host=0.0.0.0"]
