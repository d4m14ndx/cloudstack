#!/usr/bin/env bash
#
# cs-ext-to-native.sh — convert a stock "External" (Extension-framework) Proxmox cluster to a native
# Proxmox-plugin cluster on OUR build, by RE-ADOPTING the running PVE VMs through supported CloudStack
# APIs (addCluster + listUnmanagedInstances + importUnmanagedInstance). No hypervisor_type surgery and
# no destructive Proxmox operations: the actual PVE VMs are never stopped or deleted.
#
# SAFETY — CAPTURE-THEN-IMPORT: before any change, the details of every External VM to re-adopt
# (instance name, display name, service offering, template, and full NIC->network/IP/MAC map) are
# CAPTURED to a JSON manifest file. The import step reads that FROZEN manifest, not the live DB, so the
# re-adoption is deterministic, auditable and re-runnable even after the External cluster is disabled.
# Review the manifest a dry-run produces, then feed it back to the --commit run with --manifest to
# import exactly what you reviewed. Keep the manifest to diff the VMs after a later rollback.
#
# Flow (per External cluster):
#   1. CAPTURE the VM manifest (read-only) to <backup>/vm-manifest-cluster<id>-<ts>.json
#   2. back up the DB
#   3. DISABLE the External cluster (Unmanaged) so CloudStack stops orchestrating it; PVE keeps running
#   4. addCluster (native Proxmox) for the SAME PVE cluster, using the connection you supply
#   5. wait for the native hosts to come Up
#   6. for each VM IN THE MANIFEST: importUnmanagedInstance re-using its NIC->network map + IP + offering
#   7. verify, and LEAVE the External cluster disabled (not deleted) so rollback is possible
#
# DRY-RUN by default: captures the manifest and prints the exact commands it would run. --commit to act.
# Rollback: cs-ext-rollback.sh (removes the native imports, re-enables the External cluster).
#
# REQUIRED (env or flags):
#   EXT_CLUSTER_ID   the stock External cluster id to convert            (--ext-cluster)
#   PVE_URL          native Proxmox API URL, e.g. https://10.0.0.10:8006 (--url)
#   PVE_USER         API user, e.g. root@pam or user@realm!token         (--user)
#   PVE_PASS         API password / token secret (env only)
#   PVE_SSH_PASS     node root ssh password (env only; or bake into URL ?sshpassword=)
#   CS_DB_PASS       cloud DB password (for backup + reading records)
# Optional: NATIVE_CLUSTER_NAME (default derived), HEALTH_TIMEOUT (300),
#           MANIFEST (reuse a manifest file instead of capturing), MANIFEST_DIR (where to write it).
# Requires: cmk (CloudMonkey), mysql client, jq.
#
set -euo pipefail

COMMIT=0; MANIFEST="${MANIFEST:-}"
while [ $# -gt 0 ]; do case "$1" in
  --commit) COMMIT=1;;
  --ext-cluster) EXT_CLUSTER_ID="$2"; shift;;
  --url) PVE_URL="$2"; shift;;
  --user) PVE_USER="$2"; shift;;
  --name) NATIVE_CLUSTER_NAME="$2"; shift;;
  --manifest) MANIFEST="$2"; shift;;
  -h|--help) sed -n '2,/^set -euo pipefail/p' "$0" | sed '$d'; exit 0;;
  *) echo "unknown arg $1" >&2; exit 1;;
esac; shift; done

: "${EXT_CLUSTER_ID:?set --ext-cluster / EXT_CLUSTER_ID}"
: "${CS_DB_PASS:?export CS_DB_PASS}"
: "${PVE_URL:?set --url / PVE_URL}"; : "${PVE_USER:?set --user / PVE_USER}"; : "${PVE_PASS:?export PVE_PASS}"
CS_DB_NAME="${CS_DB_NAME:-cloud}"; CS_DB_HOST="${CS_DB_HOST:-127.0.0.1}"; CS_DB_USER="${CS_DB_USER:-cloud}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/cloudstack-deploy}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-300}"
CMK="${CMK:-cmk}"

# --raw: do NOT escape field contents — the manifest capture returns a JSON blob whose string values
# contain backslash escapes; default batch mode would double the backslashes and corrupt the JSON.
q(){ mysql -N --raw -B -h "$CS_DB_HOST" -u "$CS_DB_USER" -p"$CS_DB_PASS" "$CS_DB_NAME" -e "$1" 2>/dev/null; }
log(){ printf '[%s] %s\n' "$(date -u +%T)" "$*"; }
cmk(){ if [ "$COMMIT" = 1 ]; then "$CMK" "$@"; else printf '     %s %s\n' "$CMK" "$*"; fi; }

# ---- preflight -------------------------------------------------------------------------------
[ "$(command -v "$CMK")" ] || { echo "ERROR: cmk (CloudMonkey) not found/configured" >&2; exit 1; }
command -v jq >/dev/null || { echo "ERROR: jq not found (apt-get install -y jq)" >&2; exit 1; }
flavor="$(/usr/local/sbin/cs-mgmt-deploy.sh status 2>/dev/null | awk '/^flavor:/{print $2}')" || true
[ "${flavor:-}" = ours ] || log "WARNING: management build flavour is '${flavor:-unknown}', expected 'ours' (native plugin). Continue only if intended."

