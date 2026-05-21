import assert from "node:assert/strict";
import test from "node:test";

import {
  mockDeployWizardCatalog,
  mockDiskOfferings,
  mockServiceOfferings,
} from "../mock-data.ts";
import {
  deployWizardCatalogFromResponses,
  deployVirtualMachineFromWizard,
  getDeployWizardCatalogFromBff,
  queryDeployWizardJobResult,
  buildDeployVirtualMachineParams,
  mapCloudStackDiskOfferingToDeployWizardDiskOffering,
  mapCloudStackServiceOfferingToDeployWizardServiceOffering,
} from "./deploy-wizard.ts";

test("mapCloudStackServiceOfferingToDeployWizardServiceOffering maps service offering fields", () => {
  const offering = mapCloudStackServiceOfferingToDeployWizardServiceOffering({
    id: "so-1",
    name: "Compute Large",
    displaytext: "Compute Large - 4 cores / 8 GiB",
    cpunumber: "4",
    memory: "8192",
  });

  assert.deepEqual(offering, {
    id: "so-1",
    name: "Compute Large",
    cpu: 4,
    ram: 8,
    description: "Compute Large - 4 cores / 8 GiB",
  });
});

test("mapCloudStackDiskOfferingToDeployWizardDiskOffering maps fixed and custom disk offerings", () => {
  assert.deepEqual(
    mapCloudStackDiskOfferingToDeployWizardDiskOffering({
      id: "do-fixed",
      name: "SSD 100",
      displaytext: "100 GiB SSD",
      disksize: "107374182400",
      storagetype: "shared",
      customized: false,
    }),
    {
      id: "do-fixed",
      name: "SSD 100",
      sizeGiB: 100,
      customized: false,
      type: "shared",
    },
  );

  assert.deepEqual(
    mapCloudStackDiskOfferingToDeployWizardDiskOffering({
      id: "do-custom",
      name: "Custom data disk",
      iscustomized: true,
    }),
    {
      id: "do-custom",
      name: "Custom data disk",
      sizeGiB: null,
      customized: true,
      type: "shared",
    },
  );
});

test("deployWizardCatalogFromResponses maps required catalog envelopes", () => {
  const catalog = deployWizardCatalogFromResponses({
    zones: {
      listzonesresponse: {
        zone: [{ id: "zone-1", name: "syd-1", allocationstate: "Enabled" }],
      },
    },
    templates: {
      listtemplatesresponse: {
        template: [{ id: "tmpl-1", name: "Ubuntu", ostypename: "Ubuntu 24.04", size: 2147483648 }],
      },
    },
    serviceOfferings: {
      listserviceofferingsresponse: {
        serviceoffering: [{ id: "so-1", name: "Small", cpunumber: 2, memory: 4096 }],
      },
    },
    diskOfferings: {
      listdiskofferingsresponse: {
        diskoffering: [{ id: "do-1", name: "Data 50", disksize: 53687091200, storagetype: "local" }],
      },
    },
    networks: {
      listnetworksresponse: {
        network: [{ id: "net-1", name: "default", networkcidr: "10.0.0.0/24", gateway: "10.0.0.1" }],
      },
    },
    securityGroups: {
      listsecuritygroupsresponse: {
        securitygroup: [{ id: "sg-1", name: "default" }],
      },
    },
    sshKeyPairs: {
      listsshkeypairsresponse: {
        sshkeypair: [{ id: "key-1", name: "admin", fingerprint: "SHA256:key" }],
      },
    },
    projects: {
      listprojectsresponse: {
        project: [{ id: "project-1", name: "Engineering", displaytext: "Engineering workloads", state: "Active" }],
      },
    },
    affinityGroups: {
      listaffinitygroupsresponse: {
        affinitygroup: [
          {
            id: "ag-1",
            name: "spread-web",
            type: "host anti-affinity",
            description: "Spread web tier instances",
            project: "Engineering",
            projectid: "project-1",
          },
        ],
      },
    },
  });

  assert.equal(catalog.zones[0]?.name, "syd-1");
  assert.equal(catalog.templates[0]?.name, "Ubuntu");
  assert.equal(catalog.serviceOfferings[0]?.ram, 4);
  assert.equal(catalog.diskOfferings[0]?.sizeGiB, 50);
  assert.equal(catalog.networks[0]?.name, "default");
  assert.equal(catalog.securityGroups[0]?.name, "default");
  assert.equal(catalog.sshKeyPairs[0]?.name, "admin");
  assert.equal(catalog.projects[0]?.name, "Engineering");
  assert.equal(catalog.projects[0]?.displayText, "Engineering workloads");
  assert.equal(catalog.affinityGroups[0]?.name, "spread-web");
  assert.equal(catalog.affinityGroups[0]?.type, "host anti-affinity");
});

