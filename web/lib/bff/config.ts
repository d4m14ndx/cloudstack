export type BffConfig = {
  cloudstackUrl: string | null;
  serviceApiKey: string | null;
  serviceSecretKey: string | null;
  sessionTtlSeconds: number;
  refreshMarginSeconds: number;
  allowDevSession: boolean;
};

function readPositiveInteger(name: string, fallback: number): number {
  const raw = process.env[name];
  if (!raw) {
    return fallback;
  }

  const parsed = Number.parseInt(raw, 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

export function getBffConfig(): BffConfig {
  const appEnv = process.env.NEXT_PUBLIC_APP_ENV;
  const explicitDevSession = process.env.BFF_DEV_SESSION;

  return {
    cloudstackUrl: process.env.CS_URL ?? null,
    serviceApiKey: process.env.CS_SERVICE_APIKEY ?? null,
    serviceSecretKey: process.env.CS_SERVICE_SECRETKEY ?? null,
    sessionTtlSeconds: readPositiveInteger("BFF_SESSION_TTL_SECONDS", 28_800),
    refreshMarginSeconds: readPositiveInteger("CS_SESSION_REFRESH_MARGIN_SECONDS", 120),
    allowDevSession:
      explicitDevSession === "1" ||
      explicitDevSession === "true" ||
      appEnv === "dev" ||
      appEnv === "development",
  };
}
