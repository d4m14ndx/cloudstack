import assert from "node:assert/strict";
import test from "node:test";

import { mockBillingQuotaSummaries } from "../mock-data.ts";
import {
  billingQuotaSummariesFromQuotaSummaryResponse,
  getBillingQuotaSummariesFromBff,
  mapCloudStackQuotaSummaryToBillingQuotaSummary,
} from "./billing.ts";

test("mapCloudStackQuotaSummaryToBillingQuotaSummary maps an account row and preserves decimal display values", () => {
  const summary = mapCloudStackQuotaSummaryToBillingQuotaSummary({
    accountid: "acc-1",
    account: "platform",
    domainid: "dom-1",
    domain: "ROOT/platform",
    balance: "1200.3400",
    state: "ACTIVE",
    quota: "450.678900",
    startdate: "2026-05-01",
    enddate: "2026-05-31",
    currency: "AUD",
    quotaenabled: "true",
  });

  assert.deepEqual(summary, {
    id: "acc-1",
    name: "platform",
    domain: "ROOT/platform",
    accountState: "active",
    quotaState: "enabled",
    lifecycle: "active",
    balance: "1200.3400",
    periodUsage: "450.678900",
    currency: "AUD",
    period: "2026-05-01 to 2026-05-31",
  });
});

test("mapCloudStackQuotaSummaryToBillingQuotaSummary maps a project row, enabled text, and removed lifecycle", () => {
  const summary = mapCloudStackQuotaSummaryToBillingQuotaSummary({
    accountid: "acc-2",
    account: "engineering",
    projectid: "proj-1",
    projectname: "ci-runners",
    project: "ci",
    domain: "ROOT/engineering",
    balance: "-12.50",
    state: "DISABLED",
    quota: "99.010",
    startdate: "2026-04-01",
    enddate: "2026-04-30",
    currency: "USD",
    quotaenabled: "Enabled",
    projectremoved: "true",
  });

  assert.deepEqual(summary, {
    id: "proj-1",
    name: "Project: ci-runners",
    domain: "ROOT/engineering",
    accountState: "disabled",
    quotaState: "enabled",
    lifecycle: "removed",
    balance: "-12.50",
    periodUsage: "99.010",
    currency: "USD",
    period: "2026-04-01 to 2026-04-30",
  });
});

test("mapCloudStackQuotaSummaryToBillingQuotaSummary falls back to composite identifiers and disabled quota state", () => {
  const summary = mapCloudStackQuotaSummaryToBillingQuotaSummary({
    domainid: "dom-2",
    domain: "ROOT/labs",
    account: "research",
    state: undefined,
    quotaenabled: false,
    accountremoved: false,
    domainremoved: "false",
  });

  assert.deepEqual(summary, {
    id: "dom-2:research",
    name: "research",
    domain: "ROOT/labs",
    accountState: "unknown",
    quotaState: "disabled",
    lifecycle: "active",
    balance: "",
    periodUsage: "",
    currency: "",
    period: "- to -",
  });
});

test("billingQuotaSummariesFromQuotaSummaryResponse maps the quota summary envelope", () => {
  const summaries = billingQuotaSummariesFromQuotaSummaryResponse({
    quotasummaryresponse: {
      count: "1",
      summary: [
        {
          accountid: "acc-api",
          account: "api-account",
          domain: "ROOT/api",
          balance: "10.00",
          quota: "3.250",
          quotaenabled: true,
        },
      ],
    },
  });

  assert.equal(summaries.length, 1);
  assert.equal(summaries[0]?.id, "acc-api");
  assert.equal(summaries[0]?.name, "api-account");
  assert.equal(summaries[0]?.periodUsage, "3.250");
  assert.equal(summaries[0]?.quotaState, "enabled");
});

