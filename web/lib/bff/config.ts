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
  // The dev-session escape hatch grants anonymous requests a hardcoded ROOT
  // CloudStack session. It MUST never engage in a production runtime, even
  // if someone forgets to override NEXT_PUBLIC_APP_ENV or sets BFF_DEV_SESSION
  // in a deploy template. The build phase is treated as non-production so
  // `next build` against a containerised setup still tree-shakes correctly.
  const isProductionRuntime =
    process.env.NODE_ENV === "production" && process.env.NEXT_PHASE !== "phase-production-build";

  return {
    cloudstackUrl: process.env.CS_URL ?? null,
    serviceApiKey: process.env.CS_SERVICE_APIKEY ?? null,
    serviceSecretKey: process.env.CS_SERVICE_SECRETKEY ?? null,
    sessionTtlSeconds: readPositiveInteger("BFF_SESSION_TTL_SECONDS", 28_800),
    refreshMarginSeconds: readPositiveInteger("CS_SESSION_REFRESH_MARGIN_SECONDS", 120),
    allowDevSession:
      !isProductionRuntime &&
      (explicitDevSession === "1" ||
        explicitDevSession === "true" ||
        appEnv === "dev" ||
        appEnv === "development"),
  };
}
