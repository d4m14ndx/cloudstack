# Apache CloudStack Fork — Codebase Audit Report

**Date:** 2026-05-15
**Source:** Apache CloudStack 4.23.0.0-SNAPSHOT (main branch)
**Purpose:** Full codebase assessment to guide modernization of the forked project

---

## Executive Summary

CloudStack is a **2.15M-line**, **161-module** Java/Python/Vue.js codebase with 15+ years of history. It has strong test coverage (0.92:1 Java test ratio, 336K lines of Python integration tests) and a well-modularized plugin architecture (98 plugin modules). However, it carries significant technical debt:

- **~14 dead plugins** (~70K+ lines) integrating with discontinued products
- **Critical EOL dependencies** (Spring 5.3, Jetty 9.4, Bouncy Castle 1.70, OpenSAML 2.6)
- **Java 11 target** when Java 17+ is needed for the Spring 6 migration
- **God-class service implementations** (multiple 5K-10K line files in `server/`)
- **Insecure authentication options** still present (MD5, plaintext passwords)
- **Vendored dead code** (AngularJS, jQuery 1.7 in abandoned `tools/ngui/`)

The fork should prioritize: dead plugin removal, dependency modernization (especially the Spring 5→6 / javax→jakarta migration), and incremental refactoring of the `server/` module.

---

## 1. Codebase Size & Structure

### Lines by Language

| Language | Files | Lines | % of Total |
|----------|-------|-------|------------|
| Java | 7,799 | 474,672 | 22.1% |
| Python | 640 | 365,452 | 17.0% |
| Vue | 387 | 150,335 | 7.0% |
| XML | 588 | 96,042 | 4.5% |
| JavaScript | 211 | 70,829 | 3.3% |
| SQL | 221 | 36,237 | 1.7% |
| Shell | 258 | 27,557 | 1.3% |
| Other (CSS, MD, YAML, Groovy, Properties, HTML) | — | ~34,500 | 1.6% |
| **Total** | — | **~2,151,000** | |

### Module Structure

- **161 Maven modules** (pom.xml files)
- **98 plugin modules** across 22 categories
- **Production Java:** 273K lines | **Test Java:** 253K lines (0.92:1 ratio)
- **Python integration tests:** 336K lines (Marvin framework)

### Top-Level Directory Map

| Directory | Size | Role |
|-----------|------|------|
| `plugins/` | 27 MB | Plugin architecture (hypervisors, networking, storage, auth, etc.) |
| `server/` | 16 MB | Management server core — largest single module (310K Java lines) |
| `test/` | 14 MB | Python/Marvin integration tests |
| `engine/` | 14 MB | Orchestration, schema/DB, storage engine, userdata |
| `ui/` | 12 MB | Vue 3 + Ant Design web UI |
| `api/` | 11 MB | Public API command/response definitions |
| `tools/` | 4.6 MB | Marvin, CLI tools, appliance builder, **dead ngui prototype** |
| `framework/` | 3.2 MB | DB, Spring, clustering, jobs, events, security, quota |
| `systemvm/` | 3.1 MB | System VM (CPVM/SSVM) agent code |
| `core/` | 3.1 MB | Agent↔server message types |
| `services/` | 2.4 MB | Console proxy (VNC/RDP), secondary storage agent |
| `utils/` | 1.9 MB | Shared utilities |
| `scripts/` | 1.8 MB | Host-level shell scripts |
| `vmware-base/` | 924 KB | VMware vSphere SDK wrappers |
| `setup/` | 556 KB | DB schema creation & upgrade scripts |
| `usage/` | 388 KB | Usage metering server |
| `agent/` | 344 KB | Hypervisor host agent launcher |
| `extensions/` | 48 KB | Stubs only (Hyper-V, MaaS, Proxmox) |
| `cloud-cli/` | 28 KB | Legacy CLI (dead) |
| `quickcloud/` | 8 KB | Dead quick-start scripts |

### Largest Source Files (Refactoring Targets)

| Lines | File | Notes |
|-------|------|-------|
| 14,847 | `tools/ngui/static/js/lib/angular.js` | Vendored AngularJS — delete entirely |
| 10,068 | `server/.../UserVmManagerImpl.java` | God class — VM lifecycle |
| 9,515 | `server/.../ConfigurationManagerImpl.java` | God class — system config |
| 9,404 | `tools/ngui/static/js/lib/jquery-1.7.2.js` | Vendored jQuery — delete entirely |
| 7,823 | `plugins/hypervisors/vmware/.../VmwareResource.java` | VMware hypervisor handler |
| 6,699 | `engine/orchestration/.../VirtualMachineManagerImpl.java` | VM state machine |
| 6,689 | `plugins/hypervisors/kvm/.../LibvirtComputingResource.java` | KVM hypervisor handler |
| 6,484 | `server/.../NetworkServiceImpl.java` | Network service |
| 5,994 | `server/.../ManagementServerImpl.java` | Core management server |
| 5,878 | `server/.../ApiResponseHelper.java` | API serialization |

