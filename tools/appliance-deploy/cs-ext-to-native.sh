#!/usr/bin/env bash
#
# cs-ext-to-native.sh — convert a stock "External" (Extension-framework) Proxmox cluster to a native
# Proxmox-plugin cluster on OUR build, by RE-ADOPTING the running PVE VMs through supported CloudStack
# APIs (addCluster + listUnmanagedInstances + importUnmanagedInstance). No hypervisor_type surgery and
# no destructive Proxmox operations: the actual PVE VMs are never stopped or deleted.
#
# Flow (per External cluster):
#   1. back up the DB
#   2. DISABLE the External cluster (Unmanaged) so CloudStack stops orchestrating it; PVE keeps running
#   3. addCluster (native Proxmox) for the SAME PVE cluster, using the connection you supply
#   4. wait for the native hosts to come Up
#   5. for each External user VM: listUnmanagedInstances on the native cluster, then
#      importUnmanagedInstance re-using the VM's existing NIC->network map + service offering
#   6. verify, and LEAVE the External cluster disabled (not deleted) so rollback is possible
#
# DRY-RUN by default: prints the exact commands it would run. Pass --commit to execute.
# Rollback: cs-ext-rollback.sh (removes the native imports, re-enables the External cluster).
#
# REQUIRED (env or flags):
#   EXT_CLUSTER_ID   the stock External cluster id to convert            (--ext-cluster)
#   PVE_URL          native Proxmox API URL, e.g. https://10.0.0.10:8006 (--url)
#   PVE_USER         API user, e.g. root@pam or user@realm!token         (--user)
#   PVE_PASS         API password / token secret (env only)
#   PVE_SSH_PASS     node root ssh password (env only; or bake into URL ?sshpassword=)
#   CS_DB_PASS       cloud DB password (for backup + reading records)
# Optional: NATIVE_CLUSTER_NAME (default derived), HEALTH_TIMEOUT (300).
#
set -euo pipefail

COMMIT=0
while [ $# -gt 0 ]; do case "$1" in
  --commit) COMMIT=1;;
  --ext-cluster) EXT_CLUSTER_ID="$2"; shift;;
  --url) PVE_URL="$2"; shift;;
  --user) PVE_USER="$2"; shift;;
  --name) NATIVE_CLUSTER_NAME="$2"; shift;;
  -h|--help) sed -n '2,34p' "$0"; exit 0;;
  *) echo "unknown arg $1" >&2; exit 1;;
esac; shift; done

: "${EXT_CLUSTER_ID:?set --ext-cluster / EXT_CLUSTER_ID}"
: "${PVE_URL:?set --url / PVE_URL}"; : "${PVE_USER:?set --user / PVE_USER}"
: "${PVE_PASS:?export PVE_PASS}"; : "${CS_DB_PASS:?export CS_DB_PASS}"
CS_DB_NAME="${CS_DB_NAME:-cloud}"; CS_DB_HOST="${CS_DB_HOST:-127.0.0.1}"; CS_DB_USER="${CS_DB_USER:-cloud}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/cloudstack-deploy}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-300}"
CMK="${CMK:-cmk}"

q(){ mysql -N -B -h "$CS_DB_HOST" -u "$CS_DB_USER" -p"$CS_DB_PASS" "$CS_DB_NAME" -e "$1" 2>/dev/null; }
log(){ printf '[%s] %s\n' "$(date -u +%T)" "$*"; }
run(){ if [ "$COMMIT" = 1 ]; then log "RUN: $*"; "$@"; else log "DRY: $*"; fi; }
cmk(){ if [ "$COMMIT" = 1 ]; then "$CMK" "$@"; else printf '     %s %s\n' "$CMK" "$*"; fi; }

# ---- preflight -------------------------------------------------------------------------------
[ "$(command -v "$CMK")" ] || { echo "ERROR: cmk (CloudMonkey) not found/configured" >&2; exit 1; }
flavor="$(/usr/local/sbin/cs-mgmt-deploy.sh status 2>/dev/null | awk '/^flavor:/{print $2}')" || true
[ "${flavor:-}" = ours ] || log "WARNING: management build flavour is '${flavor:-unknown}', expected 'ours' (native plugin). Continue only if intended."

# NB: `|| true` so an empty result doesn't trip `set -e` before the friendly error below.
read -r CL_NAME CL_ZONE CL_POD < <(q "SELECT name, data_center_id, pod_id FROM cluster WHERE id=$EXT_CLUSTER_ID AND hypervisor_type='External' AND removed IS NULL") || true
[ -n "${CL_NAME:-}" ] || { echo "ERROR: no External cluster with id=$EXT_CLUSTER_ID" >&2; exit 1; }
NATIVE_CLUSTER_NAME="${NATIVE_CLUSTER_NAME:-${CL_NAME}-native}"
log "converting External cluster '$CL_NAME' (id=$EXT_CLUSTER_ID, zone=$CL_ZONE pod=$CL_POD) -> native '$NATIVE_CLUSTER_NAME'"
log "external hosts:"; q "SELECT id,name,status FROM host WHERE cluster_id=$EXT_CLUSTER_ID AND hypervisor_type='External' AND removed IS NULL" | sed 's/^/     /'
log "external user VMs to re-adopt:"; q "SELECT id,instance_name,name,state FROM vm_instance WHERE hypervisor_type='External' AND removed IS NULL AND type='User' AND (host_id IN (SELECT id FROM host WHERE cluster_id=$EXT_CLUSTER_ID) OR last_host_id IN (SELECT id FROM host WHERE cluster_id=$EXT_CLUSTER_ID))" | sed 's/^/     /'
[ "$COMMIT" = 1 ] || log "(DRY-RUN — pass --commit to execute; review the plan above and the commands below)"

