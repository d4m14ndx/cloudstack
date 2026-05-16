# Refactoring progress and roadmap

Tracking the incremental decomposition of god classes in the fork.

## ConfigurationManagerImpl — Pure-helper extraction phase complete

The "Phase 4 god class slices" series extracted every pure helper that
was safely extractable without changing behavior or breaking the
existing test/spy patterns. See `docs/DEVELOPMENT.md` for the pattern.

### Slices shipped

| # | Commit | What was extracted |
|---|--------|--------------------|
| 1 | `98b13ee` | 10 validation helpers (`validateValueType`, `validateRange*`, `shouldValidateConfigRange`, etc.) |
| 2 | `4e89292` | `shouldEncryptValue`, `maskEventValueIfEncrypted`, `parseConfigurationTypeIntoString` |
| 3 | `4d5d955` | `isIpConfigName`, `validateIpConfigValue`, `validateConflictingConfigValue` |
| 4 | `9628dcc` | `validateCidrList` (+ DEVELOPMENT.md) |
| 5 | `86e74b5` | Four validation sets → immutable static constants |
| 6 | `ff3934e` (part 1) | VLAN URI parsing → `BroadcastDomainType.parseVlanNumberFromUri` |
| 7 | `ff3934e` (part 2) | `validateSpecificConfigurationValues` |

### Where things landed

- **`ConfigurationValueValidator`** (540 lines, 64 unit tests) — all
  pure helpers, no Spring/DAO state. Anyone can call these without
  bootstrapping the container.
- **`BroadcastDomainType.parseVlanNumberFromUri`** — VLAN URI parsing
  lives with the rest of the URI scheme handling.

### Size impact

| State | Lines | Helpers in pure utility |
|-------|-------|-------------------------|
| Before slice 1 | 9,509 | 0 |
| After slice 7 | **9,286** | **64 tests over 16 pure methods + 4 immutable sets** |
| Net change | **−223 lines** | All gain in testability |

The instance methods on `ConfigurationManagerImpl` are kept as
delegating one-liners so Mockito spies and subclass overrides continue
to work without modification.

## What's left in ConfigurationManagerImpl (and why pure-helper extraction is exhausted)

The remaining code is **not pure** — it depends on injected DAOs and
manager beans. Further decomposition requires a different pattern:
**Spring-component extraction** (move a coherent chunk into its own
`@Component` with its own DAO injections).

Inventory of remaining decomposition targets:

| Logical unit | ~Lines | Why it can't be pure-extracted |
|--------------|--------|--------------------------------|
| Zone CRUD (`createZone`, `editZone`, `deleteZone`, …) | ~800 | Uses `_zoneDao`, `_clusterDao`, `_alertMgr` |
| Pod CRUD (`createPodIpRange`, `deletePodIpRange`, `updatePodIpRange`, `checkPodAttributes`) | ~600 | Uses `_podDao`, `_privateIpAddressDao` |
| Network/VLAN management (VLAN range CRUD) | ~700 | Uses `_vlanDao`, `_nicDao`, `_networkDao` |
| Service offering CRUD | ~700 | Uses `_serviceOfferingDao`, `_diskOfferingDao` |
| Disk offering CRUD | ~500 | Uses `_diskOfferingDao` |
| `updateConfiguration` orchestration | ~200 | Calls many DAOs, encrypts via Spring beans, fires events |
| `resetConfiguration` orchestration | ~150 | Same |
| `getConfigurationGroupAndSubGroup` | ~100 | DB-backed |

### Next-pattern playbook (Spring-component extraction)

1. **Pick a domain unit** — e.g., Pod IP range management.
2. **Create a new `@Component` class** (`PodIpRangeService` or similar)
   that takes the DAOs and managers it needs via constructor injection.
3. **Move the methods over** (and any tightly-coupled private helpers).
4. **In `ConfigurationManagerImpl`, replace the moved methods with
   delegating calls** through an injected `PodIpRangeService`.
5. **Write integration-style tests** for the new component.
6. **Verify no test or spy in `ConfigurationManagerImplTest` breaks.**

This is a heavier per-slice effort than pure-helper extraction (Spring
wiring, possible @Transactional boundaries, integration tests rather
than pure unit tests) — appropriate for a longer-lived branch with
proper review.

