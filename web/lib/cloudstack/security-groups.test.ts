import assert from "node:assert/strict";
import test from "node:test";

import { mockSecurityGroups } from "../mock-data.ts";
import {
  getSecurityGroupsFromBff,
  mapCloudStackSecurityGroupToSecurityGroup,
  securityGroupsFromListSecurityGroupsResponse,
} from "./security-groups.ts";

test("mapCloudStackSecurityGroupToSecurityGroup maps CloudStack security group fields and normalizes rules", () => {
  const securityGroup = mapCloudStackSecurityGroupToSecurityGroup({
    id: "sg-1",
    name: "web",
    description: "Web tier access",
    account: "platform",
    domainid: "domain-1",
    domain: "ROOT",
    domainpath: "ROOT/platform",
    projectid: "project-1",
    project: "core",
    virtualmachinecount: "2",
    virtualmachineids: ["vm-1", "vm-2"],
    ingressrule: [
      {
        ruleid: "ingress-1",
        protocol: "tcp",
        startport: "80",
        endport: "80",
        cidr: "203.0.113.0/24",
      },
      {
        ruleid: "ingress-2",
        protocol: "icmp",
        icmptype: "8",
        icmpcode: "0",
        securitygroupname: "monitoring",
        account: "observability",
      },
    ],
    egressrule: [
      {
        ruleid: "egress-1",
        protocol: "udp",
        startport: "53",
        endport: "54",
        cidr: "0.0.0.0/0",
      },
    ],
  });

  assert.deepEqual(securityGroup, {
    id: "sg-1",
    name: "web",
    description: "Web tier access",
    account: "platform",
    domain: "ROOT",
    domainId: "domain-1",
    domainPath: "ROOT/platform",
    project: "core",
    projectId: "project-1",
    ingressRules: [
      {
        id: "ingress-1",
        protocol: "TCP",
        range: "80",
        source: "203.0.113.0/24",
      },
      {
        id: "ingress-2",
        protocol: "ICMP",
        range: "type 8 / code 0",
        source: "observability/monitoring",
      },
    ],
    egressRules: [
      {
        id: "egress-1",
        protocol: "UDP",
        range: "53-54",
        source: "0.0.0.0/0",
      },
    ],
    instances: 2,
    instanceIds: ["vm-1", "vm-2"],
    isDefault: false,
  });
});

test("mapCloudStackSecurityGroupToSecurityGroup applies missing-field defaults", () => {
  const securityGroup = mapCloudStackSecurityGroupToSecurityGroup({
    name: "default",
    ingressrule: [{ protocol: "tcp", cidr: "0.0.0.0/0" }],
    egressrule: [{ protocol: "icmp" }],
  });

  assert.deepEqual(securityGroup, {
    id: "default",
    name: "default",
    description: "-",
    account: "unknown",
    domain: "unknown",
    domainId: null,
    domainPath: "unknown",
    project: null,
    projectId: null,
    ingressRules: [
      {
        id: "tcp-all-0.0.0.0/0",
        protocol: "TCP",
        range: "all",
        source: "0.0.0.0/0",
      },
    ],
    egressRules: [
      {
        id: "icmp-icmp-any",
        protocol: "ICMP",
        range: "type any / code any",
        source: "any",
      },
    ],
    instances: 0,
    instanceIds: [],
    isDefault: true,
  });
});

test("securityGroupsFromListSecurityGroupsResponse maps the CloudStack response envelope", () => {
  const securityGroups = securityGroupsFromListSecurityGroupsResponse({
    listsecuritygroupsresponse: {
      count: 1,
      securitygroup: [
        {
          id: "sg-2",
          name: "database",
          description: "Database access",
          domainpath: "ROOT/data",
        },
      ],
    },
  });

  assert.equal(securityGroups.length, 1);
  assert.equal(securityGroups[0]?.id, "sg-2");
  assert.equal(securityGroups[0]?.name, "database");
  assert.equal(securityGroups[0]?.domainPath, "ROOT/data");
});

