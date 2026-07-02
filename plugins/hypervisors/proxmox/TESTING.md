# Proxmox Plugin — Test Lab Guide (Ubuntu MAAS, 10 nodes)

Suggested layout for a 10-node MAAS deployment dedicated to developing/validating
this plugin:

| Nodes | Role |
|---|---|
| 1 | CloudStack management server + MySQL (Ubuntu 24.04) |
| 1 | NFS server: secondary storage + a shared primary storage export |
| 3 | PVE cluster A (`pve-a1..a3`) — main test cluster, shared NFS primary |
| 3 | PVE cluster B (`pve-b1..b3`) — second cluster: migration-between-clusters, local-storage testing |
| 2 | KVM hosts (stock CloudStack KVM agent) — behavioural reference for parity checks |

Keeping two KVM reference hosts in the same zone is the fastest way to A/B a
behaviour ("what does KVM return for this command?") while debugging.

## 1. Deploy the OSes with MAAS

- Management + NFS + KVM nodes: Ubuntu 24.04 LTS.
- PVE nodes: MAAS-deploy Debian 13 then install Proxmox VE on top
  (standard `pve-no-subscription` repo procedure), or use packer images.
  Ensure each PVE node has two bridges: `vmbr0` (management+public) and `vmbr1`
  (guest, VLAN-aware: `bridge-vlan-aware yes`, `bridge-vids 100-999`).

## 2. Build and install CloudStack from this branch

On the management node (or build on a workstation and copy debs):

```bash
sudo apt install openjdk-17-jdk maven python3 genisoimage nfs-common mysql-server
git clone <your-fork> cloudstack && cd cloudstack && git checkout proxmox-native-plugin
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
  -m /mnt/secondary -u https://download.cloudstack.org/systemvm/4.20/systemvmtemplate-4.20.2-x86_64-kvm.qcow2.bz2 \
  -h kvm -s <mgmt-secret-key> -F
```

(the same template file serves both the `kvm` and `proxmox` hypervisor entries).

## 3. PVE cluster prep

On each PVE cluster:

```bash
pvecm create cs-lab-a          # on first node; join others with pvecm add
pvesm add nfs shared-primary --server <nfs-ip> --export /export/primary-a --content images
# optional: a storage with iso content for ISO attach tests
pvesm set local --content iso,vztmpl,backup
```

Verify from the management server before adding the cluster:

```bash
curl -k -d 'username=root@pam' --data-urlencode 'password=...' https://pve-a1:8006/api2/json/access/ticket
ssh root@pve-a1 'pvesm status && qemu-img --version'
```

## 4. Zone bring-up order

1. Advanced zone; physical networks with traffic labels = bridge names
   (Management/Public → `vmbr0`, Guest → `vmbr1`).
2. Pod, guest VLAN range (must be inside the `bridge-vids` range on `vmbr1`).
3. Cluster: hypervisor **Proxmox**, URL `https://pve-a1:8006`, `root@pam` + password.
   All three nodes should appear as hosts in state `Up`.
4. Primary storage: `PreSetup`, path `/shared-primary`, scope Cluster.
5. Secondary storage: `nfs://<nfs-ip>/export/secondary`.
6. Enable the zone → watch SSVM + CPVM deploy on the PVE cluster
   (`s-1-VM`, `v-2-VM` visible in the PVE UI with vmids 10001/10002).

## 5. Smoke test sequence (mirrors KVM parity)

| # | Test | Verifies |
|---|---|---|
| 1 | SSVM+CPVM reach Up, agents connect | systemvm template boot, boot-args patch, mgmt-network control channel |
| 2 | Register a qcow2 template | SSVM ↔ secondary storage |
| 3 | Deploy an isolated-network VM | template copy to primary, VR deploy, VLAN tagging, DHCP |
| 4 | Console open in UI | vncproxy args, QMP password, CPVM path |
| 5 | Stop/start/reboot VM | lifecycle |
| 6 | Attach/detach/resize data disk | volume ops, `move_disk` reassign |
| 7 | Live-migrate VM between two nodes | PVE migrate API |
| 8 | VM snapshot (with memory), revert | snapshot API |
| 9 | Volume snapshot (stopped VM) → template from snapshot | qemu-img paths over SSH |
| 10 | Host maintenance → VMs migrate away | maintenance + HA plumbing |
| 11 | Kill a node (power off) | investigator/fencer, VM HA restart |
| 12 | Create VPC with two tiers | VR multi-nic, PlugNic |

Log locations while debugging: `/var/log/cloudstack/management/management-server.log`
(grep `ProxmoxResource`), PVE side `journalctl -u pvedaemon`, task log in PVE UI.
