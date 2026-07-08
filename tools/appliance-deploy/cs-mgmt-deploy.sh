#!/usr/bin/env bash
#
# cs-mgmt-deploy.sh — switch a CloudStack management server between the official "stock" build and
# our custom build (with the Proxmox hypervisor + Mikrotik RouterOS network plugins).
#
# Both builds are the SAME version (4.22.1.0: our plugins fork straight off the upstream 4.22.1.0
# release tag), so switching is a clean, reversible same-version dpkg swap — NOT a downgrade.
#
#   stock  : roll back to the official 4.22.1.0 packages from download.cloudstack.org  (recovery)
#   ours   : (re)install our custom 4.22.1.0 packages from OURS_DEB_DIR                 (upgrade)
#   backup : take a DB dump + install-artifact tarball and exit
#   status : show the current flavour and installed versions
#
# Every switch takes a DB dump + an artifact tarball first, health-checks the API afterward, and on
# failure automatically reinstalls the previous flavour. Run as root on the management node.
#
# IMPORTANT — database caveat for `stock`: our build adds tables/rows the stock code doesn't know
# (routeros_devices, Proxmox host/storage rows, RouterOS routers, RouterOS/VpcRouterOS providers).
# Extra tables are harmless, but stock code will alert/disable any Proxmox hosts and RouterOS routers
# still present. For a pristine rollback, also restore a DB dump taken before our build was applied:
#     cs-mgmt-deploy.sh restore-db <dump.sql.gz>
#
set -euo pipefail

### ------------------------------------------------------------------ config (override via env) ###
CS_VERSION="${CS_VERSION:-4.22.1.0}"
CS_PACKAGES=(cloudstack-common cloudstack-management cloudstack-usage cloudstack-ui)
OURS_DEB_DIR="${OURS_DEB_DIR:-/opt/cs-ours-debs}"              # where our custom .debs are staged
REPO_BASE="${REPO_BASE:-https://download.cloudstack.org/ubuntu}"
REPO_CODENAME="${REPO_CODENAME:-$( . /etc/os-release 2>/dev/null; echo "${VERSION_CODENAME:-noble}" )}"
REPO_COMPONENT="${REPO_COMPONENT:-4.22}"                       # apt component == cloudstack major.minor
BACKUP_DIR="${BACKUP_DIR:-/var/backups/cloudstack-deploy}"
SERVICES=(cloudstack-management cloudstack-usage)
JAR="/usr/share/cloudstack-management/lib/cloudstack-${CS_VERSION}.jar"
WEBAPP="/usr/share/cloudstack-management/webapp"
CONFDIR="/etc/cloudstack"
FLAVOR_MARKER="/etc/cloudstack/management/.cs-flavor"
API_URL="${API_URL:-http://localhost:8080/client/api?command=listCapabilities&response=json}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-300}"                        # seconds to wait for the API
# DB access for the pre-switch dump (best effort). Lab defaults; override via env.
CS_DB_NAME="${CS_DB_NAME:-cloud}"
CS_DB_USAGE_NAME="${CS_DB_USAGE_NAME:-cloud_usage}"
CS_DB_HOST="${CS_DB_HOST:-127.0.0.1}"
CS_DB_USER="${CS_DB_USER:-cloud}"
CS_DB_PASS="${CS_DB_PASS:-}"                                   # export CS_DB_PASS=... to enable DB dumps

ASSUME_YES="${ASSUME_YES:-0}"

### ---------------------------------------------------------------------------------- helpers ###
ts()   { date -u +%Y%m%dT%H%M%SZ; }
log()  { printf '[%s] %s\n' "$(date -u +%T)" "$*"; }
warn() { printf '[%s] WARNING: %s\n' "$(date -u +%T)" "$*" >&2; }
die()  { printf '[%s] ERROR: %s\n' "$(date -u +%T)" "$*" >&2; exit 1; }

require_root() { [ "$(id -u)" -eq 0 ] || die "must run as root"; }