test("getSecurityGroupsFromBff calls the BFF listSecurityGroups command, forwards cookies, and disables caching", async () => {
  const previousCsUrl = process.env.CS_URL;
  const previousNextAuthUrl = process.env.NEXTAUTH_URL;
  const previousAppEnv = process.env.NEXT_PUBLIC_APP_ENV;
  process.env.CS_URL = "http://cloudstack.local";
  delete process.env.NEXTAUTH_URL;
  delete process.env.NEXT_PUBLIC_APP_ENV;

  let requestedUrl = "";
  let forwardedCookie: string | undefined;
  let cacheMode: RequestCache | undefined;
  let method: string | undefined;
  const fetchImpl: typeof fetch = async (input, init) => {
    requestedUrl = String(input);
    forwardedCookie = (init?.headers as Record<string, string> | undefined)?.cookie;
    cacheMode = init?.cache;
    method = init?.method;
    return Response.json({
      listsecuritygroupsresponse: {
        securitygroup: [{ id: "sg-api", name: "api-security", virtualmachinecount: 4 }],
      },
    });
  };

  try {
    const securityGroups = await getSecurityGroupsFromBff({
      fetchImpl,
      requestHeaders: new Headers({
        cookie: "cloudstack.session=opaque",
        host: "console.example.test",
        "x-forwarded-proto": "https",
      }),
    });
    const url = new URL(requestedUrl);

    assert.equal(url.origin, "https://console.example.test");
    assert.equal(url.pathname, "/api/cs/listSecurityGroups");
    assert.equal(url.searchParams.get("listall"), "true");
    assert.equal(forwardedCookie, "cloudstack.session=opaque");
    assert.equal(cacheMode, "no-store");
    assert.equal(method, "GET");
    assert.equal(securityGroups[0]?.name, "api-security");
    assert.equal(securityGroups[0]?.instances, 4);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
    restoreEnv("NEXTAUTH_URL", previousNextAuthUrl);
    restoreEnv("NEXT_PUBLIC_APP_ENV", previousAppEnv);
  }
});

test("getSecurityGroupsFromBff returns mock security groups when CS_URL is absent", async () => {
  const previousCsUrl = process.env.CS_URL;
  delete process.env.CS_URL;
  let called = false;

  try {
    const securityGroups = await getSecurityGroupsFromBff({
      fetchImpl: async () => {
        called = true;
        return Response.json({});
      },
    });

    assert.equal(called, false);
    assert.equal(securityGroups, mockSecurityGroups);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

test("getSecurityGroupsFromBff returns mock security groups when the BFF response is not OK", async () => {
  const previousCsUrl = process.env.CS_URL;
  process.env.CS_URL = "http://cloudstack.local";

  try {
    const securityGroups = await getSecurityGroupsFromBff({
      fetchImpl: async () => new Response("{}", { status: 503 }),
    });

    assert.equal(securityGroups, mockSecurityGroups);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

test("getSecurityGroupsFromBff returns mock security groups when the CloudStack envelope is missing", async () => {
  const previousCsUrl = process.env.CS_URL;
  process.env.CS_URL = "http://cloudstack.local";

  try {
    const securityGroups = await getSecurityGroupsFromBff({
      fetchImpl: async () => Response.json({}),
    });

    assert.equal(securityGroups, mockSecurityGroups);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

test("getSecurityGroupsFromBff returns mock security groups when fetch throws", async () => {
  const previousCsUrl = process.env.CS_URL;
  process.env.CS_URL = "http://cloudstack.local";

  try {
    const securityGroups = await getSecurityGroupsFromBff({
      fetchImpl: async () => {
        throw new Error("network unavailable");
      },
    });

    assert.equal(securityGroups, mockSecurityGroups);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

test("getSecurityGroupsFromBff returns mock security groups in mock app environment", async () => {
  const previousCsUrl = process.env.CS_URL;
  const previousAppEnv = process.env.NEXT_PUBLIC_APP_ENV;
  process.env.CS_URL = "http://cloudstack.local";
  process.env.NEXT_PUBLIC_APP_ENV = "mock";
  let called = false;

  try {
    const securityGroups = await getSecurityGroupsFromBff({
      fetchImpl: async () => {
        called = true;
        return Response.json({});
      },
    });

    assert.equal(called, false);
    assert.equal(securityGroups, mockSecurityGroups);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
    restoreEnv("NEXT_PUBLIC_APP_ENV", previousAppEnv);
  }
});

function restoreEnv(name: string, value: string | undefined): void {
  if (value === undefined) {
    delete process.env[name];
  } else {
    process.env[name] = value;
  }
}
