import assert from "node:assert/strict";
import test from "node:test";

import { mockInstances } from "../mock-data.ts";
import {
  getInstancesFromBff,
  instancesFromListVirtualMachinesResponse,
  mapVirtualMachineState,
  mapVirtualMachineToInstance,
} from "./instances.ts";

test("mapVirtualMachineToInstance maps CloudStack VM fields into the UI instance contract", () => {
  const instance = mapVirtualMachineToInstance({
    id: "vm-1",
    name: "i-2-3-VM",
    displayname: "app-01",
    templatename: "Ubuntu 24.04 LTS",
    serviceofferingname: "Compute-M",
    cpunumber: "4",
    memory: "8192",
    state: "Running",
    ipaddress: "10.1.0.10",
    publicip: "203.0.113.10",
    zonename: "syd-1",
    account: "platform",
    cpuused: "12.5%",
    memorykbs: 524288,
    memorytargetkbs: 1048576,
    nic: [
      { ipaddress: "10.1.0.99", networkname: "backup", isdefault: false },
      { ipaddress: "10.1.0.11", networkname: "prod-vpc", isdefault: true, publicip: "203.0.113.11" },
    ],
  });

  assert.deepEqual(instance, {
    id: "vm-1",
    name: "app-01",
    template: "Ubuntu 24.04 LTS",
    offering: "Compute-M",
    cpu: 4,
    ram: 8,
    state: "running",
    ip: "10.1.0.10",
    publicIp: "203.0.113.10",
    zone: "syd-1",
    network: "prod-vpc",
    uptime: null,
    account: "platform",
    cpuUsage: 13,
    memUsage: 50,
  });
});

test("mapVirtualMachineState normalizes CloudStack states for the UI", () => {
  assert.equal(mapVirtualMachineState("Running"), "running");
  assert.equal(mapVirtualMachineState("Stopped"), "stopped");
  assert.equal(mapVirtualMachineState("Starting"), "starting");
  assert.equal(mapVirtualMachineState("StartingMigrate"), "starting");
  assert.equal(mapVirtualMachineState("Migrating"), "starting");
  assert.equal(mapVirtualMachineState("Stopping"), "starting");
  assert.equal(mapVirtualMachineState("Error"), "error");
  assert.equal(mapVirtualMachineState("Destroyed"), "error");
  assert.equal(mapVirtualMachineState("Expunging"), "error");
  assert.equal(mapVirtualMachineState("Unknown"), "error");
  assert.equal(mapVirtualMachineState(undefined), "stopped");
});

test("instancesFromListVirtualMachinesResponse maps the CloudStack response envelope", () => {
  const instances = instancesFromListVirtualMachinesResponse({
    listvirtualmachinesresponse: {
      count: 1,
      virtualmachine: [
        {
          id: "vm-2",
          name: "worker-01",
          state: "Stopped",
          memory: 4096,
          cpunumber: 2,
        },
      ],
    },
  });

  assert.equal(instances.length, 1);
  assert.equal(instances[0]?.name, "worker-01");
  assert.equal(instances[0]?.state, "stopped");
  assert.equal(instances[0]?.ram, 4);
});

test("getInstancesFromBff calls the BFF listVirtualMachines command and forwards cookies", async () => {
  const previousCsUrl = process.env.CS_URL;
  const previousNextAuthUrl = process.env.NEXTAUTH_URL;
  process.env.CS_URL = "http://cloudstack.local";
  delete process.env.NEXTAUTH_URL;

  let requestedUrl = "";
  let forwardedCookie: string | undefined;
  const fetchImpl: typeof fetch = async (input, init) => {
    requestedUrl = String(input);
    forwardedCookie = (init?.headers as Record<string, string> | undefined)?.cookie;
    return Response.json({
      listvirtualmachinesresponse: {
        virtualmachine: [{ id: "vm-3", name: "api-vm", state: "Running" }],
      },
    });
  };

  try {
    const instances = await getInstancesFromBff({
      fetchImpl,
      requestHeaders: new Headers({
        cookie: "cloudstack.session=opaque",
        host: "console.example.test",
        "x-forwarded-proto": "https",
      }),
    });
    const url = new URL(requestedUrl);

    assert.equal(url.origin, "https://console.example.test");
    assert.equal(url.pathname, "/api/cs/listVirtualMachines");
    assert.equal(url.searchParams.get("listall"), "true");
    assert.match(url.searchParams.get("details") ?? "", /nics/);
    assert.match(url.searchParams.get("details") ?? "", /stats/);
    assert.equal(forwardedCookie, "cloudstack.session=opaque");
    assert.equal(instances[0]?.name, "api-vm");
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
    restoreEnv("NEXTAUTH_URL", previousNextAuthUrl);
  }
});

test("getInstancesFromBff returns mock instances when CloudStack is unavailable", async () => {
  const previousCsUrl = process.env.CS_URL;
  process.env.CS_URL = "http://cloudstack.local";

  try {
    const instances = await getInstancesFromBff({
      fetchImpl: async () => new Response("{}", { status: 503 }),
    });

    assert.equal(instances, mockInstances);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

test("getInstancesFromBff does not call the BFF when CS_URL is absent", async () => {
  const previousCsUrl = process.env.CS_URL;
  delete process.env.CS_URL;
  let called = false;

  try {
    const instances = await getInstancesFromBff({
      fetchImpl: async () => {
        called = true;
        return Response.json({});
      },
    });

    assert.equal(called, false);
    assert.equal(instances, mockInstances);
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
