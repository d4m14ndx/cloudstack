import { z } from "zod";

const optionalUrl = z.string().url().optional();
const optionalNonEmpty = z.string().min(1).optional();

const envSchema = z.object({
  NODE_ENV: z.enum(["development", "test", "production"]).default("development"),
  NEXT_PHASE: z.string().optional(),
  NEXTAUTH_URL: optionalUrl,
  NEXTAUTH_SECRET: optionalNonEmpty,
  AUTHENTIK_ISSUER: optionalUrl,
  AUTHENTIK_CLIENT_ID: optionalNonEmpty,
  AUTHENTIK_CLIENT_SECRET: optionalNonEmpty,
  AUTH_MICROSOFT_ENTRA_ID_ID: optionalNonEmpty,
  AUTH_MICROSOFT_ENTRA_ID_SECRET: optionalNonEmpty,
  AUTH_MICROSOFT_ENTRA_ID_ISSUER: optionalUrl,
  REDIS_URL: optionalUrl,
  BFF_SESSION_TTL_SECONDS: z.coerce.number().int().positive().default(28_800),
});

export type WebEnv = z.infer<typeof envSchema>;

function parseEnv(): WebEnv {
  const parsed = envSchema.safeParse(process.env);

  if (!parsed.success) {
    const message = parsed.error.issues
      .map((issue) => `${issue.path.join(".")}: ${issue.message}`)
      .join("; ");
    throw new Error(`Invalid CloudStack web environment: ${message}`);
  }

  const env = parsed.data;
  const isProductionRuntime =
    env.NODE_ENV === "production" && env.NEXT_PHASE !== "phase-production-build";

  if (isProductionRuntime) {
    const missing = [
      "NEXTAUTH_URL",
      "NEXTAUTH_SECRET",
      "AUTHENTIK_ISSUER",
      "AUTHENTIK_CLIENT_ID",
      "AUTHENTIK_CLIENT_SECRET",
      "REDIS_URL",
    ].filter((key) => !env[key as keyof WebEnv]);

    if (missing.length > 0) {
      throw new Error(
        `CloudStack web auth is missing required production environment variables: ${missing.join(", ")}`
      );
    }
  }

  return env;
}

export const webEnv = parseEnv();
export const redisUrl = webEnv.REDIS_URL ?? "redis://localhost:6379";

export const authProvidersConfigured = {
  authentik: Boolean(
    webEnv.AUTHENTIK_ISSUER && webEnv.AUTHENTIK_CLIENT_ID && webEnv.AUTHENTIK_CLIENT_SECRET
  ),
  entraId: Boolean(
    webEnv.AUTH_MICROSOFT_ENTRA_ID_ID && webEnv.AUTH_MICROSOFT_ENTRA_ID_SECRET
  ),
};

export const isAuthConfigured = authProvidersConfigured.authentik || authProvidersConfigured.entraId;
