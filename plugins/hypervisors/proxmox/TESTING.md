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

# Proxmox Plugin — Test Lab Guide (Ubuntu MAAS, 10 nodes)

Suggested layout for a 10-node MAAS deployment dedicated to developing/validating
this plugin. Ceph is first-class here because it mirrors the production platform:
cluster A runs hyper-converged Ceph, cluster B consumes the same Ceph remotely.

| Nodes | Role |
|---|---|
| 1 | CloudStack management server + MySQL (Ubuntu 24.04) |
| 1 | NFS server: secondary storage (+ optional NFS primary for A/B comparison) |
| 3 | PVE cluster A (`pve-a1..a3`) — **hyper-converged Ceph** (pveceph): RBD primary + CephFS |
| 3 | PVE cluster B (`pve-b1..b3`) — consumes cluster A's Ceph as **external/remote** RBD + CephFS |
| 2 | KVM hosts (stock CloudStack KVM agent) — behavioural reference for parity checks |

Cluster A nodes need at least one spare data disk each for OSDs — set the MAAS
storage layout so the OS lands on one disk and the others are left raw.
Keeping two KVM reference hosts in the same zone is the fastest way to A/B a
behaviour ("what does KVM return for this command?") while debugging.

## 1. Deploy the OSes with MAAS

- Management + NFS + KVM nodes: Ubuntu 24.04 LTS.
- PVE nodes: MAAS-deploy Debian 13 then install Proxmox VE on top
  (standard `pve-no-subscription` repo procedure), or use packer images.
  Ensure each PVE node has two bridges: `vmbr0` (management+public) and `vmbr1`
  (guest, VLAN-aware: `bridge-vlan-aware yes`, `bridge-vids 100-999`).
- A dedicated Ceph VLAN/subnet for cluster A's storage traffic is worth it if the
  NICs allow; otherwise Ceph shares `vmbr0`.

## 2. Build and install CloudStack from this branch

On the management node (or build on a workstation and copy debs):

```bash
sudo apt install openjdk-17-jdk maven python3 genisoimage nfs-common mysql-server
git clone <your-fork> cloudstack && cd cloudstack && git checkout proxmox-native-plugin-4.22
mvn -DskipTests -P developer,systemvm clean install
# deb packaging:
dpkg-buildpackage -uc -us -b     # or: packaging/build-deb.sh
sudo apt install ./dist/debbuild/cloudstack-management_*.deb ./cloudstack-common_*.deb
```

DB + seed (fresh install picks up the Proxmox `hypervisor_capabilities` rows and
systemvm template registration automatically):

```bash
sudo cloudstack-setup-databases cloud:password@localhost --deploy-as=root
sudo cloudstack-setup-management
```

Seed the system VM template onto secondary storage (Proxmox uses the KVM qcow2
template):

```bash
sudo /usr/share/cloudstack-common/scripts/storage/secondary/cloud-install-sys-tmplt \
  -m /mnt/secondary -u https://download.cloudstack.org/systemvm/4.22/systemvmtemplate-4.22.1-x86_64-kvm.qcow2.bz2 \
  -h kvm -s <mgmt-secret-key> -F
```

(the same template file serves both the `kvm` and `proxmox` hypervisor entries).

## 3. Cluster A — hyper-converged Ceph

```bash
# on pve-a1 (repeat join on a2/a3)
pvecm create cs-lab-a
pveceph install --repository no-subscription
pveceph init --network <ceph-subnet-cidr>
pveceph mon create                    # on all three nodes
pveceph osd create /dev/sdX           # each spare disk, each node
pveceph pool create cs-rbd --add_storages        # RBD pool + PVE storage 'cs-rbd'
pveceph fs create --name cs-cephfs --add-storage # CephFS + PVE storage 'cs-cephfs'
pvesm set cs-cephfs --content iso,backup,vztmpl  # cephfs cannot hold VM images
ceph -s                                          # HEALTH_OK before continuing
```

CloudStack primary storage for cluster A: `PreSetup`, path `/cs-rbd`.

## 4. Cluster B — remote Ceph client

```bash
pvecm create cs-lab-b
# auth: create a client key on cluster A for external use
ssh pve-a1 'ceph auth get-or-create client.cloudstack-b \
  mon "profile rbd" osd "profile rbd pool=cs-rbd" mds "allow rw" -o /tmp/b.keyring && cat /tmp/b.keyring'
# on pve-b1: register the EXTERNAL pools (monhost = cluster A mons)
pvesm add rbd cs-rbd-remote --monhost "10.x.x.a1;10.x.x.a2;10.x.x.a3" \
  --pool cs-rbd --username cloudstack-b --content images
# key goes to /etc/pve/priv/ceph/cs-rbd-remote.keyring (pvesm prompts / copy manually)
pvesm add cephfs cs-cephfs-remote --monhost "10.x.x.a1;10.x.x.a2;10.x.x.a3" \
  --subdir /remote-b --username cloudstack-b --content iso,backup
```

