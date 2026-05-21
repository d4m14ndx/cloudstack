import { mockAccounts, type Account } from "../mock-data.ts";

export type CloudStackAccount = {
  id?: string;
  name?: string;
  account?: string;
  domain?: string;
  domainpath?: string;
  roletype?: string;
  rolename?: string;
  accounttype?: number | string;
  user?: unknown[];
  usercount?: number | string;
  vmtotal?: number | string;
  vmrunning?: number | string;
  vmstopped?: number | string;
  vmcount?: number | string;
  instances?: number | string;
  state?: string;
};

export type ListAccountsResponse = {
  listaccountsresponse?: {
    count?: number | string;
    account?: CloudStackAccount[];
  };
};

type FetchOptions = {
  fetchImpl?: typeof fetch;
  requestHeaders?: Pick<Headers, "get">;
};

export async function getAccountsFromBff({
  fetchImpl = fetch,
  requestHeaders,
}: FetchOptions = {}): Promise<Account[]> {
  if (process.env.NEXT_PUBLIC_APP_ENV === "mock" || !process.env.CS_URL) {
    return mockAccounts;
  }

  try {
    const response = await fetchImpl(buildListAccountsUrl(requestHeaders), {
      method: "GET",
      cache: "no-store",
      headers: buildForwardedHeaders(requestHeaders),
    });

    if (!response.ok) {
      return mockAccounts;
    }

    const payload = (await response.json()) as ListAccountsResponse;
    if (!payload.listaccountsresponse) {
      return mockAccounts;
    }

    return accountsFromListAccountsResponse(payload);
  } catch {
    return mockAccounts;
  }
}

export function accountsFromListAccountsResponse(response: ListAccountsResponse): Account[] {
  return (response.listaccountsresponse?.account ?? []).map(mapCloudStackAccountToAccount);
}

export function mapCloudStackAccountToAccount(account: CloudStackAccount): Account {
  return {
    name: account.name ?? account.account ?? account.id ?? "unknown",
    domain: account.domainpath ?? account.domain ?? "unknown",
    role: mapCloudStackAccountRole(account),
    users: readAccountUserCount(account),
    instances: readAccountInstanceCount(account),
    state: mapCloudStackAccountState(account.state),
  };
}

export function mapCloudStackAccountRole(account: CloudStackAccount): Account["role"] {
  const roleText = `${account.roletype ?? ""} ${account.rolename ?? ""}`.toLowerCase();
  if (roleText.includes("service")) {
    return "Service";
  }

  if (roleText.includes("domain")) {
    return "Domain admin";
  }

  if (roleText.includes("admin")) {
    return "Admin";
  }

  const accountType = readPositiveInteger(account.accounttype);
  if (accountType === 1) {
    return "Admin";
  }

  if (accountType === 2) {
    return "Domain admin";
  }

  return "User";
}

export function mapCloudStackAccountState(state: string | undefined): Account["state"] {
  const normalized = state?.toLowerCase() ?? "";
  if (normalized.includes("disabled") || normalized.includes("locked")) {
    return "disabled";
  }

  return "active";
}

function readAccountUserCount(account: CloudStackAccount): number {
  return readPositiveInteger(account.usercount) ?? account.user?.length ?? 0;
}

function readAccountInstanceCount(account: CloudStackAccount): number {
  const total = readPositiveInteger(account.vmtotal ?? account.vmcount ?? account.instances);
  if (total !== null) {
    return total;
  }

  return (readPositiveInteger(account.vmrunning) ?? 0) + (readPositiveInteger(account.vmstopped) ?? 0);
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

function buildListAccountsUrl(requestHeaders?: Pick<Headers, "get">): string {
  const params = new URLSearchParams({
    listall: "true",
    details: "min",
  });
  const origin = getRequestOrigin(requestHeaders);
  return `${origin}/api/cs/listAccounts?${params.toString()}`;
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
