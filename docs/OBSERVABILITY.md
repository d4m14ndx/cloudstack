# Observability

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
