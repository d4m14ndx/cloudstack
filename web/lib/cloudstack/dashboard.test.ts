import assert from "node:assert/strict";
import test from "node:test";

import { mockDashboardMetrics, mockDashboardSummary } from "../mock-data.ts";
import { dashboardFromCloudStackResponses, getDashboardInventoryFromBff } from "./dashboard.ts";

const TiB = 1024 ** 4;

test("dashboardFromCloudStackResponses maps CloudStack counts and capacities", () => {
  const inventory = dashboardFromCloudStackResponses({
    allVirtualMachines: { listvirtualmachinesresponse: { count: 13 } },
    runningVirtualMachines: { listvirtualmachinesresponse: { count: 9 } },
    hosts: { listhostsresponse: { count: 7 } },
    zones: {
      listzonesresponse: {
        count: 3,
        zone: [
          { id: "zone-1", name: "syd-1", allocationstate: "Enabled" },
          { id: "zone-2", name: "syd-2", allocationstate: "Disabled" },
          { id: "zone-3", name: "mel-1", allocationstate: "Enabled" },
        ],
      },
    },
    capacity: {
      listcapacityresponse: {
        capacity: [
          { type: 90, name: "CPU_CORE", capacityallocated: 320, capacitytotal: 512 },
          { type: 0, name: "MEMORY", capacityallocated: TiB, capacitytotal: 2 * TiB },
          { type: 2, name: "STORAGE", capacityused: 18.4 * TiB, capacitytotal: 32 * TiB },
        ],
      },
    },
  });

  assert.equal(inventory.source, "bff");
  assert.deepEqual(inventory.summary, {
    onlineZones: 2,
    totalZones: 3,
    totalHosts: 7,
    runningInstances: 9,
  });
  assert.deepEqual(
    inventory.metrics.map((metric) => ({
      label: metric.label,
      value: metric.value,
      denom: metric.denom,
      suffix: metric.suffix,
      delta: metric.delta,
    })),
    [
      { label: "Instances running", value: "9", denom: "/ 13", suffix: undefined, delta: "Running now" },
      { label: "vCPUs allocated", value: "320", denom: "/ 512", suffix: undefined, delta: "63% of capacity" },
      { label: "Memory allocated", value: "1", denom: " TiB", suffix: "/ 2 TiB", delta: "50% of capacity" },
      { label: "Storage used", value: "18.4", denom: " TiB", suffix: "/ 32 TiB", delta: "57% of capacity" },
    ],
  );
  assert.deepEqual(inventory.metrics[0]?.series, [9, 9, 9, 9, 9, 9, 9]);
});

test("getDashboardInventoryFromBff calls expected BFF commands and forwards cookies", async () => {
  const previousCsUrl = process.env.CS_URL;
  const previousNextAuthUrl = process.env.NEXTAUTH_URL;
  process.env.CS_URL = "http://cloudstack.local";
  delete process.env.NEXTAUTH_URL;

  const requestedUrls: string[] = [];
  const forwardedCookies: Array<string | undefined> = [];
  const fetchImpl: typeof fetch = async (input, init) => {
    requestedUrls.push(String(input));
    forwardedCookies.push((init?.headers as Record<string, string> | undefined)?.cookie);
    const url = new URL(String(input));
    const command = url.pathname.split("/").pop();

    if (command === "listVirtualMachines") {
      return Response.json({
        listvirtualmachinesresponse: {
          count: url.searchParams.get("state") === "Running" ? 5 : 8,
        },
      });
    }

    if (command === "listHosts") {
      return Response.json({ listhostsresponse: { count: 3 } });
    }

    if (command === "listZones") {
      return Response.json({
        listzonesresponse: {
          zone: [{ allocationstate: "Enabled" }, { allocationstate: "Disabled" }],
        },
      });
    }

    if (command === "listCapacity") {
      return Response.json({
        listcapacityresponse: {
          capacity: [
            { type: 90, name: "CPU_CORE", capacityallocated: 40, capacitytotal: 80 },
            { type: 0, name: "MEMORY", capacityallocated: 512 * 1024 ** 3, capacitytotal: TiB },
            { type: 2, name: "STORAGE", capacityused: 4 * TiB, capacitytotal: 10 * TiB },
          ],
        },
      });
    }

    return new Response("{}", { status: 404 });
  };

  try {
    const inventory = await getDashboardInventoryFromBff({
      fetchImpl,
      requestHeaders: new Headers({
        cookie: "cloudstack.session=opaque",
        host: "console.example.test",
        "x-forwarded-proto": "https",
      }),
    });
    const urls = requestedUrls.map((url) => new URL(url));

    assert.equal(inventory.source, "bff");
    assert.equal(urls.length, 5);
    assert.equal(new Set(forwardedCookies).size, 1);
    assert.equal(forwardedCookies[0], "cloudstack.session=opaque");
    assert.ok(urls.every((url) => url.origin === "https://console.example.test"));
    assert.ok(urls.some((url) => url.pathname === "/api/cs/listVirtualMachines" && url.searchParams.get("state") === "Running"));
    assert.ok(urls.some((url) => url.pathname === "/api/cs/listVirtualMachines" && url.searchParams.get("state") === null));
    assert.ok(urls.some((url) => url.pathname === "/api/cs/listHosts" && url.searchParams.get("type") === "routing"));
    assert.ok(urls.some((url) => url.pathname === "/api/cs/listZones"));
    assert.ok(urls.some((url) => url.pathname === "/api/cs/listCapacity" && url.searchParams.get("fetchlatest") === "false"));
    assert.equal(inventory.summary.runningInstances, 5);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
    restoreEnv("NEXTAUTH_URL", previousNextAuthUrl);
  }
});

test("getDashboardInventoryFromBff returns mock inventory when CloudStack is unavailable", async () => {
  const previousCsUrl = process.env.CS_URL;
  process.env.CS_URL = "http://cloudstack.local";

  try {
    const inventory = await getDashboardInventoryFromBff({
      fetchImpl: async () => new Response("{}", { status: 503 }),
    });

    assert.equal(inventory.source, "mock");
    assert.equal(inventory.summary, mockDashboardSummary);
    assert.equal(inventory.metrics, mockDashboardMetrics);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

test("getDashboardInventoryFromBff does not call the BFF when CS_URL is absent", async () => {
  const previousCsUrl = process.env.CS_URL;
  delete process.env.CS_URL;
  let called = false;

  try {
    const inventory = await getDashboardInventoryFromBff({
      fetchImpl: async () => {
        called = true;
        return Response.json({});
      },
    });

    assert.equal(called, false);
    assert.equal(inventory.source, "mock");
    assert.equal(inventory.summary, mockDashboardSummary);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

test("getDashboardInventoryFromBff returns mock inventory for malformed responses", async () => {
  const previousCsUrl = process.env.CS_URL;
  process.env.CS_URL = "http://cloudstack.local";

  try {
    const inventory = await getDashboardInventoryFromBff({
      fetchImpl: async () => Response.json({}),
    });

    assert.equal(inventory.source, "mock");
    assert.equal(inventory.metrics, mockDashboardMetrics);
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
