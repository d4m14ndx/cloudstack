<!--
 Licensed to the Apache Software Foundation (ASF) under one
 or more contributor license agreements.  See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership.  The ASF licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.
 -->

# Apache CloudStack — Native Proxmox VE Hypervisor Plugin

This module adds **Proxmox VE 8.x/9.x** as a *native* hypervisor type in Apache
CloudStack 4.22 — a first-class peer of KVM/VMware/XenServer, not an Extensions Framework
orchestrator. It supports the full CloudStack resource model: zones, pods, clusters,
hosts, primary/secondary storage, **system VMs (SSVM, Console Proxy, Virtual Router)**,
and the standard VM lifecycle.

## Architecture

**Direct-connect model** (same pattern as the VMware/vCenter plugin):

```
┌─────────────────────────────┐        REST /api2/json (8006)
│  CloudStack Management      │──────────────────────────────►┌────────────┐
│  Server                     │        SSH (root, port 22)    │  PVE node  │
│  ┌───────────────────────┐  │──────────────────────────────►│  pve1..N   │
│  │ ProxmoxResource (×N)  │  │                               └────────────┘
│  │ one per PVE node,     │  │        SSH :3922 (mgmt network)
│  │ DirectAgentAttache    │  │──────────────────────────────► System VMs / VR
│  └───────────────────────┘  │
└─────────────────────────────┘
```

- **No agent is installed on PVE nodes.** The management server drives each node
  through the Proxmox REST API, plus SSH (using the credentials supplied at
  host-add) for the operations the API cannot express:
  - `qemu-img convert` template/volume/snapshot movement against secondary-storage NFS

  System VM boot-args are injected through the **qemu guest agent** via the PVE API
  (`agent/ping` + `agent/file-write` of `/var/cache/cloud/cmdline`) — the exact
  mechanism the 4.21 KVM agent's `patch.sh` uses, so the stock system VM template
  behaves identically.
- **System VMs use the stock KVM system VM template** (Proxmox is QEMU/KVM
  underneath). Control-channel SSH to system VMs runs over the **management
  network** (like VMware/Hyper-V), because commands originate on the management
  server rather than a host agent.
- **VM identity:** CloudStack VM id `N` ⇔ PVE `vmid = vmid.base + N`
  (default base `10000`). The PVE VM `name` is the CloudStack internal name
  (`i-2-34-VM`, `r-45-VM`, …), so everything stays legible in the PVE UI.
- **Console:** each VM is started with an explicit qemu `-vnc` listener guarded by
  the CloudStack-generated VNC password (QMP `set_password`), so the CloudStack
  Console Proxy connects exactly as it does for KVM hosts.

## What maps to what

| CloudStack concept    | Proxmox VE concept |
|-----------------------|--------------------|
| Cluster               | PVE cluster (or standalone node) |
| Host                  | PVE node |
| Primary storage pool  | Existing PVE storage id (`PreSetup`, e.g. `presetup://localhost/local-nfs`) |
| Secondary storage     | NFS, mounted on demand by PVE nodes for image movement |
| Guest network VLAN    | `netX: virtio=<mac>,bridge=<traffic-label>,tag=<vlan>` |
| Traffic label         | PVE bridge name (default `vmbr0`) |
| Volume / template     | PVE volid (`storage:vmid/vm-<vmid>-disk-x.qcow2`) |
| VM snapshot           | PVE snapshot (with or without RAM state) |

## Adding a Proxmox cluster

1. Zone: create an Advanced (or Basic) zone as usual. Use the PVE bridge names as
   traffic labels (e.g. management `vmbr0`, guest `vmbr1`).
2. Cluster: *Add Cluster* → hypervisor **Proxmox** (name only), then *Add Host* →
   host `https://<any-pve-node>:8006` (or just the node IP), username `root@pam`
   (or an API token id `user@realm!tokenname` as username with the token secret as
   password), password.
   - Optional URL query overrides: `?sshuser=root&sshpassword=...&sshport=22`
   - Adding one node discovers and registers **every node of the PVE cluster**;
     re-adding an already-known node is a harmless no-op.
3. Primary storage: *Add Primary Storage* → protocol `PreSetup`, path `/<pve-storage-id>`
   (the PVE storage must be enabled on every node in the cluster and support `images`).
   In the zone wizard the PreSetup "SR Name-Label" field takes the PVE storage id.
4. Secondary storage: standard NFS secondary storage.

### PVE node prerequisites

- PVE 8.x/9.x, `root@pam` API access (or API token with `VM.*`, `Datastore.*`,
  `Sys.Audit` privileges) and root SSH.
- Bridges for each CloudStack traffic type (VLAN-aware bridge or per-VLAN tagging
  handled by the plugin via `tag=`).
- NFS client (`nfs-common`) for secondary-storage mounts (present by default on PVE).
- No CloudStack packages required on the node.

## Global settings added / reused

| Setting | Default | Meaning |
|---|---|---|
| `router.template.proxmox` | systemvm template | VR template for Proxmox clusters |
| host detail `vmid.base` | `10000` | PVE vmid offset for CloudStack VMs |
| host detail `default.bridge` | `vmbr0` | fallback bridge when no traffic label |
| host detail `task.timeout.sec` | `600` | PVE async task wait budget |

## Feature parity status (v1)

| Area | Status |
|---|---|
| Host discovery (whole PVE cluster), stats, ping | ✅ |
| VM lifecycle: deploy/start/stop/reboot/destroy/reconfigure | ✅ |
| Volumes: create/attach/detach/resize/delete, templates → root disks | ✅ (file-based PVE storages: dir/NFS; RBD/LVM-thin partial) |
| Templates: register (qcow2 via SSVM), create-from-volume/snapshot | ✅ |
| System VMs: SSVM, Console Proxy, Virtual Router | ✅ (stock KVM systemvm template) |
| Console access via Console Proxy | ✅ (VNC, password-protected) |
| Live migration (shared storage) | ✅ (PVE `migrate` API) |
| VM snapshots (disk / disk+memory) | ✅ |
| Volume snapshots → secondary storage | ⚠️ offline volumes only in v1 |
| ISO attach/detach | ✅ (requires one iso-capable PVE storage) |
| Security groups (basic zones) | ❌ roadmap (PVE firewall API) |
| VXLAN isolation | ❌ roadmap (PVE SDN zones) |
| Storage live-migration between pools | ⚠️ `move_disk` plumbing present, not wired to MigrateVolume yet |
| Direct download templates | ❌ roadmap |
| CPU/RAM dynamic scaling (hot-plug) | ❌ roadmap |
| HA fence/investigate | ✅ (cluster-quorum aware, peer-node checks) |

## Development

```
mvn -pl plugins/hypervisors/proxmox -am -DskipTests install
```

The module ships in `cloud-client-ui` (management server webapp) automatically via
`client/pom.xml`. Core patches outside this module are deliberately tiny — see
`git log` for the `proxmox-native-plugin-4.22` branch; the meaningful ones are the
`HypervisorType.Proxmox` registration, system VM template maps, control-network
selection, and `hypervisor_capabilities` seed rows.
