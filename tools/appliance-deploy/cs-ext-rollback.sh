#!/usr/bin/env bash
#
# cs-ext-rollback.sh — undo cs-ext-to-native.sh: return a converted Proxmox cluster to the stock
# Extension-framework "External" cluster. Non-destructive to the PVE VMs.
#
# Flow:
#   1. back up the DB
#   2. UNMANAGE every VM in the native cluster (removes the CloudStack record; the PVE VM keeps
#      running) so the native cluster/hosts can be removed cleanly
#   3. put the native hosts into maintenance and delete them, then delete the native cluster
#   4. RE-ENABLE the original External cluster (its records were only disabled, never deleted)
#
# After this, to also revert the management binaries to stock:  cs-mgmt-deploy.sh stock
#
# DRY-RUN by default; pass --commit to execute.
#
# REQUIRED: --native-cluster <name|id>   --ext-cluster <stock External cluster id>
#           CS_DB_PASS (env)
#
set -euo pipefail

COMMIT=0
while [ $# -gt 0 ]; do case "$1" in
  --commit) COMMIT=1;;
  --native-cluster) NATIVE_CLUSTER="$2"; shift;;
  --ext-cluster) EXT_CLUSTER_ID="$2"; shift;;
  -h|--help) sed -n '2,24p' "$0"; exit 0;;
  *) echo "unknown arg $1" >&2; exit 1;;
esac; shift; done

: "${NATIVE_CLUSTER:?set --native-cluster <name|id>}"; : "${EXT_CLUSTER_ID:?set --ext-cluster <id>}"
: "${CS_DB_PASS:?export CS_DB_PASS}"
CS_DB_NAME="${CS_DB_NAME:-cloud}"; CS_DB_HOST="${CS_DB_HOST:-127.0.0.1}"; CS_DB_USER="${CS_DB_USER:-cloud}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/cloudstack-deploy}"; CMK="${CMK:-cmk}"

q(){ mysql -N -B -h "$CS_DB_HOST" -u "$CS_DB_USER" -p"$CS_DB_PASS" "$CS_DB_NAME" -e "$1" 2>/dev/null; }
log(){ printf '[%s] %s\n' "$(date -u +%T)" "$*"; }
cmk(){ if [ "$COMMIT" = 1 ]; then command "$CMK" "$@"; else printf '     %s %s\n' "$CMK" "$*"; fi; }

# resolve native cluster id/uuid (accepts name or numeric id)
if [[ "$NATIVE_CLUSTER" =~ ^[0-9]+$ ]]; then NCL_ID="$NATIVE_CLUSTER"; else NCL_ID="$(q "SELECT id FROM cluster WHERE name='$NATIVE_CLUSTER' AND hypervisor_type='Proxmox' AND removed IS NULL ORDER BY id DESC LIMIT 1")"; fi
[ -n "${NCL_ID:-}" ] || { echo "ERROR: native Proxmox cluster '$NATIVE_CLUSTER' not found" >&2; exit 1; }
EXT_UUID="$(q "SELECT uuid FROM cluster WHERE id=$EXT_CLUSTER_ID AND hypervisor_type='External' AND removed IS NULL")"
[ -n "${EXT_UUID:-}" ] || { echo "ERROR: External cluster id=$EXT_CLUSTER_ID not found" >&2; exit 1; }

log "rolling back: native cluster id=$NCL_ID  ->  re-enable External cluster id=$EXT_CLUSTER_ID"
log "native VMs to unmanage (PVE VMs are NOT deleted):"
q "SELECT id,instance_name,name,state FROM vm_instance WHERE hypervisor_type='Proxmox' AND removed IS NULL AND type='User' AND (host_id IN (SELECT id FROM host WHERE cluster_id=$NCL_ID) OR last_host_id IN (SELECT id FROM host WHERE cluster_id=$NCL_ID))" | sed 's/^/     /'
[ "$COMMIT" = 1 ] || log "(DRY-RUN — pass --commit to execute)"

# 1. backup
if [ "$COMMIT" = 1 ]; then
  mkdir -p "$BACKUP_DIR"; dump="$BACKUP_DIR/${CS_DB_NAME}-pre-rollback-cluster${NCL_ID}-$(date -u +%Y%m%dT%H%M%SZ).sql.gz"
  log "DB backup -> $dump"; mysqldump --single-transaction --routines --triggers -h "$CS_DB_HOST" -u "$CS_DB_USER" -p"$CS_DB_PASS" "$CS_DB_NAME" | gzip > "$dump"
fi

# 2. unmanage native VMs (keeps the PVE VM; removes only the CloudStack record)
while IFS=$'\t' read -r VMID INAME; do
  [ -n "${VMID:-}" ] || continue
  VUUID="$(q "SELECT uuid FROM vm_instance WHERE id=$VMID")"
  log "  unmanage $INAME"; cmk unmanage virtualmachine id="$VUUID"
done < <(q "SELECT id,instance_name FROM vm_instance WHERE hypervisor_type='Proxmox' AND removed IS NULL AND type='User' AND (host_id IN (SELECT id FROM host WHERE cluster_id=$NCL_ID) OR last_host_id IN (SELECT id FROM host WHERE cluster_id=$NCL_ID))")

# 3. remove native hosts + cluster
while IFS=$'\t' read -r HID HNAME; do
  [ -n "${HID:-}" ] || continue
  HUUID="$(q "SELECT uuid FROM host WHERE id=$HID")"
  log "  maintenance+delete host $HNAME"
  cmk prepare hostformaintenance id="$HUUID"
  cmk delete host id="$HUUID" forced=true
done < <(q "SELECT id,name FROM host WHERE cluster_id=$NCL_ID AND hypervisor_type='Proxmox' AND removed IS NULL")

# 3b. delete host is async — wait for the hosts to actually leave, then remove the cluster's
#     primary storage pools. A cluster that still has hosts OR storage pools cannot be deleted.
if [ "$COMMIT" = 1 ]; then
  deadline=$(( $(date +%s) + 180 ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    hc="$(q "SELECT COUNT(*) FROM host WHERE cluster_id=$NCL_ID AND removed IS NULL")"
    [ "${hc:-1}" -eq 0 ] && break
    log "  waiting for $hc host(s) to leave the native cluster..."; sleep 5
  done
fi
while IFS=$'\t' read -r SPID SPNAME; do
  [ -n "${SPID:-}" ] || continue
  SPUUID="$(q "SELECT uuid FROM storage_pool WHERE id=$SPID")"
  log "  maintenance+delete primary storage pool $SPNAME"
  cmk enable storagemaintenance id="$SPUUID" || true
  cmk delete storagepool id="$SPUUID" forced=true
done < <(q "SELECT id,name FROM storage_pool WHERE cluster_id=$NCL_ID AND removed IS NULL")

log "  delete native cluster id=$NCL_ID"
cmk delete cluster id="$(q "SELECT uuid FROM cluster WHERE id=$NCL_ID")"

# 4. re-enable the External cluster
log "  re-enable External cluster id=$EXT_CLUSTER_ID"
cmk update cluster id="$EXT_UUID" allocationstate=Enabled managedstate=Managed

log "-------------------------------------------------------------------------------------------"
log "Rollback $( [ "$COMMIT" = 1 ] && echo 'COMPLETE' || echo 'PLAN (dry-run)' ). The External cluster is managed again."
log "To also revert the management binaries to stock:  cs-mgmt-deploy.sh stock"
