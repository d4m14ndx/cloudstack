import assert from "node:assert/strict";
import test from "node:test";

import { mockNetworks } from "../mock-data.ts";
import { getNetworksFromBff, networksFromCloudStackResponses } from "./networks.ts";

test("networksFromCloudStackResponses maps VPCs and standalone isolated networks into the UI network contract", () => {
  const networks = networksFromCloudStackResponses(
    {
      listvpcsresponse: {
        vpc: [
          {
            id: "vpc-1",
            name: "prod-vpc",
            displaytext: "Production VPC",
            cidr: "10.20.0.0/16",
            zonename: "syd-1",
            state: "Enabled",
            network: [{ id: "tier-nested", name: "nested-tier", gateway: "10.20.1.1" }],
          },
        ],
      },
    },
    {
      listnetworksresponse: {
        network: [
          {
            id: "tier-from-list",
            name: "tier-from-list",
            cidr: "10.20.1.0/24",
            networkcidr: "10.20.1.0/24",
            gateway: "10.20.1.1",
            zonename: "syd-1",
            state: "Implemented",
            vpcid: "vpc-1",
          },
          {
            id: "iso-1",
            name: "mgmt",
            displaytext: "Management",
            cidr: "10.99.0.0/24",
            networkcidr: "10.30.1.0/24",
            gateway: "10.30.1.1",
            zonename: "syd-1",
            state: "Allocated",
          },
        ],
      },
    },
    {
      listvirtualmachinesresponse: {
        virtualmachine: [
          {
            id: "vm-1",
            nic: [
              { vpcid: "vpc-1", networkid: "tier-a" },
              { vpcid: "vpc-1", networkid: "tier-b" },
            ],
          },
          { id: "vm-2", nic: [{ vpcid: "vpc-1", networkid: "tier-c" }] },
          {
            id: "vm-3",
            nic: [
              { networkid: "iso-1" },
              { networkid: "iso-1" },
            ],
          },
          { id: "vm-4", nic: [{ networkid: "iso-1" }] },
        ],
      },
    },
  );

  assert.deepEqual(networks, [
    {
      id: "vpc-1",
      name: "prod-vpc",
      cidr: "10.20.0.0/16",
      type: "VPC",
      zone: "syd-1",
      instances: 2,
      state: "running",
      gateway: "-",
    },
    {
      id: "iso-1",
      name: "mgmt",
      cidr: "10.30.1.0/24",
      type: "Isolated",
      zone: "syd-1",
      instances: 2,
      state: "running",
      gateway: "10.30.1.1",
    },
  ]);
  assert.equal(networks.some((network) => network.id === "tier-nested"), false);
  assert.equal(networks.some((network) => network.id === "tier-from-list"), false);
});

test("networksFromCloudStackResponses falls back through display names and warning states", () => {
  const networks = networksFromCloudStackResponses(
    {
      listvpcsresponse: {
        vpc: [{ displaytext: "Unnamed VPC", state: "Shutdown" }],
      },
    },
    {
      listnetworksresponse: {
        network: [{ displaytext: "Unnamed isolated", state: "Error" }],
      },
    },
    {
      listvirtualmachinesresponse: {
        virtualmachine: [],
      },
    },
  );

  assert.deepEqual(networks, [
    {
      id: "unknown",
      name: "Unnamed VPC",
      cidr: "-",
      type: "VPC",
      zone: "unknown",
      instances: 0,
      state: "warning",
      gateway: "-",
    },
    {
      id: "unknown",
      name: "Unnamed isolated",
      cidr: "-",
      type: "Isolated",
      zone: "unknown",
      instances: 0,
      state: "warning",
      gateway: "-",
    },
  ]);
});