confirm() {
  [ "$ASSUME_YES" = "1" ] && return 0
  read -r -p "$1 [y/N] " a; [ "$a" = "y" ] || [ "$a" = "Y" ]
}

current_flavor() {
  if [ -f "$FLAVOR_MARKER" ]; then cat "$FLAVOR_MARKER"; return; fi
  # Fall back to inspecting the jar for one of our plugin classes. The pipe runs in a subshell with
  # pipefail OFF: `grep -q` short-circuits on the first match and closes the pipe, so `unzip` exits
  # 141 (SIGPIPE) — which under pipefail would wrongly make this look like "stock".
  if [ -f "$JAR" ] && ( set +o pipefail; unzip -l "$JAR" 2>/dev/null | grep -q 'org/apache/cloudstack/network/routeros/RouterOSVmManagerImpl.class' ); then
    echo ours
  else
    echo stock
  fi
}

installed_versions() { dpkg-query -W -f='${Package} ${Version}\n' "${CS_PACKAGES[@]}" 2>/dev/null; }

svc_stop()  { for s in "${SERVICES[@]}"; do systemctl is-enabled "$s" >/dev/null 2>&1 && { log "stopping $s"; systemctl stop "$s" || true; }; done; }
svc_start() { log "starting ${SERVICES[0]}"; systemctl start "${SERVICES[0]}"; systemctl is-enabled cloudstack-usage >/dev/null 2>&1 && systemctl start cloudstack-usage || true; }

health_check() {
  log "waiting up to ${HEALTH_TIMEOUT}s for the management API..."
  local deadline=$(( $(date +%s) + HEALTH_TIMEOUT )) code
  while [ "$(date +%s)" -lt "$deadline" ]; do
    code=$(curl -s -o /dev/null -w '%{http_code}' "$API_URL" 2>/dev/null || echo 000)
    if [ "$code" = "401" ] || [ "$code" = "200" ]; then log "API up (HTTP $code)"; return 0; fi
    sleep 5
  done
  warn "API did not come up within ${HEALTH_TIMEOUT}s (last HTTP $code)"
  return 1
}

backup_all() {
  mkdir -p "$BACKUP_DIR"
  local stamp; stamp="$(ts)-$(current_flavor)"
  local art="$BACKUP_DIR/artifacts-$stamp.tar.gz"
  log "backing up install artifacts -> $art"
  tar czf "$art" -C / "${JAR#/}" "${WEBAPP#/}" "${CONFDIR#/}" 2>/dev/null || warn "artifact tar had warnings"
  echo "$art" > "$BACKUP_DIR/.last-artifacts"
  if [ -n "$CS_DB_PASS" ]; then
    for db in "$CS_DB_NAME" "$CS_DB_USAGE_NAME"; do
      local dump="$BACKUP_DIR/${db}-$stamp.sql.gz"
      log "dumping DB $db -> $dump"
      if mysqldump --single-transaction --routines --triggers -h "$CS_DB_HOST" -u "$CS_DB_USER" -p"$CS_DB_PASS" "$db" 2>/dev/null | gzip > "$dump"; then
        [ "$db" = "$CS_DB_NAME" ] && echo "$dump" > "$BACKUP_DIR/.last-db" || true
      else
        warn "mysqldump of $db failed; removing partial dump"; rm -f "$dump"
      fi
    done
  else
    warn "CS_DB_PASS not set — SKIPPING DB dump (artifacts still backed up). Export CS_DB_PASS to enable."
  fi
}

fetch_stock_debs() {          # -> prints a dir containing the 4 official .debs
  local dir; dir="$(mktemp -d /tmp/cs-stock-debs.XXXXXX)"
  local pkg url
  for pkg in "${CS_PACKAGES[@]}"; do
    url="$REPO_BASE/dists/$REPO_CODENAME/$REPO_COMPONENT/pool/${pkg}_${CS_VERSION}_all.deb"
    log "downloading $url" >&2
    curl -fsSL "$url" -o "$dir/${pkg}_${CS_VERSION}_all.deb" || { rm -rf "$dir"; die "failed to download $url"; }
  done
  echo "$dir"
}

