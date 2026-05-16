# CloudStack microservices roadmap

Investigation summary for the multi-phase split of the management-server
monolith into separately-deployable services. Phase 4 (god-class
decomposition into Spring components) is foundational; this document
covers what comes next.

## Headline

The Vue 3 UI at [`ui/`](../ui) is **already a fully separated SPA** —
a `cloudstack-ui` RPM target and a Vue + nginx Dockerfile already exist
in the tree. The management server simply also happens to serve the
built `ui/dist/` as static assets at the root context. Splitting the UI
into its own deployable is a **trivial-to-small** packaging/topology
change, not a code rewrite. The only real engineering work is making
the reverse-proxy / CORS / session-cookie story production-grade.

## Module map (~142 Maven modules)

| Group | Modules | Role |
|---|---|---|
| Core API surface | `api`, `core`, `utils`, `engine/api`, `engine/components-api` | DTOs, command objects, SPI |
| Orchestration / server | `server`, `engine/orchestration`, `engine/service`, `engine/schema` | The monolith — VM/Network/Storage/Cluster managers live in `server/` |
| Storage engines | `engine/storage/{cache,configdrive,datamotion,image,object,snapshot,volume}` | Already plugin-shaped |
| UI | `ui/` (Vue, not a Maven module) + `client/` (Jetty bootstrap + WAR) | Web UI + the JSVC daemon |
| Framework | `framework/{agent-lb,ca,cluster,config,db,direct-download,events,extensions,ipc,jobs,managed-context,quota,rest,security,spring/*}` | Shared infra |
| Standalone services | `usage/`, `services/console-proxy/{server,rdpconsole}`, `services/secondary-storage/{controller,server}` | Already separate processes today |
| Agent | `agent/` | Runs on hypervisor hosts |
| Plugins (84) | `plugins/{acl,affinity-group-processors,alert-handlers,api,backup,ca,database,dedicated-resources,deployment-planners,drs,event-bus,ha-planners,hypervisors,integrations,maintenance,metrics,network-elements,outofbandmanagement-drivers,storage-allocators,storage,user-authenticators,user-two-factor-authenticators}` | Loaded into management server via Spring |

## UI: how it's currently served

- **Code**: [`ui/`](../ui) — Vue 3 + Vue CLI 4 + webpack 4 + Ant Design Vue.
  472 JS/Vue source files, ~6.6 MB `src/`, ~3.7 MB `public/`. Builds
  to `ui/dist/`.
- **In Jetty**: [`client/src/main/java/org/apache/cloudstack/ServerDaemon.java:267-298`](../client/src/main/java/org/apache/cloudstack/ServerDaemon.java)
  mounts a single `WebAppContext` at `/client` (the API). UI static
  files are copied into the same WAR at packaging time
  ([`packaging/el8/cloud.spec:283-287`](../packaging/el8/cloud.spec)):
  `cp -r ui/dist/* /usr/share/cloudstack-management/webapp/`.
- **`web.xml`** ([`client/src/main/webapp/WEB-INF/web.xml:89-107`](../client/src/main/webapp/WEB-INF/web.xml))
  maps servlets for `/api/*`, `/console`, `/health/*`, `/metrics`;
  everything else falls through to Jetty's default static handler.
  `ServerDaemon.java:291-294` redirects `/` → `/client/`.
- **Already-existing separate deployable**: `cloudstack-ui` RPM target
  ([`packaging/el8/cloud.spec:332-336`](../packaging/el8/cloud.spec))
  ships `ui/dist/` standalone. `ui/Dockerfile` produces an
  `nginx:alpine` runtime image and `ui/nginx/default.conf` proxies
  `/client/` to a separate management server. The standalone story is
  already in the tree, just not the default.
- **API calls**: pure REST/HTTP to `${apiBase}` from a runtime-loaded
  `ui/public/config.json` (symlinked from `/etc/cloudstack/ui/config.json`
  in the RPM) — overridable without rebuilding.
- **Auth**: hybrid session cookie + double-submit token.
  `ui/src/api/index.js:43-45` pulls `sessionkey` from `localStorage` or
  the `sessionkey` cookie and appends it as a query param. JSESSIONID
  is the browser's. `ui/src/store/modules/user.js:228-229,286-287`
  writes the session key. **This is the constraint for the UI split**:
  cookies need either same-origin to the API or a reverse-proxy that
  bridges them.

## Coupling assessment

**Coupling is shallow.** Specifically:

- `ServerDaemon.java:280-284` — webapp dir is configurable via
  `server.properties` `webapp.dir`. Removing the UI assets produces an
  API-only WAR; nothing else in `web.xml` depends on UI files.