## UserVmManagerImpl — Spring-component extraction in progress

`UserVmManagerImpl` is the biggest god class in the codebase. Rather than
pure-helper extraction (which is unsuitable — see below), each slice
pulls a coherent unit of behaviour into its own `@Component` with its
own DAO injections. `UserVmManagerImpl` keeps one-line delegating
wrappers so the `UserVmManager` interface contract and existing test
spies still work.

### Slices shipped

| # | Component | Methods extracted | Dedicated tests |
|---|-----------|-------------------|-----------------|
| 1 | `VmGroupService` | 4 instance-group APIs + helpers (assign/unassign/CRUD) | 11 |
| 2 | `ServiceOfferingValidator` | 5 service-offering compatibility checks | 9 |
| 3 | `VmNicService` | 5 NIC APIs + helpers (add/remove/update/default) | 6 |
| 4 | `VmRootDiskValidator` | 4 root-disk validation/sizing methods | 9 |
| 5 | `VmUpdateValidator` | update-VM input validation + service-offering detail merging | 10 |
| 6 | `VmLeaseService` | VM lease validation, create-time apply, update-time apply, detail write | 20 |

Each slice keeps the orchestration that needs spy-verified inner calls
inside `UserVmManagerImpl` — the leaf methods become thin wrappers that
delegate to the extracted component, and the validator's own
implementation can call its helpers directly when invoked standalone.

## Other god classes in the codebase

These are the remaining `*ManagerImpl` classes over 5K lines, by size:

| File | Lines | Notes |
|------|-------|-------|
| `server/.../UserVmManagerImpl.java` | 10,068 | Biggest. Many natural decomposition seams (creation/start/stop/migration/destroy each ~200-500 lines). |
| `server/.../ConfigurationManagerImpl.java` | 9,286 | Pure-helper extraction phase complete (this doc). |
| `server/.../NetworkServiceImpl.java` | 6,484 | Network CRUD, VPC management. |
| `server/.../QueryManagerImpl.java` | 6,372 | API list query handlers. |
| `server/.../ManagementServerImpl.java` | 5,994 | Central management entry points. |
| `server/.../VolumeApiServiceImpl.java` | 5,513 | Volume lifecycle. |
| `server/.../ApiResponseHelper.java` | 5,878 | Response serialization (likely many static-extractable helpers). |

### Pure-helper extraction is the wrong tool for most remaining god classes

After surveying `ApiResponseHelper.java` (5,877 lines, 195 methods):
its few `public static` helpers (`getPrettyDomainPath`,
`setResponseIpAddress`, `populateOwner`, etc.) are already extracted
in place — they're callable directly without instantiating the class.
The remaining ~5,800 lines are instance methods that interleave DAO
lookups with response-DTO construction. Extracting the pure
post-lookup computation parts would yield 5-10 line slices for each
of 195 methods — high churn, low semantic value.

The same applies to `UserVmManagerImpl`, `NetworkServiceImpl`,
`QueryManagerImpl`, etc. These are Spring components where the
domain logic is genuinely entangled with infrastructure calls.

## Recommended next decomposition pattern

For the remaining god classes, **Spring-component extraction** is the
right pattern, not pure-helper extraction:

1. Pick a coherent domain unit (e.g., "VM clone creation",
   "Network ACL rule management").
2. Create a `@Component` that owns just those DAOs and beans, with
   constructor injection.
3. Move the related methods over (incl. their helpers).
4. Replace call sites in the original god class with delegating calls
   through an injected reference to the new component.
5. Spy/mock at the new boundary in tests.

This produces larger, less-frequent slices than the pure-helper
pattern — each one is a meaningful architectural change deserving
proper review. It's not appropriate for autonomous batch execution
in the same way pure-helper slices were.

## Suggested high-impact next targets

| Target | Why |
|--------|-----|
| Add **OpenAPI spec generation** | Modernizes the API surface; very high external value |
| **`UserVmManagerImpl` clone/migration extraction** | Natural seam in the biggest god class |
| **Dockerfile + Helm chart** | Leverages the observability endpoints already added in Phase 4 |
| **Async-job trace propagation** | Carry traceparent through the job queue so VM operations stay traceable end-to-end |
| **Per-plugin SPI improvements** | Direct support for the user's "extend the platform over time" goal |
