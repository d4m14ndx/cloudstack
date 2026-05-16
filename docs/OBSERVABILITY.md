# Observability

## Metrics endpoint (Prometheus)

The management server exposes JVM and process metrics in Prometheus text
format at `GET /metrics`. This complements the existing prometheus
integration plugin (which serves *business* metrics — VMs, hosts, storage —
on its own dedicated port).

What's exposed:

- **JVM**: heap usage by region, GC pause times and counts, thread states,
  classloader counts, JIT compilation time, heap pressure
- **Process**: uptime, CPU load, file descriptors, system load average

### Prometheus scrape config

```yaml
scrape_configs:
  - job_name: cloudstack-management
    metrics_path: /client/metrics
    static_configs:
      - targets: ['mgmt-1:8080', 'mgmt-2:8080']
```

### Add custom metrics

Anywhere in the server module:

```java
import com.cloud.servlet.MetricsRegistryHolder;
import io.micrometer.core.instrument.Counter;

private final Counter myCounter = Counter.builder("cloudstack.my.counter")
        .tag("kind", "thing")
        .register(MetricsRegistryHolder.get());

// ... later
myCounter.increment();
```

## Health check endpoints

The management server exposes lightweight health endpoints suitable for use
with Kubernetes probes, load balancers, and uptime monitors.

| Endpoint | Purpose | Behavior |
|----------|---------|----------|
| `GET /health/live` | Liveness | Always returns 200 unless the JVM is wedged. Use for restart triggers. |
| `GET /health/ready` | Readiness | Returns 200 only when the Spring context is initialized and the database is reachable. Use for traffic routing. |
| `GET /health` | Aggregate | Same as `/health/ready`. |

Responses are plain text (`OK` or a short reason like `database-unreachable`).
The HTTP status code is the source of truth.

### Kubernetes example

```yaml
livenessProbe:
  httpGet:
    path: /health/live
    port: 8080
  periodSeconds: 10
readinessProbe:
  httpGet:
    path: /health/ready
    port: 8080
  periodSeconds: 5
  failureThreshold: 3
```

## Structured logging (JSON)

CloudStack supports both human-readable text logs (default) and structured
JSON logs for log aggregation pipelines (Loki, Elasticsearch, Datadog, etc.).

### Enable JSON logs

Set the environment variable before starting the management server:

```bash
export CLOUDSTACK_LOG_FORMAT=json
```

Output format follows the Elastic Common Schema (ECS), giving you:

```json
{
  "@timestamp": "2026-05-16T01:23:45.123Z",
  "log.level": "INFO",
  "log.logger": "com.cloud.vm.UserVmManagerImpl",
  "message": "VM lifecycle event",
  "process.thread.name": "main",
  "service.name": "cloudstack-management"
}
```

### Switch back to text

Unset the variable or set it to anything other than `json`:

```bash
unset CLOUDSTACK_LOG_FORMAT
# or
export CLOUDSTACK_LOG_FORMAT=text
```

### Implementation

The routing happens in `engine/service/src/main/webapp/WEB-INF/log4j.xml` via
a `<Routing>` appender that reads the `CLOUDSTACK_LOG_FORMAT` env var. JSON
output uses Log4j2's `JsonTemplateLayout` with the bundled `EcsLayout.json`
template — no extra config needed.

## Future observability work

Planned (Phase 5):
- Micrometer-backed metrics endpoint (Prometheus scrape)
- OpenTelemetry tracing for API and orchestration spans
- Health check endpoints
- Trace ID propagation through async jobs