test("getDeployWizardCatalogFromBff calls each read-only BFF catalog endpoint without forwarded headers", async () => {
  const previousCsUrl = process.env.CS_URL;
  const previousNextAuthUrl = process.env.NEXTAUTH_URL;
  const previousAppEnv = process.env.NEXT_PUBLIC_APP_ENV;
  process.env.CS_URL = "http://cloudstack.local";
  process.env.NEXTAUTH_URL = "https://console.example.test/";
  delete process.env.NEXT_PUBLIC_APP_ENV;

  const requests: Array<{ url: string; init?: RequestInit }> = [];
  const responsesByPath = new Map<string, unknown>([
    ["/api/cs/listZones", { listzonesresponse: { zone: [{ id: "zone-1", name: "syd-1" }] } }],
    [
      "/api/cs/listTemplates",
      { listtemplatesresponse: { template: [{ id: "tmpl-1", name: "Ubuntu", size: 1073741824 }] } },
    ],
    [
      "/api/cs/listServiceOfferings",
      { listserviceofferingsresponse: { serviceoffering: [{ id: "so-1", name: "Small", cpunumber: 1, memory: 1024 }] } },
    ],
    [
      "/api/cs/listDiskOfferings",
      { listdiskofferingsresponse: { diskoffering: [{ id: "do-1", name: "Disk", disksize: 10737418240 }] } },
    ],
    ["/api/cs/listNetworks", { listnetworksresponse: { network: [{ id: "net-1", name: "default" }] } }],
    ["/api/cs/listSecurityGroups", { listsecuritygroupsresponse: { securitygroup: [{ id: "sg-1", name: "default" }] } }],
    ["/api/cs/listSSHKeyPairs", { listsshkeypairsresponse: { sshkeypair: [{ id: "key-1", name: "admin" }] } }],
    ["/api/cs/listProjects", { listprojectsresponse: { project: [{ id: "project-1", name: "Engineering" }] } }],
    [
      "/api/cs/listAffinityGroups",
      { listaffinitygroupsresponse: { affinitygroup: [{ id: "ag-1", name: "spread-web", type: "host anti-affinity" }] } },
    ],
  ]);
  const fetchImpl: typeof fetch = async (input, init) => {
    const url = new URL(String(input));
    requests.push({ url: String(input), init });
    const body = responsesByPath.get(url.pathname);
    if (!body) {
      return new Response("{}", { status: 404 });
    }

    return Response.json(body);
  };

  try {
    const catalog = await getDeployWizardCatalogFromBff({ fetchImpl });
    const urls = requests.map((request) => new URL(request.url));

    assert.equal(catalog.serviceOfferings[0]?.name, "Small");
    assert.equal(requests.length, 9);
    assert.deepEqual(
      urls.map((url) => url.pathname),
      [
        "/api/cs/listZones",
        "/api/cs/listTemplates",
        "/api/cs/listServiceOfferings",
        "/api/cs/listDiskOfferings",
        "/api/cs/listNetworks",
        "/api/cs/listSecurityGroups",
        "/api/cs/listSSHKeyPairs",
        "/api/cs/listProjects",
        "/api/cs/listAffinityGroups",
      ],
    );
    assert.equal(urls[1]?.searchParams.get("templatefilter"), "executable");
    assert.equal(urls[1]?.searchParams.get("details"), "min");
    assert.equal(urls[1]?.searchParams.get("showunique"), "true");
    assert.equal(urls[4]?.searchParams.get("listall"), "true");
    assert.equal(urls[5]?.searchParams.get("listall"), "true");
    assert.equal(urls[6]?.searchParams.size, 0);
    assert.equal(urls[7]?.searchParams.get("listall"), "true");
    assert.equal(urls[8]?.searchParams.get("listall"), "true");
    assert.deepEqual(
      requests.map((request) => request.init),
      Array.from({ length: 9 }, () => ({ method: "GET", cache: "no-store" })),
    );
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
    restoreEnv("NEXTAUTH_URL", previousNextAuthUrl);
    restoreEnv("NEXT_PUBLIC_APP_ENV", previousAppEnv);
  }
});

