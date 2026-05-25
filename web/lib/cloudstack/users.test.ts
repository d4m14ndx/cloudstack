import assert from "node:assert/strict";
import test from "node:test";

import type { CurrentUser } from "../auth/types.ts";
import {
  getCurrentUserProfileFromBff,
  mapCloudStackUserToProfile,
  usersFromListUsersResponse,
} from "./users.ts";

const currentUser: CurrentUser = {
  id: "user-uuid-1",
  username: "alex",
  email: "alex@cloudstack.local",
  name: "Alex Kim",
  role: "ROOT",
  domain: "ROOT",
  domainId: "domain-root",
};

test("mapCloudStackUserToProfile maps CloudStack user fields into profile rows", () => {
  const profile = mapCloudStackUserToProfile({
    id: "user-uuid-1",
    username: "alex",
    firstname: "Alex",
    lastname: "Kim",
    email: "alex@example.test",
    account: "admin",
    accounttype: 1,
    domain: "ROOT",
    domainid: "domain-root",
    timezone: "Australia/Perth",
    usersource: "native",
    state: "enabled",
    apikeyaccess: true,
    is2faenabled: true,
    is2famandated: false,
  }, currentUser);

  assert.deepEqual(profile, {
    id: "user-uuid-1",
    username: "alex",
    displayName: "Alex Kim",
    email: "alex@example.test",
    account: "admin",
    role: "ROOT",
    domain: "ROOT",
    domainId: "domain-root",
    timezone: "Australia/Perth",
    source: "native",
    state: "enabled",
    apiKeyAccess: "enabled",
    twoFactorEnabled: true,
    twoFactorMandated: false,
  });
});

test("usersFromListUsersResponse maps the listUsers envelope", () => {
  const users = usersFromListUsersResponse({
    listusersresponse: {
      count: 1,
      user: [{ id: "user-uuid-1", username: "alex", firstname: "Alex", lastname: "Kim" }],
    },
  }, currentUser);

  assert.equal(users.length, 1);
  assert.equal(users[0]?.displayName, "Alex Kim");
});

test("getCurrentUserProfileFromBff calls listUsers for the current user and forwards cookies", async () => {
  const previousCsUrl = process.env.CS_URL;
  const previousNextAuthUrl = process.env.NEXTAUTH_URL;
  const previousAppEnv = process.env.NEXT_PUBLIC_APP_ENV;
  process.env.CS_URL = "http://cloudstack.local";
  delete process.env.NEXTAUTH_URL;
  delete process.env.NEXT_PUBLIC_APP_ENV;

  const urls: URL[] = [];
  const headers: HeadersInit[] = [];

  try {
    const profile = await getCurrentUserProfileFromBff(currentUser, {
      requestHeaders: new Headers({ host: "ui.example.test", cookie: "cloudstack.session=opaque" }),
      fetchImpl: async (input, init) => {
        urls.push(new URL(String(input)));
        headers.push(init?.headers ?? {});
        return Response.json({
          listusersresponse: {
            count: 1,
            user: [{ id: "user-uuid-1", username: "alex", firstname: "Alex", lastname: "Kim" }],
          },
        });
      },
    });

    assert.equal(urls[0]?.origin, "https://ui.example.test");
    assert.equal(urls[0]?.pathname, "/api/cs/listUsers");
    assert.equal(urls[0]?.searchParams.get("id"), "user-uuid-1");
    assert.equal(urls[0]?.searchParams.get("showicon"), "true");
    assert.deepEqual(headers[0], { cookie: "cloudstack.session=opaque" });
    assert.equal(profile.displayName, "Alex Kim");
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
    restoreEnv("NEXTAUTH_URL", previousNextAuthUrl);
    restoreEnv("NEXT_PUBLIC_APP_ENV", previousAppEnv);
  }
});

test("getCurrentUserProfileFromBff falls back to Auth.js/mock identity when CloudStack is unavailable", async () => {
  const previousCsUrl = process.env.CS_URL;
  delete process.env.CS_URL;
  let called = false;

  try {
    const profile = await getCurrentUserProfileFromBff(currentUser, {
      fetchImpl: async () => {
        called = true;
        throw new Error("should not fetch without CS_URL");
      },
    });

    assert.equal(called, false);
    assert.equal(profile.displayName, currentUser.name);
    assert.equal(profile.email, currentUser.email);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

test("getCurrentUserProfileFromBff falls back when the BFF response is not successful", async () => {
  const previousCsUrl = process.env.CS_URL;
  const previousAppEnv = process.env.NEXT_PUBLIC_APP_ENV;
  process.env.CS_URL = "http://cloudstack.local";
  delete process.env.NEXT_PUBLIC_APP_ENV;

  try {
    const profile = await getCurrentUserProfileFromBff(currentUser, {
      fetchImpl: async () => new Response("{}", { status: 503 }),
    });

    assert.equal(profile.displayName, currentUser.name);
    assert.equal(profile.source, "session");
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
    restoreEnv("NEXT_PUBLIC_APP_ENV", previousAppEnv);
  }
});

test("getCurrentUserProfileFromBff falls back when the listUsers envelope is missing", async () => {
  const previousCsUrl = process.env.CS_URL;
  const previousAppEnv = process.env.NEXT_PUBLIC_APP_ENV;
  process.env.CS_URL = "http://cloudstack.local";
  delete process.env.NEXT_PUBLIC_APP_ENV;

  try {
    const profile = await getCurrentUserProfileFromBff(currentUser, {
      fetchImpl: async () => Response.json({}),
    });

    assert.equal(profile.displayName, currentUser.name);
    assert.equal(profile.apiKeyAccess, "unknown");
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
