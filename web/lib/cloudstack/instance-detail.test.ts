import assert from "node:assert/strict";
import test from "node:test";

import { mockInstances } from "../mock-data.ts";
import {
  getInstanceDetailFromBff,
  mapVirtualMachineToInstanceDetail,
} from "./instance-detail.ts";

test("mapVirtualMachineToInstanceDetail maps a rich CloudStack VM into operational detail", () => {
  const detail = mapVirtualMachineToInstanceDetail(
    {
      id: "vm-1",
      name: "i-2-3-VM",
      displayname: "app-01",
      instancename: "i-2-3-VM",
      state: "Running",
      account: "platform",
      domain: "ROOT/platform",
      project: "payments",
      zonename: "syd-1",
      podname: "pod-a",
      clustername: "cluster-a",
      hostname: "hyp-syd1-01",
      hypervisor: "KVM",
      templatename: "Ubuntu 24.04 LTS",
      templatedisplaytext: "Ubuntu 24.04 LTS hardened",
      serviceofferingname: "Compute-M",
      diskofferingname: "Premium NVMe",
      cpunumber: "4",
      cpuspeed: "2400",
      memory: "8192",
      cpuused: "12.5%",
      memorykbs: 524288,
      memorytargetkbs: 1048576,
      created: "2026-05-21T01:23:45+0000",
      haenable: true,
      hostcontrolstate: "Enabled",
      details: {
        "External:console_url": "https://external-console.example.test/vm-1",
      },
      nic: [
        {
          id: "nic-1",
          networkid: "net-1",
          networkname: "backup",
          ipaddress: "10.9.0.10",
          gateway: "10.9.0.1",
          netmask: "255.255.255.0",
          macaddress: "02:00:00:00:00:01",
          isdefault: false,
          type: "Isolated",
        },
        {
          id: "nic-2",
          networkid: "net-2",
          networkname: "prod-vpc",
          ipaddress: "10.1.0.10",
          publicip: "203.0.113.10",
          gateway: "10.1.0.1",
          netmask: "255.255.0.0",
          macaddress: "02:00:00:00:00:02",
          isdefault: true,
          type: "VPC",
        },
      ],
      securitygroup: [{ id: "sg-1", name: "web" }],
      affinitygroup: [{ id: "ag-1", name: "anti-affinity", type: "host anti-affinity" }],
    },
    [
      {
        id: "vol-1",
        name: "ROOT-123",
        displayname: "app-01-root",
        size: 43_000_000_000,
        type: "ROOT",
        state: "Ready",
        zonename: "syd-1",
        vmname: "app-01",
        diskofferingname: "Premium NVMe",
      },
    ],
    [
      {
        id: "event-1",
        username: "alex.kim",
        type: "VM.START",
        level: "INFO",
        resourceid: "vm-1",
        resourcetype: "UserVm",
        resourcename: "app-01",
        description: "Started VM",
        created: "2026-05-21T02:00:00+0000",
      },
    ],
  );

  assert.equal(detail.instance.id, "vm-1");
  assert.equal(detail.instance.name, "app-01");
  assert.equal(detail.identity.internalName, "i-2-3-VM");
  assert.equal(detail.identity.domain, "ROOT/platform");
  assert.equal(detail.placement.host, "hyp-syd1-01");
  assert.equal(detail.placement.hypervisor, "KVM");
  assert.equal(detail.compute.cpuSpeedMHz, 2400);
  assert.equal(detail.compute.haEnabled, true);
  assert.equal(detail.console.rawState, "Running");
  assert.equal(detail.console.hostControlState, "Enabled");
  assert.equal(detail.console.externalUrl, "https://external-console.example.test/vm-1");
  assert.equal(detail.image.template, "Ubuntu 24.04 LTS");
  assert.equal(detail.image.templateDisplayText, "Ubuntu 24.04 LTS hardened");
  assert.equal(detail.networking.length, 2);
  assert.equal(detail.networking[1]?.isDefault, true);
  assert.equal(detail.networking[1]?.publicIp, "203.0.113.10");
  assert.equal(detail.securityGroups[0]?.name, "web");
  assert.equal(detail.affinityGroups[0]?.type, "host anti-affinity");
  assert.equal(detail.storage[0]?.name, "app-01-root");
  assert.equal(detail.activity[0]?.action, "VM.START");
});

test("mapVirtualMachineToInstanceDetail reads external console URLs from detail arrays", () => {
  const detail = mapVirtualMachineToInstanceDetail({
    id: "vm-1",
    name: "app-01",
    state: "Running",
    details: [
      { name: "other", value: "ignored" },
      { name: "External:console_url", value: "https://external-console.example.test/vm-1" },
    ],
  });

  assert.equal(detail.console.externalUrl, "https://external-console.example.test/vm-1");
});

