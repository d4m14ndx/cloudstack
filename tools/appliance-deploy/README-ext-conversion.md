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
| `cs-ext-to-native.sh` | Convert one External cluster to a native Proxmox cluster by re-adopting its VMs. | dry-run by default (`--commit` to act); DB backup; leaves the External cluster **disabled, not deleted** |
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
3. **Dry-run the conversion** for each External cluster and review the exact commands:
   ```
   PVE_PASS=... PVE_SSH_PASS=... CS_DB_PASS=... \
     cs-ext-to-native.sh --ext-cluster <ID> --url https://<pve>:8006 --user root@pam
   ```
4. **Commit** it once the plan looks right:
   ```
   PVE_PASS=... PVE_SSH_PASS=... CS_DB_PASS=... \
     cs-ext-to-native.sh --ext-cluster <ID> --url https://<pve>:8006 --user root@pam --commit
   ```
5. **Verify** the natively-imported VMs (state, networking, console). The External cluster is left
   **disabled** so you can still roll back. Once satisfied, remove the External cluster + its
   extension mapping via the stock UI/API.

## What the conversion does (per External cluster)

1. DB backup.
2. `update cluster … allocationstate=Disabled managedstate=Unmanaged` on the External cluster — stops
   CloudStack orchestrating it; **the PVE VMs keep running.**
3. `add cluster hypervisor=Proxmox …` for the *same* PVE cluster using the connection you supply
   (`--url`, `--user`, `PVE_PASS`, `PVE_SSH_PASS`). The native discoverer connects to the same PVE.
4. Wait for the native hosts to come `Up`.
5. For each External user VM: `list unmanagedinstances` on the native cluster, then
   `import unmanagedinstance` **re-using the VM's existing NIC→network map + IP + service offering**
   (read from the old records) so networking is preserved. Adoption does not modify the PVE VM.
6. Leave the External cluster disabled for rollback.

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
- **Field mapping must be validated on your real setup.** The `import` NIC/offering mapping is derived
  from the live records; different extensions store connection config under different
  `extension_resource_map_details` keys. Run the inventory on the real system and confirm the dry-run
  plan before `--commit`. (This is why the scripts are dry-run-first.)
- **Not exercised end-to-end here** — the lab is 100% native, so there is no `External` cluster to test
  a real conversion against. The inventory tool is validated read-only; the converter/rollback are
  validated for flow + dry-run output and must be trialled on a real (or throwaway) extension cluster
  before production use.
- Requires `cmk` (CloudMonkey) configured on the node, and `CS_DB_PASS` for backups/record reads.
