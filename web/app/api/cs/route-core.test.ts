import assert from "node:assert/strict";
import test from "node:test";

import type { CurrentUser } from "../../../lib/auth/types.ts";
import type { BffConfig } from "../../../lib/bff/config.ts";
import { BFF_SESSION_COOKIE, type BffSession } from "../../../lib/bff/session.ts";
import type { BffSessionStore } from "../../../lib/bff/session-store.ts";
import type { MintCloudStackSessionInput, MintCloudStackSessionResult } from "../../../lib/cloudstack/client.ts";
import {
  createCloudStackRouteHandlers,
  type CloudStackRouteClient,
  type CloudStackRouteDeps,
  type CloudStackRouteRequest,
} from "./[command]/route-core.ts";

const now = Date.UTC(2026, 4, 21, 12, 0, 0);
const sessionTtlSeconds = 28_800;
const refreshMarginSeconds = 120;
const existingSessionId = "existingCloudStackSessionId000000";

const user: CurrentUser = {
  id: "idp-user-1",
  username: "alice",
  email: "alice@example.com",
  name: "Alice",
  role: "ADMIN",
  domain: "Engineering",
  domainId: "domain-uuid-1",
};

test("GET unauthenticated with no dev session returns 401 and does not call CloudStack", async () => {
  const harness = new Harness({ authenticatedUser: null });
  const { GET } = createCloudStackRouteHandlers(harness.deps());

  const response = await GET(routeRequest("https://ui.example/api/cs/listVirtualMachines"), routeContext("listVirtualMachines"));

  assert.equal(response.status, 401);
  assert.deepEqual(await jsonBody(response), { error: "Unauthenticated" });
  assert.equal(harness.client.proxyCalls.length, 0);
  assert.equal(harness.client.mintCalls.length, 0);
});

test("invalid command returns 400 before CloudStack is called", async () => {
  const harness = new Harness();
  const { GET } = createCloudStackRouteHandlers(harness.deps());

  const response = await GET(routeRequest("https://ui.example/api/cs/../listVirtualMachines"), routeContext("../listVirtualMachines"));

  assert.equal(response.status, 400);
  assert.deepEqual(await jsonBody(response), { error: "Invalid CloudStack command" });
  assert.equal(harness.client.proxyCalls.length, 0);
  assert.equal(harness.client.mintCalls.length, 0);
});

test("missing CloudStack URL returns 503", async () => {
  const harness = new Harness({ config: { cloudstackUrl: null } });
  const { GET } = createCloudStackRouteHandlers(harness.deps());

  const response = await GET(routeRequest("https://ui.example/api/cs/listVirtualMachines"), routeContext("listVirtualMachines"));

  assert.equal(response.status, 503);
  assert.deepEqual(await jsonBody(response), { error: "CloudStack API is not configured" });
  assert.equal(harness.client.proxyCalls.length, 0);
});

test("authenticated GET without a BFF cookie mints an opaque session and proxies with the server sessionkey", async () => {
  const harness = new Harness();
  const { GET } = createCloudStackRouteHandlers(harness.deps());

  const response = await GET(
    routeRequest("https://ui.example/api/cs/listVirtualMachines?sessionkey=client-secret&command=deleteAll&response=xml&id=vm-1"),
    routeContext("listVirtualMachines"),
  );

  assert.equal(response.status, 200);
  assert.equal(harness.client.mintCalls.length, 1);
  assert.deepEqual(harness.client.mintCalls[0], {
    username: "alice",
    domain: "Engineering",
    domainId: "domain-uuid-1",
  });
  assert.equal(harness.client.proxyCalls.length, 1);
  assert.equal(harness.client.proxyCalls[0]?.command, "listVirtualMachines");
  assert.equal(harness.client.proxyCalls[0]?.method, "GET");
  assert.equal(harness.client.proxyCalls[0]?.cloudstackSessionkey, "minted-session-1");
  assert.deepEqual(paramEntries(harness.client.proxyCalls[0]?.clientParams), [["id", "vm-1"]]);

  const setCookie = response.headers.get("set-cookie") ?? "";
  assert.match(setCookie, /^cloudstack\.session=[A-Za-z0-9_-]+;/);
  assert.match(setCookie, /HttpOnly/);
  assert.match(setCookie, /Path=\/api\/cs/);
  assert.equal(setCookie.includes("minted-session-1"), false);
  assert.equal(setCookie.includes("client-secret"), false);
  assert.equal(response.headers.get("cache-control"), "no-store");

  const body = await response.text();
  assert.equal(body, '{"ok":true}');
});

