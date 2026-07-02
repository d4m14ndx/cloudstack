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

# Proxmox Plugin — Internal API Contract (v1)

This file is the single source of truth for cross-class signatures while the plugin is
under initial development. All classes live in the `cloud-plugin-hypervisor-proxmox`
Maven module. Target: CloudStack 4.22.1.0, Proxmox VE 8.x/9.x, Java 11 bytecode.

## Design summary

Direct-connect hypervisor (VMware model): `ProxmoxResource` is instantiated on the
management server by `ProxmoxServerDiscoverer` (one resource per PVE node), attached via
`DirectAgentAttache`. Primary channel is the PVE REST API (`https://node:8006/api2/json`),
with SSH-to-node (root credentials from host-add) for operations the API cannot do:
qemu-img template import from secondary NFS. System VM boot-args go through the qemu guest agent (PVE API).

- CloudStack VM id `N` maps to PVE vmid `vmidBase + N` (default base 10000).
- CloudStack primary storage pool of type `PreSetup` maps to an existing PVE storage id:
  pool path `/<pve-storage-id>` (e.g. presetup://localhost/local-lvm → path `/local-lvm`).
- NicTO broadcastUri `vlan://X` → PVE `netN: virtio=<mac>,bridge=<label-or-default>,tag=X`.
  Traffic-label on the physical network names the PVE bridge; default `vmbr0`.
- VNC: per-VM qemu `args` add `-vnc 0.0.0.0:<display>,password=on` with
  `display = vmid % 20000`, port = `5900 + display`.
  VNC password from `VirtualMachineTO.getVncPassword()` set via
  QMP `set_password` through `POST /nodes/{node}/qemu/{vmid}/monitor`.
- System VM boot args: replicate KVM 4.21 `scripts/vm/hypervisor/kvm/patch.sh` — wait
  for the qemu guest agent (`agent/ping`), then `agent/file-write` the raw boot args to
  `/var/cache/cloud/cmdline` through the PVE API (VMs are created with `agent=1`).

## com.cloud.hypervisor.proxmox.api.ProxmoxApiClient

Constructor:
```java
public ProxmoxApiClient(String host, int port, String username, String password,
                        String tokenId, String tokenSecret, boolean verifyTls,
                        int connectTimeoutSec, int readTimeoutSec)
```
If `tokenId != null` use `Authorization: PVEAPIToken=<tokenId>=<tokenSecret>` header auth;
else ticket auth via `POST /access/ticket` (cookie `PVEAuthCookie`, header
`CSRFPreventionToken` on write verbs; re-authenticate on 401 once, tickets last 2h).
Uses Apache HttpClient 4.5 + Gson. All paths are relative to `/api2/json`.

Generic verbs (throw `ProxmoxApiException` on non-2xx; return the `data` member):
```java
public JsonElement get(String path)
public JsonElement post(String path, Map<String, Object> params)
public JsonElement put(String path, Map<String, Object> params)
public JsonElement delete(String path)
public String postTask(String path, Map<String, Object> params)   // returns UPID string
public void waitForTask(String node, String upid, long timeoutMs) // poll task status; throw on exitstatus != "OK" or timeout
```

Typed helpers (all throw ProxmoxApiException):
```java
public String getVersion()                                  // /version → "8.2.4"
public List<String> getNodeNames()                          // /nodes
public JsonObject getNodeStatus(String node)                // /nodes/{n}/status
public JsonArray  getClusterResources(String type)          // /cluster/resources?type=vm|storage|node
public String getClusterName()                              // /cluster/status → cluster name, or node name if standalone
public String findNodeOfVm(int vmid)                        // /cluster/resources type=vm → node name or null
public Integer findVmidByName(String name)                  // /cluster/resources type=vm, match name field, null if absent
public JsonObject getVmStatus(String node, int vmid)        // /nodes/{n}/qemu/{v}/status/current
public JsonObject getVmConfig(String node, int vmid)        // /nodes/{n}/qemu/{v}/config (current, not pending)
public void createVm(String node, int vmid, Map<String, Object> config, long timeoutMs)   // POST /nodes/{n}/qemu (task)
public void setVmConfig(String node, int vmid, Map<String, Object> config)                // POST .../config (synchronous form)
public void startVm(String node, int vmid, long timeoutMs)      // POST .../status/start (task)
public void stopVm(String node, int vmid, long timeoutMs)       // POST .../status/stop (task, hard)
public boolean shutdownVm(String node, int vmid, long timeoutMs) // POST .../status/shutdown with timeout+forceStop=0; false if failed/timed out
public void rebootVm(String node, int vmid, long timeoutMs)     // POST .../status/reboot
public void resetVm(String node, int vmid, long timeoutMs)      // POST .../status/reset
public void destroyVm(String node, int vmid, long timeoutMs)    // DELETE /nodes/{n}/qemu/{v}?purge=1&destroy-unreferenced-disks=0 (task)
public void migrateVm(String node, int vmid, String targetNode, boolean online,
                      boolean withLocalDisks, long timeoutMs)   // POST .../migrate (task)
public void resizeDisk(String node, int vmid, String disk, long deltaOrAbsoluteBytes, boolean absolute)
        // PUT .../resize  disk e.g. "scsi0", size "+<bytes>" or absolute "<bytes>"
public void createSnapshot(String node, int vmid, String snapName, String description,
                           boolean withVmState, long timeoutMs)  // POST .../snapshot (task)
public void deleteSnapshot(String node, int vmid, String snapName, long timeoutMs)
public void rollbackSnapshot(String node, int vmid, String snapName, long timeoutMs)
public JsonArray listSnapshots(String node, int vmid)
public JsonArray listStorage(String node)                        // /nodes/{n}/storage
public JsonObject getStorageStatus(String node, String storage)  // /nodes/{n}/storage/{s}/status
public JsonArray listStorageContent(String node, String storage, String content) // content nullable
public String allocDiskImage(String node, String storage, int ownerVmid,
                             String filename, long sizeBytes, String format)
        // POST /nodes/{n}/storage/{s}/content  (size takes e.g. "10G"/"1024M" — use bytes/1024 + "K" ceil) → returns volid
public void freeVolume(String node, String storage, String volid, long timeoutMs) // DELETE content/{volid} (task)
public String getVolumePath(String node, String volid)           // SSH-free path lookup unsupported by API for all types; implement via /nodes/{n}/storage/{s}/content/{volid} → 'path' field
public void moveDisk(String node, int vmid, String disk, Integer targetVmid,
                     String targetStorage, boolean deleteSource, long timeoutMs)
        // POST /nodes/{n}/qemu/{v}/move_disk  ('disk', plus 'target-vmid'+'target-disk' or 'storage')
public void unlinkDisk(String node, int vmid, String idList, boolean force)
        // PUT /nodes/{n}/qemu/{v}/unlink?idlist=...&force=1
public JsonObject vncProxy(String node, int vmid)                // POST .../vncproxy (websocket=0... include generate-password=0) → {ticket,port,user,...}
public String monitorCommand(String node, int vmid, String command) // POST .../monitor → human-readable string
public int getNextVmid()                                         // /cluster/nextid
public boolean isQuorate()                                       // /cluster/status quorate flag; standalone node → true
public String getNodeNetworkAddress(String node, String iface)   // /nodes/{n}/network/{iface} → address or null
public void close()
```

`ProxmoxApiException extends CloudRuntimeException` (com.cloud.utils.exception) with
`int getStatusCode()` and the PVE error body in the message.

## com.cloud.hypervisor.proxmox.resource.ProxmoxResource

```java
public class ProxmoxResource extends ServerResourceBase
        implements ServerResource, VirtualRouterDeployer {
    // configure(name, params) keys set by discoverer:
    //   zone, pod, cluster (String ids), guid,
    //   url        — https://<node-mgmt-ip>:8006
    //   node       — PVE node name (e.g. "pve1")
    //   nodeAddress— IP/host used for API + SSH
    //   username/password — PVE API realm user (root@pam typical)
    //   token.id/token.secret — optional API token (preferred if set)
    //   ssh.username/ssh.password/ssh.port — node shell (defaults root/password/22)
    //   vmid.base  — default "10000"
    //   default.bridge — default "vmbr0"
    //   verify.tls — "false" default
    //   task.timeout.sec — default "600"

    // Helper contract used by storage/other classes:
    public ProxmoxApiClient getApiClient();
    public String getNodeName();
    public String getNodeAddress();
    public int getVmidBase();
    public int vmidOf(VirtualMachineTO vm);            // vmidBase + vm.getId()
    public int vmidOfInstanceName(String internalName); // parse i-a-b-VM / r-N-VM / v-N-VM / s-N-VM → base + N
    public Pair<Boolean, String> executeOnNode(String command);           // SSH exec, 10 min default timeout
    public Pair<Boolean, String> executeOnNode(String command, int timeoutSec);
    public String getPveStorageId(DataStoreTO store);   // PrimaryDataStoreTO → last path segment
    public long getTaskTimeoutMs();
    public String getDefaultBridge();
    public String resolveBridge(NicTO nic);             // traffic label if set else default bridge
}
```
`executeInVR`/`createFileInVR` (VirtualRouterDeployer) SSH from the management server to
the VR control IP (management network) on port 3922 with the systemvm key
(`/var/cloudstack/management/.ssh/id_rsa`) — copy the VMware implementation
(`VmwareResource.executeInVR`, uses `VRScripts` + `SshHelper`).

## com.cloud.hypervisor.proxmox.storage

```java
public class ProxmoxStorageProcessor implements StorageProcessor {
    public ProxmoxStorageProcessor(ProxmoxResource resource) {...}
}
public class ProxmoxStorageSubsystemCommandHandler extends StorageSubsystemCommandHandlerBase {
    public ProxmoxStorageSubsystemCommandHandler(StorageProcessor processor) { super(processor); }
}
```
Volume path convention: CloudStack volume/template `path` stores the PVE **volid**
(`<storage>:<ownerVmid>/vm-<ownerVmid>-disk-<n>.qcow2` or subdir form). Template on
primary = volid owned by reserved vmid `vmidBase - 1` (the "template holder"; no actual
VM exists). Data disks alloc'd lazily under their first owner VM; reattach to another VM
uses `move_disk` with `target-vmid`.

## Command coverage (v1 must-implement in ProxmoxResource.executeRequest)

ReadyCommand, CheckHealthCommand, PingTestCommand, GetHostStatsCommand, GetVmStatsCommand,
GetVmDiskStatsCommand, GetVmNetworkStatsCommand (empty answers OK where PVE lacks data),
StartCommand, StopCommand, RebootCommand, RebootRouterCommand, CheckVirtualMachineCommand,
PrepareForMigrationCommand, MigrateCommand, CheckOnHostCommand, FenceCommand, MaintainCommand,
GetVncPortCommand, CheckNetworkCommand, ModifyStoragePoolCommand, DeleteStoragePoolCommand,
GetStorageStatsCommand, GetVolumeStatsCommand, ResizeVolumeCommand, ModifySshKeysCommand,
CheckSshCommand, NetworkElementCommand (→ VirtualRoutingResource), PlugNicCommand,
UnPlugNicCommand, CreateVMSnapshotCommand, DeleteVMSnapshotCommand, RevertToVMSnapshotCommand,
NetworkUsageCommand, StorageSubSystemCommand (→ handler), UnsupportedAnswer for the rest.
