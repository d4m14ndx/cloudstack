export const BFF_SESSION_COOKIE = "cloudstack.session";

export type BffRole = "USER" | "DOMAIN_ADMIN" | "ADMIN" | "ROOT";

export type BffSession = {
  userId: string;
  username: string;
  domain: string;
  domainId: string;
  role: BffRole;
  cloudstackSessionkey: string;
  cloudstackExpiresAt: number;
  idpSub: string;
  idpEmail: string;
  createdAt: number;
  lastSeenAt: number;
};

export type RedactedBffSession = Omit<BffSession, "cloudstackSessionkey"> & {
  cloudstackSessionkey: "[redacted]";
};

const SESSION_ID_PATTERN = /^[A-Za-z0-9_-]{32,256}$/;

export function extractSessionIdFromCookieHeader(cookieHeader: string | null): string | null {
  if (!cookieHeader) {
    return null;
  }

  const cookies = cookieHeader.split(";");
  for (const cookie of cookies) {
    const [rawName, ...rawValue] = cookie.trim().split("=");
    if (rawName !== BFF_SESSION_COOKIE) {
      continue;
    }

    const value = rawValue.join("=");
    if (!SESSION_ID_PATTERN.test(value)) {
      return null;
    }

    return value;
  }

  return null;
}

export function shouldRefreshCloudStackSession(
  cloudstackExpiresAt: number,
  now = Date.now(),
  refreshMarginSeconds = 120,
): boolean {
  return now >= cloudstackExpiresAt - refreshMarginSeconds * 1_000;
}

export function redactedBffSession(session: BffSession): RedactedBffSession {
  return {
    ...session,
    cloudstackSessionkey: "[redacted]",
  };
}

export function createDevBffSession(now = Date.now()): BffSession {
  return {
    userId: "dev-user",
    username: process.env.BFF_DEV_USERNAME ?? "dev",
    domain: process.env.BFF_DEV_DOMAIN ?? "ROOT",
    domainId: process.env.BFF_DEV_DOMAIN_ID ?? "dev-domain-root",
    role: "ROOT",
    cloudstackSessionkey: process.env.BFF_DEV_SESSIONKEY ?? "dev-sessionkey",
    cloudstackExpiresAt: now + 8 * 60 * 60 * 1_000,
    idpSub: "dev-idp-sub",
    idpEmail: process.env.BFF_DEV_EMAIL ?? "dev@cloudstack.local",
    createdAt: now,
    lastSeenAt: now,
  };
}