test("existing matching BFF cookie reuses the stored session without minting", async () => {
  const harness = new Harness();
  await harness.store.set(existingSessionId, bffSession("stored-sessionkey", now + 60 * 60 * 1_000), sessionTtlSeconds);
  const { GET } = createCloudStackRouteHandlers(harness.deps());

  const response = await GET(
    routeRequest("https://ui.example/api/cs/listZones", {
      headers: { cookie: `${BFF_SESSION_COOKIE}=${existingSessionId}` },
    }),
    routeContext("listZones"),
  );

  assert.equal(response.status, 200);
  assert.equal(response.headers.get("set-cookie"), null);
  assert.equal(harness.client.mintCalls.length, 0);
  assert.equal(harness.client.proxyCalls[0]?.cloudstackSessionkey, "stored-sessionkey");
});

test("POST combines query and JSON body params, strips unsafe overrides, and forwards as POST", async () => {
  const harness = new Harness();
  await harness.store.set(existingSessionId, bffSession("stored-sessionkey", now + 60 * 60 * 1_000), sessionTtlSeconds);
  const { POST } = createCloudStackRouteHandlers(harness.deps());

  const response = await POST(
    routeRequest("https://ui.example/api/cs/deployVirtualMachine?zoneid=zone-1&sessionkey=client-secret", {
      method: "POST",
      headers: {
        cookie: `${BFF_SESSION_COOKIE}=${existingSessionId}`,
        "content-type": "application/json",
      },
      body: JSON.stringify({
        serviceofferingid: "small",
        templateid: "tmpl-1",
        command: "override",
        response: "xml",
        sessionkey: "body-secret",
        tags: ["blue", "batch"],
      }),
    }),
    routeContext("deployVirtualMachine"),
  );

  assert.equal(response.status, 200);
  assert.equal(harness.client.proxyCalls.length, 1);
  assert.equal(harness.client.proxyCalls[0]?.method, "POST");
  assert.equal(harness.client.proxyCalls[0]?.cloudstackSessionkey, "stored-sessionkey");
  assert.deepEqual(paramEntries(harness.client.proxyCalls[0]?.clientParams), [
    ["zoneid", "zone-1"],
    ["serviceofferingid", "small"],
    ["templateid", "tmpl-1"],
    ["tags", "blue"],
    ["tags", "batch"],
  ]);
});

test("expiring stored CloudStack session is reminted before proxying and updates the store", async () => {
  const harness = new Harness();
  await harness.store.set(existingSessionId, bffSession("old-sessionkey", now + 30_000), sessionTtlSeconds);
  const { GET } = createCloudStackRouteHandlers(harness.deps());

  const response = await GET(
    routeRequest("https://ui.example/api/cs/listVirtualMachines", {
      headers: { cookie: `${BFF_SESSION_COOKIE}=${existingSessionId}` },
    }),
    routeContext("listVirtualMachines"),
  );

  assert.equal(response.status, 200);
  assert.equal(harness.client.mintCalls.length, 1);
  assert.equal(harness.client.proxyCalls[0]?.cloudstackSessionkey, "minted-session-1");
  assert.equal((await harness.store.get(existingSessionId))?.cloudstackSessionkey, "minted-session-1");
});

