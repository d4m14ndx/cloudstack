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

## ConfigurationManagerImpl — Spring-component extraction phase

Having exhausted pure-helper extraction (slices 1–7 above), the next
phase pulls coherent domain units into dedicated `@Component` classes
with their own DAO injections. `ConfigurationManagerImpl` retains
one-line delegating wrappers so the `ConfigurationService` /
`ConfigurationManager` interface contracts are unchanged.

### Spring-component slices shipped

| # | Commit | Component | Methods extracted | Dedicated tests |
|---|--------|-----------|-------------------|-----------------|
| 1 | `285223c` | `PodService` | Pod CRUD (`createPod` ×2, `deletePod`) + IP-range ops (`createPodIpRange`, `deletePodIpRange`, `updatePodIpRange`) | 32 |
| 2 | `7acbaa3` | `DiskOfferingService` | Disk-offering CRUD (`createDiskOffering`, `updateDiskOffering`, `deleteDiskOffering`) + associated helpers | 41 |
| 3 | `e57bd08` | `PortableIpRangeService` | `createPortableIpRange`, `deletePortableIpRange`, `listPortableIpRanges`, `listPortableIps` | 22 |
| 4 | `d8799bc` | `ServiceOfferingService` | Service-offering CRUD (`createServiceOffering`, `updateServiceOffering`, `deleteServiceOffering`, `getServiceOfferingDomains`, `getServiceOfferingZones`) | 24 |
| 5 | `5366f7c` | `ZoneService` | Zone CRUD (`createZone`, `editZone`, `deleteZone`, `createDefaultSystemNetworks`) | 22 |

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
| 7 | `VmAssignmentValidator` | assignVMToAccount pre-flight: VM movability, rule absence, snapshot absence, template access, account validity, caller access | 21 |
| 8 | `VmExtraConfigService` | Hypervisor extra-config (KVM/Xen/VMware) decode + allow-list validation + persist | 12 |
| 9 | `VmMigrationValidator` | VM (storage) migration pre-flight: caller/state/snapshot, dest hypervisor/SAGs/tags/dedication/maintenance | 19 |
| 10 | `VmCreationValidator` | createVirtualMachine pre-flight: service-offering / template / details / min-max IOPS | 23 |
| 11 | `VmDestroyPermissionService` | destroy/expunge/force-stop permission cluster (admin + global config + role API access + Kubernetes plugin veto) | 13 |
| 12 | _(skipped)_ | — | — |
| 13 | `VmHostNameUniquenessService` | hostname uniqueness across `vm.distinct.hostname.scope` (global/domain/subdomain/account/network+VPC) + extra-DHCP-option network presence check | 23 |
| 14 | `VmSecurityGroupAssignmentService` | security-group ID resolution (names→IDs, mutex check, VNF-appliance default group injection) + stopped-VM security-group reassignment (`checkAndUpdateSecurityGroupForVM` / `updateSecurityGroup`) | 20 |
| 15 | `VmCredentialResetService` | userdata propagation (`updateUserData`, `applyUserData`), userdata finalization (`finalizeUserData`), password encryption (`encryptAndStorePassword`), SSH-key detail cleanup (`removeEncryptedPasswordFromUserVmVoDetails`) | 21 |
| 16 | `VmUsageEventPublisher` | VM-level usage event publishing (`generateUsageEvent` with dynamic-offering parameter support), per-NIC network-offering events (`generateNetworkUsageForVm`), and the state-aware bulk publish fired on `displayVm` flips (`saveUsageEvent`) | 20 |
| 17 | `VmDisplayFlagService` | `displayVm` flag mutation: set flag on the VO, conditional VM resource-count increment/decrement (suppressed when `resource.count.running.vms.only` is on), bulk usage-event publication via `VmUsageEventPublisher`, and ROOT + DATADISK volume display cascade | 15 |
| 18 | `VmRootVolumeStorageCleanupService` | Hypervisor-aware managed-storage cleanup on VM destroy: XenServer `DetachCommand`, VMware `DeleteCommand` + `ModifyTargetsCommand` cluster broadcast, KVM no-op | 21 |

> **Note on slice 12:** Slice 12 was claimed and abandoned (duplicate of slice 13
> domain — VmNetworkHostnameValidator); skipped intentionally.

Each slice keeps the orchestration that needs spy-verified inner calls
inside `UserVmManagerImpl` — the leaf methods become thin wrappers that
delegate to the extracted component, and the validator's own
implementation can call its helpers directly when invoked standalone.

## VpcManagerImpl — Spring-component extraction in progress

Each slice carves a coherent VPC domain unit into its own `@Component`.
`VpcManagerImpl` retains one-line delegating wrappers.

### Slices shipped

| # | Commit | Component | Methods extracted | Dedicated tests |
|---|--------|-----------|-------------------|-----------------|
| 1 | `3cbfa35` | `StaticRouteService` | VPC static-route CRUD (`getStaticRoute`, `createStaticRoute`, `listStaticRoutes`, `getVpcStaticRoutes`) + validation + conflict detection + provider application | 27 |
| 2 | `b8b3729` | `PrivateGatewayService` | VPC private-gateway lifecycle (`getVpcPrivateGateways`, `getVpcPrivateGateway`, `getPrivateGatewayProfile`, `createVpcPrivateGateway`, `applyVpcPrivateGateway`, `deleteVpcPrivateGateway`) | 27 |
| 3 | `01c3e51` | `VpcIpAllocationService` | VPC public-IP allocation and release (`allocateIPToVpc`, `releaseIpFromVpc`, and associated helpers) | 23 |