---

## 2. Dependency Health

### Critical — EOL / Known CVEs

| Dependency | Current Version | Issue | Action |
|------------|----------------|-------|--------|
| **Spring Framework** | 5.3.26 | EOL (Dec 2024). No security patches. | Migrate to Spring 6.x (requires Java 17+, javax→jakarta) |
| **Jetty** | 9.4.58 | EOL. No security fixes. | Migrate to Jetty 11/12 (align with Jakarta) |
| **Bouncy Castle** | 1.70 (jdk15on) | jdk15on deprecated; CVEs between 1.70 and 1.78+ | Upgrade to 1.78+ (jdk18on) |
| **OpenSAML** | 2.6.6 | Ancient/EOL. Known vulnerabilities. | Upgrade to 4.x (major rewrite of SAML2 plugin) |
| **OWASP ESAPI** | 2.1.0.1 | Multiple CVEs | Upgrade to 2.5.x |
| **JSch** | 0.1.55 | Abandoned upstream | Replace with `com.github.mwiede:jsch` 0.2.x or Apache MINA SSHD |
| **Apache CXF** | 3.2.14 | EOL | Upgrade to 4.x |
| **commons-httpclient** | 3.1 | Deprecated since 2011 | Replace with HttpClient 5.x |
| **commons-fileupload** | 1.4 | CVE-2023-24998 (DoS) | Replace with commons-fileupload2-jakarta |
| **Kafka clients** | 2.7.0 | EOL, multiple CVEs | Upgrade to 3.7+ |

### High — Significantly Behind

| Dependency | Current | Latest | Notes |
|------------|---------|--------|-------|
| Jackson | 2.13.3 | 2.17+ | Security fixes missed |
| Groovy | 2.4.17 | 4.x | EOL |
| Axis2 | 1.6.4 | — | Unmaintained; used for VMware SOAP |
| Ehcache | 2.6.11 | 3.x | EOL |
| AWS SDK | v1 1.12.795 | v2 | v1 in maintenance mode |
| Guava | 31.1 | 33+ | Security/bug fixes |
| Google Tink | 1.7.0 | 1.14+ | Behind 7 major versions |
| Log4j 2 | 2.19.0 | 2.24+ | Should update |

### Build Tooling

| Tool | Current | Latest | Priority |
|------|---------|--------|----------|
| maven-surefire-plugin | 2.22.2 | 3.x | High (JDK compat) |
| maven-failsafe-plugin | 2.22.2 | 3.x | High |
| maven-compiler-plugin | 3.8.1 | 3.13+ | Medium |
| Checkstyle lib | 8.18 | 10.x | Medium |
| OWASP Dependency-Check | 7.4.4 | 10.x | High (DB schema) |

### Code Quality Configuration

- **Checkstyle:** Active on `validate` phase with custom `cloud-style.xml`
- **SpotBugs:** Disabled by default; `failOnError=false`
- **PMD:** Active with custom rules; `failOnViolation=false`
- **JaCoCo:** Only in `quality` profile; **0% threshold** (not enforced)
- **SonarCloud:** Configured for Apache org

---

## 3. Subsystem Inventory

### Hypervisor Plugins

| Plugin | Location | Java Lines | Tests | Status |
|--------|----------|-----------|-------|--------|
| **KVM/libvirt** | `plugins/hypervisors/kvm` | 58,851 | 54 files | Active |
| **VMware vSphere** | `plugins/hypervisors/vmware` + `vmware-base/` | 44,085 | 7 files | Active |
| **XenServer/XCP-ng** | `plugins/hypervisors/xenserver` | 25,189 | 23 files | Active |
| **Simulator** | `plugins/hypervisors/simulator` | 9,840 | 0 | Active (CI) |
| **External** | `plugins/hypervisors/external` | 3,878 | 4 files | Active |
| **OVM3 (Oracle VM 3)** | `plugins/hypervisors/ovm3` | 14,945 | 23 files | Abandoned (product EOL) |
| **Baremetal** | `plugins/hypervisors/baremetal` | 8,290 | 0 | Neglected |
| **Hyper-V** | `plugins/hypervisors/hyperv` | 4,392 | 1 file | Abandoned |
| **OVM (Oracle VM 2)** | `plugins/hypervisors/ovm` | 2,733 | 0 | Dead |
| **UCS (Cisco)** | `plugins/hypervisors/ucs` | 2,171 | 0 | Dead |

### Network Plugins