# ---- 1. backup -------------------------------------------------------------------------------
if [ "$COMMIT" = 1 ]; then
  mkdir -p "$BACKUP_DIR"; dump="$BACKUP_DIR/${CS_DB_NAME}-pre-convert-cluster${EXT_CLUSTER_ID}-$(date -u +%Y%m%dT%H%M%SZ).sql.gz"
  log "DB backup -> $dump"
  mysqldump --single-transaction --routines --triggers -h "$CS_DB_HOST" -u "$CS_DB_USER" -p"$CS_DB_PASS" "$CS_DB_NAME" | gzip > "$dump"
  echo "$dump" > "$BACKUP_DIR/.last-convert-db"
else log "would DB-dump the '$CS_DB_NAME' database"; fi

# ---- 2. disable the External cluster (stop orchestration; PVE keeps running) -----------------
CL_UUID="$(q "SELECT uuid FROM cluster WHERE id=$EXT_CLUSTER_ID")"
cmk update cluster id="$CL_UUID" allocationstate=Disabled managedstate=Unmanaged

# ---- 3. add the native Proxmox cluster -------------------------------------------------------
# NB: SSH node creds ride in the URL as ?sshpassword=...  (see the ProxmoxServerDiscoverer docs)
NURL="$PVE_URL"; [ -n "${PVE_SSH_PASS:-}" ] && NURL="${PVE_URL}?sshpassword=${PVE_SSH_PASS}"
ZONE_UUID="$(q "SELECT uuid FROM data_center WHERE id=$CL_ZONE")"; POD_UUID="$(q "SELECT uuid FROM host_pod_ref WHERE id=$CL_POD")"
cmk add cluster zoneid="$ZONE_UUID" podid="$POD_UUID" hypervisor=Proxmox clustertype=CloudManaged \
    clustername="$NATIVE_CLUSTER_NAME" username="$PVE_USER" password="$PVE_PASS" url="$NURL"

# ---- 4. wait for native hosts Up (commit mode only) ------------------------------------------
if [ "$COMMIT" = 1 ]; then
  log "waiting for native cluster hosts to come Up..."
  deadline=$(( $(date +%s) + HEALTH_TIMEOUT ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    up=$(q "SELECT COUNT(*) FROM host h JOIN cluster c ON c.id=h.cluster_id WHERE c.name='$NATIVE_CLUSTER_NAME' AND h.hypervisor_type='Proxmox' AND h.status='Up' AND h.removed IS NULL")
    [ "${up:-0}" -ge 1 ] && { log "native hosts Up: $up"; break; }
    sleep 5
  done
fi
NCL_ID="$(q "SELECT id FROM cluster WHERE name='$NATIVE_CLUSTER_NAME' AND hypervisor_type='Proxmox' AND removed IS NULL ORDER BY id DESC LIMIT 1")"
NCL_UUID="$(q "SELECT uuid FROM cluster WHERE id=${NCL_ID:-0}" 2>/dev/null || true)"

# ---- 5. re-adopt each External VM via importUnmanagedInstance --------------------------------
# For each External user VM, build a nicnetworklist from its existing NICs (device -> same network),
# preserving networks/IPs, and import it into the native cluster by its PVE instance name.
log "re-adopting VMs (importUnmanagedInstance):"
while IFS=$'\t' read -r VMID INAME DNAME SOID; do
  [ -n "${VMID:-}" ] || continue
  SO_UUID="$(q "SELECT uuid FROM service_offering WHERE id=$SOID")"
  # nicnetworklist: nicnetworklist[i].nic / .network / .ip  (map device_id order -> network uuid + ip)
  nnl=""; i=0
  while IFS=$'\t' read -r DEV NWID IP4; do
    [ -n "${NWID:-}" ] || continue
    NW_UUID="$(q "SELECT uuid FROM networks WHERE id=$NWID")"
    nnl="${nnl} nicnetworklist[$i].nic=NIC${DEV} nicnetworklist[$i].network=${NW_UUID}"
    [ -n "${IP4:-}" ] && [ "$IP4" != "NULL" ] && nnl="${nnl} nicnetworklist[$i].ip=${IP4}"
    i=$((i+1))
  done < <(q "SELECT device_id, network_id, ip4_address FROM nics WHERE instance_id=$VMID AND removed IS NULL ORDER BY device_id")
  log "  VM $INAME (was id $VMID): import into cluster '$NATIVE_CLUSTER_NAME' offering=$SOID nics=$i"
  # 'name' must match the PVE-side VM name the native discoverer lists as unmanaged.
  cmk import unmanagedinstance clusterid="${NCL_UUID:-<native-cluster-uuid>}" name="$INAME" \
      displayname="$DNAME" serviceofferingid="$SO_UUID" $nnl migrateallowed=false
done < <(q "SELECT vi.id, vi.instance_name, vi.name, vi.service_offering_id FROM vm_instance vi WHERE vi.hypervisor_type='External' AND vi.removed IS NULL AND vi.type='User' AND (vi.host_id IN (SELECT id FROM host WHERE cluster_id=$EXT_CLUSTER_ID) OR vi.last_host_id IN (SELECT id FROM host WHERE cluster_id=$EXT_CLUSTER_ID))")

log "-------------------------------------------------------------------------------------------"
log "Conversion ${COMMIT:+}$( [ "$COMMIT" = 1 ] && echo 'COMPLETE' || echo 'PLAN (dry-run)' )."
log "The External cluster is left DISABLED (not deleted) so you can roll back with cs-ext-rollback.sh."
log "After you have verified the natively-imported VMs, remove the External cluster with the stock UI/API"
log "(or re-run with a future --finalize step). Backups (if committed) are in $BACKUP_DIR."
