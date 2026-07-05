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

# Mikrotik RouterOS network plugin

This plugin lets CloudStack deploy a **Mikrotik RouterOS CHR** (Cloud Hosted
Router) virtual machine as the router of an isolated guest network or a VPC,
as an alternative to the CloudStack system VM virtual router. The appliance is
deployed from an admin-registered CHR template and programmed over the
**RouterOS v7 REST API** (`https://<router>/rest`, HTTP basic auth, JSON).

Two providers are registered:

| Provider      | Scope                     | Element                                        |
|---------------|---------------------------|------------------------------------------------|
| `RouterOS`    | Isolated guest networks   | `org.apache.cloudstack.network.routeros.element.RouterOSElement` |
| `VpcRouterOS` | VPCs and their tiers      | `org.apache.cloudstack.network.routeros.element.RouterOSVpcElement` |

## Service parity matrix

| Service            | Isolated (`RouterOS`) | VPC (`VpcRouterOS`) | Implementation |
|--------------------|-----------------------|---------------------|----------------|
| Gateway            | implemented           | implemented         | gateway IP programmed on the guest/tier interface |
| DHCP               | implemented           | implemented         | RouterOS DHCP server with `address-pool=static-only`; one static lease per NIC (CloudStack owns IP allocation) |
| Source NAT         | implemented           | implemented         | `srcnat` `src-nat` rule per network/tier to the source NAT IP |
| Static NAT         | implemented           | implemented         | symmetric `dst-nat`/`src-nat` pair per IP |
| Port Forwarding    | implemented           | implemented         | `dstnat` rules with port ranges |
| Firewall (ingress) | implemented           | n/a (ACLs)          | connection marks in mangle/prerouting (pre-dst-nat) + base accept/drop rules in forward |
| Firewall (egress)  | implemented           | n/a (ACLs)          | forward-chain accept rules towards the public interface + default policy rule from the offering |
| Network ACL        | n/a                   | implemented         | ordered filter rules per tier interface, anchored with `place-before` ahead of per-tier default-drop rules |
| VPC static routes  | n/a                   | implemented         | `/ip/route` entries |
| Load balancing     | not supported         | not supported       | roadmap (RouterOS has no native L7 LB; script-based L4 balancing is being evaluated) |
| Remote access VPN  | not supported         | not supported       | roadmap |
| Site-to-site VPN   | n/a                   | not supported       | roadmap (RouterOS IPsec is a natural fit) |
| UserData           | not supported         | not supported       | use the ConfigDrive provider in the offering |
| VPC private gateway| n/a                   | not supported (fails on use) | roadmap |
| Redundant router / HA | not supported      | not supported       | roadmap (VRRP between two CHRs) |

Unsupported services are deliberately **not** advertised in the capability
maps, so network offerings selecting them with this provider are rejected up
front. Because core managers (load balancing, remote-access/site-to-site VPN,
firewall) invoke the corresponding SPI methods on **every** registered provider
for a network regardless of ownership, those methods are implemented as safe
"not handled" no-ops (mirroring `VirtualRouterElement`) rather than throwing —
throwing there would break LB/VPN cloud-wide the moment this jar is loaded. VPC
private gateways are not a capability-gated service, so an explicit attempt to
create one on a RouterOS VPC still fails loudly (that single operation only).

## Architecture

```
 mgmt server                                   hypervisor
 ┌────────────────────────────┐                ┌────────────────────────────┐
 │ RouterOSElement /          │                │      CHR appliance VM      │
 │ RouterOSVpcElement         │                │ (Type.RouterOSVm, sys acct)│
 │        │                   │   REST/JSON    │                            │
 │ RouterOSVmManagerImpl ─────┼───────────────►│ ether1: public (SNAT IP)   │
 │   │        │               │ https://ip/rest│ ether2: guest gw / tier 1  │
 │   │  RouterOSApiClient     │                │ ether3+: more VPC tiers    │
 │   │  RouterOSRuleTranslator│                │                            │
 │   ▼                        │                └───────────┬────────────────┘
 │ routeros_devices (DB)      │                     guest instances
 └────────────────────────────┘                (DHCP + routed via the CHR)
```

