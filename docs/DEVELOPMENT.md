# Development guide

Practical notes for working on the fork. Apache upstream contribution
guidance lives in `CONTRIBUTING.md`; this file documents how to build,
test, and extend this particular fork.

## Prerequisites

| Tool | Version |
|------|---------|
| JDK | 17 (Temurin, OpenJDK) |
| Maven | 3.9+ |
| Python | 3.10+ (for Marvin integration tests) |
| MySQL | 8.0+ (for DB tests and runtime) |

On macOS:

```bash
brew install openjdk@17 maven
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
```

On Ubuntu 24.04:

```bash
sudo apt-get install -y openjdk-17-jdk maven mysql-server python3 python3-pip
```

## Build

Full reactor, skipping tests (fastest for iterating):

```bash
mvn -B -ntp install -DskipTests -T1C
```

With tests (~3 minutes on a modern laptop):

```bash
mvn -B -ntp install -T1C
```

Just one module and its dependencies:

```bash
mvn -B -ntp install -DskipTests -pl server -am
```

Resume after a partial failure:

```bash
mvn -B -ntp install -DskipTests -rf :cloud-server
```

## Testing

Unit tests use JUnit 4 (older modules) and JUnit 5 (newer code). Both
run via Surefire.

Run all server-module unit tests:

```bash
mvn -B -ntp test -pl server
```

Run a single test class:

```bash
mvn -B -ntp test -pl server -Dtest=ConfigurationValueValidatorTest \
    -Dsurefire.failIfNoSpecifiedTests=false
```

Run a single method:

```bash
mvn -B -ntp test -pl server \
    -Dtest=ConfigurationValueValidatorTest#validIp4AcceptedForIpConfig \
    -Dsurefire.failIfNoSpecifiedTests=false
```

Surefire reports land at `<module>/target/surefire-reports/*.txt`.

### Marvin (integration tests)

Marvin integration tests require a running simulator. See the upstream
docs in `tools/marvin/README.md`. They run in CI via
`.github/workflows/ci.yml` against a Simulator-backed datacenter.

## Code quality checks

Local advisory runs (won't fail the build):

```bash
mvn -B -ntp spotbugs:spotbugs -DskipTests           # writes target/spotbugsXml.xml
mvn -B -ntp pmd:pmd -DskipTests                     # writes target/pmd.xml
mvn -B -ntp -P quality \
    org.owasp:dependency-check-maven:check \
    -DskipTests -DfailBuildOnCVSS=11                # writes target/dependency-check-report.html
```

CI runs all three on every PR and uploads the reports as artifacts.

## CI

Two workflows that matter for the fork:

| Workflow | Trigger | What it does |
|----------|---------|--------------|
| `fork-ci.yml` | push/PR | Build + unit tests (required) + SpotBugs/PMD/OWASP (advisory) |
| `build.yml` | push/PR | Disabled on the fork; runs only on `apache/cloudstack` |

The upstream `ci.yml` (Marvin simulator integration tests) is gated to
`apache/cloudstack` because it depends on the proprietary
`shapeblue/cloudstack-nonoss` repo. To run integration tests on the fork
locally, follow `tools/marvin/README.md`.

## Project layout

The codebase is a large Maven multi-module reactor. The most relevant modules:

| Path | Purpose |
|------|---------|
| `api/` | Public API command/response definitions (`BaseCmd` and friends) |
| `server/` | Management server core (3,170+ unit tests live here) |
| `engine/orchestration/` | VM/network lifecycle orchestration |
| `engine/schema/` | DB schema VOs and DAOs |
| `framework/` | Cross-cutting: DB, Spring, jobs, events, security |
| `plugins/hypervisors/{kvm,vmware,xenserver,...}/` | Hypervisor integrations |
| `plugins/network-elements/` | Network providers (NSX, OVS, OpenDaylight, etc.) |
| `plugins/storage/{volume,image,object}/` | Storage providers |
| `services/console-proxy/` | VNC/RDP console proxy |
| `client/` | Management server WAR assembly |
| `ui/` | Vue 3 web UI |
| `tools/marvin/` | Python integration test framework |
| `utils/` | Shared utilities |

## God class decomposition pattern

We're incrementally carving down large `*ManagerImpl` classes. The
established pattern:

1. **Identify a pure helper** — no DAO calls, no field state. Logger and
   constants are fine; static utility imports are fine.
2. **Move it to a focused static utility class** (e.g.
   `ConfigurationValueValidator` for configuration concerns).
3. **Keep the original instance method as a one-line delegating wrapper**
   so any subclasses or Mockito spies in existing tests continue working.
4. **Add focused unit tests** for the new utility class — they don't
   need to bootstrap Spring.

See commits `98b13ee`, `4e89292`, `4d5d955` for examples of this pattern.

When the orchestration of helpers must stay on the instance (e.g.
because tests spy on the building blocks), do that — only move pure
leaves.

## Observability hooks

The management server exposes a modern operational surface. See
`docs/OBSERVABILITY.md` for details.

| Endpoint | Use |
|----------|-----|
| `/health/live` | Kubernetes liveness probe |
| `/health/ready` | Kubernetes readiness probe |
| `/metrics` | Prometheus scrape (JVM + process + HTTP) |

Environment-driven settings:

| Variable | Purpose |
|----------|---------|
| `CLOUDSTACK_LOG_FORMAT=json` | ECS-formatted JSON logs |
| `OTEL_EXPORTER_OTLP_ENDPOINT=...` | OpenTelemetry trace export |
| `OTEL_TRACES_SAMPLER_ARG=0.1` | Sampling rate (0.0–1.0) |

## Common pitfalls

- **Mockito spies and static delegations**: if you replace an instance
  method with a call to a static helper, any existing test that
  `doReturn(...).when(spy).theInstanceMethod(...)` will silently no-op
  because the spy isn't intercepted. Keep the instance method as a
  delegating wrapper.
- **DB-backed validations**: methods that touch DAOs aren't pure. Extract
  the value-shape check as a pure helper that returns an error message,
  and keep the DB lookup in the instance method.
- **`getCidrSize(String)` in NetUtils**: takes a *netmask* string, not a
  CIDR with `/N`. Don't confuse with parsing the prefix length from
  `"1.2.3.0/24"`. Either inline `Integer.parseInt(cidr.split("/")[1])`
  or check whether you actually want netmask conversion.
- **Jakarta vs javax**: this fork is on Jakarta EE 9+ (`jakarta.*`).
  Only Java SE packages (`javax.naming`, `javax.crypto`, `javax.net`,
  `javax.management`, `javax.script`, `javax.sql`, most of `javax.xml`)
  remain on the `javax` namespace.

## Reporting bugs / proposing changes

Open issues and PRs in this fork's repository. For upstream issues,
file at <https://github.com/apache/cloudstack/issues>.