test("getInstanceDetailFromBff calls all detail BFF commands with instance id and forwards cookies", async () => {
  const previousCsUrl = process.env.CS_URL;
  const previousNextAuthUrl = process.env.NEXTAUTH_URL;
  const previousAppEnv = process.env.NEXT_PUBLIC_APP_ENV;
  process.env.CS_URL = "http://cloudstack.local";
  delete process.env.NEXTAUTH_URL;
  delete process.env.NEXT_PUBLIC_APP_ENV;

  const requested: Array<{ url: URL; cookie?: string; cache?: RequestCache }> = [];
  const fetchImpl: typeof fetch = async (input, init) => {
    const url = new URL(String(input));
    requested.push({
      url,
      cookie: (init?.headers as Record<string, string> | undefined)?.cookie,
      cache: init?.cache,
    });

    if (url.pathname.endsWith("/listVirtualMachines")) {
      return Response.json({
        listvirtualmachinesresponse: {
          virtualmachine: [{ id: "vm-2", name: "api-vm", state: "Running" }],
        },
      });
    }

    if (url.pathname.endsWith("/listVolumes")) {
      return Response.json({ listvolumesresponse: { volume: [] } });
    }

    return Response.json({ listeventsresponse: { event: [] } });
  };

  try {
    const detail = await getInstanceDetailFromBff("vm-2", {
      fetchImpl,
      requestHeaders: new Headers({
        cookie: "cloudstack.session=opaque",
        host: "console.example.test",
        "x-forwarded-proto": "https",
      }),
    });

    assert.equal(detail?.instance.name, "api-vm");
    assert.deepEqual(requested.map((request) => request.url.pathname), [
      "/api/cs/listVirtualMachines",
      "/api/cs/listVolumes",
      "/api/cs/listEvents",
    ]);
    assert.ok(requested.every((request) => request.url.origin === "https://console.example.test"));
    assert.ok(requested.every((request) => request.cookie === "cloudstack.session=opaque"));
    assert.ok(requested.every((request) => request.cache === "no-store"));

    const vmUrl = requested[0]!.url;
    const volumesUrl = requested[1]!.url;
    const eventsUrl = requested[2]!.url;
    assert.equal(vmUrl.searchParams.get("id"), "vm-2");
    assert.equal(vmUrl.searchParams.get("listall"), "true");
    assert.equal(
      vmUrl.searchParams.get("details"),
      "group,nics,stats,secgrp,tmpl,servoff,diskoff,iso,volume,affgrp",
    );
    assert.equal(volumesUrl.searchParams.get("virtualmachineid"), "vm-2");
    assert.equal(volumesUrl.searchParams.get("listall"), "true");
    assert.equal(eventsUrl.searchParams.get("resourceid"), "vm-2");
    assert.equal(eventsUrl.searchParams.get("resourcetype"), "UserVm");
    assert.equal(eventsUrl.searchParams.get("page"), "1");
    assert.equal(eventsUrl.searchParams.get("pagesize"), "25");
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
    restoreEnv("NEXTAUTH_URL", previousNextAuthUrl);
    restoreEnv("NEXT_PUBLIC_APP_ENV", previousAppEnv);
  }
});

test("getInstanceDetailFromBff derives matching mock detail when CS_URL is absent", async () => {
  const previousCsUrl = process.env.CS_URL;
  delete process.env.CS_URL;
  let called = false;

  try {
    const detail = await getInstanceDetailFromBff(mockInstances[0]!.id, {
      fetchImpl: async () => {
        called = true;
        return Response.json({});
      },
    });

    assert.equal(called, false);
    assert.equal(detail?.instance, mockInstances[0]);
    assert.ok(detail?.storage.every((volume) => volume.attachedTo === mockInstances[0]!.name));
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

test("getInstanceDetailFromBff falls back to matching mock detail on non-OK, malformed, and thrown responses", async () => {
  const previousCsUrl = process.env.CS_URL;
  process.env.CS_URL = "http://cloudstack.local";
  const id = mockInstances[0]!.id;

  try {
    const nonOk = await getInstanceDetailFromBff(id, {
      fetchImpl: async () => new Response("{}", { status: 503 }),
    });
    const malformed = await getInstanceDetailFromBff(id, {
      fetchImpl: async () => Response.json({}),
    });
    const thrown = await getInstanceDetailFromBff(id, {
      fetchImpl: async () => {
        throw new Error("network unavailable");
      },
    });

    assert.equal(nonOk?.instance, mockInstances[0]);
    assert.equal(malformed?.instance, mockInstances[0]);
    assert.equal(thrown?.instance, mockInstances[0]);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

test("getInstanceDetailFromBff returns null when no real VM and no matching mock instance exists", async () => {
  const previousCsUrl = process.env.CS_URL;
  process.env.CS_URL = "http://cloudstack.local";

  try {
    const detail = await getInstanceDetailFromBff("missing-vm", {
      fetchImpl: async () => Response.json({
        listvirtualmachinesresponse: { virtualmachine: [] },
        listvolumesresponse: { volume: [] },
        listeventsresponse: { event: [] },
      }),
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
