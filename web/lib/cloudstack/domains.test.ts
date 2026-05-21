import assert from "node:assert/strict";
import test from "node:test";

import { mockDomains } from "../mock-data.ts";
import {
  domainsFromListDomainsResponse,
  getDomainsFromBff,
  mapCloudStackDomainToTenantDomain,
} from "./domains.ts";

test("mapCloudStackDomainToTenantDomain maps CloudStack domain fields into the UI domain contract", () => {
  const domain = mapCloudStackDomainToTenantDomain({
    id: "domain-1",
    name: "engineering",
    path: "ROOT/engineering",
    parentdomainname: "ROOT",
    level: 1,
    state: "Active",
    haschild: true,
    vmtotal: 12,
    projecttotal: 3,
    networktotal: 4,
    vpctotal: 2,
  });

  assert.deepEqual(domain, {
    id: "domain-1",
    name: "engineering",
    path: "ROOT/engineering",
    parent: "ROOT",
    level: 1,
    state: "active",
    hasChildren: true,
    instances: 12,
    projects: 3,
    networks: 6,
  });
});

test("mapCloudStackDomainToTenantDomain normalizes fallback values, booleans, counts, and suspicious states", () => {
  const domain = mapCloudStackDomainToTenantDomain({
    name: "labs",
    level: "-2",
    state: "Disabled by operator",
    haschild: "true",
    vmtotal: "not-a-number",
    projecttotal: "7",
    networktotal: "5",
  });

  assert.deepEqual(domain, {
    id: "labs",
    name: "labs",
    path: "labs",
    parent: null,
    level: 0,
    state: "inactive",
    hasChildren: true,
    instances: 0,
    projects: 7,
    networks: 5,
  });
});

test("mapCloudStackDomainToTenantDomain falls back to unknown identifiers and inactive error states", () => {
  const domain = mapCloudStackDomainToTenantDomain({
    state: "Error",
    haschild: "false",
    networktotal: "bad",
    vpctotal: "3",
  });

  assert.deepEqual(domain, {
    id: "unknown",
    name: "unnamed-domain",
    path: "unknown",
    parent: null,
    level: 0,
    state: "inactive",
    hasChildren: false,
    instances: 0,
    projects: 0,
    networks: 3,
  });
});

test("domainsFromListDomainsResponse maps the CloudStack response envelope", () => {
  const domains = domainsFromListDomainsResponse({
    listdomainsresponse: {
      count: 1,
      domain: [
        {
          id: "domain-2",
          name: "customers",
          path: "ROOT/customers",
          level: "1",
          haschild: "false",
        },
      ],
    },
  });

  assert.equal(domains.length, 1);
  assert.equal(domains[0]?.id, "domain-2");
  assert.equal(domains[0]?.name, "customers");
  assert.equal(domains[0]?.path, "ROOT/customers");
  assert.equal(domains[0]?.state, "active");
});

test("getDomainsFromBff calls the BFF listDomains command and forwards cookies", async () => {
  const previousCsUrl = process.env.CS_URL;
  const previousNextAuthUrl = process.env.NEXTAUTH_URL;
  const previousAppEnv = process.env.NEXT_PUBLIC_APP_ENV;
  process.env.CS_URL = "http://cloudstack.local";
  delete process.env.NEXTAUTH_URL;
  delete process.env.NEXT_PUBLIC_APP_ENV;

  let requestedUrl = "";
  let forwardedCookie: string | undefined;
  const fetchImpl: typeof fetch = async (input, init) => {
    requestedUrl = String(input);
    forwardedCookie = (init?.headers as Record<string, string> | undefined)?.cookie;
    return Response.json({
      listdomainsresponse: {
        domain: [{ id: "domain-api", name: "api-domain", path: "ROOT/api-domain" }],
      },
    });
  };

  try {
    const domains = await getDomainsFromBff({
      fetchImpl,
      requestHeaders: new Headers({
        cookie: "cloudstack.session=opaque",
        host: "console.example.test",
        "x-forwarded-proto": "https",
      }),
    });
    const url = new URL(requestedUrl);

    assert.equal(url.origin, "https://console.example.test");
    assert.equal(url.pathname, "/api/cs/listDomains");
    assert.equal(url.searchParams.get("listall"), "true");
    assert.equal(url.searchParams.get("details"), "min");
    assert.equal(forwardedCookie, "cloudstack.session=opaque");
    assert.equal(domains[0]?.name, "api-domain");
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
    restoreEnv("NEXTAUTH_URL", previousNextAuthUrl);
    restoreEnv("NEXT_PUBLIC_APP_ENV", previousAppEnv);
  }
});

test("getDomainsFromBff returns mock domains when CloudStack is unavailable", async () => {
  const previousCsUrl = process.env.CS_URL;
  process.env.CS_URL = "http://cloudstack.local";

  try {
    const domains = await getDomainsFromBff({
      fetchImpl: async () => new Response("{}", { status: 503 }),
    });

    assert.equal(domains, mockDomains);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

test("getDomainsFromBff returns mock domains when the response envelope is missing", async () => {
  const previousCsUrl = process.env.CS_URL;
  process.env.CS_URL = "http://cloudstack.local";

  try {
    const domains = await getDomainsFromBff({
      fetchImpl: async () => Response.json({}),
    });

    assert.equal(domains, mockDomains);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

test("getDomainsFromBff does not call the BFF when CS_URL is absent", async () => {
  const previousCsUrl = process.env.CS_URL;
  delete process.env.CS_URL;
  let called = false;

  try {
    const domains = await getDomainsFromBff({
      fetchImpl: async () => {
        called = true;
        return Response.json({});
      },
    });

    assert.equal(called, false);
    assert.equal(domains, mockDomains);
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