test("getDeployWizardCatalogFromBff returns mock catalog when CloudStack is unavailable or a response is malformed", async () => {
  const previousCsUrl = process.env.CS_URL;
  const previousAppEnv = process.env.NEXT_PUBLIC_APP_ENV;
  process.env.CS_URL = "http://cloudstack.local";
  delete process.env.NEXT_PUBLIC_APP_ENV;

  try {
    const failedCatalog = await getDeployWizardCatalogFromBff({
      fetchImpl: async () => new Response("{}", { status: 503 }),
    });
    const malformedCatalog = await getDeployWizardCatalogFromBff({
      fetchImpl: async () => Response.json({ unexpected: true }),
    });

    assert.equal(failedCatalog, mockDeployWizardCatalog);
    assert.equal(malformedCatalog, mockDeployWizardCatalog);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
    restoreEnv("NEXT_PUBLIC_APP_ENV", previousAppEnv);
  }
});

test("getDeployWizardCatalogFromBff does not call BFF in mock mode or without CS_URL", async () => {
  const previousCsUrl = process.env.CS_URL;
  const previousAppEnv = process.env.NEXT_PUBLIC_APP_ENV;
  let calls = 0;

  try {
    delete process.env.CS_URL;
    delete process.env.NEXT_PUBLIC_APP_ENV;
    const noCsUrlCatalog = await getDeployWizardCatalogFromBff({
      fetchImpl: async () => {
        calls += 1;
        return Response.json({});
      },
    });

    process.env.CS_URL = "http://cloudstack.local";
    process.env.NEXT_PUBLIC_APP_ENV = "mock";
    const mockModeCatalog = await getDeployWizardCatalogFromBff({
      fetchImpl: async () => {
        calls += 1;
        return Response.json({});
      },
    });

    assert.equal(calls, 0);
    assert.equal(noCsUrlCatalog, mockDeployWizardCatalog);
    assert.equal(mockModeCatalog, mockDeployWizardCatalog);
    assert.equal(mockDeployWizardCatalog.serviceOfferings, mockServiceOfferings);
    assert.equal(mockDeployWizardCatalog.diskOfferings, mockDiskOfferings);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
    restoreEnv("NEXT_PUBLIC_APP_ENV", previousAppEnv);
  }
});

test("buildDeployVirtualMachineParams maps wizard fields to safe CloudStack deploy parameters", () => {
  const params = buildDeployVirtualMachineParams({
    name: "web-01",
    displayName: "Web 01",
    zoneId: "zone-1",
    templateId: "tmpl-1",
    serviceOfferingId: "so-1",
    networkId: "net-1",
    diskOfferingId: "do-1",
    securityGroupId: "sg-1",
    sshKeyPairName: "admin-key",
    userData: "#cloud-config\npackage_update: true",
  });

  assert.equal(params.get("name"), "web-01");
  assert.equal(params.get("displayname"), "Web 01");
  assert.equal(params.get("zoneid"), "zone-1");
  assert.equal(params.get("templateid"), "tmpl-1");
  assert.equal(params.get("serviceofferingid"), "so-1");
  assert.equal(params.get("networkids"), "net-1");
  assert.equal(params.get("diskofferingid"), "do-1");
  assert.equal(params.get("sshkeypairs"), "admin-key");
  assert.equal(params.get("startvm"), "true");
  assert.equal(params.get("userdata"), Buffer.from("#cloud-config\npackage_update: true", "utf8").toString("base64"));
  assert.equal(params.has("securitygroupids"), false);
  assert.equal(params.has("sessionkey"), false);
  assert.equal(params.has("command"), false);
  assert.equal(params.has("response"), false);
});

