import { NextResponse, type NextRequest } from "next/server";

import { getBffConfig } from "@/lib/bff/config";
import { extractSessionIdFromCookieHeader, shouldRefreshCloudStackSession, type BffSession } from "@/lib/bff/session";
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
  const sessionId = extractSessionIdFromCookieHeader(request.headers.get("cookie"));
  const effectiveSessionId = sessionId ?? (config.allowDevSession ? "__dev_cloudstack_session" : null);
  const session = effectiveSessionId
    ? await getSessionForProxy(store, effectiveSessionId, config.sessionTtlSeconds, config.allowDevSession)
    : null;

  if (!effectiveSessionId || !session) {
    return jsonError("Unauthenticated", 401);
  }

  if (!config.cloudstackUrl) {
    return jsonError("CloudStack API is not configured", 503);
  }

  const client = new CloudStackClient({
    baseUrl: config.cloudstackUrl,
    serviceApiKey: config.serviceApiKey,
    serviceSecretKey: config.serviceSecretKey,
  });

  const bffSessionId = effectiveSessionId;
  let refreshedSession: BffSession;
  try {
    refreshedSession = await refreshIfNeeded(client, store, bffSessionId, session, config.refreshMarginSeconds, config.sessionTtlSeconds);
  } catch {
    return jsonError("CloudStack session refresh failed", 401);
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
      return cloudStackResponse(firstResponse);
    }

    let reminted: BffSession;
    try {
      reminted = await remintCloudStackSession(client, store, bffSessionId, refreshedSession, config.sessionTtlSeconds);
    } catch {
      await store.delete(bffSessionId);
      return jsonError("Unauthenticated", 401);
    }

    const retryResponse = await client.proxyCommand(command, clientParams, reminted.cloudstackSessionkey, method);
    if (retryResponse.status === 401) {
      await store.delete(bffSessionId);
    }
    return cloudStackResponse(retryResponse);
  } catch {
    return jsonError("CloudStack API request failed", 503);
  }
}

async function getSessionForProxy(
  store: BffSessionStore,
  sessionId: string,
  ttlSeconds: number,
  allowDevSession: boolean,
): Promise<BffSession | null> {
  const session = await store.get(sessionId);
  if (session) {
    return session;
  }

  if (!allowDevSession) {
    return null;
  }

  return getOrCreateDevSession(store, sessionId, ttlSeconds);
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

async function cloudStackResponse(response: Response): Promise<Response> {
  const body = await response.text();
  return new Response(body || "{}", {
    status: response.status,
    headers: {
      "content-type": response.headers.get("content-type") ?? "application/json",
      "cache-control": "no-store",
    },
  });
}

function jsonError(error: string, status: number): NextResponse<{ error: string }> {
  return NextResponse.json({ error }, { status, headers: { "cache-control": "no-store" } });
}
