# Non-Web Backend Cleanup and Java 21 Audit

**Date:** 2026-05-22
**Branch:** `modernize-2026`
**Scope:** Everything outside `web/`. The new Next.js app is intentionally
excluded because it is still active in-flight work.

This document consolidates the non-web findings from the unused-code and
duplication audit, then adds a Java 21 modernization pass. Treat it as the
backend cleanup queue, not as proof that every candidate is safe to delete
without tests. CloudStack still uses Spring wiring, API command discovery,
reflection, generated/serialized DTOs, plugin loading, and long-lived database
compatibility paths.

## CloudStack 5.0 Compatibility Stance

This modernization project is framed as **CloudStack 5.0**, not as a strict
4.x-compatible continuation. Apache CloudStack 4.x can keep evolving in
parallel for compatibility users, while this fork is allowed to remove baggage
that has accumulated since the 4.0 era.

Consequences for this audit:

- API, plugin, CLI, packaging, and internal interface breaks are acceptable when
  they materially simplify the platform or unlock the modernization roadmap.
- Breaking changes must be documented with migration notes.
- Where the capability is still relevant, provide an equivalent or replacement
  API/workflow rather than keeping the old interface shape by default.
- Database compatibility stubs should be evaluated case by case: keep them for
  practical upgrade/import paths, but do not treat every 4.x artifact as
  load-bearing forever.
- Cleanup slices should prefer the CloudStack 5.0 design over preserving 4.x
  behavior unless there is a current product reason to keep the behavior.

## Current Java Baseline

The fork has moved beyond upstream's source baseline:

- Apache `cloudstack/main` still has `<cs.jdk.version>11</cs.jdk.version>` in
  `pom.xml` as observed from the GitHub mirror and local `upstream/main`.
- Apache CloudStack 4.20 added Java 17 runtime support, and 4.22 documentation
  says management server and KVM agent require Java 17.
- This fork now uses `<cs.jdk.version>21</cs.jdk.version>` in root `pom.xml`
  and `.java-version` is `21`.

The first Java 21 baseline pass has aligned build, packaging, and developer
docs. Follow-up cleanup remains:

- Root `pom.xml`, developer POMs, LDAP tests, and systemd defaults still carry
  some `-noverify`, `--add-opens`, and `--add-exports` flags. Remove only after
  focused tests prove each reflective path is gone.
- Mockito inline mocking now runs through an explicit test JVM `-javaagent`
  configured via `maven-dependency-plugin:properties`; this avoids Java 21
  self-attach failures while preserving static/final mocking tests.

## Java 21 Migration Work

### Baseline Update Slice

1. Set `.java-version` to `21`.
2. Set root `<cs.jdk.version>` to `21`.
3. Change Maven compiler configuration from source/target to
   `<release>${cs.jdk.version}</release>` unless a specific module genuinely
   needs different cross-compilation behavior.
4. Remove the ONTAP module's source/target `11` override or align it with the
   root property.
5. Update Docker images from `eclipse-temurin:17-*` to `21-*`.
6. Update Debian build dependencies to prefer OpenJDK 21 while keeping any
   deliberate distro compatibility fallback explicit.
7. Update Java 17 references in developer and CI docs.
8. Run a full build on Java 21 before changing language idioms.

Expected first verification:

```bash
JAVA_HOME=<jdk21> mvn -T 4 -DskipTests install
JAVA_HOME=<jdk21> mvn -T 4 test
```

Status on 2026-05-22: the Java 21 baseline slice completed `.java-version`,
root compiler release, Docker, Debian, ONTAP test stack alignment, AspectJ
1.9.19 alignment, and developer/CI docs. The full reactor passed under
OpenJDK 21 with `mvn -B -ntp install -DskipTests -T4`.

### Java 21 Compatibility Risks To Check

- Internal JDK APIs:
  - Done: `sun.security.x509.X509CertImpl` in direct-download certificate
    handling and RDP console code was replaced with public certificate APIs.
  - Done: `sun.security.provider.MD4` in RDP NTLM code was replaced with
    Bouncy Castle `MD4Digest`.
  - Still open: `com.sun.net.httpserver.*` in console proxy and Prometheus
    exporter should be contained or replaced in a later server slice.