> The commit message for `VpcIpAllocationService` labels it "slice 4" because
> it was the fourth parallel extraction in the worktree series; the table above
> uses sequential semantic ordering (StaticRoute → PrivateGateway → IpAllocation).

## VirtualMachineManagerImpl (engine) — Spring-component extraction in progress

Engine-layer god class. Each slice extracts one infrastructure concern
into a dedicated `@Component` under `engine/orchestration`.

### Slices shipped

| # | Commit | Component | Methods extracted | Dedicated tests |
|---|--------|-----------|-------------------|-----------------|
| 1 | `b6b6b9f` | `VmServiceOfferingUpgradeManager` | Service-offering upgrade persistence helpers: volume / primary-store / instance-details rewrite on scale-up | 18 |
| 2 | `f23af88` | `VmIscsiTargetManager` | VMware managed-iSCSI dynamic-target cleanup trio (`getTargets`, `removeDynamicTargets`, `sendModifyTargetsCommand`) | 16 |
| 3 | `c212a28` | `VmStatsCollector` | Per-host VM / disk / network stats collection and aggregation | 29 |

## ManagementServerImpl — Spring-component extraction in progress

Each slice extracts one management-surface concern from the
`ManagementServerImpl` god class into a dedicated `@Component`.

### Slices shipped

| # | Commit | Component | Methods extracted | Dedicated tests |
|---|--------|-----------|-------------------|-----------------|
| 1 | `0c002f8` | `SshKeyPairService` | SSH keypair generation, registration, listing, deletion | 18 |
| 2 | `6206c91` | `AuditTrailService` | Audit-trail and alert lifecycle: `archiveEvents`, `deleteEvents`, `searchForAlerts`, `archiveAlerts`, `deleteAlerts`, `listEventTypes` | 15 |
| 3 | `91ffe0e` | `HypervisorCapabilitiesService` | Hypervisor-capabilities catalogue read/write: `listHypervisorCapabilities`, `updateHypervisorCapabilities`, `getHypervisorCapabilitiesForUpdate` | 18 |
| 4 | `468d7cc` | `HostCredentialsService` | Host/cluster credential management: `getVMPassword`, `getSSHPublicKeys`, `getCloudIdentifier` | 20 |
| 5 | `7555c01` | `ConsoleAccessService` | Console-proxy and VNC lookups: `getConsoleAccessUrlRoot`, `setConsoleAccessForVm`, `getConsoleAccessAddress`, `getVncPort` | 20 |
| 6 | `0005daf` | `SystemVmLifecycleService` | Type-aware system-VM start/stop/reboot/destroy dispatch + `findSystemVMTypeById` | 22 |
| 7 | `b6d0250` | `CapabilitiesService` | Deployment introspection: `listCapabilities` (with `getVpnCustomerGatewayParameters` helper) and `getVersion` | 20 |
| 8 | `c8b9ab1` | `ConfigurationListingService` | `searchForConfigurations` (scope validation, domain-admin/user defaulting, keyword/group/category filters, ConfigDepot re-population) and `listConfigurationGroups` | 17 |

## AccountManagerImpl — Spring-component extraction in progress

Each slice extracts one account-management concern from
`AccountManagerImpl` into a dedicated `@Component`.

### Slices shipped

| # | Commit | Component | Methods extracted | Dedicated tests |
|---|--------|-----------|-------------------|-----------------|
| 1 | `245e543` | `AccountLookupService` | Read-only account/user lookup cluster: `getActiveAccountByName`, `getActiveUserAccount`, and related `findByX` accessors | 19 |
| 2 | `e90ec28` | `ApiKeyPermissionService` | API-key permission / superset-check cluster: caller API-key parsing + rule-set resolution | 17 |
| 3 | `452a0db` | `ApiKeyLifecycleService` | API-key generation, persistence, and removal helpers | 18 |
| 4 | `2e5a21d` | `TwoFactorAuthenticationService` | User 2FA provider-registry lookups + login-time setup-state cleanup | 18 |
| 5 | `dec0092` | `AclSearchBuilderService` | ACL search-builder/criteria/parameters cluster: `buildACLSearchBuilder`, `buildACLSearchCriteria`, `buildACLSearchParameters`, `buildACLViewSearchBuilder`, `buildACLViewSearchCriteria` | 37 |

## Other god classes in the codebase

These are the remaining `*ManagerImpl` classes over 5K lines, by size.
Classes already receiving Phase 4 slices are noted.

| File | Lines (approx.) | Notes |
|------|-----------------|-------|
| `server/.../UserVmManagerImpl.java` | 10,068 | **18 slices shipped** (see above). |
| `server/.../ConfigurationManagerImpl.java` | 9,286 | Pure-helper extraction complete (7 slices) + **5 Spring-component slices shipped** (see above). |
| `server/.../NetworkServiceImpl.java` | 6,484 | Network CRUD, VPC management. Parallel slice extraction started. |
| `server/.../QueryManagerImpl.java` | 6,372 | API list query handlers. Parallel slice extraction started. |
| `server/.../ManagementServerImpl.java` | 5,994 | **8 slices shipped** (see above). |
| `server/.../ApiResponseHelper.java` | 5,878 | Response serialization (likely many static-extractable helpers). |
| `server/.../VolumeApiServiceImpl.java` | 5,513 | Volume lifecycle. Parallel slice extraction started. |
| `server/.../VpcManagerImpl.java` | ~5,200 | **4 slices shipped** (see above). |
| `server/.../AccountManagerImpl.java` | ~5,000 | **5 slices shipped** (see above). |
| `engine/.../VirtualMachineManagerImpl.java` | ~4,800 | **3 slices shipped** (see above). |

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
