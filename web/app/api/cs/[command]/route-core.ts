import type { CurrentUser } from "../../../../lib/auth/types.ts";
import type { BffConfig } from "../../../../lib/bff/config.ts";
import { extractSessionIdFromCookieHeader, shouldRefreshCloudStackSession, type BffSession } from "../../../../lib/bff/session.ts";
import {
  expiredBffSessionCookieHeader,
  getOrCreateBffSessionForUser,
  type BffSessionResolution,
  type CloudStackSessionIssuer,
} from "../../../../lib/bff/session-exchange.ts";
import type { BffSessionStore } from "../../../../lib/bff/session-store.ts";
import type { MintCloudStackSessionInput, MintCloudStackSessionResult } from "../../../../lib/cloudstack/client.ts";
import { appendSafeClientParams, assertValidCloudStackCommand, readClientBodyParams } from "../../../../lib/cloudstack/request.ts";

export type CloudStackRouteRequest = Request & { nextUrl: URL };

export type CloudStackRouteContext = {
  params: {
    command: string;
  };
};

export type CloudStackRouteClient = CloudStackSessionIssuer & {
  mintUserSessionToken(input: MintCloudStackSessionInput): Promise<MintCloudStackSessionResult>;
  proxyCommand(
    command: string,
    clientParams: URLSearchParams,
    cloudstackSessionkey: string,
    method: "GET" | "POST",
  ): Promise<Response>;
};

export type CloudStackRouteDeps = {
  getConfig(): BffConfig;
  getStore(): BffSessionStore;
  getAuthenticatedUser(): Promise<CurrentUser | null>;
  createClient(config: BffConfig): CloudStackRouteClient;
  getOrCreateDevSession?(store: BffSessionStore, sessionId: string, ttlSeconds: number): Promise<BffSession>;
  now?(): number;
};

export function createCloudStackRouteHandlers(deps: CloudStackRouteDeps): {
  GET(request: CloudStackRouteRequest, context: CloudStackRouteContext): Promise<Response>;
  POST(request: CloudStackRouteRequest, context: CloudStackRouteContext): Promise<Response>;
} {
  return {
    GET: (request, context) => proxyCloudStackCommand(request, context, "GET", deps),
    POST: (request, context) => proxyCloudStackCommand(request, context, "POST", deps),
  };
}

async function proxyCloudStackCommand(
  request: CloudStackRouteRequest,
  context: CloudStackRouteContext,
  method: "GET" | "POST",
  deps: CloudStackRouteDeps,
): Promise<Response> {
  let command: string;
  try {
    command = decodeURIComponent(context.params.command);
    assertValidCloudStackCommand(command);
  } catch {
    return jsonError("Invalid CloudStack command", 400);
  }

  const config = deps.getConfig();
  const store = deps.getStore();
  const authenticatedUser = await deps.getAuthenticatedUser();
  const sessionId = extractSessionIdFromCookieHeader(request.headers.get("cookie"));
  const secureCookie = isSecureRequest(request);

  if (!authenticatedUser && !config.allowDevSession) {
    return jsonError("Unauthenticated", 401, sessionId ? expiredBffSessionCookieHeader(secureCookie) : undefined);
  }

  if (!config.cloudstackUrl) {
    return jsonError("CloudStack API is not configured", 503);
  }

  const client = deps.createClient(config);
  let sessionResolution: BffSessionResolution | null = null;
  if (authenticatedUser) {
    try {
      sessionResolution = await getOrCreateBffSessionForUser({
        store,
        client,
        user: authenticatedUser,
        existingSessionId: sessionId,
        ttlSeconds: config.sessionTtlSeconds,
        secureCookie,
        now: deps.now?.(),
      });
    } catch {
      return jsonError("CloudStack session exchange failed", 503, sessionId ? expiredBffSessionCookieHeader(secureCookie) : undefined);
    }
  } else if (config.allowDevSession && deps.getOrCreateDevSession) {
    const devSessionId = "__dev_cloudstack_session";
    sessionResolution = {
      sessionId: devSessionId,
      session: await deps.getOrCreateDevSession(store, devSessionId, config.sessionTtlSeconds),
    };
  }

  if (!sessionResolution) {
    return jsonError("Unauthenticated", 401);
  }

  const bffSessionId = sessionResolution.sessionId;
  const session = sessionResolution.session;
  const setCookieHeader = sessionResolution.setCookieHeader;
  let refreshedSession: BffSession;
  try {
    refreshedSession = await refreshIfNeeded(
      client,
      store,
      bffSessionId,
      session,
      config.refreshMarginSeconds,
      config.sessionTtlSeconds,
      deps.now,
    );
  } catch {
    await store.delete(bffSessionId);
    return jsonError("CloudStack session refresh failed", 401, expiredBffSessionCookieHeader(secureCookie));
  }

  let clientParams: URLSearchParams;
  try {
    clientParams = await readClientParams(request, method);
  } catch {
    return jsonError("Invalid CloudStack request parameters", 400);
  }

  try {
    const firstResponse = await client.proxyCommand(command, clientParams, refreshedSession.cloudstackSessionkey, method);
    if (firstResponse.status !== 401) {
      return cloudStackResponse(firstResponse, setCookieHeader);
    }

    let reminted: BffSession;
    try {
      reminted = await remintCloudStackSession(client, store, bffSessionId, refreshedSession, config.sessionTtlSeconds, deps.now);
    } catch {
      await store.delete(bffSessionId);
      return jsonError("Unauthenticated", 401, expiredBffSessionCookieHeader(secureCookie));
    }

    const retryResponse = await client.proxyCommand(command, clientParams, reminted.cloudstackSessionkey, method);
    if (retryResponse.status === 401) {
      await store.delete(bffSessionId);
      return cloudStackResponse(retryResponse, expiredBffSessionCookieHeader(secureCookie));
    }
    return cloudStackResponse(retryResponse, setCookieHeader);
  } catch {
    return jsonError("CloudStack API request failed", 503);
  }
}

