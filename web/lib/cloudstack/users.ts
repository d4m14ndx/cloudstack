import type { CurrentUser, Role } from "../auth/types.ts";

export type CloudStackUser = {
  id?: string;
  username?: string;
  firstname?: string;
  lastname?: string;
  email?: string;
  account?: string;
  accounttype?: number | string;
  roletype?: string;
  rolename?: string;
  domain?: string;
  domainid?: string;
  timezone?: string;
  usersource?: string;
  state?: string;
  apikeyaccess?: boolean | string;
  is2faenabled?: boolean | string;
  is2famandated?: boolean | string;
  isdefault?: boolean | string;
  passwordchangerequired?: boolean | string;
};

export type ListUsersResponse = {
  listusersresponse?: {
    count?: number | string;
    user?: CloudStackUser[];
  };
};

export type CloudStackUserProfile = {
  id: string;
  username: string;
  displayName: string;
  email: string;
  account: string;
  role: Role;
  domain: string;
  domainId: string;
  timezone: string;
  source: string;
  state: string;
  apiKeyAccess: "enabled" | "disabled" | "unknown";
  twoFactorEnabled: boolean;
  twoFactorMandated: boolean;
};

type FetchOptions = {
  fetchImpl?: typeof fetch;
  requestHeaders?: Pick<Headers, "get">;
};

const fallbackUser: CurrentUser = {
  id: "mock-uuid-alex",
  username: "alex",
  email: "alex@cloudstack.local",
  name: "Alex Kim",
  role: "ROOT",
  domain: "ROOT",
  domainId: "mock-uuid-domain-root",
};

export async function getCurrentUserProfileFromBff(
  currentUser: CurrentUser | null = fallbackUser,
  { fetchImpl = fetch, requestHeaders }: FetchOptions = {},
): Promise<CloudStackUserProfile> {
  const user = currentUser ?? fallbackUser;

  if (process.env.NEXT_PUBLIC_APP_ENV === "mock" || !process.env.CS_URL) {
    return profileFromCurrentUser(user);
  }

  try {
    const response = await fetchImpl(buildListUsersUrl(user.id, requestHeaders), {
      method: "GET",
      cache: "no-store",
      headers: buildForwardedHeaders(requestHeaders),
    });

    if (!response.ok) {
      return profileFromCurrentUser(user);
    }

    const payload = (await response.json()) as ListUsersResponse;
    const profile = usersFromListUsersResponse(payload, user)[0];
    return profile ?? profileFromCurrentUser(user);
  } catch {
    return profileFromCurrentUser(user);
  }
}

export function usersFromListUsersResponse(
  response: ListUsersResponse,
  currentUser: CurrentUser | null = fallbackUser,
): CloudStackUserProfile[] {
  const user = currentUser ?? fallbackUser;
  return (response.listusersresponse?.user ?? []).map((cloudStackUser) => mapCloudStackUserToProfile(cloudStackUser, user));
}

export function mapCloudStackUserToProfile(
  user: CloudStackUser,
  currentUser: CurrentUser | null = fallbackUser,
): CloudStackUserProfile {
  const fallback = currentUser ?? fallbackUser;

  return {
    id: user.id ?? fallback.id,
    username: user.username ?? fallback.username,
    displayName: formatDisplayName(user, fallback),
    email: user.email ?? fallback.email,
    account: user.account ?? fallback.username,
    role: mapRole(user, fallback.role),
    domain: user.domain ?? fallback.domain,
    domainId: user.domainid ?? fallback.domainId,
    timezone: user.timezone ?? "Browser default",
    source: user.usersource ?? "session",
    state: user.state ?? "active",
    apiKeyAccess: mapApiKeyAccess(user.apikeyaccess),
    twoFactorEnabled: readBoolean(user.is2faenabled),
    twoFactorMandated: readBoolean(user.is2famandated),
  };
}

export function buildListUsersUrl(userId: string, requestHeaders?: Pick<Headers, "get">): string {
  const params = new URLSearchParams({
    id: userId,
    listall: "true",
    showicon: "true",
  });
  return `${getRequestOrigin(requestHeaders)}/api/cs/listUsers?${params.toString()}`;
}

function profileFromCurrentUser(user: CurrentUser): CloudStackUserProfile {
  return {
    id: user.id,
    username: user.username,
    displayName: user.name,
    email: user.email,
    account: user.username,
    role: user.role,
    domain: user.domain,
    domainId: user.domainId,
    timezone: "Browser default",
    source: "session",
    state: "active",
    apiKeyAccess: "unknown",
    twoFactorEnabled: false,
    twoFactorMandated: false,
  };
}

function formatDisplayName(user: CloudStackUser, currentUser: CurrentUser): string {
  const name = [user.firstname, user.lastname].filter(Boolean).join(" ").trim();
  return name || currentUser.name || user.username || "Unknown user";
}

function mapRole(user: CloudStackUser, fallback: Role): Role {
  const roleText = `${user.roletype ?? ""} ${user.rolename ?? ""}`.toLowerCase();
  if (roleText.includes("root")) {
    return "ROOT";
  }

  if (roleText.includes("resource") || roleText.includes("admin")) {
    return "ADMIN";
  }

  if (roleText.includes("domain")) {
    return "DOMAIN_ADMIN";
  }

  const accountType = readPositiveInteger(user.accounttype);
  if (accountType === 1) {
    return "ROOT";
  }

  if (accountType === 2) {
    return "DOMAIN_ADMIN";
  }

  return fallback;
}

function mapApiKeyAccess(value: CloudStackUser["apikeyaccess"]): CloudStackUserProfile["apiKeyAccess"] {
  const normalized = String(value ?? "").toLowerCase();
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

function readPositiveInteger(value: number | string | undefined): number | null {
  if (typeof value === "number" && Number.isFinite(value) && value >= 0) {
    return Math.floor(value);
  }

  if (typeof value === "string") {
    const parsed = Number.parseInt(value, 10);
    return Number.isFinite(parsed) && parsed >= 0 ? parsed : null;
  }

  return null;
}

function getRequestOrigin(requestHeaders?: Pick<Headers, "get">): string {
  if (process.env.NEXTAUTH_URL) {
    return process.env.NEXTAUTH_URL.replace(/\/$/, "");
  }

  const host = requestHeaders?.get("x-forwarded-host") ?? requestHeaders?.get("host") ?? "localhost:3000";
  const protocol = requestHeaders?.get("x-forwarded-proto") ?? (host.startsWith("localhost") ? "http" : "https");
  return `${protocol}://${host}`;
}

export function buildForwardedHeaders(requestHeaders?: Pick<Headers, "get">): HeadersInit | undefined {
  const cookie = requestHeaders?.get("cookie");
  return cookie ? { cookie } : undefined;
}
