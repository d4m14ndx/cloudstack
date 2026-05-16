# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0

# CloudStack management server — production runtime image.
#
# Build:
#   docker build -t cloudstack-management:dev .
#
# Run (requires external MySQL):
#   docker run --rm -p 8080:8080 -p 8443:8443 \
#     -e CLOUDSTACK_LOG_FORMAT=json \
#     -e DB_HOST=mysql.example.internal \
#     -e DB_USER=cloud -e DB_PASSWORD=cloud \
#     cloudstack-management:dev
#
# Or use docker compose for a one-command local stack:
#   docker compose up

# -----------------------------------------------------------------------------
# Build stage: compile the management server WAR + dependencies
# -----------------------------------------------------------------------------
FROM eclipse-temurin:17-jdk-noble AS build

RUN apt-get update && apt-get install -y --no-install-recommends \
        maven \
        git \
        python3 \
        python3-mysql.connector \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /src
COPY pom.xml ./
COPY . ./

# -Dnoredist=false: skip noredist proprietary bits; -P developer,systemvm: standard build profile
RUN mvn -B -ntp install -DskipTests -P developer,systemvm -T1C

# -----------------------------------------------------------------------------
# Runtime stage: minimal Java image with the built jars
# -----------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-noble

LABEL org.opencontainers.image.title="CloudStack Management Server" \
      org.opencontainers.image.description="Apache CloudStack management server (fork)" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.source="https://github.com/d4m14ndx/cloudstack"

RUN apt-get update && apt-get install -y --no-install-recommends \
        curl \
        ca-certificates \
        tini \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r cloud --gid 1000 \
    && useradd -r -g cloud --uid 1000 --shell /usr/sbin/nologin --home-dir /var/lib/cloudstack cloud \
    && mkdir -p /etc/cloudstack/management /var/log/cloudstack/management /var/lib/cloudstack \
    && chown -R cloud:cloud /etc/cloudstack /var/log/cloudstack /var/lib/cloudstack

# Copy the built artifacts
COPY --from=build --chown=cloud:cloud /src/client/target/cloud-client-ui-*.jar /usr/share/cloudstack-management/cloud-client-ui.jar
COPY --from=build --chown=cloud:cloud /src/client/target/lib /usr/share/cloudstack-management/lib
COPY --from=build --chown=cloud:cloud /src/client/target/common/scripts /usr/share/cloudstack-management/scripts

# Default server.properties — overridden by mounting your own at /etc/cloudstack/management/server.properties
COPY --from=build --chown=cloud:cloud /src/client/target/conf/server.properties /etc/cloudstack/management/server.properties
COPY --from=build --chown=cloud:cloud /src/client/target/conf/db.properties /etc/cloudstack/management/db.properties
COPY --from=build --chown=cloud:cloud /src/client/target/conf/log4j-cloud.xml /etc/cloudstack/management/log4j-cloud.xml

USER cloud
WORKDIR /var/lib/cloudstack

EXPOSE 8080 8443

# Defaults that should usually be overridden:
ENV JAVA_OPTS="-Xmx2g -Xms512m" \
    CLOUDSTACK_LOG_FORMAT=text \
    OTEL_SERVICE_NAME=cloudstack-management

# Liveness probe target (HEALTHCHECK uses /health/live; readiness is for orchestrators)
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD curl -fsS http://localhost:8080/client/health/live || exit 1

ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["sh", "-c", "exec java $JAVA_OPTS \
    -classpath /etc/cloudstack/management:/usr/share/cloudstack-management/cloud-client-ui.jar:/usr/share/cloudstack-management/lib/* \
    -Dlog4j.configurationFile=/etc/cloudstack/management/log4j-cloud.xml \
    -Djavax.net.ssl.trustStorePassword=vmops.com \
    --add-opens=java.base/java.lang=ALL-UNNAMED \
    --add-opens=java.base/java.util=ALL-UNNAMED \
    --add-exports=java.base/sun.security.x509=ALL-UNNAMED \
    --add-opens=java.base/jdk.internal.reflect=ALL-UNNAMED \
    org.apache.cloudstack.ServerDaemon"]
