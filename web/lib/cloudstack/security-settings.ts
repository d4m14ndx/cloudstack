import type { CurrentUser } from "../auth/types.ts";

export type CloudStackUser = {
  id?: string;
  usersource?: string;
  state?: string;
  apikeyaccess?: boolean | string;
  is2faenabled?: boolean | string;
  is2famandated?: boolean | string;
  passwordchangerequired?: boolean | string;
};

export type ListUsersResponse = {
  listusersresponse?: {
    count?: number | string;
    user?: CloudStackUser[];
  };
};

export type CurrentUserSecuritySettings = {
  source: string;
  state: string;
  apiKeyAccess: "enabled" | "disabled" | "unknown";
  twoFactorEnabled: boolean;
  twoFactorMandated: boolean;
  passwordChangeRequired: boolean;
};

type FetchOptions = {
  fetchImpl?: typeof fetch;
  requestHeaders?: Pick<Headers, "get">;
};

export async function getCurrentUserSecuritySettingsFromBff(
  user: CurrentUser,
  { fetchImpl = fetch, requestHeaders }: FetchOptions = {},
): Promise<CurrentUserSecuritySettings> {
  if (process.env.NEXT_PUBLIC_APP_ENV === "mock" || !process.env.CS_URL) {
    return fallbackSecuritySettings();
  }

  try {
    const response = await fetchImpl(buildListUsersUrl(user.id, requestHeaders), {
      method: "GET",
      cache: "no-store",
      headers: buildForwardedHeaders(requestHeaders),
    });

    if (!response.ok) {
      return fallbackSecuritySettings();
    }

    const payload = (await response.json()) as ListUsersResponse;
    const cloudStackUser = payload.listusersresponse?.user?.[0];
    return cloudStackUser ? mapCloudStackUserToSecuritySettings(cloudStackUser) : fallbackSecuritySettings();
  } catch {
    return fallbackSecuritySettings();
  }
}

export function mapCloudStackUserToSecuritySettings(user: CloudStackUser): CurrentUserSecuritySettings {
  return {
    source: normalizeText(user.usersource, "unknown"),
    state: normalizeText(user.state, "unknown"),
    apiKeyAccess: mapAccess(user.apikeyaccess),
    twoFactorEnabled: readBoolean(user.is2faenabled),
    twoFactorMandated: readBoolean(user.is2famandated),
    passwordChangeRequired: readBoolean(user.passwordchangerequired),
  };
}

export function buildListUsersUrl(userId: string, requestHeaders?: Pick<Headers, "get">): string {
  const params = new URLSearchParams({
    id: userId,
    listall: "true",
  });
  const origin = getRequestOrigin(requestHeaders);
  return `${origin}/api/cs/listUsers?${params.toString()}`;
}

function fallbackSecuritySettings(): CurrentUserSecuritySettings {
  return {
    source: "session",
    state: "active",
    apiKeyAccess: "unknown",
    twoFactorEnabled: false,
    twoFactorMandated: false,
    passwordChangeRequired: false,
  };
}

function mapAccess(value: CloudStackUser["apikeyaccess"]): CurrentUserSecuritySettings["apiKeyAccess"] {
  const normalized = String(value).toLowerCase();
  if (value === true || normalized === "enabled" || normalized === "true") {
    return "enabled";
  }

  if (value === false || normalized === "disabled" || normalized === "false") {
    return "disabled";
  }

  return "unknown";
}

function readBoolean(value: boolean | string | undefined): boolean {
  return value === true || String(value).toLowerCase() === "true";
}

function normalizeText(value: string | undefined, fallback: string): string {
  const normalized = value?.trim().toLowerCase();
  return normalized && normalized.length > 0 ? normalized : fallback;
}

function getRequestOrigin(requestHeaders?: Pick<Headers, "get">): string {
  if (process.env.NEXTAUTH_URL) {
    return process.env.NEXTAUTH_URL.replace(/\/$/, "");
  }

  const host = requestHeaders?.get("x-forwarded-host") ?? requestHeaders?.get("host") ?? "localhost:3000";
  const protocol = requestHeaders?.get("x-forwarded-proto") ?? (host.startsWith("localhost") ? "http" : "https");
  return `${protocol}://${host}`;
}

function buildForwardedHeaders(requestHeaders?: Pick<Headers, "get">): HeadersInit | undefined {
  const cookie = requestHeaders?.get("cookie");
  return cookie ? { cookie } : undefined;
}