test("buildDeployVirtualMachineParams sends security group only when no advanced network is selected", () => {
  const params = buildDeployVirtualMachineParams({
    name: "basic-01",
    zoneId: "zone-1",
    templateId: "tmpl-1",
    serviceOfferingId: "so-1",
    securityGroupId: "sg-1",
  });

  assert.equal(params.get("securitygroupids"), "sg-1");
  assert.equal(params.has("networkids"), false);
});

test("buildDeployVirtualMachineParams maps advanced project, affinity, static IP, and stopped launch options", () => {
  const params = buildDeployVirtualMachineParams({
    name: "advanced-01",
    zoneId: "zone-1",
    templateId: "tmpl-1",
    serviceOfferingId: "so-1",
    networkId: "net-1",
    securityGroupId: "sg-1",
    projectId: "project-1",
    affinityGroupIds: ["ag-1", " ", "ag-2"],
    ipAddress: "10.0.0.25",
    startVm: false,
  });

  assert.equal(params.get("projectid"), "project-1");
  assert.equal(params.get("affinitygroupids"), "ag-1,ag-2");
  assert.equal(params.get("ipaddress"), "10.0.0.25");
  assert.equal(params.get("startvm"), "false");
  assert.equal(params.get("networkids"), "net-1");
  assert.equal(params.has("securitygroupids"), false);
});

test("buildDeployVirtualMachineParams ignores static IP when no explicit network is selected", () => {
  const params = buildDeployVirtualMachineParams({
    name: "basic-01",
    zoneId: "zone-1",
    templateId: "tmpl-1",
    serviceOfferingId: "so-1",
    securityGroupId: "sg-1",
    ipAddress: "10.0.0.25",
  });

  assert.equal(params.get("securitygroupids"), "sg-1");
  assert.equal(params.has("networkids"), false);
  assert.equal(params.has("ipaddress"), false);
});

test("buildDeployVirtualMachineParams sends custom disk offering id and size when the size is valid", () => {
  const params = buildDeployVirtualMachineParams({
    name: "custom-disk-01",
    zoneId: "zone-1",
    templateId: "tmpl-1",
    serviceOfferingId: "so-1",
    diskOfferingId: "do-custom",
    diskOfferingCustomized: true,
    diskOfferingSizeGiB: 75,
  });

  assert.equal(params.get("diskofferingid"), "do-custom");
  assert.equal(params.get("size"), "75");
});

test("buildDeployVirtualMachineParams rejects custom disk offerings without a positive integer size", () => {
  const baseInput = {
    name: "custom-disk-01",
    zoneId: "zone-1",
    templateId: "tmpl-1",
    serviceOfferingId: "so-1",
    diskOfferingId: "do-custom",
    diskOfferingCustomized: true,
  };

  assert.throws(() => buildDeployVirtualMachineParams(baseInput), /custom disk size/i);
  assert.throws(
    () => buildDeployVirtualMachineParams({ ...baseInput, diskOfferingSizeGiB: 0 }),
    /custom disk size/i,
  );
  assert.throws(
    () => buildDeployVirtualMachineParams({ ...baseInput, diskOfferingSizeGiB: 10.5 }),
    /custom disk size/i,
  );
});

test("buildDeployVirtualMachineParams never sends size for fixed disk offerings", () => {
  const params = buildDeployVirtualMachineParams({
    name: "fixed-disk-01",
    zoneId: "zone-1",
    templateId: "tmpl-1",
    serviceOfferingId: "so-1",
    diskOfferingId: "do-fixed",
    diskOfferingCustomized: false,
    diskOfferingSizeGiB: 75,
  });

  assert.equal(params.get("diskofferingid"), "do-fixed");
  assert.equal(params.has("size"), false);
});