- **`RouterOSVmManagerImpl`** deploys one CHR per isolated network (or per
  VPC), owns the `routeros_devices` row and pushes all configuration.
- **`RouterOSApiClient`** wraps the REST API. Every object it writes carries a
  `comment` beginning with `cs-` (e.g. `cs-pf-<rule-uuid>`); the comment is the
  idempotency key: *apply* = ensure-present-by-comment, *revoke* =
  remove-by-comment. Re-applying rules is therefore safe and cheap.
- **`RouterOSRuleTranslator`** is a pure, unit-tested translation layer from
  CloudStack rule models to RouterOS parameter maps.

### Deployment flow (isolated network)

1. `implement(network)` → the manager acquires the network lock.
2. The **source NAT IP** is assigned and the CHR VM is allocated from the
   template named by `routeros.template.name` with two NICs:
   `ether1` = public (default NIC, source NAT IP), `ether2` = guest (gateway IP).
3. The VM is started; the manager polls `GET /rest/system/resource` at the
   public IP for up to `routeros.provision.wait` seconds.
4. On first contact the manager authenticates with the **template credentials**
   (`routeros.api.user` / `routeros.template.password`), rotates the password
   to a generated per-appliance secret (stored encrypted in
   `routeros_devices.password`), sets the identity, and **firewalls the API
   port to the management network CIDR**.
5. Base config is pushed: gateway address, public IP, default route, source
   NAT, base firewall rules, DHCP server. State becomes `Active`.
6. Firewall / PF / static NAT / IP changes then flow through the element as
   usual CloudStack operations.

For VPCs, `implementVpc` deploys the CHR with only the public NIC; each tier
`implement` hot-plugs a NIC (`ether3`, `ether4`, ...) and programs the tier
(gateway IP, DHCP, ACL default-drop anchors).

### Firewall semantics on RouterOS

CloudStack ingress firewall rules reference the *public* IP and port, but the
RouterOS forward chain only sees post-dst-nat (translated) addresses. The plugin
therefore marks allowed connections in **mangle/prerouting** (which runs before
dst-nat and still sees the original destination): each CloudStack ingress rule
becomes a `mark-connection` rule setting the `cs-fw-allow` mark. Two base rules
per network then enforce the policy in the forward chain: accept dst-nat
connections carrying the mark, drop new dst-nat connections without it.

### The CHR bootstrap gap

RouterOS CHR does not support cloud-init/config-drive, and CloudStack public
IPs are not DHCP-served, so a freshly booted CHR does not know its public IP.
The plugin handles this honestly:

- If the appliance's REST API becomes reachable at the expected public IP
  within `routeros.provision.wait` (e.g. the template auto-configures itself,
  or your public VLAN has operator-managed DHCP with MAC reservations),
  provisioning completes automatically.
- Otherwise the device is parked in **`RequiresBootstrap`** state and the exact
  one-paste bootstrap snippet (public IP, default route, `www-ssl` enablement)
  is logged at WARN level. Apply it on the CHR console once; every subsequent
  plugin operation retries provisioning automatically.

Full zero-touch bootstrap (e.g. host-side console injection on KVM) is on the
roadmap.

## Setting it up

### 1. Prepare and register the CHR template

Download a CHR image (7.10+; the REST API requires RouterOS v7) and prepare it:

```
# on a scratch CHR instance, then export/convert the disk
/user set admin password=<template-password>     # optional but recommended
/ip service enable www-ssl                        # REST API over TLS (self-signed is fine)
/ip service disable telnet,ftp,www
```

Register the resulting image as a template named **`routeros-chr`** (or point
`routeros.template.name` at your name), for the hypervisor type of the zone,
with "Password enabled" and "HVM" as appropriate for the image.

### 2. Global settings

| Setting | Default | Meaning |
|---------|---------|---------|
| `routeros.template.name` | `routeros-chr` | Template used for appliances |
| `routeros.service.offering` | *(empty)* | Compute offering UUID; a built-in 1 vCPU / 512 MB offering is used when empty |
| `routeros.api.port` | `443` | REST API port (`www-ssl`) |
| `routeros.api.user` | `admin` | API user |
| `routeros.template.password` | *(empty)* | Initial password baked into the template; rotated on first contact |
| `routeros.api.timeout` | `30` | HTTP timeout (seconds) |
| `routeros.provision.wait` | `300` | Max wait for a new appliance's API (seconds) |