- Reflective construction:
  - Done for low-risk production sites found in the Java 21 scout. Remaining
    direct `Class.newInstance()` calls are test-only and tracked in
    `docs/JAVA21_COMPAT_SCOUT.md`.
- Finalization:
  - `DirectAgentAttache`, `ConnectedAgentAttache`, `TransactionLegacy`,
    `ConnectionConcierge`, and `SearchBase` still use `finalize()` style
    cleanup. Move to explicit close/cleanup or `Cleaner` where needed.
- JVM flags:
  - Root `argLine`, Dockerfile, developer POMs, LDAP tests, and systemd defaults
    all carry `--add-opens` / `--add-exports`. Revalidate under Java 21 and
    remove only after tests prove the reflective path is gone.
  - Done for Mockito: Surefire/Failsafe now attach `mockito-core` explicitly as
    a Java agent so Java 21 test runs do not depend on dynamic self-attach.
- Preview features:
  - Do not use Java 21 preview features in production code. Avoid string
    templates, unnamed patterns, unnamed classes, scoped values, and structured
    concurrency until they are final in the chosen target.

## Unused or Dead Non-Web Code

### High Confidence

These are the best first cleanup candidates.

Status update on 2026-05-22: the stale ngui RAT excludes and the smokedev
Dockerfile copy of the removed test backup directory were cleaned up in the
`backend-deadcode-stale-build-artifacts` slice. They are no longer pending
items in this queue.

| Area | Candidate | Evidence | Verification |
|---|---|---|---|
| OVM3 leftovers | `scripts/vm/hypervisor/ovm3/` | The Java OVM3 plugin is gone; only DB compatibility enum/fields should stay. | Delete scripts, run RAT/checkstyle/build. |
| `AnnotationManagerImpl` | private `isDomainAdminAllowedType(EntityType)` | Static search found only the definition. | Remove with annotation permission tests. |
| `IndirectAgentLBServiceImpl` | private `getAllAgentBasedHostsInDc(long,long)` | Static search found only the definition. | Remove with agent LB tests. |
| `OutOfBandManagementServiceImpl` | private `getOutOfBandManagementHostLock(long)` | Static search found only the definition. | Remove with OOBM sync/lock tests. |
| `NetworkACLManagerImpl` | private `containsIpv6Cidr(List<String>)` | Static search found only the definition. | Remove with IPv6 ACL tests. |
| `VolumeServiceImpl` | private `waitForTemplateDownloaded(...)` | Static search found only the definition. | Remove with template-to-volume tests. |
| `RolePermissionsDaoImpl` | private `updateSortOrder(...)` | Static search found only the definition. | Verify role permission reorder/move behavior. |
| `VlanDaoImpl` | private `findNextVlan(long, VlanType)` | Static search found only the definition. | Verify VLAN/public IP allocation tests. |
| `LibvirtComputingResource` | private `isSnapshotSupported()` | Static search found only the definition. | Verify KVM snapshot tests. |
| `ResourceCountDaoImpl` | private `baseSqlCountComputingResourceAllocatedToAccount` and `executeSqlCountComputingResourcesForAccount(...)` | Field and helper appear only locally. | Verify quota/resource count tests. |

### Sensitive Candidates

These are likely dead but should be treated with extra care.

| Area | Candidate | Why sensitive |
|---|---|---|
| API security | `ApiDispatcher#doAccessChecks(...)` | Active checks appear to live in `ParamProcessWorker`, but this is access-control code. Trace all dispatch paths before removal. |
| API serialization | `ResponseObjectTypeAdapter#getGetMethod(...)` and `getGetMethodName(...)` | Reflection/serialization-adjacent code can be indirectly load-bearing. |
| API docs | `ApiXmlDocWriter#zipDir(...)` and `addDir(...)` | May only be used by manual doc-generation flows. |
| `NetworkServiceImpl` | private `canIpsUseOffering(...)` | Duplicate of the active method in `NetworkMigrationServiceImpl`; appears unused in `NetworkServiceImpl`, but network offering validation is high-impact. |

### Non-Code Orphans and Fork-Only Cleanup

