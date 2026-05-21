import { NextResponse, type NextRequest } from "next/server";

import { getAuthenticatedUser } from "@/lib/auth/server";
import { getBffConfig } from "@/lib/bff/config";
import { extractSessionIdFromCookieHeader, shouldRefreshCloudStackSession, type BffSession } from "@/lib/bff/session";
import {
  expiredBffSessionCookieHeader,
  getOrCreateBffSessionForUser,
  type BffSessionResolution,
} from "@/lib/bff/session-exchange";
import { getBffSessionStore, getOrCreateDevSession, type BffSessionStore } from "@/lib/bff/session-store";
import { CloudStackClient } from "@/lib/cloudstack/client";
import { assertValidCloudStackCommand, readClientBodyParams } from "@/lib/cloudstack/request";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

type RouteContext = {
  params: {
    command: string;
  };
};

export async function GET(request: NextRequest, context: RouteContext): Promise<Response> {
  return proxyCloudStackCommand(request, context, "GET");
}

export async function POST(request: NextRequest, context: RouteContext): Promise<Response> {
  return proxyCloudStackCommand(request, context, "POST");
}

async function proxyCloudStackCommand(
  request: NextRequest,
  context: RouteContext,
  method: "GET" | "POST",
): Promise<Response> {
  let command: string;
  try {
    command = decodeURIComponent(context.params.command);
    assertValidCloudStackCommand(command);
  } catch {
    return jsonError("Invalid CloudStack command", 400);
  }

  const config = getBffConfig();
  const store = getBffSessionStore();
  const authenticatedUser = await getAuthenticatedUser();
  const sessionId = extractSessionIdFromCookieHeader(request.headers.get("cookie"));
  const secureCookie = isSecureRequest(request);

  if (!authenticatedUser && !config.allowDevSession) {
    return jsonError("Unauthenticated", 401, sessionId ? expiredBffSessionCookieHeader(secureCookie) : undefined);
  }

  if (!config.cloudstackUrl) {
    return jsonError("CloudStack API is not configured", 503);
  }

  const client = new CloudStackClient({
    baseUrl: config.cloudstackUrl,
    serviceApiKey: config.serviceApiKey,
    serviceSecretKey: config.serviceSecretKey,
  });
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
      });
    } catch {
      return jsonError("CloudStack session exchange failed", 503, sessionId ? expiredBffSessionCookieHeader(secureCookie) : undefined);
    }
  } else if (config.allowDevSession) {
    const devSessionId = "__dev_cloudstack_session";
    sessionResolution = {
      sessionId: devSessionId,
      session: await getOrCreateDevSession(store, devSessionId, config.sessionTtlSeconds),
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
    refreshedSession = await refreshIfNeeded(client, store, bffSessionId, session, config.refreshMarginSeconds, config.sessionTtlSeconds);
  } catch {
    await store.delete(bffSessionId);
    return jsonError("CloudStack session refresh failed", 401, expiredBffSessionCookieHeader(secureCookie));
  }

  let clientParams: URLSearchParams;
  try {
    clientParams = method === "GET" ? request.nextUrl.searchParams : await combinedPostParams(request);
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
      reminted = await remintCloudStackSession(client, store, bffSessionId, refreshedSession, config.sessionTtlSeconds);
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
  client: CloudStackClient,
  store: BffSessionStore,
  sessionId: string,
  session: BffSession,
  refreshMarginSeconds: number,
  ttlSeconds: number,
): Promise<BffSession> {
  if (!shouldRefreshCloudStackSession(session.cloudstackExpiresAt, Date.now(), refreshMarginSeconds)) {
    return session;
  }

  return remintCloudStackSession(client, store, sessionId, session, ttlSeconds);
}

async function remintCloudStackSession(
  client: CloudStackClient,
  store: BffSessionStore,
  sessionId: string,
  session: BffSession,
  ttlSeconds: number,
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
    lastSeenAt: Date.now(),
  };
  await store.set(sessionId, updated, ttlSeconds);
  return updated;
}

async function combinedPostParams(request: NextRequest): Promise<URLSearchParams> {
  const params = new URLSearchParams(request.nextUrl.searchParams);
  const bodyParams = await readClientBodyParams(request);
  for (const [key, value] of bodyParams.entries()) {
    params.append(key, value);
  }
  return params;
}

function isSecureRequest(request: NextRequest): boolean {
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

function jsonError(error: string, status: number, setCookieHeader?: string): NextResponse<{ error: string }> {
  const response = NextResponse.json({ error }, { status, headers: { "cache-control": "no-store" } });
  if (setCookieHeader) {
    response.headers.set("set-cookie", setCookieHeader);
  }
  return response;
}