test("first CloudStack 401 remints once and retries the proxy call", async () => {
  const harness = new Harness();
  harness.client.proxyResponses = [
    jsonResponse({ error: "expired" }, 401),
    jsonResponse({ ok: true, retried: true }, 200),
  ];
  await harness.store.set(existingSessionId, bffSession("old-sessionkey", now + 60 * 60 * 1_000), sessionTtlSeconds);
  const { GET } = createCloudStackRouteHandlers(harness.deps());

  const response = await GET(
    routeRequest("https://ui.example/api/cs/listVirtualMachines", {
      headers: { cookie: `${BFF_SESSION_COOKIE}=${existingSessionId}` },
    }),
    routeContext("listVirtualMachines"),
  );

  assert.equal(response.status, 200);
  assert.equal(harness.client.proxyCalls.length, 2);
  assert.equal(harness.client.proxyCalls[0]?.cloudstackSessionkey, "old-sessionkey");
  assert.equal(harness.client.proxyCalls[1]?.cloudstackSessionkey, "minted-session-1");
  assert.equal(await response.text(), '{"ok":true,"retried":true}');
});

test("retry 401 deletes the stored session and expires the BFF session cookie", async () => {
  const harness = new Harness();
  harness.client.proxyResponses = [
    jsonResponse({ error: "expired" }, 401),
    jsonResponse({ error: "expired again" }, 401),
  ];
  await harness.store.set(existingSessionId, bffSession("old-sessionkey", now + 60 * 60 * 1_000), sessionTtlSeconds);
  const { GET } = createCloudStackRouteHandlers(harness.deps());

  const response = await GET(
    routeRequest("https://ui.example/api/cs/listVirtualMachines", {
      headers: { cookie: `${BFF_SESSION_COOKIE}=${existingSessionId}` },
    }),
    routeContext("listVirtualMachines"),
  );

  assert.equal(response.status, 401);
  assert.equal(await harness.store.get(existingSessionId), null);
  assert.match(response.headers.get("set-cookie") ?? "", /^cloudstack\.session=;/);
  assert.match(response.headers.get("set-cookie") ?? "", /Max-Age=0/);
});

test("unsupported content type and bad JSON return 400", async () => {
  const harness = new Harness();
  await harness.store.set(existingSessionId, bffSession("stored-sessionkey", now + 60 * 60 * 1_000), sessionTtlSeconds);
  const { POST } = createCloudStackRouteHandlers(harness.deps());

  const unsupportedResponse = await POST(
    routeRequest("https://ui.example/api/cs/deployVirtualMachine", {
      method: "POST",
      headers: {
        cookie: `${BFF_SESSION_COOKIE}=${existingSessionId}`,
        "content-type": "text/plain",
      },
      body: "name=value",
    }),
    routeContext("deployVirtualMachine"),
  );
  const badJsonResponse = await POST(
    routeRequest("https://ui.example/api/cs/deployVirtualMachine", {
      method: "POST",
      headers: {
        cookie: `${BFF_SESSION_COOKIE}=${existingSessionId}`,
        "content-type": "application/json",
      },
      body: "[1,2,3]",
    }),
    routeContext("deployVirtualMachine"),
  );

  assert.equal(unsupportedResponse.status, 400);
  assert.equal(badJsonResponse.status, 400);
  assert.equal(harness.client.proxyCalls.length, 0);
});

test("CloudStack proxy throw returns 503", async () => {
  const harness = new Harness();
  harness.client.proxyError = new Error("network down");
  await harness.store.set(existingSessionId, bffSession("stored-sessionkey", now + 60 * 60 * 1_000), sessionTtlSeconds);
  const { GET } = createCloudStackRouteHandlers(harness.deps());

  const response = await GET(
    routeRequest("https://ui.example/api/cs/listVirtualMachines", {
      headers: { cookie: `${BFF_SESSION_COOKIE}=${existingSessionId}` },
    }),
    routeContext("listVirtualMachines"),
  );

  assert.equal(response.status, 503);
  assert.deepEqual(await jsonBody(response), { error: "CloudStack API request failed" });
});

