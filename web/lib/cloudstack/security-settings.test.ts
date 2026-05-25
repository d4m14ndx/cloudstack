import assert from "node:assert/strict";
import test from "node:test";

import type { CurrentUser } from "../auth/types.ts";
import {
  getCurrentUserSecuritySettingsFromBff,
  mapCloudStackUserToSecuritySettings,
} from "./security-settings.ts";

const currentUser: CurrentUser = {
  id: "mock-uuid-alex",
  username: "alex",
  email: "alex@cloudstack.local",
  name: "Alex Kim",
  role: "ROOT",
  domain: "ROOT",
  domainId: "mock-uuid-domain-root",
};

test("mapCloudStackUserToSecuritySettings maps current user security flags", () => {
  const settings = mapCloudStackUserToSecuritySettings({
    id: "user-1",
    usersource: "native",
    state: "enabled",
    apikeyaccess: "Enabled",
    is2faenabled: true,
    is2famandated: false,
    passwordchangerequired: true,
  });

  assert.deepEqual(settings, {
    source: "native",
    state: "enabled",
    apiKeyAccess: "enabled",
    twoFactorEnabled: true,
    twoFactorMandated: false,
    passwordChangeRequired: true,
  });
});

test("mapCloudStackUserToSecuritySettings normalizes string booleans and unknown API access", () => {
  const settings = mapCloudStackUserToSecuritySettings({
    id: "user-2",
    usersource: "ldap",
    state: "disabled",
    apikeyaccess: "Inherited",
    is2faenabled: "true",
    is2famandated: "false",
    passwordchangerequired: "false",
  });

  assert.equal(settings.apiKeyAccess, "unknown");
  assert.equal(settings.twoFactorEnabled, true);
  assert.equal(settings.twoFactorMandated, false);
  assert.equal(settings.passwordChangeRequired, false);
});

test("getCurrentUserSecuritySettingsFromBff calls listUsers for the current user and forwards cookies", async () => {
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
      listusersresponse: {
        user: [
          {
            id: currentUser.id,
            usersource: "saml2",
            state: "enabled",
            apikeyaccess: false,
            is2faenabled: "false",
            is2famandated: "true",
            passwordchangerequired: "true",
          },
        ],
      },
    });
  };

  try {
    const settings = await getCurrentUserSecuritySettingsFromBff(currentUser, {
      fetchImpl,
      requestHeaders: new Headers({
        cookie: "cloudstack.session=opaque",
        host: "console.example.test",
        "x-forwarded-proto": "https",
      }),
    });
    const url = new URL(requestedUrl);

    assert.equal(url.origin, "https://console.example.test");
    assert.equal(url.pathname, "/api/cs/listUsers");
    assert.equal(url.searchParams.get("id"), currentUser.id);
    assert.equal(url.searchParams.get("listall"), "true");
    assert.equal(forwardedCookie, "cloudstack.session=opaque");
    assert.equal(cacheMode, "no-store");
    assert.equal(settings.source, "saml2");
    assert.equal(settings.apiKeyAccess, "disabled");
    assert.equal(settings.twoFactorMandated, true);
    assert.equal(settings.passwordChangeRequired, true);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
    restoreEnv("NEXTAUTH_URL", previousNextAuthUrl);
    restoreEnv("NEXT_PUBLIC_APP_ENV", previousAppEnv);
  }
});

test("getCurrentUserSecuritySettingsFromBff falls back when CS_URL is absent", async () => {
  const previous = process.env.CS_URL;
  delete process.env.CS_URL;

  try {
    const settings = await getCurrentUserSecuritySettingsFromBff(currentUser, {
      fetchImpl: async () => {
        throw new Error("should not fetch without CS_URL");
      },
    });
    assert.equal(settings.source, "session");
    assert.equal(settings.state, "active");
    assert.equal(settings.apiKeyAccess, "unknown");
  } finally {
    restoreEnv("CS_URL", previous);
  }
});

test("getCurrentUserSecuritySettingsFromBff falls back when CloudStack is unavailable", async () => {
  const previous = process.env.CS_URL;
  process.env.CS_URL = "http://cloudstack.local";

  try {
    const settings = await getCurrentUserSecuritySettingsFromBff(currentUser, {
      fetchImpl: async () => new Response("{}", { status: 503 }),
    });

    assert.equal(settings.source, "session");
    assert.equal(settings.apiKeyAccess, "unknown");
  } finally {
    restoreEnv("CS_URL", previous);
  }
});

test("getCurrentUserSecuritySettingsFromBff falls back when the listUsers envelope is missing", async () => {
  const previous = process.env.CS_URL;
  process.env.CS_URL = "http://cloudstack.local";

  try {
    const settings = await getCurrentUserSecuritySettingsFromBff(currentUser, {
      fetchImpl: async () => Response.json({}),
    });

    assert.equal(settings.source, "session");
    assert.equal(settings.apiKeyAccess, "unknown");
  } finally {
    restoreEnv("CS_URL", previous);
  }
});

function restoreEnv(name: string, value: string | undefined): void {
  if (value === undefined) {
    delete process.env[name];
  } else {
    process.env[name] = value;
  }
}