- No backend Java code references HTML files, templates, or UI asset
  paths beyond Jetty's default static-resource handler.
- No JVM-shared state between UI and backend (UI is a pure browser SPA).
- Only remaining "coupling" is the cookie domain + session story.
  Three valid topologies:
  1. **Reverse proxy** (nginx / Envoy / Traefik) serving UI from `/`,
     proxying `/client/*` to management. Same-origin → cookies just
     work. *Preferred.*
  2. **Separate origins with CORS** — requires backend CORS config +
     `SameSite=None; Secure` cookies; auditable but more moving parts.
  3. **Move auth to JWT/bearer tokens** — bigger lift, but unblocks
     federated / multi-cluster deployments later.

## Other natural microservice seams (post-UI)

1. **Usage server** ([`usage/pom.xml`](../usage/pom.xml), `cloud-usage`).
   Already a separate JVM/daemon (`cloudstack-usage.service`). Mostly a
   packaging + observability + API-fronting cleanup, not an extraction.
   Lowest-risk follow-up.
2. **Async-job worker pool** ([`framework/jobs/`](../framework/jobs),
   [`engine/orchestration/`](../engine/orchestration)). The hottest part
   of the management server. Splitting "API frontend (stateless, replicas)"
   from "job engine (workers)" unlocks horizontal scale without touching
   domain code. Job queue already uses MySQL + cluster lock manager.
3. **Plugin classes as services**:
   - **Event bus** (`plugins/event-bus/{inmemory,kafka,rabbitmq,webhook}`) —
     events already abstracted; sidecar that publishes domain events to
     external consumers.
   - **Metrics/observability** (`plugins/metrics/`,
     `plugins/integrations/prometheus/`) — already runs alongside; could
     be lifted out behind the existing `/metrics` Prometheus endpoint.
4. **Console proxy** ([`services/console-proxy`](../services/console-proxy)).
   Already a separate JVM but tightly coupled via `ConsoleProxyServlet`
   in the management WAR (`web.xml:71-75`). Cleanup pass to make it
   consumable as an independent service.
5. **Hypervisor connector subset**
   (`plugins/hypervisors/{kvm,vmware,xenserver,hyperv,baremetal,external,simulator}`).
   Long-term: each hypervisor's resource manager could become a
   per-cluster scale-out service talking to the orchestrator via
   gRPC/Kafka instead of in-JVM Spring beans. Major effort — defer
   until Phase 4 settles the boundaries.

## Phased roadmap

| Phase | Scope | Depends on | Effort |
|---|---|---|---|
| **5a — UI split** | Stop bundling `ui/dist` in the management WAR. First-class `cloudstack/ui` container + Helm sub-chart with nginx reverse-proxy template. Document the same-origin reverse-proxy pattern; add CORS allowlist in `ApiServlet` for the separate-origin path. CI builds & publishes both images. | None — Phase 4 continues in parallel. | **S** |
| **5b — Stateless API frontends** | Make the management WAR cleanly horizontally scalable: audit `HttpSession` use (`ApiSessionListener` today), move session state to an external store (Redis) or switch to opaque-token auth backed by DB. Add load-balanced ingress in Helm chart. | 5a complete | **M** |
| **5c — Job engine split** | Carve `framework/jobs/` + `engine/orchestration/` async dispatcher into its own deployment. API frontends enqueue; job workers run. Use existing MySQL job queue + cluster lock manager (or upgrade to Kafka for fan-out). | 5b done; Phase 4 close to landed so service boundaries stable. | **L** |
| **5d — Usage + console-proxy as proper services** | Tighten + document the already-separate `usage` and `console-proxy` daemons. Define stable internal APIs. Helm sub-charts. | Independent — can interleave with 5c. | **M** |
| **5e — Event-driven plugin boundary** | Promote `framework/events` + `plugins/event-bus/kafka` to default for cross-service comms. External consumers can subscribe without poking the management server. Sets up future per-hypervisor / per-zone services. | 5c done so workers already emit events. | **M** |

## Recommended first concrete action

Land a CI workflow that builds two separate images — `cloudstack-management`
(current Dockerfile **minus** `ui/dist` copying, plus a server-side CORS
allowlist read from a property) and `cloudstack-ui` (promote
`ui/Dockerfile`) — and a Helm sub-chart pairing the two behind a single
nginx-ingress with `/` → UI and `/client/*` → management. This delivers
the UI split with zero domain-code risk and unblocks every subsequent
phase.
