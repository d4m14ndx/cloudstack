import assert from "node:assert/strict";
import test from "node:test";

import {
  BFF_SESSION_COOKIE,
  extractSessionIdFromCookieHeader,
  redactedBffSession,
  shouldRefreshCloudStackSession,
} from "./session.ts";

test("extractSessionIdFromCookieHeader returns an opaque session id", () => {
  const sessionId = "abcDEF0123_-".repeat(4);
  const cookie = `theme=dark; ${BFF_SESSION_COOKIE}=${sessionId}; other=value`;

  assert.equal(extractSessionIdFromCookieHeader(cookie), sessionId);
});

test("extractSessionIdFromCookieHeader rejects missing or unsafe ids", () => {
  assert.equal(extractSessionIdFromCookieHeader(null), null);
  assert.equal(extractSessionIdFromCookieHeader(`${BFF_SESSION_COOKIE}=../secret`), null);
  assert.equal(extractSessionIdFromCookieHeader(`${BFF_SESSION_COOKIE}=short`), null);
});

test("shouldRefreshCloudStackSession uses the configured refresh margin", () => {
  const now = Date.UTC(2026, 4, 21, 10, 0, 0);

  assert.equal(shouldRefreshCloudStackSession(now + 119_000, now, 120), true);
  assert.equal(shouldRefreshCloudStackSession(now + 121_000, now, 120), false);
});

test("redactedBffSession never exposes CloudStack secrets", () => {
  const redacted = redactedBffSession({
    userId: "user-1",
    username: "alice",
    domain: "ROOT",
    domainId: "domain-1",
    role: "USER",
    cloudstackSessionkey: "super-secret-session-key",
    cloudstackExpiresAt: Date.UTC(2026, 4, 21, 10, 30, 0),
    idpSub: "idp-1",
    idpEmail: "alice@example.com",
    createdAt: Date.UTC(2026, 4, 21, 9, 0, 0),
    lastSeenAt: Date.UTC(2026, 4, 21, 9, 30, 0),
  });

  assert.equal(redacted.cloudstackSessionkey, "[redacted]");
  assert.equal(JSON.stringify(redacted).includes("super-secret"), false);
});