CloudStack primary storage for cluster B: `PreSetup`, path `/cs-rbd-remote`.
This validates the plugin against external-Ceph auth (keyring/conf flags parsed
from `pvesm path` output), which is exactly the production shape.

Verify from the management server before adding either cluster:

```bash
curl -k -d 'username=root@pam' --data-urlencode 'password=...' https://pve-a1:8006/api2/json/access/ticket
ssh root@pve-a1 'pvesm status && qemu-img --version && rbd -p cs-rbd ls'
```

## 5. Zone bring-up order

1. Advanced zone; physical networks with traffic labels = bridge names
   (Management/Public → `vmbr0`, Guest → `vmbr1`).
2. Pod, guest VLAN range (must be inside the `bridge-vids` range on `vmbr1`).
3. Cluster: hypervisor **Proxmox**, URL `https://pve-a1:8006`, `root@pam` + password.
   All three nodes should appear as hosts in state `Up`.
4. Primary storage: `PreSetup`, path `/cs-rbd`, scope Cluster.
5. Secondary storage: `nfs://<nfs-ip>/export/secondary`.
6. Enable the zone → watch SSVM + CPVM deploy on the PVE cluster
   (`s-1-VM`, `v-2-VM` visible in the PVE UI with vmids 10001/10002).
7. Repeat 3–4 for cluster B with `/cs-rbd-remote`.

## 6. Smoke test sequence (mirrors KVM parity)

| # | Test | Verifies |
|---|---|---|
| 1 | SSVM+CPVM reach Up, agents connect | systemvm template boot, boot-args patch, mgmt-network control channel |
| 2 | Register a qcow2 template | SSVM ↔ secondary storage |
| 3 | Deploy an isolated-network VM (cluster A) | template → **RBD raw** copy, VR deploy, VLAN tagging, DHCP |
| 4 | Console open in UI | vncproxy args, QMP password, CPVM path |
| 5 | Stop/start/reboot VM | lifecycle |
| 6 | Attach/detach/resize data disk on RBD | raw alloc, `rbd rename` reassign, rbd resize |
| 7 | Live-migrate VM between two nodes | PVE migrate API over shared RBD |
| 8 | VM snapshot (with memory), revert | PVE snapshot API on RBD |
| 9 | Volume snapshot (stopped VM) → template from snapshot | `rbd snap` + qemu-img rbd→qcow2 export |
| 10 | ISO attach from CephFS storage | cephfs iso-content path |
| 11 | Deploy VM on cluster B (remote RBD) | external-Ceph auth flags, cross-cluster storage |
| 12 | Host maintenance → VMs migrate away | maintenance + HA plumbing |
| 13 | Kill a node (power off) | investigator/fencer, quorum awareness, VM HA restart |
| 14 | Create VPC with two tiers | VR multi-nic, PlugNic |
| 15 | Repeat 3/6/9 on an NFS primary | file-storage (qcow2) code paths stay healthy |

## 7. Marvin integration tests (the suite CI runs)

CloudStack CI runs Marvin smoke/component suites against the Simulator; the same
tests run against a real zone. Once the zone is up:

```bash
cd cloudstack/tools/marvin && pip3 install --user .
# describe the lab zone in marvin.cfg (mgmt server IP, zone/pod/cluster ids, DB creds)
python3 -m marvin.deployDataCenter -i marvin.cfg   # only if deploying zone via marvin
nosetests --with-marvin --marvin-config=marvin.cfg -w test/integration/smoke \
  test_vm_life_cycle.py test_volumes.py test_snapshots.py test_ssvm.py \
  test_routers.py test_network.py test_templates.py
```

Start with `test_ssvm.py` (system VM health) — everything else depends on it.
The hypervisor-agnostic smoke tests should pass unmodified; failures localize to
either the plugin (ProxmoxResource logs) or genuinely unsupported v1 features
(see README parity matrix).

Log locations while debugging: `/var/log/cloudstack/management/management-server.log`
(grep `ProxmoxResource`), PVE side `journalctl -u pvedaemon`, task log in PVE UI,
Ceph health `ceph -s` on cluster A.
