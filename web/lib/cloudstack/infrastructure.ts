import { mockHosts, type Host } from "../mock-data.ts";

export type CloudStackHost = {
  id?: string;
  name?: string;
  zonename?: string;
  clustername?: string;
  state?: string;
  resourcestate?: string;
  cpuallocatedpercentage?: number | string;
  memoryallocatedpercentage?: number | string;
  virtualmachinecount?: number | string;
  vmcount?: number | string;
  instances?: number | string;
  hypervisor?: string;
};

export type ListHostsResponse = {
  listhostsresponse?: {
    count?: number | string;
    host?: CloudStackHost[];
  };
};

type FetchOptions = {
  fetchImpl?: typeof fetch;
  requestHeaders?: Pick<Headers, "get">;
};

export async function getInfrastructureFromBff({
  fetchImpl = fetch,
  requestHeaders,
}: FetchOptions = {}): Promise<Host[]> {
  if (process.env.NEXT_PUBLIC_APP_ENV === "mock" || !process.env.CS_URL) {
    return mockHosts;
  }

  try {
    const response = await fetchImpl(buildListHostsUrl(requestHeaders), {
      method: "GET",
      cache: "no-store",
      headers: buildForwardedHeaders(requestHeaders),
    });

    if (!response.ok) {
      return mockHosts;
    }

    const payload = (await response.json()) as ListHostsResponse;
    if (!payload.listhostsresponse) {
      return mockHosts;
    }

    return hostsFromListHostsResponse(payload);
  } catch {
    return mockHosts;
  }
}

export function hostsFromListHostsResponse(response: ListHostsResponse): Host[] {
  return (response.listhostsresponse?.host ?? []).map(mapCloudStackHostToHost);
}

export function mapCloudStackHostToHost(host: CloudStackHost): Host {
  return {
    id: host.id ?? host.name ?? "unknown",
    name: host.name ?? host.id ?? "unnamed-host",
    zone: host.zonename ?? "unknown",
    cluster: host.clustername ?? "unknown",
    state: normalizeHostState(host.resourcestate, host.state),
    cpu: normalizePercentage(host.cpuallocatedpercentage),
    mem: normalizePercentage(host.memoryallocatedpercentage),
    instances: normalizeInstanceCount(host.virtualmachinecount ?? host.vmcount ?? host.instances),
    hypervisor: normalizeHypervisor(host.hypervisor),
  };
}

function normalizeHostState(resourceState: string | undefined, state: string | undefined): Host["state"] {
  const combined = `${resourceState ?? ""} ${state ?? ""}`.trim().toLowerCase();

  if (!combined) {
    return "alert";
  }

  if (combined.includes("maintenance") || combined.includes("prep")) {
    return "maintenance";
  }

  if (
    combined.includes("disconnected") ||
    combined.includes("error") ||
    combined.includes("alert") ||
    combined.includes("down") ||
    combined.includes("unknown") ||
    combined.includes("disabled") ||
    combined.includes("unmanaged")
  ) {
    return "alert";
  }

  if (combined.includes("up") || combined.includes("connected") || combined.includes("enabled")) {
    return "up";
  }

  return "alert";
}

function normalizePercentage(value: number | string | undefined): number {
  const parsed = typeof value === "number" ? value : Number.parseFloat(value?.replace("%", "") ?? "");
  if (!Number.isFinite(parsed)) {
    return 0;
  }

  return Math.round(Math.min(100, Math.max(0, parsed)));
}

function normalizeInstanceCount(value: number | string | undefined): number {
  const parsed = typeof value === "number" ? value : Number.parseInt(value ?? "", 10);
  if (!Number.isFinite(parsed) || parsed < 0) {
    return 0;
  }

  return Math.floor(parsed);
}

function normalizeHypervisor(hypervisor: string | undefined): Host["hypervisor"] {
  const normalized = hypervisor?.trim().toLowerCase();

  switch (normalized) {
    case "vmware":
      return "VMware";
    case "hyperv":
    case "hyper-v":
      return "Hyper-V";
    case "xen":
    case "xenserver":
      return "XenServer";
    case "kvm":
    default:
      return "KVM";
  }
}

function buildListHostsUrl(requestHeaders?: Pick<Headers, "get">): string {
  const params = new URLSearchParams({
    type: "Routing",
    details: "capacity",
  });
  const origin = getRequestOrigin(requestHeaders);
  return `${origin}/api/cs/listHosts?${params.toString()}`;
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