function routeRequest(input: string, init?: RequestInit): CloudStackRouteRequest {
  const request = new Request(input, init) as CloudStackRouteRequest;
  request.nextUrl = new URL(input);
  return request;
}

function routeContext(command: string): { params: { command: string } } {
  return { params: { command } };
}

async function jsonBody(response: Response): Promise<unknown> {
  return JSON.parse(await response.text()) as unknown;
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

function paramEntries(params: URLSearchParams | undefined): [string, string][] {
  return params ? [...params.entries()] : [];
}

function bffSession(cloudstackSessionkey: string, cloudstackExpiresAt: number): BffSession {
  return {
    userId: "cs-user-1",
    username: "alice",
    domain: "Engineering",
    domainId: "domain-uuid-1",
    role: "ADMIN",
    cloudstackSessionkey,
    cloudstackExpiresAt,
    idpSub: "idp-user-1",
    idpEmail: "alice@example.com",
    createdAt: now,
    lastSeenAt: now,
  };
}

class Harness {
  public readonly store = new MemoryStore();
  public readonly client = new FakeClient();
  private readonly configValue: BffConfig;
  private readonly authenticatedUser: CurrentUser | null;

  public constructor({
    config,
    authenticatedUser = user,
  }: {
    config?: Partial<BffConfig>;
    authenticatedUser?: CurrentUser | null;
  } = {}) {
    this.configValue = {
      cloudstackUrl: "https://cloudstack.example",
      serviceApiKey: "service-api-key",
      serviceSecretKey: "service-secret-key",
      sessionTtlSeconds,
      refreshMarginSeconds,
      allowDevSession: false,
      ...config,
    };
    this.authenticatedUser = authenticatedUser;
  }

  public deps(): CloudStackRouteDeps {
    return {
      getConfig: () => this.configValue,
      getStore: () => this.store,
      getAuthenticatedUser: async () => this.authenticatedUser,
      createClient: () => this.client,
      now: () => now,
    };
  }
}

class FakeClient implements CloudStackRouteClient {
  public readonly mintCalls: MintCloudStackSessionInput[] = [];
  public readonly proxyCalls: {
    command: string;
    clientParams: URLSearchParams;
    cloudstackSessionkey: string;
    method: "GET" | "POST";
  }[] = [];
  public proxyResponses: Response[] = [jsonResponse({ ok: true })];
  public proxyError: Error | null = null;

  public async mintUserSessionToken(input: MintCloudStackSessionInput): Promise<MintCloudStackSessionResult> {
    this.mintCalls.push(input);
    const suffix = this.mintCalls.length;
    return {
      sessionkey: `minted-session-${suffix}`,
      userid: "cs-user-1",
      username: input.username,
      domainid: input.domainId ?? "domain-uuid-1",
      domain: input.domain ?? "Engineering",
      role: "USER",
      expiresAt: now + 60 * 60 * 1_000,
      timeout: 3_600,
    };
  }

  public async proxyCommand(
    command: string,
    clientParams: URLSearchParams,
    cloudstackSessionkey: string,
    method: "GET" | "POST",
  ): Promise<Response> {
    this.proxyCalls.push({
      command,
      clientParams: new URLSearchParams(clientParams),
      cloudstackSessionkey,
      method,
    });
    if (this.proxyError) {
      throw this.proxyError;
    }

    return this.proxyResponses.shift() ?? jsonResponse({ ok: true });
  }
}

class MemoryStore implements BffSessionStore {
  private readonly sessions = new Map<string, BffSession>();

  public async get(sessionId: string): Promise<BffSession | null> {
    return this.sessions.get(sessionId) ?? null;
  }

  public async set(sessionId: string, session: BffSession, ttlSeconds: number): Promise<void> {
    void ttlSeconds;
    this.sessions.set(sessionId, session);
  }

  public async delete(sessionId: string): Promise<void> {
    this.sessions.delete(sessionId);
  }
}
