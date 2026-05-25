import assert from "node:assert/strict";
import test from "node:test";

import { CloudStackClient, signCloudStackParams } from "./client.ts";

test("signCloudStackParams signs lower-cased sorted params without including signature", () => {
  const params = new URLSearchParams({
    command: "listVirtualMachines",
    response: "json",
    signature: "client-supplied",
    apiKey: "service-key",
  });

  assert.equal(
    signCloudStackParams(params, "secret"),
    signCloudStackParams(new URLSearchParams("apikey=service-key&command=listvirtualmachines&response=json"), "secret"),
  );
});

test("mintUserSessionToken signs POST body, prefers trusted domainId, and parses timeout expiry", async () => {
  let requestUrl = "";
  let requestInit: RequestInit | undefined;
  const before = Date.now();
  const client = new CloudStackClient({
    baseUrl: "http://cloudstack.local/",
    serviceApiKey: "service-key",
    serviceSecretKey: "secret-key",
    fetchImpl: async (url, init) => {
      requestUrl = String(url);
      requestInit = init;
      return Response.json({
        createusersessiontokenresponse: {
          sessionkey: "server-session-key",
          userid: "user-uuid",
          username: "alice",
          domainid: "domain-uuid",
          timeout: 1800,
        },
      });
    },
  });

  const result = await client.mintUserSessionToken({
    username: "alice",
    domain: "ROOT",
    domainId: "domain-uuid",
  });

  const body = new URLSearchParams(String(requestInit?.body));
  assert.equal(requestUrl, "http://cloudstack.local/client/api");
  assert.equal(requestInit?.method, "POST");
  assert.equal(body.get("command"), "createUserSessionToken");
  assert.equal(body.get("username"), "alice");
  assert.equal(body.get("domainId"), "domain-uuid");
  assert.equal(body.get("domain"), null);
  assert.equal(body.get("apiKey"), "service-key");
  assert.ok(body.get("signature"));
  assert.equal(result.sessionkey, "server-session-key");
  assert.equal(result.userid, "user-uuid");
  assert.equal(result.domainid, "domain-uuid");
  assert.equal(result.timeout, 1800);
  assert.ok(result.expiresAt >= before + 1_799_000);
});

test("mintUserSessionToken omits ROOT domain fallback and rejects missing expiry", async () => {
  let requestInit: RequestInit | undefined;
  const client = new CloudStackClient({
    baseUrl: "http://cloudstack.local",
    serviceApiKey: "service-key",
    serviceSecretKey: "secret-key",
    fetchImpl: async (_url, init) => {
      requestInit = init;
      return Response.json({
        createusersessiontokenresponse: {
          sessionkey: "server-session-key",
        },
      });
    },
  });

  await assert.rejects(
    () =>
      client.mintUserSessionToken({
        username: "alice",
        domain: "ROOT",
        domainId: "ROOT",
      }),
    /invalid expiry/,
  );

  const body = new URLSearchParams(String(requestInit?.body));
  assert.equal(body.get("domain"), null);
  assert.equal(body.get("domainId"), null);
});