test("getBillingQuotaSummariesFromBff calls the BFF quotaSummary command and forwards cookies", async () => {
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
      quotasummaryresponse: {
        summary: [{ accountid: "acc-api", account: "api-account", domain: "ROOT/api" }],
      },
    });
  };

  try {
    const summaries = await getBillingQuotaSummariesFromBff({
      fetchImpl,
      requestHeaders: new Headers({
        cookie: "cloudstack.session=opaque",
        host: "console.example.test",
        "x-forwarded-proto": "https",
      }),
    });
    const url = new URL(requestedUrl);

    assert.equal(url.origin, "https://console.example.test");
    assert.equal(url.pathname, "/api/cs/quotaSummary");
    assert.equal(url.searchParams.get("listall"), "true");
    assert.equal(url.searchParams.get("accountstatetoshow"), "ACTIVE");
    assert.equal(url.searchParams.get("page"), "1");
    assert.equal(url.searchParams.get("pagesize"), "50");
    assert.equal(forwardedCookie, "cloudstack.session=opaque");
    assert.equal(summaries[0]?.name, "api-account");
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
    restoreEnv("NEXTAUTH_URL", previousNextAuthUrl);
    restoreEnv("NEXT_PUBLIC_APP_ENV", previousAppEnv);
  }
});

test("getBillingQuotaSummariesFromBff returns mock rows when CloudStack is unavailable", async () => {
  const previousCsUrl = process.env.CS_URL;
  const previousAppEnv = process.env.NEXT_PUBLIC_APP_ENV;
  process.env.CS_URL = "http://cloudstack.local";
  delete process.env.NEXT_PUBLIC_APP_ENV;

  try {
    const summaries = await getBillingQuotaSummariesFromBff({
      fetchImpl: async () => new Response("{}", { status: 503 }),
    });

    assert.equal(summaries, mockBillingQuotaSummaries);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
    restoreEnv("NEXT_PUBLIC_APP_ENV", previousAppEnv);
  }
});

test("getBillingQuotaSummariesFromBff returns mock rows when the response envelope is missing", async () => {
  const previousCsUrl = process.env.CS_URL;
  const previousAppEnv = process.env.NEXT_PUBLIC_APP_ENV;
  process.env.CS_URL = "http://cloudstack.local";
  delete process.env.NEXT_PUBLIC_APP_ENV;

  try {
    const summaries = await getBillingQuotaSummariesFromBff({
      fetchImpl: async () => Response.json({}),
    });

    assert.equal(summaries, mockBillingQuotaSummaries);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
    restoreEnv("NEXT_PUBLIC_APP_ENV", previousAppEnv);
  }
});

test("getBillingQuotaSummariesFromBff returns mock rows when fetch throws", async () => {
  const previousCsUrl = process.env.CS_URL;
  const previousAppEnv = process.env.NEXT_PUBLIC_APP_ENV;
  process.env.CS_URL = "http://cloudstack.local";
  delete process.env.NEXT_PUBLIC_APP_ENV;

  try {
    const summaries = await getBillingQuotaSummariesFromBff({
      fetchImpl: async () => {
        throw new Error("quota plugin unavailable");
      },
    });

    assert.equal(summaries, mockBillingQuotaSummaries);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
    restoreEnv("NEXT_PUBLIC_APP_ENV", previousAppEnv);
  }
});

test("getBillingQuotaSummariesFromBff does not call the BFF when CS_URL is absent", async () => {
  const previousCsUrl = process.env.CS_URL;
  const previousAppEnv = process.env.NEXT_PUBLIC_APP_ENV;
  delete process.env.CS_URL;
  delete process.env.NEXT_PUBLIC_APP_ENV;
  let called = false;

  try {
    const summaries = await getBillingQuotaSummariesFromBff({
      fetchImpl: async () => {
        called = true;
        return Response.json({});
      },
    });

    assert.equal(called, false);
    assert.equal(summaries, mockBillingQuotaSummaries);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
    restoreEnv("NEXT_PUBLIC_APP_ENV", previousAppEnv);
  }
});

test("getBillingQuotaSummariesFromBff returns mock rows in mock app mode", async () => {
  const previousCsUrl = process.env.CS_URL;
  const previousAppEnv = process.env.NEXT_PUBLIC_APP_ENV;
  process.env.CS_URL = "http://cloudstack.local";
  process.env.NEXT_PUBLIC_APP_ENV = "mock";
  let called = false;

  try {
    const summaries = await getBillingQuotaSummariesFromBff({
      fetchImpl: async () => {
        called = true;
        return Response.json({});
      },
    });

    assert.equal(called, false);
    assert.equal(summaries, mockBillingQuotaSummaries);
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
