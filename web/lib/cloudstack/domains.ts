import { mockDomains, type TenantDomain } from "../mock-data.ts";

export type CloudStackDomain = {
  id?: string;
  name?: string;
  level?: number | string;
  parentdomainid?: string;
  parentdomainname?: string;
  haschild?: boolean | string;
  networkdomain?: string;
  path?: string;
  state?: string;
  created?: string;
  vmtotal?: number | string;
  iptotal?: number | string;
  volumetotal?: number | string;
  projecttotal?: number | string;
  networktotal?: number | string;
  vpctotal?: number | string;
};

export type ListDomainsResponse = {
  listdomainsresponse?: {
    count?: number | string;
    domain?: CloudStackDomain[];
  };
};

type FetchOptions = {
  fetchImpl?: typeof fetch;
  requestHeaders?: Pick<Headers, "get">;
};

export async function getDomainsFromBff({
  fetchImpl = fetch,
  requestHeaders,
}: FetchOptions = {}): Promise<TenantDomain[]> {
  if (process.env.NEXT_PUBLIC_APP_ENV === "mock" || !process.env.CS_URL) {
    return mockDomains;
  }

  try {
    const response = await fetchImpl(buildListDomainsUrl(requestHeaders), {
      method: "GET",
      cache: "no-store",
      headers: buildForwardedHeaders(requestHeaders),
    });

    if (!response.ok) {
      return mockDomains;
    }

    const payload = (await response.json()) as ListDomainsResponse;
    if (!payload.listdomainsresponse) {
      return mockDomains;
    }

    return domainsFromListDomainsResponse(payload);
  } catch {
    return mockDomains;
  }
}

export function domainsFromListDomainsResponse(response: ListDomainsResponse): TenantDomain[] {
  return (response.listdomainsresponse?.domain ?? []).map(mapCloudStackDomainToTenantDomain);
}

export function mapCloudStackDomainToTenantDomain(domain: CloudStackDomain): TenantDomain {
  const path = domain.path ?? domain.name ?? "unknown";

  return {
    id: domain.id ?? domain.path ?? domain.name ?? "unknown",
    name: domain.name ?? domain.path ?? domain.id ?? "unnamed-domain",
    path,
    parent: domain.parentdomainname ?? null,
    level: readNonNegativeInteger(domain.level) ?? 0,
    state: mapCloudStackDomainState(domain.state),
    hasChildren: readBoolean(domain.haschild),
    instances: readNonNegativeInteger(domain.vmtotal) ?? 0,
    projects: readNonNegativeInteger(domain.projecttotal) ?? 0,
    networks: (readNonNegativeInteger(domain.networktotal) ?? 0) + (readNonNegativeInteger(domain.vpctotal) ?? 0),
  };
}

function mapCloudStackDomainState(state: string | undefined): TenantDomain["state"] {
  const normalized = state?.toLowerCase() ?? "";
  if (
    normalized.includes("inactive") ||
    normalized.includes("disabled") ||
    normalized.includes("removed") ||
    normalized.includes("error")
  ) {
    return "inactive";
  }

  return "active";
}

function readBoolean(value: boolean | string | undefined): boolean {
  if (typeof value === "boolean") {
    return value;
  }

  return value?.toLowerCase() === "true";
}

function readNonNegativeInteger(value: number | string | undefined): number | null {
  if (typeof value === "number" && Number.isFinite(value) && value >= 0) {
    return Math.floor(value);
  }

  if (typeof value === "string") {
    const parsed = Number.parseInt(value, 10);
    return Number.isFinite(parsed) && parsed >= 0 ? parsed : null;
  }

  return null;
}

function buildListDomainsUrl(requestHeaders?: Pick<Headers, "get">): string {
  const params = new URLSearchParams({
    listall: "true",
    details: "min",
  });
  const origin = getRequestOrigin(requestHeaders);
  return `${origin}/api/cs/listDomains?${params.toString()}`;
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
