# Convert Extension-framework Proxmox hosts → native Proxmox-plugin hosts

When you move a management node from **stock** CloudStack (Proxmox integrated via the 4.22 **Extension
framework**, hypervisor type `External`) to **our build** (the **native Proxmox plugin**, hypervisor
type `Proxmox`), these tools re-adopt the existing PVE VMs into the native cluster and give you a
defined rollback. They use **supported CloudStack APIs** (`addCluster`, `listUnmanagedInstances`,
`importUnmanagedInstance`, `unmanageVirtualMachine`) — **no `hypervisor_type` DB surgery, and the
running PVE VMs are never stopped or deleted.**

## Tools

| Script | Purpose | Safety |
|--------|---------|--------|
| `cs-ext-proxmox-inventory.sh` | READ-ONLY inventory of the stock extension setup (extensions, External clusters/hosts/VMs, connection config, NIC→network maps). Run this first and share the output. | read-only |
| `cs-ext-to-native.sh` | Convert one External cluster to a native Proxmox cluster by re-adopting its VMs. | dry-run by default (`--commit` to act); DB backup; **captures a VM manifest first**; leaves the External cluster **disabled, not deleted** |
| `cs-ext-rollback.sh` | Undo a conversion: unmanage the native imports (PVE VMs kept), delete the native cluster, re-enable the External cluster. | dry-run by default; DB backup |

## Order of operations (full stock → ours migration)

1. **On stock**, capture a baseline and the inventory:
   ```
   CS_DB_PASS=... cs-mgmt-deploy.sh backup                 # DB dump + artifact tarball
   CS_DB_PASS=... cs-ext-proxmox-inventory.sh --out /root/ext-inventory.txt
   ```
   Share `ext-inventory.txt` — it identifies the External cluster id(s), hosts, VMs and NIC→network
   maps that drive the conversion. **Secrets are masked**, so it is safe to share.
2. **Switch the binaries** to our build (see the appliance-deploy README):
   ```
   cs-mgmt-deploy.sh ours
   ```
3. **Dry-run the conversion** for each External cluster. This **captures the VM manifest** and prints the
   exact commands; review both (see *Safety: capture-then-import* below):
   ```
   PVE_PASS=... PVE_SSH_PASS=... CS_DB_PASS=... \
     cs-ext-to-native.sh --ext-cluster <ID> --url https://<pve>:8006 --user root@pam
   ```
4. **Commit** it once the plan looks right, reusing the reviewed manifest so you import exactly what you
   saw:
   ```
   PVE_PASS=... PVE_SSH_PASS=... CS_DB_PASS=... \
     cs-ext-to-native.sh --ext-cluster <ID> --url https://<pve>:8006 --user root@pam \
       --manifest <manifest-from-the-dry-run.json> --commit
   ```
5. **Verify** the natively-imported VMs (state, networking, console). The External cluster is left
   **disabled** so you can still roll back. Once satisfied, remove the External cluster + its
   extension mapping via the stock UI/API.

## Safety: capture-then-import (VM manifest)

Before it changes anything, `cs-ext-to-native.sh` **captures the details of every VM to re-adopt into a
JSON manifest file** (`<backup-dir>/vm-manifest-cluster<ID>-<ts>.json`): instance name, display name,
service offering, template, and the full NIC→network/IP/MAC map. The import step then reads that
**frozen manifest**, not the live DB — so:

- re-adoption is **deterministic and auditable** — you review the exact file that drives the import;
- disabling the External cluster (or any concurrent DB change) **cannot alter what gets imported**;
- the run is **re-runnable** — if the import is interrupted, re-run with the same manifest;
- you keep an artifact to **diff the VMs after a later rollback** (Gate A vs Gate D).

Recommended flow: let the **dry-run capture** the manifest, review it, then pass it straight back to the
`--commit` run so you import exactly what you reviewed:

```
# dry-run captures the manifest and prints the plan derived from it
PVE_PASS=… PVE_SSH_PASS=… CS_DB_PASS=… \
  cs-ext-to-native.sh --ext-cluster <ID> --url https://<pve>:8006 --user root@pam
#   → captured VM manifest -> /var/backups/cloudstack-deploy/vm-manifest-clusterN-….json

# review it
jq . /var/backups/cloudstack-deploy/vm-manifest-clusterN-….json

# commit, reusing the reviewed manifest
PVE_PASS=… PVE_SSH_PASS=… CS_DB_PASS=… \
  cs-ext-to-native.sh --ext-cluster <ID> --url https://<pve>:8006 --user root@pam \
    --manifest /var/backups/cloudstack-deploy/vm-manifest-clusterN-….json --commit
```

Requires `jq` and a MySQL 8 server (uses `JSON_ARRAYAGG`/`JSON_OBJECT` to build the manifest).

## What the conversion does (per External cluster)

1. **Capture the VM manifest** (read-only) — the authoritative list of what to re-adopt.
2. DB backup (the manifest is copied alongside it).
3. `update cluster … allocationstate=Disabled managedstate=Unmanaged` on the External cluster — stops
   CloudStack orchestrating it; **the PVE VMs keep running.**
4. `add cluster hypervisor=Proxmox …` for the *same* PVE cluster using the connection you supply
   (`--url`, `--user`, `PVE_PASS`, `PVE_SSH_PASS`). The native discoverer connects to the same PVE.
5. Wait for the native hosts to come `Up`.
6. For each VM **in the manifest**: `list unmanagedinstances` on the native cluster, then
   `import unmanagedinstance` **re-using the VM's NIC→network map + IP + service offering**
   (from the manifest) so networking is preserved. Adoption does not modify the PVE VM.
7. Leave the External cluster disabled for rollback.

## Rollback

```
cs-ext-rollback.sh --native-cluster <name|id> --ext-cluster <ID>            # dry-run
cs-ext-rollback.sh --native-cluster <name|id> --ext-cluster <ID> --commit   # execute
cs-mgmt-deploy.sh stock                                                      # optional: revert binaries
```
Rollback unmanages the native VMs (records removed, **PVE VMs untouched**), deletes the native
cluster/hosts, and re-enables the original External cluster (which was only disabled, never deleted),
so the stock extension manages the same running PVE VMs again.

## Important caveats

- **The double-management window** (native cluster added while the External cluster still has records)
  is why the External cluster is *disabled* first and its VM records are never deleted by these tools —
  only your explicit final cleanup removes them. Never run `expunge`/destroy against the External VMs
  during the window: that would ask the extension to delete the real PVE VMs.
- **Field mapping must be validated on your real setup.** The `import` NIC/offering mapping comes from
  the captured manifest, but the exact `nicnetworklist[i].nic` key format must match what a real
  `listUnmanagedInstances` reports on the native cluster (the manifest stores `device_id` **and** `mac`
  so it can be matched either way). Different extensions also store connection config under different
  `extension_resource_map_details` keys. Run the inventory on the real system, review the manifest, and
  confirm the dry-run plan before `--commit`. (This is why the scripts are dry-run-first.)
- **Not exercised end-to-end here** — the lab is 100% native, so there is no `External` cluster to test
  a real conversion against. The inventory tool is validated read-only; the converter/rollback are
  validated for flow + dry-run output and must be trialled on a real (or throwaway) extension cluster
  before production use.
- Requires `cmk` (CloudMonkey) configured on the node, and `CS_DB_PASS` for backups/record reads.