| Plugin | Location | Java Lines | Status |
|--------|----------|-----------|--------|
| **Tungsten Fabric** | `plugins/network-elements/tungsten` | 35,620 | Active |
| **NSX** | `plugins/network-elements/nsx` | 7,953 | Active |
| **Netris** | `plugins/network-elements/netris` | 9,432 | Active |
| **OVS** | `plugins/network-elements/ovs` | 3,850 | Active |
| **Internal LB** | `plugins/network-elements/internal-loadbalancer` | 3,182 | Active |
| **VXLAN** | `plugins/network-elements/vxlan` | 447 | Active |
| **DNS Notifier** | `plugins/network-elements/dns-notifier` | 120 | Active |
| **NetScaler** | `plugins/network-elements/netscaler` | 9,237 | Neglected |
| **Palo Alto** | `plugins/network-elements/palo-alto` | 4,430 | Neglected |
| **Elastic LB** | `plugins/network-elements/elastic-loadbalancer` | 1,996 | Neglected |
| **Nicira NVP** | `plugins/network-elements/nicira-nvp` | 12,505 | Dead (replaced by NSX) |
| **Juniper Contrail** | `plugins/network-elements/juniper-contrail` | 11,076 | Dead (replaced by Tungsten) |
| **BigSwitch** | `plugins/network-elements/bigswitch` | 6,771 | Dead |
| **Cisco VNMC** | `plugins/network-elements/cisco-vnmc` | 5,838 | Dead |
| **OpenDaylight** | `plugins/network-elements/opendaylight` | 4,733 | Dead |
| **Brocade VCS** | `plugins/network-elements/brocade-vcs` | 3,924 | Dead |
| **GloboDNS** | `plugins/network-elements/globodns` | 2,144 | Dead |
| **Stratosphere SSP** | `plugins/network-elements/stratosphere-ssp` | 2,086 | Dead |

### Storage Plugins — Volume (Primary)

| Plugin | Java Lines | Status |
|--------|-----------|--------|
| **StorPool** | 8,558 | Active |
| **ScaleIO/PowerFlex** | 6,966 | Active |
| **NetApp ONTAP** | 6,740 | Active |
| **LINSTOR** | 5,317 | Active |
| **Primera/3PAR** | 3,862 | Neglected |
| **Adaptive** | 2,663 | Active |
| **Flash Array** | 2,270 | Active |
| **Default (NFS/Local)** | 1,430 | Active |
| **SolidFire** | 5,231 | Dead |
| **CloudByte** | 4,688 | Dead |
| **Datera** | 4,248 | Dead |
| **Nexenta** | 2,077 | Dead |

### Storage Plugins — Image / Object / SharedFS

All image storage (Default, S3, Swift, Sample), object storage (Cloudian, MinIO, Ceph, Simulator), and shared filesystem (StorageVM) plugins are **Active**, except Swift (Neglected).

### Other Active Plugin Areas

- **Authentication:** LDAP, SAML 2.0, OAuth 2.0, SHA256, PBKDF2 (all active). MD5 and plain-text are insecure and should be removed.
- **2FA:** TOTP and Static PIN (active)
- **Backup:** Networker, Veeam, NAS (all active)
- **Event Bus:** Webhook, RabbitMQ, In-Memory, Kafka (all active)
- **Integrations:** Kubernetes Service (17K lines, major feature), Cloudian, Prometheus
- **DRS:** Balanced and Condensed cluster scheduling (active, newer)
- **Quota/Billing:** Active
- **Metrics/Maintenance:** Active

### UI Layer

| Property | Value |
|----------|-------|
| Framework | Vue 3.2 + Ant Design Vue 3.2 |
| Location | `/ui` (387 Vue files, 102 JS files) |
| Build | Vue CLI |
| Status | Active — current and only UI |

---

## 4. Dead Code — Removal Candidates

### Immediate Removal (products/companies no longer exist)

