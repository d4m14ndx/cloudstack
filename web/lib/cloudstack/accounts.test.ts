import assert from "node:assert/strict";
import test from "node:test";

import { mockAccounts } from "../mock-data.ts";
import {
  accountsFromListAccountsResponse,
  getAccountsFromBff,
  mapCloudStackAccountToAccount,
} from "./accounts.ts";

test("mapCloudStackAccountToAccount maps CloudStack account fields into the UI account contract", () => {
  const account = mapCloudStackAccountToAccount({
    name: "platform",
    domainpath: "ROOT/platform",
    roletype: "Admin",
    user: [{ id: "user-1" }, { id: "user-2" }],
    vmtotal: 7,
    state: "enabled",
  });

  assert.deepEqual(account, {
    name: "platform",
    domain: "ROOT/platform",
    role: "Admin",
    users: 2,
    instances: 7,
    state: "active",
  });
});

test("mapCloudStackAccountToAccount normalizes domain admin roles, disabled state, and string counts", () => {
  const account = mapCloudStackAccountToAccount({
    account: "engineering",
    domain: "eng",
    rolename: "Domain Administrator",
    usercount: "14",
    vmrunning: "2",
    vmstopped: "1",
    state: "disabled",
  });

  assert.deepEqual(account, {
    name: "engineering",
    domain: "eng",
    role: "Domain admin",
    users: 14,
    instances: 3,
    state: "disabled",
  });
});

test("accountsFromListAccountsResponse maps the CloudStack response envelope", () => {
  const accounts = accountsFromListAccountsResponse({
    listaccountsresponse: {
      count: 1,
      account: [
        {
          name: "research",
          domainpath: "ROOT/labs",
          accounttype: 0,
          usercount: 3,
          vmtotal: "1",
          state: "enabled",
        },
      ],
    },
  });

  assert.equal(accounts.length, 1);
  assert.equal(accounts[0]?.name, "research");
  assert.equal(accounts[0]?.domain, "ROOT/labs");
  assert.equal(accounts[0]?.role, "User");
  assert.equal(accounts[0]?.instances, 1);
});

test("getAccountsFromBff calls the BFF listAccounts command and forwards cookies", async () => {
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
      listaccountsresponse: {
        account: [{ name: "api-account", domain: "ROOT", roletype: "User" }],
      },
    });
  };

  try {
    const accounts = await getAccountsFromBff({
      fetchImpl,
      requestHeaders: new Headers({
        cookie: "cloudstack.session=opaque",
        host: "console.example.test",
        "x-forwarded-proto": "https",
      }),
    });
    const url = new URL(requestedUrl);

    assert.equal(url.origin, "https://console.example.test");
    assert.equal(url.pathname, "/api/cs/listAccounts");
    assert.equal(url.searchParams.get("listall"), "true");
    assert.equal(url.searchParams.get("details"), "min");
    assert.equal(forwardedCookie, "cloudstack.session=opaque");
    assert.equal(accounts[0]?.name, "api-account");
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
    restoreEnv("NEXTAUTH_URL", previousNextAuthUrl);
    restoreEnv("NEXT_PUBLIC_APP_ENV", previousAppEnv);
  }
});

test("getAccountsFromBff returns mock accounts when CloudStack is unavailable", async () => {
  const previousCsUrl = process.env.CS_URL;
  process.env.CS_URL = "http://cloudstack.local";

  try {
    const accounts = await getAccountsFromBff({
      fetchImpl: async () => new Response("{}", { status: 503 }),
    });

    assert.equal(accounts, mockAccounts);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

test("getAccountsFromBff does not call the BFF when CS_URL is absent", async () => {
  const previousCsUrl = process.env.CS_URL;
  delete process.env.CS_URL;
  let called = false;

  try {
    const accounts = await getAccountsFromBff({
      fetchImpl: async () => {
        called = true;
        return Response.json({});
      },
    });

    assert.equal(called, false);
    assert.equal(accounts, mockAccounts);
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
