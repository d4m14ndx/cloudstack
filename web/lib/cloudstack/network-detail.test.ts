import assert from "node:assert/strict";
import test from "node:test";

import { mockNetworks } from "../mock-data.ts";
import { getNetworkDetailFromBff } from "./network-detail.ts";

test("getNetworkDetailFromBff maps a VPC with tiers, public IPs, ACL rules, and unique VM count", async () => {
  const previousCsUrl = process.env.CS_URL;
  process.env.CS_URL = "http://cloudstack.local";

  try {
    const detail = await getNetworkDetailFromBff("vpc-1", {
      fetchImpl: async (input) => {
        const url = new URL(String(input));

        if (url.pathname.endsWith("/listVPCs")) {
          return Response.json({
            listvpcsresponse: {
              vpc: [
                {
                  id: "vpc-1",
                  name: "prod-vpc",
                  displaytext: "Production VPC",
                  cidr: "10.20.0.0/16",
                  state: "Enabled",
                  zonename: "syd-1",
                  account: "platform",
                  domain: "ROOT",
                  project: "payments",
                  vpcofferingname: "Default VPC offering",
                  networkdomain: "prod.internal",
                  redundantvpc: true,
                  distributedvpc: false,
                  restartrequired: false,
                },
              ],
            },
          });
        }

        if (url.pathname.endsWith("/listNetworks") && url.searchParams.get("id") === "vpc-1") {
          return Response.json({ listnetworksresponse: { network: [] } });
        }

        if (url.pathname.endsWith("/listNetworks") && url.searchParams.get("vpcid") === "vpc-1") {
          return Response.json({
            listnetworksresponse: {
              network: [
                {
                  id: "tier-web",
                  name: "web-tier",
                  networkcidr: "10.20.1.0/24",
                  gateway: "10.20.1.1",
                  netmask: "255.255.255.0",
                  state: "Implemented",
                  networkofferingname: "VPC tier offering",
                  aclid: "acl-list-1",
                },
                {
                  id: "tier-db",
                  name: "db-tier",
                  cidr: "10.20.2.0/24",
                  gateway: "10.20.2.1",
                  state: "Allocated",
                  aclid: "acl-list-1",
                },
              ],
            },
          });
        }

        if (url.pathname.endsWith("/listPublicIpAddresses")) {
          return Response.json({
            listpublicipaddressesresponse: {
              publicipaddress: [
                {
                  id: "ip-1",
                  ipaddress: "203.0.113.10",
                  state: "Allocated",
                  issourcenat: true,
                  isstaticnat: false,
                  associatednetworkname: "web-tier",
                  virtualmachinename: "web-01",
                },
              ],
            },
          });
        }

        if (url.pathname.endsWith("/listNetworkACLLists")) {
          return Response.json({
            listnetworkacllistsresponse: {
              networkacllist: [{ id: "acl-list-1", name: "web-acl", description: "Web tier ACL" }],
            },
          });
        }

        if (url.pathname.endsWith("/listNetworkACLs")) {
          return Response.json({
            listnetworkaclsresponse: {
              networkacl: [
                {
                  id: "acl-rule-1",
                  aclid: "acl-list-1",
                  number: "100",
                  action: "Allow",
                  protocol: "tcp",
                  cidrlist: "0.0.0.0/0",
                  startport: "443",
                  endport: "443",
                  traffictype: "Ingress",
                  state: "Active",
                },
              ],
            },
          });
        }

        if (url.pathname.endsWith("/listVirtualMachines")) {
          return Response.json({
            listvirtualmachinesresponse: {
              virtualmachine: [
                { id: "vm-1", nic: [{ vpcid: "vpc-1", networkid: "tier-web" }, { vpcid: "vpc-1", networkid: "tier-db" }] },
                { id: "vm-2", nic: [{ vpcid: "vpc-1", networkid: "tier-web" }] },
                { id: "vm-3", nic: [{ vpcid: "other-vpc", networkid: "other-tier" }] },
              ],
            },
          });
        }

        return Response.json({ listeventsresponse: { event: [] } });
      },
    });

    assert.equal(detail?.kind, "VPC");
    assert.equal(detail?.network.id, "vpc-1");
    assert.equal(detail?.network.name, "prod-vpc");
    assert.equal(detail?.network.gateway, "-");
    assert.equal(detail?.addressing.cidr, "10.20.0.0/16");
    assert.equal(detail?.addressing.gateway, "-");
    assert.equal(detail?.ownership.account, "platform");
    assert.equal(detail?.offering.name, "Default VPC offering");
    assert.equal(detail?.flags.redundant, true);
    assert.equal(detail?.summary.instanceCount, 2);
    assert.equal(detail?.tiers.length, 2);
    assert.equal(detail?.tiers[0]?.gateway, "10.20.1.1");
    assert.equal(detail?.publicIps[0]?.address, "203.0.113.10");
    assert.equal(detail?.aclLists[0]?.rules[0]?.protocol, "tcp");
    assert.equal(detail?.aclLists[0]?.rules[0]?.range, "443");
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

test("getNetworkDetailFromBff maps an isolated network with addressing, offering, public IPs, ACL rules, and unique VM count", async () => {
  const previousCsUrl = process.env.CS_URL;
  process.env.CS_URL = "http://cloudstack.local";

  try {
    const detail = await getNetworkDetailFromBff("net-1", {
      fetchImpl: async (input) => {
        const url = new URL(String(input));

        if (url.pathname.endsWith("/listVPCs")) {
          return Response.json({ listvpcsresponse: { vpc: [] } });
        }

        if (url.pathname.endsWith("/listNetworks") && url.searchParams.get("id") === "net-1") {
          return Response.json({
            listnetworksresponse: {
              network: [
                {
                  id: "net-1",
                  name: "mgmt",
                  displaytext: "Management",
                  networkcidr: "10.30.1.0/24",
                  gateway: "10.30.1.1",
                  netmask: "255.255.255.0",
                  state: "Implemented",
                  zonename: "syd-1",
                  account: "platform",
                  domain: "ROOT",
                  project: "core",
                  networkofferingname: "Isolated network offering",
                  networkdomain: "mgmt.internal",
                  restartrequired: true,
                  specifyipranges: false,
                  canusefordeploy: true,
                },
              ],
            },
          });
        }

        if (url.pathname.endsWith("/listPublicIpAddresses")) {
          return Response.json({
            listpublicipaddressesresponse: {
              publicipaddress: [{ id: "ip-2", ipaddress: "203.0.113.20", state: "Allocated", issourcenat: true }],
            },
          });
        }

        if (url.pathname.endsWith("/listNetworkACLLists")) {
          return Response.json({
            listnetworkacllistsresponse: {
              networkacllist: [{ id: "acl-list-net", name: "mgmt-acl", description: "Management ACL" }],
            },
          });
        }

        if (url.pathname.endsWith("/listNetworkACLs")) {
          return Response.json({
            listnetworkaclsresponse: {
              networkacl: [
                {
                  id: "acl-rule-net",
                  number: "1",
                  action: "Deny",
                  protocol: "icmp",
                  cidrlist: "10.0.0.0/8",
                  traffictype: "Egress",
                },
              ],
            },
          });
        }

        if (url.pathname.endsWith("/listVirtualMachines")) {
          return Response.json({
            listvirtualmachinesresponse: {
              virtualmachine: [
                { id: "vm-1", nic: [{ networkid: "net-1" }, { networkid: "net-1" }] },
                { id: "vm-2", nic: [{ networkid: "net-1" }] },
                { id: "vm-3", nic: [{ networkid: "other-net" }] },
              ],
            },
          });
        }

        return Response.json({ listeventsresponse: { event: [] } });
      },
    });

    assert.equal(detail?.kind, "Isolated");
    assert.equal(detail?.network.id, "net-1");
    assert.equal(detail?.network.cidr, "10.30.1.0/24");
    assert.equal(detail?.addressing.gateway, "10.30.1.1");
    assert.equal(detail?.addressing.netmask, "255.255.255.0");
    assert.equal(detail?.offering.name, "Isolated network offering");
    assert.equal(detail?.flags.restartRequired, true);
    assert.equal(detail?.flags.canUseForDeploy, true);
    assert.equal(detail?.summary.instanceCount, 2);
    assert.equal(detail?.publicIps[0]?.sourceNat, true);
    assert.equal(detail?.aclLists[0]?.rules[0]?.action, "Deny");
    assert.equal(detail?.aclLists[0]?.rules[0]?.range, "icmp");
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

test("getNetworkDetailFromBff calls detection and follow-up BFF URLs with forwarded cookie and no-store cache", async () => {
  const previousCsUrl = process.env.CS_URL;
  const previousNextAuthUrl = process.env.NEXTAUTH_URL;
  const previousAppEnv = process.env.NEXT_PUBLIC_APP_ENV;
  process.env.CS_URL = "http://cloudstack.local";
  delete process.env.NEXTAUTH_URL;
  delete process.env.NEXT_PUBLIC_APP_ENV;

  const requests: Array<{ url: URL; cache?: RequestCache; cookie?: string; method?: string }> = [];

  try {
    await getNetworkDetailFromBff("vpc-1", {
      fetchImpl: async (input, init) => {
        const url = new URL(String(input));
        requests.push({
          url,
          cache: init?.cache,
          cookie: (init?.headers as Record<string, string> | undefined)?.cookie,
          method: init?.method,
        });

        if (url.pathname.endsWith("/listVPCs")) {
          return Response.json({ listvpcsresponse: { vpc: [{ id: "vpc-1", name: "prod-vpc" }] } });
        }

        if (url.pathname.endsWith("/listNetworks")) {
          return Response.json({ listnetworksresponse: { network: [] } });
        }

        if (url.pathname.endsWith("/listPublicIpAddresses")) {
          return Response.json({ listpublicipaddressesresponse: { publicipaddress: [] } });
        }

        if (url.pathname.endsWith("/listNetworkACLLists")) {
          return Response.json({ listnetworkacllistsresponse: { networkacllist: [{ id: "acl-1", name: "default" }] } });
        }

        if (url.pathname.endsWith("/listNetworkACLs")) {
          return Response.json({ listnetworkaclsresponse: { networkacl: [] } });
        }

        if (url.pathname.endsWith("/listVirtualMachines")) {
          return Response.json({ listvirtualmachinesresponse: { virtualmachine: [] } });
        }

        return Response.json({ listeventsresponse: { event: [] } });
      },
      requestHeaders: new Headers({
        cookie: "cloudstack.session=opaque",
        host: "console.example.test",
        "x-forwarded-proto": "https",
      }),
    });

    assert.equal(requests.every((request) => request.url.origin === "https://console.example.test"), true);
    assert.equal(requests.every((request) => request.cache === "no-store"), true);
    assert.equal(requests.every((request) => request.cookie === "cloudstack.session=opaque"), true);
    assert.equal(requests.every((request) => request.method === "GET"), true);

    const detectionVpc = requests.find((request) => request.url.pathname.endsWith("/listVPCs"))?.url;
    const detectionNetwork = requests.find(
      (request) => request.url.pathname.endsWith("/listNetworks") && request.url.searchParams.get("id") === "vpc-1",
    )?.url;
    assert.equal(detectionVpc?.searchParams.get("id"), "vpc-1");
    assert.equal(detectionVpc?.searchParams.get("listall"), "true");
    assert.equal(detectionVpc?.searchParams.get("showicon"), "true");
    assert.equal(detectionNetwork?.searchParams.get("type"), "all");
    assert.equal(detectionNetwork?.searchParams.get("showicon"), "true");

    assert.ok(requests.some((request) => request.url.pathname.endsWith("/listNetworks") && request.url.searchParams.get("vpcid") === "vpc-1"));
    assert.ok(requests.some((request) => request.url.pathname.endsWith("/listPublicIpAddresses") && request.url.searchParams.get("vpcid") === "vpc-1" && request.url.searchParams.get("allocatedonly") === "true"));
    assert.ok(requests.some((request) => request.url.pathname.endsWith("/listNetworkACLLists") && request.url.searchParams.get("vpcid") === "vpc-1"));
    assert.ok(requests.some((request) => request.url.pathname.endsWith("/listNetworkACLs") && request.url.searchParams.get("aclid") === "acl-1"));
    assert.ok(requests.some((request) => request.url.pathname.endsWith("/listVirtualMachines") && request.url.searchParams.get("details") === "nics"));
    assert.ok(requests.some((request) => request.url.pathname.endsWith("/listEvents") && request.url.searchParams.get("resourcetype") === "Vpc"));
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
    restoreEnv("NEXTAUTH_URL", previousNextAuthUrl);
    restoreEnv("NEXT_PUBLIC_APP_ENV", previousAppEnv);
  }
});

test("getNetworkDetailFromBff falls back to mock detail when CS_URL is absent, non-OK, malformed, or fetch throws", async () => {
  const previousCsUrl = process.env.CS_URL;
  const id = mockNetworks[0]!.id;

  try {
    delete process.env.CS_URL;
    let called = false;
    const noCsUrl = await getNetworkDetailFromBff(id, {
      fetchImpl: async () => {
        called = true;
        return Response.json({});
      },
    });
    assert.equal(called, false);
    assert.equal(noCsUrl?.network.id, id);

    process.env.CS_URL = "http://cloudstack.local";
    const nonOk = await getNetworkDetailFromBff(id, {
      fetchImpl: async () => new Response("{}", { status: 503 }),
    });
    const malformed = await getNetworkDetailFromBff(id, {
      fetchImpl: async () => Response.json({}),
    });
    const thrown = await getNetworkDetailFromBff(id, {
      fetchImpl: async () => {
        throw new Error("network unavailable");
      },
    });

    assert.equal(nonOk?.network.id, id);
    assert.equal(malformed?.network.id, id);
    assert.equal(thrown?.network.id, id);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

test("getNetworkDetailFromBff returns null when no real or mock network matches the id", async () => {
  const previousCsUrl = process.env.CS_URL;
  process.env.CS_URL = "http://cloudstack.local";

  try {
    const detail = await getNetworkDetailFromBff("missing-network", {
      fetchImpl: async (input) => {
        const url = new URL(String(input));
        if (url.pathname.endsWith("/listVPCs")) {
          return Response.json({ listvpcsresponse: { vpc: [] } });
        }
        if (url.pathname.endsWith("/listNetworks")) {
          return Response.json({ listnetworksresponse: { network: [] } });
        }
        return Response.json({});
      },
    });

    assert.equal(detail, null);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

function restoreEnv(name: string, value: string | undefined): void {
  if (value === undefined) {
    delete process.env[name];
  } else {
    process.env[name] = value;
  }
}