The earlier cleanup audit remains valid outside `web/`:

- `tools/whisker/`, `tools/transifex/`, `tools/bugs-wiki/`, `tools/jira/`,
  `tools/eclipse/`, `tools/devcloud4/`, and `tools/devcloud-kvm/` appear
  upstream-process/dev-environment specific. Removing `devcloud4` and
  `devcloud-kvm` also requires editing `tools/pom.xml`.
- Upstream-only Apache CI workflows guarded by
  `github.repository == 'apache/cloudstack'` can be removed or archived if the
  fork has its own CI.
- `scripts/vm/hypervisor/ovm3/` should be removed separately from the OVM3 DB
  compatibility enum and schema fields, which should stay.
- `test/integration/broken/` should be fixed or removed by explicit project
  decision.

## Repeated Backend Code

### Highest Value Refactor Targets

| Pattern | Examples | Suggested extraction | Risk |
|---|---|---|---|
| IPv4 range validation | `PodServiceImpl`, `VlanServiceImpl`, related `NetworkServiceImpl` gateway/netmask checks | `Ipv4RangeValidator` or `IpRangeValidationService` | Medium: preserve exact error behavior. |
| API success/error boilerplate | Many delete/cancel commands build `SuccessResponse` or throw `ServerApiException` | `ApiCommandSuccessHandler` | Low-medium. |
| Network element API exception mapping | NetScaler and Palo Alto add/configure commands repeat exception mapping | `NetworkElementApiExecutor` | Low-medium. |
| Tungsten Fabric command boilerplate | Create/delete/list/apply commands repeat owner, command-name, event-description, list-response patterns | `BaseTungstenFabricCmd` and list response helper | Low-medium. |
| Usage DAO date binding | `UsageIPAddressDaoImpl`, `UsageVolumeDaoImpl`, `UsageStorageDaoImpl`, and related DAOs | `UsageDateRangeBinder` | Low-medium: parameter order differs. |
| Query `Pair<List<T>, count>` conversion | Repeated in `QueryManagerImpl` and query services | `ListResponseBuilder.fromPair(...)` | Low. |
| `canIpsUseOffering` | `NetworkServiceImpl` and `NetworkMigrationServiceImpl` | `NetworkOfferingIpCompatibilityService` | Medium: network offering upgrade behavior. |
| Storage access group orchestration | `StorageAccessGroupServiceImpl` explicitly notes duplicated direct/orchestrator method bodies | Shared private implementation or service split | Medium: Phase 4 extraction compatibility. |
| Listener no-op methods | Agent/listener implementations repeat identical no-op bodies | Java 17+ default interface methods or `NoopAgentListener` | Medium: broad interface impact. |
| Network element lifecycle no-ops | Security group, Baremetal, VMware, DNS notifier, OpenDaylight, NSX/Netris-style elements | Default interface methods or adapter base | Medium. |
| Upgrade DAO boilerplate | Many `Upgrade*` classes repeat version/script/null migration methods | `DbUpgradeScriptLoader` or `DbUpgradeDescriptor` base | Medium-high: upgrade paths are sensitive. |
| Resource detail VOs | Many VOs duplicate `id/resourceId/name/value/display` mappings | Mapped superclass or smaller helper | High: JPA mapping risk. |

### Existing Intentional Duplication from Prior Slices

The codebase contains comments marking duplication introduced during safe
god-class decomposition. These should be revisited only after the extracted
services have stabilized:

- `StorageAccessGroupService` / `StorageAccessGroupServiceImpl` direct vs
  orchestrator bodies.
- `UserUpdateServiceImpl` helpers copied from `AccountManagerImpl`.
- `VlanServiceImpl`, `ZoneServiceImpl`, and `PortableIpRangeServiceImpl`
  helpers copied from `ConfigurationManagerImpl`.
- `VpcOfferingCrudServiceImpl` constants copied per playbook.
- `PhysicalNetworkManagementServiceImpl` shared DAO comments.

## Java 21 Modernization Opportunities

The repo should not be mechanically rewritten. Use Java 21 features where they
make repeated or error-prone code smaller and clearer.

### Pattern Matching for `instanceof` and `switch`

