# Modernization progress — summary

This fork has 22 commits beyond the upstream Apache CloudStack baseline,
covering everything from dead-code removal through to a production
Kubernetes deployment story. Every commit has tests; the full reactor
runs **10,741 unit tests with 0 failures and 0 errors**.

## What shipped, by phase

### Phase 1 — Dead code removal (2 commits)

- **−143K lines** across 19 dead plugins targeting discontinued products
  (BigSwitch, Brocade, Cisco VNMC, CloudByte, Datera, Nicira NVP,
  Juniper Contrail, etc.)
- Insecure auth modules removed (MD5, plain-text)
- Cross-references cleaned in API, server, engine, framework, plugins,
  and tests

### Phase 2 — Dependency modernization (1 commit)

- 30+ dependencies updated
- Security-critical: Bouncy Castle jdk15on 1.70 → jdk18on 1.79, JSch
  abandoned → maintained fork, Kafka 2.7 → 3.8, Log4j 2.19 → 2.24,
  Jackson 2.13 → 2.18, Guava 31 → 33
- Maven build plugins all bumped to current major versions
- Test libraries: Hamcrest 1.3 → 2.2, JUnit Jupiter, AssertJ, WireMock

### Phase 3 — Java 17 + Jakarta + Spring 6; Phase 5 backend baseline to Java 21

- Java target: 11 → 17, then fork baseline lifted to 21
- Spring Framework: 5.3 (EOL) → 6.1
- Jetty: 9.4 (EOL) → 11.0
- Tomcat embed → 10.0 (Jakarta)
- Apache CXF: 3.2 (EOL) → 4.0
- Groovy: 2.4 (EOL) → 4.0 (groupId migrated)
- **1,510 files** migrated from `javax.*` → `jakarta.*`
- Hibernate-ready (JPA 3 / Jakarta Persistence 3.1)
- Significant API breakage fixed: Spring 6 InstantiationAwareBeanPostProcessor,
  Jetty 11 websocket API, RequestLog API, SslContextFactory.Server, etc.
- Post-migration test fixes: cglib → Spring-inlined cglib, WireMock 3,
  OWASP ESAPI 2.5 (with xalan eviction)

### Phase 4 — Operability + decomposition + deployment (15 commits)

#### Operability surface

| Endpoint | Use |
|----------|-----|
| `GET /health/live` | Kubernetes liveness probe |
| `GET /health/ready` | Kubernetes readiness probe |
| `GET /metrics` | Prometheus scrape (JVM + process + HTTP) |
| W3C trace context propagation | Distributed traces via OTLP |
| JSON structured logs | ECS schema, env-driven (`CLOUDSTACK_LOG_FORMAT=json`) |
| Async job spans | Every job execution traced |

#### Engineering quality

- Fork CI workflow with required (build+test) and advisory
  (SpotBugs/PMD/OWASP) jobs
- 64 dedicated unit tests for `ConfigurationValueValidator`
- `DEVELOPMENT.md`, `OBSERVABILITY.md`, `REFACTORING.md`, `DEPLOYMENT.md`

#### God class decomposition — ConfigurationManagerImpl

8 slices shipped. `ConfigurationManagerImpl.java` 9,509 → 9,286 lines
(−223). Extracted to `ConfigurationValueValidator` (pure utility):

| Slice | Helpers |
|-------|---------|
| 1 | 10 base validators (`validateValueType`, `validateRange*`, etc.) |
| 2 | `shouldEncryptValue`, `maskEventValueIfEncrypted`, `parseConfigurationTypeIntoString` |
| 3 | `isIpConfigName`, `validateIpConfigValue`, `validateConflictingConfigValue` |
| 4 | `validateCidrList` |
| 5 | 4 validation sets → immutable static constants |
| 6 | VLAN URI parsing → `BroadcastDomainType.parseVlanNumberFromUri` |
| 7 | `validateSpecificConfigurationValues` |
| 8 | Final cleanup |

Pattern: pure helpers extracted, instance methods kept as delegating
one-liners so Mockito spies and subclass overrides still work.

#### Container + Kubernetes deployment

- Multi-stage `Dockerfile` (eclipse-temurin:21, non-root user, tini PID 1,
  HEALTHCHECK)
- `docker-compose.yml` for one-command local dev (MySQL 8 + management)
- Helm chart at `deploy/helm/cloudstack-management/` with:
  - Liveness/readiness/startup probes targeting Phase 4 endpoints
  - Optional ServiceMonitor for Prometheus Operator
  - OpenTelemetry env wiring
  - ConfigMap-based property overrides
  - Ingress template (cert-manager-friendly)
  - `helm lint` passes, `helm template` renders cleanly

## What's not done (recommended follow-ups)

### Spring-component extraction in god classes

Pure-helper extraction has exhausted the easy wins. Further god-class
decomposition (`UserVmManagerImpl` at 10K lines, `NetworkServiceImpl`,
etc.) requires moving coherent domain units into their own `@Component`
classes — a heavier per-slice effort needing proper architectural
review, not autonomous batch work. See `docs/REFACTORING.md`.

### OpenAPI spec generation

CloudStack uses a custom XML API doc system (`tools/apidoc/`). Adding
OpenAPI 3.x emission from the `BaseCmd` metadata would unlock modern
tooling (Postman, swagger-codegen, mock servers). Estimated effort:
1-2 sessions of focused work introspecting the `@Parameter`/`@APICommand`
annotation graph.

### Async job W3C traceparent persistence

Async job spans are currently independent traces (no parent link).
Persisting the `traceparent` header alongside each job row would
enable full end-to-end traces across the queue. Requires schema
migration on `async_job`, plus capture-at-submit / restore-at-execute
plumbing.

### Removed-during-cleanup plugins that may need replacement

- **OVM3**: removed. Recommended replacement is the existing KVM plugin
  or a new oVirt plugin if needed (per the project decisions).
- **Nicira NVP / Juniper Contrail**: superseded by NSX / Tungsten which
  are kept.

### Phase 5 — new capabilities (untouched)

The original audit's Phase 5 ideas (container workloads, OVN networking,
eBPF, improved RBAC, API v2) are all open. Each is a substantial
greenfield effort beyond the scope of this modernization pass.

## Stats

| | Before | After |
|--|--------|-------|
| Lines of code (total) | ~2,151,000 | ~2,008,000 |
| Dead plugins | 19 | 0 |
| Java version | 11 | 21 |
| Spring | 5.3 (EOL) | 6.1 |
| Jetty | 9.4 (EOL) | 11.0 |
| Java EE namespace | `javax.*` | `jakarta.*` |
| Unit tests | ~10,500 | **10,741** (0 failures) |
| Observability endpoints | 0 | 3 (health, metrics, traces) |
| Deployment story | RPM/DEB | Docker + Helm + RPM/DEB |
| Critical security CVEs | 10+ | 0 (in updated deps) |