test("deployVirtualMachineFromWizard posts deploy params and returns the async job id", async () => {
  const requests: Array<{ url: string; init?: RequestInit; body: Record<string, string> }> = [];
  const fetchImpl: typeof fetch = async (input, init) => {
    requests.push({
      url: String(input),
      init,
      body: JSON.parse(String(init?.body)) as Record<string, string>,
    });
    return Response.json({
      deployvirtualmachineresponse: {
        id: "vm-1",
        jobid: "job-1",
      },
    });
  };

  const result = await deployVirtualMachineFromWizard(
    {
      name: "web-01",
      zoneId: "zone-1",
      templateId: "tmpl-1",
      serviceOfferingId: "so-1",
      networkId: "net-1",
    },
    { fetchImpl },
  );

  assert.deepEqual(result, { jobId: "job-1", virtualMachineId: "vm-1" });
  assert.equal(requests.length, 1);
  assert.equal(new URL(requests[0]!.url).pathname, "/api/cs/deployVirtualMachine");
  assert.equal(requests[0]!.init?.method, "POST");
  assert.equal((requests[0]!.init?.headers as Record<string, string>)["content-type"], "application/json");
  assert.deepEqual(requests[0]!.body, {
    name: "web-01",
    zoneid: "zone-1",
    templateid: "tmpl-1",
    serviceofferingid: "so-1",
    networkids: "net-1",
    startvm: "true",
  });
});

test("deployVirtualMachineFromWizard rejects failed or malformed deploy responses", async () => {
  await assert.rejects(
    deployVirtualMachineFromWizard(
      {
        name: "web-01",
        zoneId: "zone-1",
        templateId: "tmpl-1",
        serviceOfferingId: "so-1",
      },
      { fetchImpl: async () => Response.json({ error: "capacity unavailable" }, { status: 503 }) },
    ),
    /capacity unavailable/,
  );

  await assert.rejects(
    deployVirtualMachineFromWizard(
      {
        name: "web-01",
        zoneId: "zone-1",
        templateId: "tmpl-1",
        serviceOfferingId: "so-1",
      },
      { fetchImpl: async () => Response.json({ deployvirtualmachineresponse: {} }) },
    ),
    /missing async job id/,
  );
});

test("queryDeployWizardJobResult normalizes pending, success, and failed async jobs", async () => {
  const payloads = [
    { queryasyncjobresultresponse: { jobid: "job-1", jobstatus: 0, jobprocstatus: 40 } },
    {
      queryasyncjobresultresponse: {
        jobid: "job-1",
        jobstatus: 1,
        jobresult: { virtualmachine: { id: "vm-1", name: "web-01", state: "Running" } },
      },
    },
    {
      queryasyncjobresultresponse: {
        jobid: "job-2",
        jobstatus: 2,
        jobresultcode: 530,
        jobresult: { errortext: "Insufficient capacity" },
      },
    },
  ];
  const urls: string[] = [];
  const fetchImpl: typeof fetch = async (input) => {
    urls.push(String(input));
    return Response.json(payloads.shift());
  };

  const pending = await queryDeployWizardJobResult("job-1", { fetchImpl });
  const success = await queryDeployWizardJobResult("job-1", { fetchImpl });
  const failed = await queryDeployWizardJobResult("job-2", { fetchImpl });

  assert.deepEqual(pending, { jobId: "job-1", status: "pending", progress: 40 });
  assert.deepEqual(success, {
    jobId: "job-1",
    status: "success",
    virtualMachineId: "vm-1",
    virtualMachineName: "web-01",
    virtualMachineState: "Running",
  });
  assert.deepEqual(failed, {
    jobId: "job-2",
    status: "failed",
    resultCode: 530,
    errorText: "Insufficient capacity",
  });
  assert.equal(new URL(urls[0]!).pathname, "/api/cs/queryAsyncJobResult");
  assert.equal(new URL(urls[0]!).searchParams.get("jobid"), "job-1");
});

function restoreEnv(name: string, value: string | undefined): void {
  if (value === undefined) {
    delete process.env[name];
  } else {
    process.env[name] = value;
  }
}