Scan result: roughly 1,600 non-web `instanceof` hits.

Good targets:

- Command dispatch chains in secondary storage resources and storage command
  handlers.
- Data object type branching in `AncientDataMotionStrategy`,
  `StorageSystemDataMotionStrategy`, `SecondaryStorageServiceImpl`, and
  image/volume object callbacks.
- API/entity access checks such as `DomainChecker` once tests cover each entity
  branch.

Avoid or defer:

- Serialization/deserialization adapters.
- Branches where type checks are deliberately ordered for compatibility.
- Public API response DTOs unless tests cover wire output.

### Records and Record Patterns

Best candidates are private immutable holder classes, not JPA entities, API
responses, command payloads, or Gson/Jackson-reflected objects.

Good targets:

- Private usage parser holders: `VMInfo`, `IpInfo`, `VolInfo`, `NetworkInfo`,
  `PFInfo`, `LBInfo`, `VUInfo`, `NOInfo`, `SGInfo`, `StorageInfo`.
- Small private job/context holders such as `DownloadJob`, `UploadJob`,
  `ActiveTaskRecord`, `VcenterData`, `NetworkCopy`, and simple result holders.
- Test-only fixture holder classes.

Avoid:

- JPA `VO`/DAO model classes.
- API command/response classes.
- Agent command payloads that are serialized over the wire.
- Classes requiring mutable JavaBean setters for frameworks.

### Sequenced Collections

Scan result: roughly 1,500 non-web `get(0)`, `get(size() - 1)`, and related
first/last collection access hits.

Good targets:

- Repeated "first result after non-empty check" helpers.
- Tungsten model response builders using
  `referredName.get(referredName.size() - 1)`.
- Utility classes that already require ordered lists.

Rules:

- Replace with `getFirst()` / `getLast()` only where the static type is a Java
  21 sequenced collection type and tests cover empty-list behavior.
- Do not hide missing empty checks; add explicit validation where needed.

### Streams

Scan result: 360+ non-web `Collectors.toList()` / `Collectors.toSet()` sites.

Use `stream().toList()` only where the returned list is not mutated. It returns
an unmodifiable list, while `Collectors.toList()` historically produced a
mutable list in practice. Do not mechanically replace set collectors because
there is no `Stream.toSet()`.

Good first targets:

- API response ID/UUID lists that are immediately passed onward.
- Log/debug string construction.
- Query list transformations where the returned collection is read-only.

### Text Blocks and Formatted Strings

Scan result: 600+ non-web `StringBuilder` / `StringBuffer` construction sites.

Good targets:

- Long SQL strings in DAOs.
- XML snippets in KVM/libvirt command wrappers.
- Multi-line config-drive, metadata, or cloud-init templates.

Avoid:

- Tight loops accumulating large strings.
- Security-sensitive command-line generation until escaping rules are clear.

### Virtual Threads

Java 21 virtual threads are useful for blocking I/O fan-out, but they are not a
blanket replacement for CloudStack's scheduled scanners and bounded queues.

Good candidates:

- Short-lived executor fan-out in `IndirectAgentLBServiceImpl` setup/migration
  helpers.
- Webhook delivery and alert/email sending if back-pressure remains explicit.
- Script or command wrappers that block on external processes, once timeouts and
  cancellation are tested.
- IPMI/out-of-band command execution, where the driver blocks on external
  process/network operations.

Avoid first:

- Scheduled background scanners.
- Agent task pools where queue bounds are part of flow control.
- Database transaction workers unless connection-pool pressure is explicitly
  tested.
- NIO/selector loops.

### Finalization and Resource Cleanup

Modern Java has moved away from finalization. Before or alongside Java 21, clean
these up:

- `DirectAgentAttache#finalize`
- `ConnectedAgentAttache#finalize`
- `TransactionLegacy#finalize`
- `ConnectionConcierge#finalize`
- `SearchBase#finalize`

Preferred replacements are explicit lifecycle methods, `AutoCloseable`, and
`Cleaner` only where last-resort cleanup is still needed.

## Proposed Work Packages

### A. Java 21 Baseline

Update build/runtime/config/docs only. Do not modernize source code in the same
branch. Acceptance: full Java 21 build and targeted module tests pass.

