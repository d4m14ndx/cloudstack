#!/usr/bin/env bash
#
# cs-ext-proxmox-inventory.sh — inventory a stock CloudStack that runs Proxmox via the Extension
# framework, producing everything the extension->native conversion needs (and everything to share
# for review). READ-ONLY: it only SELECTs from the DB; it changes nothing.
#
# Run on (or with DB access to) the stock management node BEFORE switching to our build. Output is a
# human section plus machine-readable TSV blocks the converter consumes.
#
#   CS_DB_PASS=... cs-ext-proxmox-inventory.sh [--out inventory.txt]
#
set -euo pipefail

CS_DB_NAME="${CS_DB_NAME:-cloud}"
CS_DB_HOST="${CS_DB_HOST:-127.0.0.1}"
CS_DB_USER="${CS_DB_USER:-cloud}"
CS_DB_PASS="${CS_DB_PASS:-}"
OUT="${OUT:-}"
[ "${1:-}" = "--out" ] && { OUT="${2:-}"; }

[ -n "$CS_DB_PASS" ] || { echo "ERROR: export CS_DB_PASS (the 'cloud' DB user password)" >&2; exit 1; }
q() { mysql -N -B -h "$CS_DB_HOST" -u "$CS_DB_USER" -p"$CS_DB_PASS" "$CS_DB_NAME" -e "$1" 2>/dev/null; }

emit() { if [ -n "$OUT" ]; then printf '%s\n' "$*" >> "$OUT"; else printf '%s\n' "$*"; fi; }
[ -n "$OUT" ] && : > "$OUT"

emit "# CloudStack extension/Proxmox inventory  ($(date -u +%FT%TZ))"
emit "# READ-ONLY snapshot for extension->native conversion. Secrets are masked (shown as <set>)."
emit ""

emit "## extensions"
emit "$(q "SELECT id, name, type, state, path_ready, relative_path FROM extension WHERE removed IS NULL")"
emit ""
emit "## extension_details (extension-level config; secrets masked)"
emit "$(q "SELECT extension_id, name, CASE WHEN name REGEXP 'pass|secret|token|key' THEN '<set>' ELSE value END FROM extension_details")"
emit ""

emit "## extension_resource_map (extension -> resource links)"
emit "$(q "SELECT id, extension_id, resource_id, resource_type FROM extension_resource_map WHERE removed IS NULL")"
emit ""
emit "## extension_resource_map_details (per-mapping connection config; secrets masked)"
emit "$(q "SELECT m.extension_id, m.resource_type, m.resource_id, d.name, CASE WHEN d.name REGEXP 'pass|secret|token|key' THEN '<set>' ELSE d.value END
          FROM extension_resource_map_details d JOIN extension_resource_map m ON m.id=d.extension_resource_map_id WHERE m.removed IS NULL")"
emit ""

emit "## External clusters  (TSV: cluster_id  name  pod_id  data_center_id)"
emit "$(q "SELECT id, name, pod_id, data_center_id FROM cluster WHERE hypervisor_type='External' AND removed IS NULL")"
emit ""

emit "## External hosts  (TSV: host_id  name  cluster_id  status  resource_state  private_ip)"
emit "$(q "SELECT id, name, cluster_id, status, resource_state, private_ip_address FROM host WHERE hypervisor_type='External' AND type='Routing' AND removed IS NULL")"
emit ""
emit "## External host_details (non-secret; per host)"
emit "$(q "SELECT h.id, d.name, CASE WHEN d.name REGEXP 'pass|secret|token|key' THEN '<set>' ELSE d.value END
          FROM host h JOIN host_details d ON d.host_id=h.id WHERE h.hypervisor_type='External' AND h.removed IS NULL ORDER BY h.id")"
emit ""

emit "## External VMs  (TSV: vm_id  instance_name  display_name  state  host_id  last_host_id  service_offering_id  template_id)"
emit "$(q "SELECT vi.id, vi.instance_name, vi.name, vi.state, vi.host_id, vi.last_host_id, vi.service_offering_id, vi.vm_template_id
          FROM vm_instance vi WHERE vi.hypervisor_type='External' AND vi.removed IS NULL AND vi.type='User' ORDER BY vi.id")"
emit ""
emit "## External VM NICs  (TSV: vm_id  device_id  network_id  network_name  ip4  mac  default_nic) -- used to re-map networks on import"
emit "$(q "SELECT n.instance_id, n.device_id, n.network_id, nw.name, n.ip4_address, n.mac_address, n.default_nic
          FROM nics n JOIN vm_instance vi ON vi.id=n.instance_id JOIN networks nw ON nw.id=n.network_id
          WHERE vi.hypervisor_type='External' AND vi.removed IS NULL AND n.removed IS NULL ORDER BY n.instance_id, n.device_id")"
emit ""
emit "## service offerings referenced by External VMs  (TSV: id  name  cpu  speed  ram_mb)"
emit "$(q "SELECT DISTINCT so.id, so.name, so.cpu, so.speed, so.ram_size FROM service_offering so
          JOIN vm_instance vi ON vi.service_offering_id=so.id WHERE vi.hypervisor_type='External' AND vi.removed IS NULL")"
emit ""

# quick counts / verdict
ext=$(q "SELECT COUNT(*) FROM extension WHERE removed IS NULL")
extcl=$(q "SELECT COUNT(*) FROM cluster WHERE hypervisor_type='External' AND removed IS NULL")
exthost=$(q "SELECT COUNT(*) FROM host WHERE hypervisor_type='External' AND type='Routing' AND removed IS NULL")
extvm=$(q "SELECT COUNT(*) FROM vm_instance WHERE hypervisor_type='External' AND removed IS NULL AND type='User'")
emit "## summary"
emit "extensions=$ext  external_clusters=$extcl  external_hosts=$exthost  external_user_vms=$extvm"
[ "${extcl:-0}" = 0 ] && emit "=> no External clusters found: nothing to convert on this system."
[ -n "$OUT" ] && echo "wrote $OUT"
