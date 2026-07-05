#!/bin/bash
# Proxmox VE 9 first-boot bootstrap for MAAS deployability.
# Runs once, after network-online, on the freshly installed PVE system in the
# builder VM. Installs + configures cloud-init (MAAS datasource) and the
# qemu-guest-agent, then leaves cloud-init in a "clean" state so that when MAAS
# dd's this image onto a real node, cloud-init runs fresh and applies the
# per-node hostname / network / SSH keys from the MAAS metadata service.
set -euxo pipefail
exec > /var/log/maas-first-boot.log 2>&1
echo "=== MAAS first-boot bootstrap starting $(date -u) ==="

export DEBIAN_FRONTEND=noninteractive

# PVE 9 ships the pve-enterprise + pve-no-subscription lists. The enterprise
# repo 401s without a subscription and breaks apt update. Disable enterprise
# repos and use the public no-subscription repo so apt works.
for f in /etc/apt/sources.list.d/pve-enterprise.list \
         /etc/apt/sources.list.d/ceph.list \
         /etc/apt/sources.list.d/pve-enterprise.sources \
         /etc/apt/sources.list.d/ceph.sources; do
  if [ -f "$f" ]; then
    sed -i 's/^\([^#]\)/# \1/' "$f" || true
  fi
done

# Ensure the Debian trixie base + PVE no-subscription repos are present so
# cloud-init (from Debian) and qemu-guest-agent are installable.
if ! ls /etc/apt/sources.list.d/*no-subscription* >/dev/null 2>&1 && \
   ! grep -rq "pve-no-subscription" /etc/apt/ 2>/dev/null; then
  cat > /etc/apt/sources.list.d/pve-no-subscription.sources <<'EOF'
Types: deb
URIs: http://download.proxmox.com/debian/pve
Suites: trixie
Components: pve-no-subscription
Signed-By: /usr/share/keyrings/proxmox-archive-keyring.gpg
EOF
fi

apt-get update -o Acquire::Retries=3
apt-get install -y --no-install-recommends cloud-init qemu-guest-agent

# Configure cloud-init to prefer the MAAS datasource. MAAS custom (non-Ubuntu)
# images use the MAAS datasource over the metadata URL injected via the kernel
# cmdline / config-drive at deploy time.
cat > /etc/cloud/cloud.cfg.d/90_maas.cfg <<'EOF'
datasource_list: [ MAAS, NoCloud, ConfigDrive, None ]
EOF

# Keep cloud-init from clobbering the hostname on the builder here; MAAS sets it.
# Enable the services we need on the deployed node.
systemctl enable cloud-init.service cloud-init-local.service \
                 cloud-config.service cloud-final.service || true
systemctl enable qemu-guest-agent.service || true
systemctl enable ssh.service || systemctl enable sshd.service || true

# pvestatd races pmxcfs at boot and can die silently (node shows a grey '?' in
# the PVE GUI even though everything else works). Pin it behind pve-cluster and
# let systemd retry instead of giving up.
install -d /etc/systemd/system/pvestatd.service.d
cat > /etc/systemd/system/pvestatd.service.d/wait-pmxcfs.conf <<'EOF'
[Unit]
After=pve-cluster.service
Requires=pve-cluster.service
[Service]
Restart=on-failure
RestartSec=3
StartLimitBurst=10
EOF

# Permit root SSH login (MAAS + our baked key rely on root).
sed -i 's/^#\?PermitRootLogin.*/PermitRootLogin yes/' /etc/ssh/sshd_config || true

# The auto-installer/cloud-init can leave /root/.ssh/authorized_keys as a
# DANGLING SYMLINK, so key writes silently vanish and root SSH fails on the
# deployed node. Force it to be a real file holding the baked key.
if [ -L /root/.ssh/authorized_keys ]; then
  KEY_TARGET="$(readlink -f /root/.ssh/authorized_keys || true)"
  rm -f /root/.ssh/authorized_keys
  [ -n "$KEY_TARGET" ] && [ -f "$KEY_TARGET" ] && cp "$KEY_TARGET" /root/.ssh/authorized_keys
fi
install -d -m700 /root/.ssh
# root-ssh-keys from answer.toml also lands here on some installs; keep both.
touch /root/.ssh/authorized_keys && chmod 600 /root/.ssh/authorized_keys

# Reset cloud-init so the NEXT boot (on the real MAAS node) is treated as a
# fresh first boot: re-detects datasource, re-applies hostname/network/keys.
cloud-init clean --logs --seed || cloud-init clean --logs || true
# Remove any machine-id so systemd regenerates it per node (avoids DHCP clashes).
: > /etc/machine-id || true
rm -f /var/lib/dbus/machine-id || true

echo "=== MAAS first-boot bootstrap finished $(date -u) ==="
