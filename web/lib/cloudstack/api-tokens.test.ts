import assert from "node:assert/strict";
import test from "node:test";

import {
  getUserApiTokenSummaryFromBff,
  mapGetUserKeysResponse,
  mapRegisterUserKeysResponse,
  maskSecret,
  registerUserApiToken,
} from "./api-tokens.ts";

test("maskSecret preserves short inspection prefix and suffix", () => {
  assert.equal(maskSecret("abcdefghijklmnop"), "abcd...mnop");
  assert.equal(maskSecret("abc"), "••••");
  assert.equal(maskSecret(null), "Not generated");
});

test("mapGetUserKeysResponse maps API key access and masks sensitive values", () => {
  const summary = mapGetUserKeysResponse({
    getuserkeysresponse: {
      userkeys: {
        apikeyaccess: true,
        apikey: "api-key-123456",
        secretkey: "secret-key-abcdef",
      },
    },
  });

  assert.deepEqual(summary, {
    access: "enabled",
    apiKeyMasked: "api-...3456",
    secretKeyMasked: "secr...cdef",
    hasApiKey: true,
    hasSecretKey: true,
  });
});

test("mapGetUserKeysResponse reports unknown access when the envelope is malformed", () => {
  assert.deepEqual(mapGetUserKeysResponse({}), {
    access: "unknown",
    apiKeyMasked: "Not generated",
    secretKeyMasked: "Not generated",
    hasApiKey: false,
    hasSecretKey: false,
  });
});

test("mapRegisterUserKeysResponse maps generated key pair without dropping the one-time secret", () => {
  const generated = mapRegisterUserKeysResponse({
    registeruserkeysresponse: {
      userkeys: {
        id: "keypair-1",
        name: "automation",
        apikey: "generated-api",
        secretkey: "generated-secret",
      },
    },
  });

  assert.equal(generated.id, "keypair-1");
  assert.equal(generated.name, "automation");
  assert.equal(generated.apiKey, "generated-api");
  assert.equal(generated.secretKey, "generated-secret");
});

test("mapRegisterUserKeysResponse accepts flatter CloudStack register responses", () => {
  const generated = mapRegisterUserKeysResponse({
    registeruserkeysresponse: {
      id: "keypair-2",
      apikey: "flat-api",
      secretkey: "flat-secret",
    },
  });

  assert.equal(generated.id, "keypair-2");
  assert.equal(generated.apiKey, "flat-api");
  assert.equal(generated.secretKey, "flat-secret");
});

test("getUserApiTokenSummaryFromBff calls getUserKeys for the current user and forwards only cookies", async () => {
  const previousCsUrl = process.env.CS_URL;
  const previousNextAuthUrl = process.env.NEXTAUTH_URL;
  const previousAppEnv = process.env.NEXT_PUBLIC_APP_ENV;
  process.env.CS_URL = "http://cloudstack.local";
  delete process.env.NEXTAUTH_URL;
  delete process.env.NEXT_PUBLIC_APP_ENV;

  const urls: URL[] = [];
  let forwardedCookie: string | undefined;
  const summary = await getUserApiTokenSummaryFromBff("user-1", {
    requestHeaders: new Headers({
      host: "ui.example.test",
      cookie: "cloudstack.session=opaque",
      authorization: "Bearer should-not-forward",
    }),
    fetchImpl: async (input, init) => {
      urls.push(new URL(String(input)));
      forwardedCookie = (init?.headers as Record<string, string> | undefined)?.cookie;
      return Response.json({ getuserkeysresponse: { userkeys: { apikeyaccess: false } } });
    },
  });

  assert.equal(urls[0]?.pathname, "/api/cs/getUserKeys");
  assert.equal(urls[0]?.searchParams.get("id"), "user-1");
  assert.equal(forwardedCookie, "cloudstack.session=opaque");
  assert.equal(summary.access, "disabled");

  restoreEnv("CS_URL", previousCsUrl);
  restoreEnv("NEXTAUTH_URL", previousNextAuthUrl);
  restoreEnv("NEXT_PUBLIC_APP_ENV", previousAppEnv);
});

test("getUserApiTokenSummaryFromBff uses mock fallback when CS_URL is absent", async () => {
  const previousCsUrl = process.env.CS_URL;
  delete process.env.CS_URL;
  let called = false;

  const summary = await getUserApiTokenSummaryFromBff("user-1", {
    fetchImpl: async () => {
      called = true;
      return Response.json({});
    },
  });

  assert.equal(called, false);
  assert.equal(summary.access, "enabled");
  assert.equal(summary.hasApiKey, true);
  assert.equal(summary.hasSecretKey, false);

  restoreEnv("CS_URL", previousCsUrl);
});

test("registerUserApiToken posts safe JSON params to registerUserKeys", async () => {
  const calls: Array<{ url: URL; body: unknown }> = [];
  await registerUserApiToken({
    userId: "user-1",
    name: "automation",
    description: "Created from settings",
    fetchImpl: async (input, init) => {
      calls.push({ url: new URL(String(input), "http://ui.example.test"), body: JSON.parse(String(init?.body)) });
      return Response.json({
        registeruserkeysresponse: {
          userkeys: { id: "keypair-1", apikey: "generated-api", secretkey: "generated-secret" },
        },
      });
    },
  });

  assert.equal(calls[0]?.url.pathname, "/api/cs/registerUserKeys");
  assert.deepEqual(calls[0]?.body, {
    id: "user-1",
    name: "automation",
    description: "Created from settings",
  });
});

test("registerUserApiToken omits blank descriptions and rejects missing generated secrets", async () => {
  let body: unknown;

  await assert.rejects(
    registerUserApiToken({
      userId: "user-1",
      name: "automation",
      description: "   ",
      fetchImpl: async (_input, init) => {
        body = JSON.parse(String(init?.body));
        return Response.json({
          registeruserkeysresponse: {
            userkeys: { id: "keypair-1", apikey: "generated-api" },
          },
        });
      },
    }),
    /missing generated key material/,
  );

  assert.deepEqual(body, {
    id: "user-1",
    name: "automation",
  });
});

test("registerUserApiToken throws CloudStack error text", async () => {
  await assert.rejects(
    registerUserApiToken({
      userId: "user-1",
      name: "automation",
      fetchImpl: async () =>
        Response.json({ errorresponse: { errortext: "API key generation is disabled" } }, { status: 403 }),
    }),
    /API key generation is disabled/,
  );
});

function restoreEnv(name: string, value: string | undefined): void {
  if (value === undefined) {
    delete process.env[name];
  } else {
    process.env[name] = value;
  }
}
