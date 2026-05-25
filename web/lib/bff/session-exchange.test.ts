import assert from "node:assert/strict";
import test from "node:test";

import type { CurrentUser } from "../auth/types.ts";
import type { MintCloudStackSessionInput, MintCloudStackSessionResult } from "../cloudstack/client.ts";
import type { BffSession } from "./session.ts";
import {
  bffSessionBelongsToUser,
  bffSessionCookieHeader,
  createBffSessionFromUser,
  expiredBffSessionCookieHeader,
  getOrCreateBffSessionForUser,
  type CloudStackSessionIssuer,
} from "./session-exchange.ts";
import type { BffSessionStore } from "./session-store.ts";

const now = Date.UTC(2026, 4, 21, 11, 0, 0);

const user: CurrentUser = {
  id: "idp-user-1",
  username: "alice",
  email: "alice@example.com",
  name: "Alice",
  role: "ADMIN",
  domain: "Engineering",
  domainId: "domain-uuid-1",
};

const minted: MintCloudStackSessionResult = {
  sessionkey: "cloudstack-secret-session",
  userid: "cs-user-1",
  username: "alice",
  domainid: "domain-uuid-1",
  domain: "Engineering",
  role: "USER",
  expiresAt: now + 1_800_000,
  timeout: 1_800,
};

test("createBffSessionFromUser keeps CloudStack sessionkey server-side only", () => {
  const session = createBffSessionFromUser(user, minted, now);

  assert.equal(session.userId, "cs-user-1");
  assert.equal(session.idpSub, "idp-user-1");
  assert.equal(session.cloudstackSessionkey, "cloudstack-secret-session");
  assert.equal(session.cloudstackExpiresAt, minted.expiresAt);
  assert.equal(session.role, "ADMIN");
});

test("bffSessionBelongsToUser binds stored BFF sessions to the Auth.js identity", () => {
  const session = createBffSessionFromUser(user, minted, now);

  assert.equal(bffSessionBelongsToUser(session, user), true);
  assert.equal(
    bffSessionBelongsToUser(session, {
      ...user,
      id: "different-idp-user",
    }),
    false,
  );
});

test("cookie helpers emit opaque HttpOnly API-scope cookies", () => {
  const setCookie = bffSessionCookieHeader("opaque-session-id", 28800, true);
  const expiredCookie = expiredBffSessionCookieHeader(false);

  assert.match(setCookie, /^cloudstack\.session=opaque-session-id;/);
  assert.match(setCookie, /Path=\/api\/cs/);
  assert.match(setCookie, /HttpOnly/);
  assert.match(setCookie, /SameSite=Lax/);
  assert.match(setCookie, /Secure/);
  assert.equal(setCookie.includes("cloudstack-secret-session"), false);
  assert.match(expiredCookie, /Max-Age=0/);
  assert.equal(expiredCookie.includes("Secure"), false);
});

test("getOrCreateBffSessionForUser reuses matching stored sessions", async () => {
  const store = new MemoryStore();
  const existing = createBffSessionFromUser(user, minted, now);
  await store.set("existing-session", existing, 28800);
  const client = new FakeIssuer();

  const result = await getOrCreateBffSessionForUser({
    store,
    client,
    user,
    existingSessionId: "existing-session",
    ttlSeconds: 28800,
    secureCookie: false,
    now,
  });

  assert.equal(result.sessionId, "existing-session");
  assert.equal(result.session, existing);
  assert.equal(result.setCookieHeader, undefined);
  assert.equal(client.requests.length, 0);
});

test("getOrCreateBffSessionForUser deletes mismatched sessions and mints a new one", async () => {
  const store = new MemoryStore();
  const mismatched = createBffSessionFromUser(
    {
      ...user,
      id: "other-idp-user",
    },
    minted,
    now,
  );
  await store.set("stale-session", mismatched, 28800);
  const client = new FakeIssuer();

  const result = await getOrCreateBffSessionForUser({
    store,
    client,
    user,
    existingSessionId: "stale-session",
    ttlSeconds: 28800,
    secureCookie: false,
    now,
  });

  assert.notEqual(result.sessionId, "stale-session");
  assert.equal(await store.get("stale-session"), null);
  assert.equal((await store.get(result.sessionId))?.cloudstackSessionkey, minted.sessionkey);
  assert.deepEqual(client.requests, [
    {
      username: "alice",
      domain: "Engineering",
      domainId: "domain-uuid-1",
    },
  ]);
  assert.match(result.setCookieHeader ?? "", /HttpOnly/);
  assert.equal((result.setCookieHeader ?? "").includes(minted.sessionkey), false);
});

class FakeIssuer implements CloudStackSessionIssuer {
  public readonly requests: MintCloudStackSessionInput[] = [];

  public async mintUserSessionToken(input: MintCloudStackSessionInput): Promise<MintCloudStackSessionResult> {
    this.requests.push(input);
    return minted;
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
