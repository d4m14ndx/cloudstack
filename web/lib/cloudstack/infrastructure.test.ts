import assert from "node:assert/strict";
import test from "node:test";

import { mockHosts } from "../mock-data.ts";
import {
  getInfrastructureFromBff,
  hostsFromListHostsResponse,
  mapCloudStackHostToHost,
} from "./infrastructure.ts";

test("mapCloudStackHostToHost maps CloudStack host fields into the UI host contract", () => {
  const host = mapCloudStackHostToHost({
    id: "host-1",
    name: "hyp-syd1-01",
    zonename: "syd-1",
    clustername: "cluster-a",
    state: "Up",
    resourcestate: "Enabled",
    cpuallocatedpercentage: "78.4%",
    memoryallocatedpercentage: "82.49",
    virtualmachinecount: "12",
    hypervisor: "Hyperv",
  });

  assert.deepEqual(host, {
    id: "host-1",
    name: "hyp-syd1-01",
    zone: "syd-1",
    cluster: "cluster-a",
    state: "up",
    cpu: 78,
    mem: 82,
    instances: 12,
    hypervisor: "Hyper-V",
  });
});

test("mapCloudStackHostToHost falls back through minimal and suspicious CloudStack fields", () => {
  const host = mapCloudStackHostToHost({
    name: "hyp-alert-01",
    state: "Disconnected",
    cpuallocatedpercentage: "120%",
    memoryallocatedpercentage: "-2",
    vmcount: 3,
    hypervisor: "Xen",
  });

  assert.equal(host.id, "hyp-alert-01");
  assert.equal(host.name, "hyp-alert-01");
  assert.equal(host.zone, "unknown");
  assert.equal(host.cluster, "unknown");
  assert.equal(host.state, "alert");
  assert.equal(host.cpu, 100);
  assert.equal(host.mem, 0);
  assert.equal(host.instances, 3);
  assert.equal(host.hypervisor, "XenServer");
});

test("mapCloudStackHostToHost maps maintenance states and default hypervisor fallback", () => {
  const host = mapCloudStackHostToHost({
    id: "host-2",
    resourcestate: "PrepareForMaintenance",
    instances: "5",
  });

  assert.equal(host.id, "host-2");
  assert.equal(host.name, "host-2");
  assert.equal(host.state, "maintenance");
  assert.equal(host.instances, 5);
  assert.equal(host.hypervisor, "KVM");
});

test("mapCloudStackHostToHost treats disabled resource state as an alert even when the host is connected", () => {
  const host = mapCloudStackHostToHost({
    id: "host-disabled",
    resourcestate: "Disabled",
    state: "Up",
  });

  assert.equal(host.state, "alert");
});

test("hostsFromListHostsResponse maps the CloudStack response envelope", () => {
  const hosts = hostsFromListHostsResponse({
    listhostsresponse: {
      count: 1,
      host: [
        {
          id: "host-3",
          name: "hyp-mel1-01",
          zonename: "mel-1",
          clustername: "cluster-d",
          state: "Connected",
          cpuallocatedpercentage: 89.2,
          memoryallocatedpercentage: "91%",
          hypervisor: "VMware",
        },
      ],
    },
  });

  assert.equal(hosts.length, 1);
  assert.equal(hosts[0]?.name, "hyp-mel1-01");
  assert.equal(hosts[0]?.state, "up");
  assert.equal(hosts[0]?.cpu, 89);
  assert.equal(hosts[0]?.mem, 91);
  assert.equal(hosts[0]?.hypervisor, "VMware");
});

test("getInfrastructureFromBff calls the BFF listHosts command and forwards cookies", async () => {
  const previousCsUrl = process.env.CS_URL;
  const previousNextAuthUrl = process.env.NEXTAUTH_URL;
  const previousAppEnv = process.env.NEXT_PUBLIC_APP_ENV;
  process.env.CS_URL = "http://cloudstack.local";
  delete process.env.NEXTAUTH_URL;
  delete process.env.NEXT_PUBLIC_APP_ENV;

  let requestedUrl = "";
  let forwardedCookie: string | undefined;
  let cacheMode: RequestCache | undefined;
  const fetchImpl: typeof fetch = async (input, init) => {
    requestedUrl = String(input);
    forwardedCookie = (init?.headers as Record<string, string> | undefined)?.cookie;
    cacheMode = init?.cache;
    return Response.json({
      listhostsresponse: {
        host: [{ id: "host-4", name: "api-host", state: "Up", cpuallocatedpercentage: "42%" }],
      },
    });
  };

  try {
    const hosts = await getInfrastructureFromBff({
      fetchImpl,
      requestHeaders: new Headers({
        cookie: "cloudstack.session=opaque",
        host: "console.example.test",
        "x-forwarded-proto": "https",
      }),
    });
    const url = new URL(requestedUrl);

    assert.equal(url.origin, "https://console.example.test");
    assert.equal(url.pathname, "/api/cs/listHosts");
    assert.equal(url.searchParams.get("type"), "Routing");
    assert.equal(url.searchParams.get("details"), "capacity");
    assert.equal(forwardedCookie, "cloudstack.session=opaque");
    assert.equal(cacheMode, "no-store");
    assert.equal(hosts[0]?.name, "api-host");
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
    restoreEnv("NEXTAUTH_URL", previousNextAuthUrl);
    restoreEnv("NEXT_PUBLIC_APP_ENV", previousAppEnv);
  }
});

test("getInfrastructureFromBff returns mock hosts when CloudStack is unavailable", async () => {
  const previousCsUrl = process.env.CS_URL;
  process.env.CS_URL = "http://cloudstack.local";

  try {
    const hosts = await getInfrastructureFromBff({
      fetchImpl: async () => new Response("{}", { status: 503 }),
    });

    assert.equal(hosts, mockHosts);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

test("getInfrastructureFromBff returns mock hosts when the response envelope is missing", async () => {
  const previousCsUrl = process.env.CS_URL;
  process.env.CS_URL = "http://cloudstack.local";

  try {
    const hosts = await getInfrastructureFromBff({
      fetchImpl: async () => Response.json({}),
    });

    assert.equal(hosts, mockHosts);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

test("getInfrastructureFromBff does not call the BFF when CS_URL is absent", async () => {
  const previousCsUrl = process.env.CS_URL;
  delete process.env.CS_URL;
  let called = false;

  try {
    const hosts = await getInfrastructureFromBff({
      fetchImpl: async () => {
        called = true;
        return Response.json({});
      },
    });

    assert.equal(called, false);
    assert.equal(hosts, mockHosts);
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
