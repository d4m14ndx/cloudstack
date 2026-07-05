# Building a Proxmox VE MAAS image (for the test lab)

CloudStack's Proxmox plugin needs PVE hosts. In a MAAS-managed lab, the fastest
way to get reproducible, production-shaped PVE nodes is a **custom MAAS dd image**
built from the **official Proxmox ISO** (not a Debian-then-`apt`-convert), with
just enough cloud-init glue for MAAS to assign per-node identity at deploy.

This directory holds the artifacts used to build the image for the reference lab.
It is a lab convenience, not part of the plugin itself.

## What's here

| File | Purpose |
|---|---|
| `answer.toml.example` | `proxmox-auto-install-assistant` answer file (fill in your SSH key + root password) |
| `first-boot.sh` | runs once in the build VM: installs cloud-init (MAAS datasource) + qemu-guest-agent, netplan shim, fixes root SSH, resets cloud-init to a clean first-boot state |
| `curtin-hooks` | runs on the real node at deploy time (curtin dd path): applies MAAS cloud-init/network config, creates the UEFI boot entry via `efibootmgr` |

## Build outline (on a KVM-capable Ubuntu builder)

1. `proxmox-auto-install-assistant prepare-iso proxmox-ve_9.x.iso --fetch-from iso --answer-file answer.toml` → an unattended ISO.
2. Boot it under QEMU/KVM into a **~24G raw disk**. Two hard-won details:
   - attach the target disk as **SATA/AHCI** so it enumerates as `/dev/sda` to match `disk-list = ["sda"]` — with virtio (`/dev/vda`) the installer silently writes nothing;
   - use **non-Secure-Boot OVMF** to match nodes with Secure Boot disabled.
3. `first-boot.sh` runs once (wired via the answer file's `[first-boot]`), then the VM powers off.
4. Mount the raw image, drop `/curtin/curtin-hooks` (755) into the root filesystem, unmount.
5. `gzip`/`pigz` → `.dd.gz`, upload as a MAAS boot resource:
   `maas <profile> boot-resources create name='custom/proxmox-ve-9' architecture='amd64/generic' content@=proxmox-ve-9.dd.gz`
6. Deploy with `osystem=custom`, `distro_series=proxmox-ve-9`.

## Gotchas this image works around (all learned the hard way)

- **`answer.toml` keys are kebab-case** in `proxmox-auto-install-assistant` 9.2.7 (`root-password`, `disk-list`) — the underscore form in older examples is rejected.
- **curtin needs `/curtin/curtin-hooks`** in a dd image's root FS. Without it, deploy fails with `Did not find any filesystem on ['sda'] that contained one of ['curtin', ...]`. The hook also creates the UEFI NVRAM boot entry, which the build VM's firmware entry does not carry into the image.
- **MAAS validates `netplan info` succeeds** in the target (`99-validate-custom-image-has-netplan`). PVE uses ifupdown2, so install `netplan.io` as an inert shim (no yaml under `/etc/netplan`) and pin cloud-init's renderer to `eni` (`/etc/cloud/cloud.cfg.d/95_renderer.cfg`) so network config lands in `/etc/network/interfaces`.
- **`/root/.ssh/authorized_keys` can be a dangling symlink**, so key writes vanish and root SSH fails on the deployed node (the MAAS-injected user still works). `first-boot.sh` forces it to a real file.
- **`pvestatd` races `pmxcfs` at boot** and dies silently — the node then shows a grey `?` in the PVE GUI while everything else works. `first-boot.sh` bakes a systemd drop-in ordering it after `pve-cluster.service` with `Restart=on-failure`.
- Post-deploy each node still needs its LVM grown to the full disk (the image is ~24G): `sgdisk -e /dev/sda; sfdisk -N 3` to grow the partition, `pvresize`, then `lvextend` root.
