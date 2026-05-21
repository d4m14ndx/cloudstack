import crypto from "node:crypto";

import type { CurrentUser } from "../auth/types.ts";
import type { MintCloudStackSessionInput, MintCloudStackSessionResult } from "../cloudstack/client.ts";
import { BFF_SESSION_COOKIE, type BffSession } from "./session.ts";
import type { BffSessionStore } from "./session-store.ts";

export type CloudStackSessionIssuer = {
  mintUserSessionToken(input: MintCloudStackSessionInput): Promise<MintCloudStackSessionResult>;
};

export type BffSessionResolution = {
  sessionId: string;
  session: BffSession;
  setCookieHeader?: string;
};

export function createBffSessionId(): string {
  return crypto.randomBytes(32).toString("base64url");
}

export function createBffSessionFromUser(
  user: CurrentUser,
  cloudstackSession: MintCloudStackSessionResult,
  now = Date.now(),
): BffSession {
  return {
    userId: cloudstackSession.userid || user.id,
    username: cloudstackSession.username || user.username,
    domain: cloudstackSession.domain || user.domain,
    domainId: cloudstackSession.domainid || user.domainId,
    role: user.role,
    cloudstackSessionkey: cloudstackSession.sessionkey,
    cloudstackExpiresAt: cloudstackSession.expiresAt,
    idpSub: user.id,
    idpEmail: user.email,
    createdAt: now,
    lastSeenAt: now,
  };
}

export function bffSessionBelongsToUser(session: BffSession, user: CurrentUser): boolean {
  return session.idpSub === user.id && session.username === user.username && session.domain === user.domain;
}

export async function getOrCreateBffSessionForUser({
  store,
  client,
  user,
  existingSessionId,
  ttlSeconds,
  secureCookie,
  now = Date.now(),
}: {
  store: BffSessionStore;
  client: CloudStackSessionIssuer;
  user: CurrentUser;
  existingSessionId: string | null;
  ttlSeconds: number;
  secureCookie: boolean;
  now?: number;
}): Promise<BffSessionResolution> {
  if (existingSessionId) {
    const existing = await store.get(existingSessionId);
    if (existing && bffSessionBelongsToUser(existing, user)) {
      return { sessionId: existingSessionId, session: existing };
    }

    if (existing) {
      await store.delete(existingSessionId);
    }
  }

  const sessionId = createBffSessionId();
  const cloudstackSession = await client.mintUserSessionToken({
    username: user.username,
    domain: user.domain,
    domainId: user.domainId,
  });
  const session = createBffSessionFromUser(user, cloudstackSession, now);
  await store.set(sessionId, session, ttlSeconds);

  return {
    sessionId,
    session,
    setCookieHeader: bffSessionCookieHeader(sessionId, ttlSeconds, secureCookie),
  };
}

export function bffSessionCookieHeader(
  sessionId: string,
  ttlSeconds: number,
  secure: boolean,
): string {
  const attributes = [
    `${BFF_SESSION_COOKIE}=${sessionId}`,
    "Path=/api/cs",
    "HttpOnly",
    "SameSite=Lax",
    `Max-Age=${ttlSeconds}`,
  ];

  if (secure) {
    attributes.push("Secure");
  }

  return attributes.join("; ");
}

export function expiredBffSessionCookieHeader(secure: boolean): string {
  const attributes = [
    `${BFF_SESSION_COOKIE}=`,
    "Path=/api/cs",
    "HttpOnly",
    "SameSite=Lax",
    "Max-Age=0",
  ];

  if (secure) {
    attributes.push("Secure");
  }

  return attributes.join("; ");
}