### 3. Enable the provider on the physical network

```
add network service provider: name=RouterOS    (and/or VpcRouterOS)
enable the provider
```

### 4. Create a network offering

Create an isolated network offering with:

- Gateway / DHCP / SourceNat / StaticNat / PortForwarding / Firewall →
  provider **RouterOS**
- UserData → **ConfigDrive** (RouterOS cannot serve CloudStack userdata)
- no LB / VPN services (unsupported — offering creation will fail otherwise)

For VPCs, create a VPC offering using **VpcRouterOS** for
Gateway/DHCP/SourceNat/StaticNat/PortForwarding/NetworkACL.

### 5. Database schema

The `routeros_devices` table is created by the 4.22.1.0 upgrade path
(`schema-42200to42210.sql`). If your database was already at 4.22.1.0 before
this plugin was installed, apply the `CREATE TABLE IF NOT EXISTS
cloud.routeros_devices ...` statement from that file manually (it is
idempotent).

## Design decisions and trade-offs

- **VM type**: the CHR runs as a **system VM** — a `DomainRouterVO` of
  `VirtualMachine.Type.RouterOSVm` with role `ROUTEROS_VM`, owned by the system
  account, with the manager registered as its `VirtualMachineGuru` (the same
  pattern as the NetScaler VPX and internal load balancer appliances; the fork
  adds the enum values). It is invisible to user VM listings, exempt from
  account resource limits, and protected from user lifecycle operations. The
  VR background sweeps (health checks, stats, alerts) skip appliance roles that
  are not backed by the CloudStack system VM template.
- **Persistence**: follows the NSX pattern — VO + DAO live in `engine/schema`
  (`RouterOSDeviceVO`, `RouterOSDeviceDao`), the table ships in the release
  upgrade SQL, passwords are encrypted via the `@Encrypt` column support.
- **Management path**: the appliance is programmed via its **public IP**; the
  API port is firewalled to the management network CIDR
  (`management.network.cidr`) during provisioning. The management server must
  be able to reach the public network.
- **DHCP**: `address-pool=static-only` — the CHR never allocates addresses on
  its own; CloudStack remains the source of truth.

## Roadmap (priorities set 2026-07-05)

- **VPC private gateways** — cheapest item: hot-plug a NIC into the private
  VLAN (tier mechanism), gateway IP, per-interface ACLs, source-NAT flag;
  static routes via the gateway already work.
- **Site-to-site VPN**:
  1. Policy-based IPsec, IKEv2 and IKEv1 — maps 1:1 onto the core customer
     gateway model (ikeversion, IKE/ESP policy strings, PSK, DPD, CIDR lists).
  2. Route-based VPN with addressed tunnel interfaces (for OSPF etc.).
     RouterOS has no VTI: route-based IPsec = GRE/IPIP-over-IPsec; WireGuard
     is the simplest native route-based option. Tunnel IPs are not part of
     the core S2S model and ride in customer-gateway/VPC details.
  3. OpenVPN site-to-site, after the above.
- **Remote access VPN** — WireGuard first (needs a key/config distribution
  design: core's RemoteAccessVPN model is username/password-shaped, so the
  server generates client keypairs and exposes ready-made client configs),
  OpenVPN with user/password auth second (fits the core model directly).
- **HA pairs via VRRP** — active/passive. NOTE: RouterOS has no conntrack
  synchronization (verify against current docs at design time), so failover
  drops established NAT sessions; document as stateless failover unless that
  changes.
- **Dynamic routing** — BGP by plugging into the CloudStack 4.20+ routed-mode
  /BGP model (zone ASN, BGP peers); OSPF as plugin-level configuration over
  the addressed route-based tunnel interfaces.
- L4 load balancing via RouterOS scripts / `/ip firewall nat` round-robin.
- Zero-touch bootstrap (KVM console injection; RouterOS cloud-init if/when
  supported upstream).