### B. Decomposition Shim Removal

Revisit the god-class decomposition slices after the extracted services have
stabilized. Many Phase 4 extractions intentionally left manager-level shims so
call sites could be migrated safely in small batches.

For CloudStack 5.0, the cleaner end state is:

1. Move internal call sites to the extracted service interfaces/classes.
2. Keep only genuinely public or compatibility-required facade methods on the
   old manager classes.
3. Delete shim methods once all non-reflective call sites have moved.
4. Document any broken internal API paths and the replacement service location.
5. Add or update focused tests around each migrated call path before deleting
   the shim.

Suggested first targets are the extraction areas already called out above:

- `StorageAccessGroupServiceImpl` direct/orchestrator duplicated paths.
- `UserUpdateServiceImpl` helpers copied from `AccountManagerImpl`.
- `VlanServiceImpl`, `ZoneServiceImpl`, and `PortableIpRangeServiceImpl`
  helpers copied from `ConfigurationManagerImpl`.
- `VpcOfferingCrudServiceImpl` constants copied per playbook.
- `PhysicalNetworkManagementServiceImpl` DAO helper comments.

Acceptance: each slice replaces downstream call sites with the extracted
service, proves behavior with focused tests, then removes the now-unused manager
shim or marks it as a deliberate public facade.

### C. High-Confidence Dead Code Cleanup

Remove private unused helpers, OVM3 scripts, and stale Java config. Acceptance:
relevant unit tests plus full compile. The stale ngui RAT excludes and the
smokedev Dockerfile copy of the removed test backup directory have already been
cleaned up.

### D. Repeated Backend Code Extraction

Start with cohesive, low-to-medium risk helpers:

Status on 2026-05-22: the first backend refactor pass completed the planned
low-to-medium risk helper extractions below. Follow-up slices should now
continue from broader call-site migration, shim removal, and Java 21 idiom
modernization rather than recreating these helpers.

1. `Ipv4RangeValidator`: extracted for pod and VLAN IPv4 range checks, with
   focused validator coverage.
2. `BaseCmd#setSuccessResponse(...)`: added as the shared success-response path
   for simple API commands, including dedicated-resource release commands.
3. `NetworkElementApiExecutor`: extracted for Palo Alto and NetScaler command
   exception mapping.
4. `UsageDateRangeBinder`: extracted and applied across the first two usage DAO
   batches.
5. `TungstenFabricAsyncCmd`: extracted shared Tungsten delete-command success
   response handling.
6. `ListResponseBuilder`: extracted for query-service `Pair<List<T>, count>`
   response construction across the first two query-service batches.

### E. Java 21 Idiom Passes

Run only after Java 21 is the green baseline:

1. Record conversions for private immutable holders.
2. Pattern matching for command/data-object dispatch.
3. Safe `stream().toList()` conversions.
4. Sequenced collection first/last cleanup.
5. Text block cleanup for SQL/XML/config literals.
6. Virtual-thread experiments behind focused tests.

## Source Notes

- Apache CloudStack GitHub mirror,
  [`pom.xml` on `main`](https://github.com/apache/cloudstack/blob/main/pom.xml),
  shows upstream `cs.jdk.version` as `11`.
- Apache CloudStack
  [upgrade docs](https://docs.cloudstack.apache.org/en/latest/upgrading/upgrade/upgrade_java_17_notes.html)
  state Java 17 support was added in 4.20 and CloudStack 4.22 requires Java 17
  for management server and KVM agent.
- The [OpenJDK JDK 21 project page](https://openjdk.org/projects/jdk/21/)
  lists sequenced collections, record patterns, pattern matching for switch,
  and virtual threads.
- Oracle's
  [Java language changes summary](https://docs.oracle.com/en/java/javase/21/language/java-language-changes-summary.html)
  lists record patterns and switch pattern matching as permanent Java 21
  language features.
- Mockito's
  [Java 21 inline mocking guidance](https://javadoc.io/static/org.mockito/mockito-core/5.16.1/org.mockito/org/mockito/Mockito.html#0.3)
  recommends explicit Java-agent setup for Maven Surefire.