install_debs() {              # install_debs <dir>
  local dir="$1" pkg debs=()
  for pkg in "${CS_PACKAGES[@]}"; do
    local f="$dir/${pkg}_${CS_VERSION}_all.deb"
    [ -f "$f" ] || die "missing package: $f"
    debs+=("$f")
  done
  log "dpkg -i ${#debs[@]} packages from $dir"
  dpkg -i "${debs[@]}"        # same-version swap: dpkg replaces the files regardless of version match
}

install_flavor() {            # install_flavor <stock|ours>
  case "$1" in
    stock) local d; d="$(fetch_stock_debs)"; install_debs "$d"; rm -rf "$d" ;;
    ours)  [ -d "$OURS_DEB_DIR" ] || die "OURS_DEB_DIR ($OURS_DEB_DIR) not found — stage our .debs there first (see README)"; install_debs "$OURS_DEB_DIR" ;;
    *) die "unknown flavor $1" ;;
  esac
}

do_switch() {                 # do_switch <stock|ours>
  local target="$1" from; from="$(current_flavor)"
  require_root
  [ "$target" = ours ] && [ ! -d "$OURS_DEB_DIR" ] && die "OURS_DEB_DIR ($OURS_DEB_DIR) not found; stage our .debs first"
  log "switching management build: ${from} -> ${target} (both ${CS_VERSION})"
  confirm "This stops the management server, swaps packages and restarts it. Continue?" || die "aborted"
  backup_all
  svc_stop
  if install_flavor "$target" && { svc_start; health_check; }; then
    echo "$target" > "$FLAVOR_MARKER"
    log "SUCCESS: management server is now running the '${target}' build"
    log "installed:"; installed_versions | sed 's/^/    /'
    [ "$target" = stock ] && warn "stock code ignores Proxmox hosts & RouterOS routers still in the DB; for a pristine rollback restore a pre-build DB dump (restore-db)."
    return 0
  fi
  warn "'${target}' build failed to come up healthy — rolling back to '${from}'"
  svc_stop || true
  if install_flavor "$from" && { svc_start; health_check; }; then
    echo "$from" > "$FLAVOR_MARKER"
    die "rolled back to '${from}' after '${target}' failed. Inspect /var/log/cloudstack/management/management-server.log; backups in $BACKUP_DIR"
  fi
  die "BOTH '${target}' and rollback to '${from}' failed to come up. Restore manually from $BACKUP_DIR (artifacts + DB dump). Check the mgmt log."
}

restore_db() {                # restore_db <dump.sql.gz>
  require_root
  local dump="$1"; [ -f "$dump" ] || die "dump not found: $dump"
  [ -n "$CS_DB_PASS" ] || die "export CS_DB_PASS to restore the DB"
  confirm "This OVERWRITES the '$CS_DB_NAME' database from $dump. Continue?" || die "aborted"
  svc_stop
  log "restoring $CS_DB_NAME from $dump"
  gzip -dc "$dump" | mysql -h "$CS_DB_HOST" -u "$CS_DB_USER" -p"$CS_DB_PASS" "$CS_DB_NAME"
  svc_start; health_check || warn "API not healthy after DB restore"
  log "DB restore complete"
}

usage() { sed -n '2,40p' "$0"; }

### ------------------------------------------------------------------------------------- main ###
case "${1:-}" in
  stock)      do_switch stock ;;
  ours)       do_switch ours ;;
  backup)     require_root; backup_all; log "backup complete -> $BACKUP_DIR" ;;
  restore-db) shift; restore_db "${1:-}" ;;
  status)     echo "flavor: $(current_flavor)"; echo "version: $CS_VERSION"; echo "packages:"; installed_versions | sed 's/^/    /'; echo "services:"; for s in "${SERVICES[@]}"; do printf '    %s: %s\n' "$s" "$(systemctl is-active "$s" 2>/dev/null)"; done ;;
  -h|--help|help|"") usage ;;
  *) die "unknown action '$1' (use: stock | ours | backup | restore-db <f> | status)" ;;
esac
