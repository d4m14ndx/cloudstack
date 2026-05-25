# Project directory cleanup audit

Read-only audit identifying stale files, orphaned tooling, and removal
candidates in the fork. Earlier phases already removed the bulk of the
historical baggage (19 dead plugins, OVM3 hypervisor module, ngui
prototype, cloud-cli, quickcloud); this is the focused second pass.

## Executive summary

What remains is roughly:

1. **OVM3 leftovers** — scripts and rat-excludes pointing at directories
   that no longer exist (the Java DB-compat stubs should stay).
2. **Upstream-only `tools/` subdirs** the fork won't use (transifex,
   whisker, bugs-wiki, jira, eclipse, devcloud4, devcloud-kvm).
3. **~640 MB of local `target/` build output** — git-ignored but on
   disk. `mvn clean` reclaims it; no commit needed.
4. **Upstream-only CI workflows** guarded by
   `github.repository == 'apache/cloudstack'` that always skip in the
   fork.
5. **Small orphans** like `test/bindirbak/` and stale rat-excludes.

Highest-leverage removal: the upstream-only `tools/` subdirs plus the
OVM3 leftovers — ~50 files dropped without touching active code paths.

---

## High-confidence removals

### 1. OVM3 leftovers

The plugin module `plugins/hypervisors/ovm3/` is gone. Keep the two
intentional DB-compat stubs (annotated `@deprecated`):
- `api/src/main/java/com/cloud/hypervisor/Hypervisor.java:57-58` — the
  `HypervisorType.Ovm3` enum constant (load-bearing for DB deserialization)
- `engine/schema/src/main/java/com/cloud/network/dao/PhysicalNetworkTrafficTypeVO.java:66-85` —
  the `ovm3NetworkLabel` field

Pure orphans to delete:
- `scripts/vm/hypervisor/ovm3/` — 2 Python files, 843 lines, 32 KB.
  No references anywhere; the Java agent that called them is gone.
- `framework/quota/.../Value.java:90` — outdated docstring listing
  `Ovm3` as a hypervisor (cosmetic doc drift).
- `engine/schema/templateConfig.sh` contains an `ovm3` template config
  string — worth checking.

Keep the 3 test references in `ConfigurationManagerImplTest.java:304,310`
and `VirtualMachineManagerImplTest.java:435` — they validate the
deprecated compat path.

### 2. Stale `pom.xml` rat-excludes

Root `pom.xml:1104-1105` excludes `tools/ngui/static/bootstrap/*` and
`tools/ngui/static/js/lib/*` from rat. `tools/ngui/` was deleted in
Phase 1 — dead config.

### 3. Local `target/` build output

~640 MB across 70+ `target/` directories. `git ls-files | grep target/`
returns 0 tracked files — all output. Notable cruft:
- `plugins/hypervisors/kvm/target/dependencies/` ships three Bouncy
  Castle versions (`bcprov-jdk15on-{1.69,1.70,1.79}.jar`)
- Two Groovy versions (2.4.17 and 4.0.24) in the same path

Fix: `mvn clean` on the reactor. Not a commit.

### 4. `test/bindirbak/` orphan

Single file `test/bindirbak/cloud-run-test.in` (1.4 KB). Name suggests
"bindir backup". No references anywhere (`grep -rn "bindirbak"` returns
nothing).

### 5. Upstream-only `tools/` subdirs

| Path | Size | Files | Why dead in fork |
|------|------|-------|------------------|
| `tools/whisker/` | 580 KB | 4 | ASF release license aggregation; fork doesn't cut ASF releases |
| `tools/transifex/` | 16 KB | 3 | Apache uses Transifex; fork won't |
| `tools/bugs-wiki/` | 8 KB | 2 | Apache JIRA/Confluence search |
| `tools/jira/` | 4 KB | 1 | Only ref is `CHANGES.md:1057` |
| `tools/eclipse/` | 164 KB | 3 | Not in `tools/pom.xml`; nobody using Eclipse in this fork |
| `tools/devcloud4/` | 196 KB | 22 | Vagrant dev env; superseded by Helm + docker-compose |
| `tools/devcloud-kvm/` | 48 KB | 8 | Older Vagrant KVM dev env; superseded |

Both `devcloud4` and `devcloud-kvm` are declared as modules in
`tools/pom.xml:49-50` — removing them needs a one-line edit there.

Combined: ~1 MB, ~49 files, ~3,450 LOC.

### 6. Upstream-only CI workflows

Seven workflows guarded by `if: github.repository == 'apache/cloudstack'`
(always no-op in this fork):

- `.github/workflows/build.yml` (70 lines) — needs proprietary
  `shapeblue/cloudstack-nonoss`
- `.github/workflows/codecov.yml` (59 lines) — same
- `.github/workflows/ci.yml` (351 lines, 16 KB) — replaced by `fork-ci.yml`
- `.github/workflows/docker-cloudstack-simulator.yml` (65 lines)
- `.github/workflows/main-sonar-check.yml` (68 lines) — Apache SonarCloud
- `.github/workflows/sonar-check.yml` (73 lines) — same
- `.github/workflows/rat.yml` (50 lines) — clones `shapeblue/cloudstack-nonoss`
- `.github/workflows/ui.yml` (67 lines, partially guarded)

Plus two auto-generated `.lock.yml` files for Apache-INFRA-managed AI
workflows (`issue-triage-agent.lock.yml`, `daily-repo-status.lock.yml`)
— ~100 KB each, almost certainly dead in fork (confirm).

~10 files, ~2,900 lines, ~120 KB.

---

## Medium-confidence — worth a quick check