# NB: `|| true` so an empty result doesn't trip `set -e` before the friendly error below.
read -r CL_NAME CL_ZONE CL_POD < <(q "SELECT name, data_center_id, pod_id FROM cluster WHERE id=$EXT_CLUSTER_ID AND hypervisor_type='External' AND removed IS NULL") || true
[ -n "${CL_NAME:-}" ] || { echo "ERROR: no External cluster with id=$EXT_CLUSTER_ID" >&2; exit 1; }
NATIVE_CLUSTER_NAME="${NATIVE_CLUSTER_NAME:-${CL_NAME}-native}"

# ---- 1. CAPTURE the VM manifest (read-only, BEFORE any change) -------------------------------
# The manifest is the authoritative list of what to re-adopt. Once captured it is not re-read from the
# DB, so disabling the External cluster (step 3) cannot change what gets imported (step 6).
MANIFEST_DIR="${MANIFEST_DIR:-$BACKUP_DIR}"
if [ -n "$MANIFEST" ]; then
  [ -f "$MANIFEST" ] || { echo "ERROR: --manifest '$MANIFEST' not found" >&2; exit 1; }
  jq -e '.vms' "$MANIFEST" >/dev/null 2>&1 || { echo "ERROR: --manifest '$MANIFEST' is not a valid VM manifest" >&2; exit 1; }
  log "reusing supplied manifest: $MANIFEST"
else
  mkdir -p "$MANIFEST_DIR" 2>/dev/null || MANIFEST_DIR="${TMPDIR:-/tmp}"
  MANIFEST="$MANIFEST_DIR/vm-manifest-cluster${EXT_CLUSTER_ID}-$(date -u +%Y%m%dT%H%M%SZ).json"
  ts="$(date -u +%FT%TZ)"
  MANIFEST_SQL="$(cat <<SQL
SELECT JSON_OBJECT(
  'schema','cs-ext-to-native/vm-manifest/v1',
  'captured_utc','$ts',
  'ext_cluster_id',$EXT_CLUSTER_ID,
  'ext_cluster_name',(SELECT name FROM cluster WHERE id=$EXT_CLUSTER_ID),
  'zone_id',$CL_ZONE,'pod_id',$CL_POD,
  'vms',COALESCE((
    SELECT JSON_ARRAYAGG(JSON_OBJECT(
      'vm_id',vi.id,
      'instance_name',vi.instance_name,
      'display_name',vi.name,
      'state',vi.state,
      'service_offering_id',vi.service_offering_id,
      'service_offering_uuid',(SELECT so.uuid FROM service_offering so WHERE so.id=vi.service_offering_id),
      'service_offering_name',(SELECT so.name FROM service_offering so WHERE so.id=vi.service_offering_id),
      'template_id',vi.vm_template_id,
      'template_uuid',(SELECT t.uuid FROM vm_template t WHERE t.id=vi.vm_template_id),
      'nics',COALESCE((
        SELECT JSON_ARRAYAGG(JSON_OBJECT(
          'device_id',n.device_id,
          'network_id',n.network_id,
          'network_uuid',(SELECT nw.uuid FROM networks nw WHERE nw.id=n.network_id),
          'network_name',(SELECT nw.name FROM networks nw WHERE nw.id=n.network_id),
          'ip4',n.ip4_address,'mac',n.mac_address,'default_nic',n.default_nic
        )) FROM nics n WHERE n.instance_id=vi.id AND n.removed IS NULL
      ),JSON_ARRAY())
    ))
    FROM vm_instance vi
    WHERE vi.hypervisor_type='External' AND vi.removed IS NULL AND vi.type='User'
      AND (vi.host_id IN (SELECT id FROM host WHERE cluster_id=$EXT_CLUSTER_ID)
        OR vi.last_host_id IN (SELECT id FROM host WHERE cluster_id=$EXT_CLUSTER_ID))
  ),JSON_ARRAY())
)
SQL
)"
  q "$MANIFEST_SQL" > "$MANIFEST" || { echo "ERROR: manifest capture query failed" >&2; exit 1; }
  jq -e '.vms' "$MANIFEST" >/dev/null 2>&1 || { echo "ERROR: manifest capture produced invalid JSON: $MANIFEST" >&2; exit 1; }
  log "captured VM manifest -> $MANIFEST"
fi

VM_COUNT="$(jq '.vms | length' "$MANIFEST")"
log "converting External cluster '$CL_NAME' (id=$EXT_CLUSTER_ID, zone=$CL_ZONE pod=$CL_POD) -> native '$NATIVE_CLUSTER_NAME'"
log "external hosts:"; q "SELECT id,name,status FROM host WHERE cluster_id=$EXT_CLUSTER_ID AND hypervisor_type='External' AND removed IS NULL" | sed 's/^/     /'
log "VMs to re-adopt (from manifest, n=$VM_COUNT):"
jq -r '.vms[] | "     \(.instance_name)  disp=\(.display_name)  offering=\(.service_offering_name)  nics=\(.nics|length)  state=\(.state)"' "$MANIFEST"
[ "$VM_COUNT" -gt 0 ] || log "WARNING: manifest has 0 VMs — nothing to import (check the cluster id / host mapping)."
[ "$COMMIT" = 1 ] || log "(DRY-RUN — pass --commit to execute; review the manifest above and the commands below)"