| Component | Path | Lines | Reason |
|-----------|------|-------|--------|
| BigSwitch BCF | `plugins/network-elements/bigswitch` | ~6,771 | Arista discontinued BCF (2020) |
| Brocade VCS | `plugins/network-elements/brocade-vcs` | ~3,924 | Broadcom killed VCS (2017) |
| Cisco VNMC | `plugins/network-elements/cisco-vnmc` | ~5,838 | ASA 1000V EOL (2017) |
| Stratosphere SSP | `plugins/network-elements/stratosphere-ssp` | ~2,086 | Company gone |
| OpenDaylight | `plugins/network-elements/opendaylight` | ~4,733 | Project stagnant |
| Juniper Contrail | `plugins/network-elements/juniper-contrail` | ~11,076 | Superseded by Tungsten plugin |
| Nicira NVP | `plugins/network-elements/nicira-nvp` | ~12,505 | Superseded by NSX plugin |
| GloboDNS | `plugins/network-elements/globodns` | ~2,144 | Single-company internal project |
| Oracle VM 2 | `plugins/hypervisors/ovm` | ~2,733 | EOL for years |
| Cisco UCS | `plugins/hypervisors/ucs` | ~2,171 | Not a real hypervisor plugin |
| CloudByte | `plugins/storage/volume/cloudbyte` | ~4,688 | Company gone |
| Datera | `plugins/storage/volume/datera` | ~4,248 | Company bankrupt (2020) |
| SolidFire | `plugins/storage/volume/solidfire` | ~5,231 | Product discontinued |
| Nexenta | `plugins/storage/volume/nexenta` | ~2,077 | Product sunset |
| SolidFire test | `plugins/api/solidfire-intg-test` | ~500 | Test for dead product |
| MD5 auth | `plugins/user-authenticators/md5` | ~168 | Cryptographically broken |
| Plain-text auth | `plugins/user-authenticators/plain-text` | ~63 | Security risk |
| ngui (dead UI) | `tools/ngui/` | ~25K+ | Abandoned Angular/jQuery UI prototype |
| cloud-cli | `/cloud-cli` | ~500 | Dead legacy CLI |
| quickcloud | `/quickcloud` | ~200 | Dead quick-start |
| **Total** | | **~96,000+** | |

### Consider for Removal (product declining or plugin neglected)

| Component | Path | Lines | Notes |
|-----------|------|-------|-------|
| Oracle VM 3 | `plugins/hypervisors/ovm3` | ~14,945 | Product EOL |
| Hyper-V | `plugins/hypervisors/hyperv` | ~4,392 | Product deprecated by Microsoft |
| Baremetal | `plugins/hypervisors/baremetal` | ~8,290 | No tests, unclear demand |
| Swift image store | `plugins/storage/image/swift` | ~363 | Niche |
| Extensions stubs | `/extensions/` | ~200 | Non-functional stubs |

---

## 5. Recommended Modernization Roadmap

### Phase 1: Low-Risk Cleanup (Weeks 1-4)

1. **Remove dead plugins** — delete the 17 components listed above (~96K lines)
2. **Remove dead tooling** — `tools/ngui/`, `cloud-cli/`, `quickcloud/`
3. **Update Maven build plugins** — surefire/failsafe to 3.x, compiler to 3.13+
4. **Update safe dependencies** — Guava, commons-io, commons-lang3, Jackson, Log4j2
5. **Enable JaCoCo with real thresholds** — enforce minimum coverage
6. **Fix SpotBugs/PMD** — set `failOnError=true` / `failOnViolation=true`

### Phase 2: Security & Dependency Modernization (Weeks 4-10)

7. **Replace JSch** with maintained fork
8. **Remove commons-httpclient 3.1** — migrate callers to HttpClient 5
9. **Upgrade Bouncy Castle** to jdk18on 1.78+
10. **Upgrade OWASP ESAPI** to 2.5.x
11. **Replace commons-fileupload** with fileupload2-jakarta
12. **Upgrade Kafka client** to 3.7+
13. **Upgrade OWASP Dependency-Check** to 10.x and run full scan

### Phase 3: The Big Migration — Java 17 + Spring 6 + Jakarta (Weeks 10-20)

14. **Upgrade Java target** from 11 to 17
15. **Migrate javax → jakarta** namespace (affects entire codebase)
16. **Upgrade Spring** 5.3 → 6.x
17. **Upgrade Jetty** 9.4 → 11 or 12
18. **Upgrade Apache CXF** 3.2 → 4.x
19. **Upgrade OpenSAML** 2.6 → 4.x (or evaluate dropping SAML)
20. **Upgrade Groovy** 2.4 → 4.x

### Phase 4: Refactoring (Ongoing)

21. **Break up god classes** — `UserVmManagerImpl` (10K), `ConfigurationManagerImpl` (9.5K), etc.
22. **Standardize API layer** — inconsistent patterns across commands
23. **Improve plugin SPI** — make plugin development easier
24. **Modernize UI** — update Vue 3 / Ant Design versions, improve DX
25. **Add observability** — structured logging, metrics, distributed tracing

### Phase 5: New Capabilities

26. **Container workloads** — expand CKS (Kubernetes Service)
27. **Modern networking** — OVN integration, eBPF
28. **Improved multi-tenancy** and RBAC
29. **API v2** — REST-native with OpenAPI spec
30. **Better developer experience** — faster builds, better docs, docker-compose dev env

---

## Appendix: Dependency Inventory

Total unique external dependencies: ~202 artifacts from 146 group IDs.
Dependencies managed centrally in root POM: ~90.
Full dependency tree available via `mvn dependency:tree`.