- **Docs drift**: `docs/AUDIT.md:150,254` references the removed
  `plugins/hypervisors/ovm3`. Five other lines reference removed
  network plugins (Nicira, BigSwitch, Brocade, etc.). Add a
  "Resolved in Phase 1" note rather than deleting.
- **`tools/build/`** (40 KB, 4 files): `build_asf.sh` is upstream-only.
  `installer/` subdirectory may or may not be live — grep first.
- **`tools/logo/`** (184 KB): contains `acsxmas.jpg` and
  `apache_cloudstack.png`. Trademark concern — Apache wordmark / logo
  can't be used by non-Apache derivatives without permission.
- **Pre-7.0 XenServer support scripts**:
  `scripts/vm/hypervisor/xenserver/{xenserver56,xenserver56fp1,xenserver60,xenserver62,xenserver65,xcposs,xcpserver,xcpserver83}`
  are 2011–2014 era. Could be a big LOC win but is a feature decision
  (drop pre-7.0 XenServer support?), not pure cleanup.
- **Dockerfile variants**: `tools/docker/Dockerfile.s390x` (IBM Z) and
  `Dockerfile.marvin` likely dead in fork.
- **`test/integration/broken/`** — literally named "broken". 18 files,
  272 KB of Python integration tests. Fix or remove (project decision).
- **`developer/`** top-level — 3 files (`developer-prefill.sql`,
  `developer-saml.sql`, `pom.xml`). Referenced under a profile in root
  `pom.xml:1300` only — looks live, but worth a build-config check.

---

## Uncertain — flag for user review

1. **`systemvm/agent/noVNC/vendor/`** (208 KB) — contains the `pako`
   library (zlib in JS). Legitimate vendored dependency of noVNC for
   the console proxy.
2. **`scripts/installer/windows/` exclude in `pom.xml:1078`** —
   references `acs_license.rtf`, but `find scripts/installer -type d`
   only shows `scripts/installer`. Either the dir was deleted and the
   exclude is stale, or the file is regenerated.
3. **`systemvm/pom.xml:183` `<id>quickcloud</id>` profile** — matching
   the deleted `quickcloud/` top-level dir. May be a vestigial profile
   name for a still-useful dev launcher (sets
   `mainClass=com.cloud.agent.AgentShell`). Verify before removing.
4. **`extensions/` directory** — 3 files (`HyperV/hyperv.py`,
   `MaaS/maas.py`, `Proxmox/proxmox.sh`). HyperV stays (already
   confirmed). MaaS and Proxmox stubs may or may not be on the roadmap.
5. **`.github/workflows/*.lock.yml`** — auto-generated by `gh-aw`, tied
   to Apache INFRA AI workflow infrastructure. Almost certainly
   inactive in the fork but the imports point at `apache/.github/gh-aw`.
6. **57 files contain `svn://svn.lab.vmops.com`** in shebang/header
   comments — pre-Citrix-acquisition VMOps SVN URLs from 2010.
   Cosmetic dead `$Id$` SVN keywords. Not removal candidates.

---

## Recommended cleanup PRs

### PR 1 — "Drop OVM3 leftovers" (lowest risk, ~5 min review)

- Delete `scripts/vm/hypervisor/ovm3/` (2 files, 843 lines)
- Remove dead rat-excludes in root `pom.xml:1104-1105` (ngui)
- Fix `framework/quota/.../Value.java:90` docstring to drop "Ovm3"
- Add `## Resolved in Phase 1` notes to `docs/AUDIT.md` for removed
  plugins
- Test: `mvn -P quality -DskipTests=false test` passes unchanged

### PR 2 — "Remove upstream-only `tools/` modules" (low risk, ~1 KLOC)

- Delete `tools/whisker/`, `tools/transifex/`, `tools/bugs-wiki/`,
  `tools/jira/`, `tools/eclipse/`, `tools/devcloud4/`,
  `tools/devcloud-kvm/`
- Edit `tools/pom.xml` to drop `<module>devcloud4</module>` and
  `<module>devcloud-kvm</module>`
- Update `CHANGES.md:1057` (only ref to `tools/jira/jira-changes.py`)
- Verify `pom.xml:1106` `tools/transifex/.tx/config` exclude isn't
  needed after deletion
- ~49 files, ~3,450 LOC

### PR 3 — "Trim upstream-only CI workflows" (low risk, isolated to `.github/`)

- Delete `build.yml`, `codecov.yml`, `ci.yml`,
  `docker-cloudstack-simulator.yml`, `main-sonar-check.yml`,
  `sonar-check.yml`, `rat.yml` (all guarded by Apache repo check)
- Decide on `.lock.yml` / `md` Apache INFRA agent workflows
- Keep `fork-ci.yml`, `pre-commit.yml`, `codeql-analysis.yml`,
  `merge-conflict-checker.yml`, `stale.yml`, `ui.yml`
- ~10 files, ~2,900 lines, ~120 KB

### PR 4 — "Misc orphans" (low risk, mop-up)

- Delete `test/bindirbak/` (1 file)
- Delete `test/integration/broken/` *if* user confirms tests are not
  being fixed (18 files, 272 KB)
- Decide on `tools/docker/Dockerfile.s390x` and `Dockerfile.marvin`
- XenServer pre-7.0 script subdirs — bigger feature decision; own PR

### Not a PR — local hygiene

- `mvn clean` on the reactor reclaims ~640 MB of `target/` output.
  All paths are in `.gitignore`; no commit needed.

---

Total tracked file removal across PRs 1–4: roughly **80–100 files,
~7,500 lines**, with the largest single chunk being the auto-generated
`.lock.yml` workflows.