# ---- 2. backup -------------------------------------------------------------------------------
if [ "$COMMIT" = 1 ]; then
  mkdir -p "$BACKUP_DIR"; dump="$BACKUP_DIR/${CS_DB_NAME}-pre-convert-cluster${EXT_CLUSTER_ID}-$(date -u +%Y%m%dT%H%M%SZ).sql.gz"
  log "DB backup -> $dump"
  mysqldump --single-transaction --routines --triggers -h "$CS_DB_HOST" -u "$CS_DB_USER" -p"$CS_DB_PASS" "$CS_DB_NAME" | gzip > "$dump"
  echo "$dump" > "$BACKUP_DIR/.last-convert-db"
  cp -n "$MANIFEST" "$BACKUP_DIR/" 2>/dev/null || true   # keep the manifest alongside the DB backup
else log "would DB-dump the '$CS_DB_NAME' database"; fi

# ---- 3. disable the External cluster (stop orchestration; PVE keeps running) -----------------
CL_UUID="$(q "SELECT uuid FROM cluster WHERE id=$EXT_CLUSTER_ID")"
cmk update cluster id="$CL_UUID" allocationstate=Disabled managedstate=Unmanaged

# ---- 4. add the native Proxmox cluster -------------------------------------------------------
# NB: SSH node creds ride in the URL as ?sshpassword=...  (see the ProxmoxServerDiscoverer docs)
NURL="$PVE_URL"; [ -n "${PVE_SSH_PASS:-}" ] && NURL="${PVE_URL}?sshpassword=${PVE_SSH_PASS}"
ZONE_UUID="$(q "SELECT uuid FROM data_center WHERE id=$CL_ZONE")"; POD_UUID="$(q "SELECT uuid FROM host_pod_ref WHERE id=$CL_POD")"
cmk add cluster zoneid="$ZONE_UUID" podid="$POD_UUID" hypervisor=Proxmox clustertype=CloudManaged \
    clustername="$NATIVE_CLUSTER_NAME" username="$PVE_USER" password="$PVE_PASS" url="$NURL"

# ---- 5. wait for native hosts Up (commit mode only) ------------------------------------------
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

# ---- 6. re-adopt each VM FROM THE MANIFEST via importUnmanagedInstance ------------------------
# Everything below reads ONLY the frozen manifest + the freshly-created native cluster uuid — no live
# read of the (now disabled) External VM records.
log "re-adopting VMs from manifest (importUnmanagedInstance):"
while IFS= read -r vm; do
  INAME="$(jq -r '.instance_name' <<<"$vm")"
  DNAME="$(jq -r '.display_name' <<<"$vm")"
  SO_UUID="$(jq -r '.service_offering_uuid // empty' <<<"$vm")"
  [ -n "$SO_UUID" ] || log "  WARNING: $INAME has no service_offering_uuid in the manifest — import will need one"
  # nicnetworklist[i].nic / .network / .ip  — .nic names the source NIC by its device id; validate the
  # exact key format against a real listUnmanagedInstances on your setup before --commit.
  nnl=""; i=0
  while IFS= read -r nic; do
    NW_UUID="$(jq -r '.network_uuid // empty' <<<"$nic")"; DEV="$(jq -r '.device_id' <<<"$nic")"; IP4="$(jq -r '.ip4 // empty' <<<"$nic")"
    [ -n "$NW_UUID" ] || continue
    nnl="${nnl} nicnetworklist[$i].nic=NIC${DEV} nicnetworklist[$i].network=${NW_UUID}"
    [ -n "$IP4" ] && nnl="${nnl} nicnetworklist[$i].ip=${IP4}"
    i=$((i+1))
  done < <(jq -c '.nics[]?' <<<"$vm")
  log "  VM $INAME (disp=$DNAME): import into '$NATIVE_CLUSTER_NAME' offering=$SO_UUID nics=$i"
  cmk import unmanagedinstance clusterid="${NCL_UUID:-<native-cluster-uuid>}" name="$INAME" \
      displayname="$DNAME" serviceofferingid="$SO_UUID" $nnl migrateallowed=false
done < <(jq -c '.vms[]' "$MANIFEST")

log "-------------------------------------------------------------------------------------------"
log "Conversion $( [ "$COMMIT" = 1 ] && echo 'COMPLETE' || echo 'PLAN (dry-run)' )."
log "Manifest (authoritative import record): $MANIFEST"
log "The External cluster is left DISABLED (not deleted) so you can roll back with cs-ext-rollback.sh."
log "Re-run --commit with  --manifest $MANIFEST  to import exactly what you reviewed here."
log "After you have verified the natively-imported VMs, remove the External cluster with the stock UI/API."
