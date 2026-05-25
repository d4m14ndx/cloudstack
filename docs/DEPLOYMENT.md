# Deployment

The fork ships with a `Dockerfile`, `docker-compose.yml` for local
development, and a Helm chart at `deploy/helm/cloudstack-management/`.
All three leverage the Phase 4 observability surface (health probes,
metrics endpoint, structured logging, distributed tracing).

## Docker

Build the image:

```bash
docker build -t cloudstack-management:dev .
```

Run with an external MySQL:

```bash
docker run --rm -p 8080:8080 -p 8443:8443 \
    -e CLOUDSTACK_LOG_FORMAT=json \
    -e DB_HOST=mysql.example.internal \
    -e DB_USER=cloud -e DB_PASSWORD=cloud \
    cloudstack-management:dev
```

The image:

- Runs as non-root user `cloud` (UID 1000)
- Uses `tini` as PID 1 for proper signal handling
- Has a `HEALTHCHECK` against `/client/health/live`
- Exposes ports 8080 (HTTP) and 8443 (HTTPS)
- Config lives at `/etc/cloudstack/management/` (mount your own
  `server.properties` / `db.properties` to override)

## Local development with docker-compose

```bash
docker compose up --build
```

This starts MySQL 8 and the management server. The compose file uses
the readiness probe (`/client/health/ready`) — the cloudstack service
won't be marked healthy until the database is reachable.

Browse to:
- http://localhost:8080/client — UI
- http://localhost:8080/client/metrics — Prometheus scrape target
- http://localhost:8080/client/health/ready — readiness

## Kubernetes via Helm

The chart at `deploy/helm/cloudstack-management/` is production-shaped:

- Liveness probe on `/health/live` (restart trigger)
- Readiness probe on `/health/ready` (traffic routing)
- Startup probe with a generous failure threshold (CloudStack boot
  is slow — ~2 minutes)
- Non-root pod security context (UID/GID 1000, capability drop ALL)
- Optional `ServiceMonitor` for the Prometheus Operator
- Pod annotations for simple Prometheus scrape config
- Ingress template for HTTPS exposure with cert-manager
- Configurable JVM heap, OpenTelemetry exporter, log format

### Install

```bash
helm install cloudstack ./deploy/helm/cloudstack-management \
    --namespace cloudstack --create-namespace \
    --set image.repository=ghcr.io/d4m14ndx/cloudstack-management \
    --set image.tag=v0.1.0 \
    --set database.host=mysql-primary.db.svc.cluster.local \
    --set database.existingSecret=cloudstack-db-creds \
    --set database.existingSecretKey=password \
    --set logFormat=json \
    --set serviceMonitor.enabled=true \
    --set serviceMonitor.labels.release=kube-prometheus-stack
```

### Enable OpenTelemetry tracing

```bash
helm upgrade cloudstack ./deploy/helm/cloudstack-management \
    --reuse-values \
    --set tracing.enabled=true \
    --set tracing.endpoint=http://otel-collector.observability:4318 \
    --set tracing.samplerRatio=0.05
```

### Override the embedded config

The container ships with a default `server.properties` and
`db.properties`. To override:

```yaml
configOverride:
  enabled: true
  serverPropertiesConfigMap: my-cloudstack-server-config
  dbPropertiesConfigMap: my-cloudstack-db-config
```

Where the ConfigMaps each have a single key matching the filename
(`server.properties` and `db.properties` respectively).

### Resource sizing

The chart defaults to 1 CPU request and 1 Gi memory request with a
4 Gi limit. CloudStack's heap settings (`javaOpts`) and the container
memory limit should be set together — leave 25% headroom for
Metaspace, off-heap buffers, and code cache. Example for a busy
deployment:

```yaml
resources:
  requests: { cpu: 2, memory: 4Gi }
  limits:   {           memory: 8Gi }
javaOpts: "-Xmx6g -Xms2g"
```

### Multi-replica deployment caveats

CloudStack supports horizontal scaling of the management server, but
expects a shared DB and shared NFS/secondary storage. The chart sets
`replicaCount` and uses a standard Deployment — for multi-replica
clustered deployment you should also configure CloudStack's
`management.network.cidr` and cluster ID in `server.properties` (via
the configOverride above) and run all replicas pointing at the same
DB.