async function refreshIfNeeded(
  client: CloudStackRouteClient,
  store: BffSessionStore,
  sessionId: string,
  session: BffSession,
  refreshMarginSeconds: number,
  ttlSeconds: number,
  now: (() => number) | undefined,
): Promise<BffSession> {
  if (!shouldRefreshCloudStackSession(session.cloudstackExpiresAt, now?.() ?? Date.now(), refreshMarginSeconds)) {
    return session;
  }

  return remintCloudStackSession(client, store, sessionId, session, ttlSeconds, now);
}

async function remintCloudStackSession(
  client: CloudStackRouteClient,
  store: BffSessionStore,
  sessionId: string,
  session: BffSession,
  ttlSeconds: number,
  now: (() => number) | undefined,
): Promise<BffSession> {
  const fresh = await client.mintUserSessionToken({
    username: session.username,
    domain: session.domain,
    domainId: session.domainId,
  });
  const updated = {
    ...session,
    cloudstackSessionkey: fresh.sessionkey,
    cloudstackExpiresAt: fresh.expiresAt,
    lastSeenAt: now?.() ?? Date.now(),
  };
  await store.set(sessionId, updated, ttlSeconds);
  return updated;
}

async function readClientParams(request: CloudStackRouteRequest, method: "GET" | "POST"): Promise<URLSearchParams> {
  const unsafeParams = method === "GET" ? request.nextUrl.searchParams : await combinedPostParams(request);
  const safeParams = new URLSearchParams();
  appendSafeClientParams(safeParams, unsafeParams);
  return safeParams;
}

async function combinedPostParams(request: CloudStackRouteRequest): Promise<URLSearchParams> {
  const params = new URLSearchParams(request.nextUrl.searchParams);
  const bodyParams = await readClientBodyParams(request);
  for (const [key, value] of bodyParams.entries()) {
    params.append(key, value);
  }
  return params;
}

function isSecureRequest(request: CloudStackRouteRequest): boolean {
  return process.env.NODE_ENV === "production" || request.nextUrl.protocol === "https:";
}

async function cloudStackResponse(response: Response, setCookieHeader?: string): Promise<Response> {
  const body = await response.text();
  const headers: Record<string, string> = {
    "content-type": response.headers.get("content-type") ?? "application/json",
    "cache-control": "no-store",
  };

  if (setCookieHeader) {
    headers["set-cookie"] = setCookieHeader;
  }

  return new Response(body || "{}", {
    status: response.status,
    headers,
  });
}

function jsonError(error: string, status: number, setCookieHeader?: string): Response {
  const headers: Record<string, string> = {
    "content-type": "application/json",
    "cache-control": "no-store",
  };
  if (setCookieHeader) {
    headers["set-cookie"] = setCookieHeader;
  }

  return new Response(JSON.stringify({ error }), {
    status,
    headers,
  });
}