test("getNetworksFromBff calls all required BFF endpoints, forwards cookies, and disables caching", async () => {
  const previousCsUrl = process.env.CS_URL;
  const previousNextAuthUrl = process.env.NEXTAUTH_URL;
  const previousAppEnv = process.env.NEXT_PUBLIC_APP_ENV;
  process.env.CS_URL = "http://cloudstack.local";
  delete process.env.NEXTAUTH_URL;
  delete process.env.NEXT_PUBLIC_APP_ENV;

  const calls: Array<{ url: URL; cache?: RequestCache; cookie?: string; method?: string }> = [];
  const fetchImpl: typeof fetch = async (input, init) => {
    calls.push({
      url: new URL(String(input)),
      cache: init?.cache,
      cookie: (init?.headers as Record<string, string> | undefined)?.cookie,
      method: init?.method,
    });

    if (String(input).includes("/api/cs/listVPCs")) {
      return Response.json({ listvpcsresponse: { vpc: [{ id: "vpc-api", name: "api-vpc", state: "Implemented" }] } });
    }

    if (String(input).includes("/api/cs/listNetworks")) {
      return Response.json({ listnetworksresponse: { network: [] } });
    }

    return Response.json({ listvirtualmachinesresponse: { virtualmachine: [] } });
  };

  try {
    const networks = await getNetworksFromBff({
      fetchImpl,
      requestHeaders: new Headers({
        cookie: "cloudstack.session=opaque",
        host: "console.example.test",
        "x-forwarded-proto": "https",
      }),
    });

    assert.equal(networks[0]?.id, "vpc-api");
    assert.deepEqual(
      calls.map((call) => call.url.pathname).sort(),
      ["/api/cs/listNetworks", "/api/cs/listVPCs", "/api/cs/listVirtualMachines"].sort(),
    );
    assert.equal(calls.every((call) => call.url.origin === "https://console.example.test"), true);
    assert.equal(calls.every((call) => call.cache === "no-store"), true);
    assert.equal(calls.every((call) => call.cookie === "cloudstack.session=opaque"), true);
    assert.equal(calls.every((call) => call.method === "GET"), true);

    const vpcsUrl = calls.find((call) => call.url.pathname === "/api/cs/listVPCs")?.url;
    const networksUrl = calls.find((call) => call.url.pathname === "/api/cs/listNetworks")?.url;
    const vmsUrl = calls.find((call) => call.url.pathname === "/api/cs/listVirtualMachines")?.url;
    assert.equal(vpcsUrl?.searchParams.get("listall"), "true");
    assert.equal(networksUrl?.searchParams.get("listall"), "true");
    assert.equal(networksUrl?.searchParams.get("type"), "isolated");
    assert.equal(networksUrl?.searchParams.get("forvpc"), "false");
    assert.equal(vmsUrl?.searchParams.get("listall"), "true");
    assert.equal(vmsUrl?.searchParams.get("details"), "nics");
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
    restoreEnv("NEXTAUTH_URL", previousNextAuthUrl);
    restoreEnv("NEXT_PUBLIC_APP_ENV", previousAppEnv);
  }
});

test("getNetworksFromBff returns mock networks when CS_URL is absent", async () => {
  const previousCsUrl = process.env.CS_URL;
  delete process.env.CS_URL;
  let called = false;

  try {
    const networks = await getNetworksFromBff({
      fetchImpl: async () => {
        called = true;
        return Response.json({});
      },
    });

    assert.equal(called, false);
    assert.equal(networks, mockNetworks);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

test("getNetworksFromBff returns mock networks when any BFF response is not OK", async () => {
  const previousCsUrl = process.env.CS_URL;
  process.env.CS_URL = "http://cloudstack.local";

  try {
    const networks = await getNetworksFromBff({
      fetchImpl: async (input) => {
        if (String(input).includes("/api/cs/listNetworks")) {
          return new Response("{}", { status: 503 });
        }

        return Response.json({});
      },
    });

    assert.equal(networks, mockNetworks);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

test("getNetworksFromBff returns mock networks when any response envelope is missing", async () => {
  const previousCsUrl = process.env.CS_URL;
  process.env.CS_URL = "http://cloudstack.local";

  try {
    const networks = await getNetworksFromBff({
      fetchImpl: async (input) => {
        if (String(input).includes("/api/cs/listVPCs")) {
          return Response.json({ listvpcsresponse: { vpc: [] } });
        }

        if (String(input).includes("/api/cs/listNetworks")) {
          return Response.json({});
        }

        return Response.json({ listvirtualmachinesresponse: { virtualmachine: [] } });
      },
    });

    assert.equal(networks, mockNetworks);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

test("getNetworksFromBff returns mock networks when fetch throws", async () => {
  const previousCsUrl = process.env.CS_URL;
  process.env.CS_URL = "http://cloudstack.local";

  try {
    const networks = await getNetworksFromBff({
      fetchImpl: async () => {
        throw new Error("network unavailable");
      },
    });

    assert.equal(networks, mockNetworks);
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
