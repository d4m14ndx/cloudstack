# CloudStack management: stock ↔ custom-build switch

`cs-mgmt-deploy.sh` swaps a CloudStack **management node** between the official upstream build and
our custom build (Proxmox hypervisor plugin + Mikrotik RouterOS network plugin), for safe rollback
and re-deploy.

## Why this is safe (same-version swap, not a downgrade)

Our plugin branches fork directly off the upstream **`4.22.1.0`** release tag, and our packages keep
that version. The official `download.cloudstack.org` repo serves an official `cloudstack-*_4.22.1.0`
for Ubuntu `noble` in the `4.22` component. So "stock" and "ours" are the **same version** — the
switch just replaces the installed files (`dpkg -i`), which is fully reversible. It is **not** a
version downgrade (CloudStack does not support in-place schema downgrades).

- **stock** = official `4.22.1.0` from `download.cloudstack.org` — the recovery / clean-baseline build.
- **ours**  = custom `4.22.1.0` with the two plugins, `dpkg -i` from `OURS_DEB_DIR`.

## Usage (run as root on the management node)

```
cs-mgmt-deploy.sh status                 # show current flavour + package versions
cs-mgmt-deploy.sh backup                 # DB dump + install-artifact tarball, then exit
cs-mgmt-deploy.sh stock                  # roll back to the official build
cs-mgmt-deploy.sh ours                   # (re)install our custom build
cs-mgmt-deploy.sh restore-db <dump.sql.gz>   # restore the cloud DB from a dump
```

Every `stock`/`ours` switch:
1. takes a **DB dump** (if `CS_DB_PASS` is set) and an **artifact tarball** into `BACKUP_DIR`;
2. stops the management (+ usage) service;
3. installs the target packages (`stock` downloads the official `.deb`s from the repo pool; `ours`
   installs from `OURS_DEB_DIR`);
4. starts the service and **health-checks** the API;
5. on failure, **automatically reinstalls the previous flavour** and reports.

Add `ASSUME_YES=1` to skip the confirmation prompt (e.g. in automation).

## Configuration (environment overrides)

| Var | Default | Notes |
|-----|---------|-------|
| `CS_VERSION` | `4.22.1.0` | must match both the installed and the official package version |
| `OURS_DEB_DIR` | `/opt/cs-ours-debs` | our custom `.debs` (see "staging our build") |
| `REPO_BASE` / `REPO_CODENAME` / `REPO_COMPONENT` | `https://download.cloudstack.org/ubuntu` / autodetected / `4.22` | official repo |
| `BACKUP_DIR` | `/var/backups/cloudstack-deploy` | DB dumps + artifact tarballs |
| `CS_DB_PASS` | *(unset)* | **export to enable DB dumps** (lab: the `cloud` user password) |
| `CS_DB_USER` / `CS_DB_NAME` / `CS_DB_HOST` | `cloud` / `cloud` / `127.0.0.1` | DB access for dumps/restore |
| `HEALTH_TIMEOUT` | `300` | seconds to wait for the API after a restart |

## Staging our build (`OURS_DEB_DIR`)

`ours` installs the four packages from `OURS_DEB_DIR`:
`cloudstack-common`, `cloudstack-management`, `cloudstack-usage`, `cloudstack-ui` (all `_4.22.1.0_all.deb`).

Build them from the fork (the branch that merges both plugin branches — e.g. `integration-scratch`)
with the standard CloudStack Debian build, then stage:

```
# on a build host, from a checkout of the merged branch:
dpkg-buildpackage -uc -us    # (or the project's packaging/build target)
mkdir -p /opt/cs-ours-debs
cp cloudstack-{common,management,usage,ui}_4.22.1.0_all.deb /opt/cs-ours-debs/
```

(The current lab build is already in `~ubuntu/*.deb` on cs-mgmt.)

## Database caveat for rollback

Our build adds objects the stock code doesn't understand: the `routeros_devices` table (harmless,
ignored), plus **Proxmox host/storage rows, RouterOS system routers, and RouterOS/VpcRouterOS network
providers**. After rolling back to `stock`, the stock code will **alert/disable** any Proxmox hosts
and RouterOS routers still present — it won't crash, but those resources are inert under stock.

For a **pristine** rollback, restore a DB dump taken *before* our build was ever applied:

```
CS_DB_PASS=... cs-mgmt-deploy.sh stock
CS_DB_PASS=... cs-mgmt-deploy.sh restore-db /var/backups/cloudstack-deploy/cloud-<pre-build>.sql.gz
```

Take that baseline dump with `cs-mgmt-deploy.sh backup` on a stock system before you first go `ours`.

## Notes

- Ubuntu `noble` / `amd64`, CloudStack `4.22.1.0`. For a different Ubuntu release set `REPO_CODENAME`.
- The script does not permanently add an apt source, so routine `apt upgrade` won't silently pull the
  official build over ours; it fetches the official `.deb`s directly from the repo pool only during
  `stock`.
- Health check = the API answering `HTTP 401` (management up). Under `stock`, Proxmox hosts staying
  down is expected, not a health failure.
