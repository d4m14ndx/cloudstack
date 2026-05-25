import { mockNetworks, type Network } from "../mock-data.ts";

export type CloudStackVpc = {
  id?: string;
  name?: string;
  displaytext?: string;
  cidr?: string;
  zonename?: string;
  state?: string;
  network?: CloudStackNetwork[];
};

export type CloudStackNetwork = {
  id?: string;
  name?: string;
  displaytext?: string;
  cidr?: string;
  networkcidr?: string;
  gateway?: string;
  type?: string;
  zonename?: string;
  state?: string;
  vpcid?: string;
  vpcname?: string;
};

export type CloudStackVirtualMachine = {
  id?: string;
  nic?: CloudStackNic[];
};

export type CloudStackNic = {
  networkid?: string;
  vpcid?: string;
};

export type ListVpcsResponse = {
  listvpcsresponse?: {
    count?: number | string;
    vpc?: CloudStackVpc[];
  };
};

export type ListNetworksResponse = {
  listnetworksresponse?: {
    count?: number | string;
    network?: CloudStackNetwork[];
  };
};

export type ListVirtualMachinesResponse = {
  listvirtualmachinesresponse?: {
    count?: number | string;
    virtualmachine?: CloudStackVirtualMachine[];
  };
};

type FetchOptions = {
  fetchImpl?: typeof fetch;
  requestHeaders?: Pick<Headers, "get">;
};

export async function getNetworksFromBff({
  fetchImpl = fetch,
  requestHeaders,
}: FetchOptions = {}): Promise<Network[]> {
  if (process.env.NEXT_PUBLIC_APP_ENV === "mock" || !process.env.CS_URL) {
    return mockNetworks;
  }

  try {
    const fetchOptions: RequestInit = {
      method: "GET",
      cache: "no-store",
      headers: buildForwardedHeaders(requestHeaders),
    };
    const [vpcsResponse, networksResponse, virtualMachinesResponse] = await Promise.all([
      fetchImpl(buildListVpcsUrl(requestHeaders), fetchOptions),
      fetchImpl(buildListNetworksUrl(requestHeaders), fetchOptions),
      fetchImpl(buildListVirtualMachinesUrl(requestHeaders), fetchOptions),
    ]);

    if (!vpcsResponse.ok || !networksResponse.ok || !virtualMachinesResponse.ok) {
      return mockNetworks;
    }

    const [vpcsPayload, networksPayload, virtualMachinesPayload] = (await Promise.all([
      vpcsResponse.json(),
      networksResponse.json(),
      virtualMachinesResponse.json(),
    ])) as [ListVpcsResponse, ListNetworksResponse, ListVirtualMachinesResponse];

    if (
      !vpcsPayload.listvpcsresponse ||
      !networksPayload.listnetworksresponse ||
      !virtualMachinesPayload.listvirtualmachinesresponse
    ) {
      return mockNetworks;
    }

    return networksFromCloudStackResponses(vpcsPayload, networksPayload, virtualMachinesPayload);
  } catch {
    return mockNetworks;
  }
}

export function networksFromCloudStackResponses(
  vpcsResponse: ListVpcsResponse,
  networksResponse: ListNetworksResponse,
  virtualMachinesResponse: ListVirtualMachinesResponse,
): Network[] {
  const virtualMachines = virtualMachinesResponse.listvirtualmachinesresponse?.virtualmachine ?? [];
  const vpcInstanceCounts = countVirtualMachinesByNicField(virtualMachines, "vpcid");
  const networkInstanceCounts = countVirtualMachinesByNicField(virtualMachines, "networkid");
  const vpcs = (vpcsResponse.listvpcsresponse?.vpc ?? []).map((vpc) =>
    mapCloudStackVpcToNetwork(vpc, vpcInstanceCounts),
  );
  const isolatedNetworks = (networksResponse.listnetworksresponse?.network ?? [])
    .filter((network) => !network.vpcid)
    .map((network) => mapCloudStackIsolatedNetworkToNetwork(network, networkInstanceCounts));

  return [...vpcs, ...isolatedNetworks];
}

export function mapCloudStackVpcToNetwork(vpc: CloudStackVpc, instanceCounts: Map<string, number>): Network {
  const id = vpc.id ?? vpc.name ?? "unknown";

  return {
    id,
    name: vpc.name ?? vpc.displaytext ?? id,
    cidr: vpc.cidr ?? "-",
    type: "VPC",
    zone: vpc.zonename ?? "unknown",
    instances: instanceCounts.get(id) ?? 0,
    state: mapCloudStackVpcState(vpc.state),
    gateway: "-",
  };
}

export function mapCloudStackIsolatedNetworkToNetwork(
  network: CloudStackNetwork,
  instanceCounts: Map<string, number>,
): Network {
  const id = network.id ?? network.name ?? "unknown";

  return {
    id,
    name: network.name ?? network.displaytext ?? id,
    cidr: network.networkcidr ?? network.cidr ?? "-",
    type: "Isolated",
    zone: network.zonename ?? "unknown",
    instances: instanceCounts.get(id) ?? 0,
    state: mapCloudStackIsolatedNetworkState(network.state),
    gateway: network.gateway ?? "-",
  };
}

function countVirtualMachinesByNicField(
  virtualMachines: CloudStackVirtualMachine[],
  field: keyof Pick<CloudStackNic, "networkid" | "vpcid">,
): Map<string, number> {
  const instanceIdsByResourceId = new Map<string, Set<string>>();

  for (const virtualMachine of virtualMachines) {
    if (!virtualMachine.id) {
      continue;
    }

    const resourceIdsForVirtualMachine = new Set<string>();
    for (const nic of virtualMachine.nic ?? []) {
      const resourceId = nic[field];
      if (resourceId) {
        resourceIdsForVirtualMachine.add(resourceId);
      }
    }

    for (const resourceId of resourceIdsForVirtualMachine) {
      const instanceIds = instanceIdsByResourceId.get(resourceId) ?? new Set<string>();
      instanceIds.add(virtualMachine.id);
      instanceIdsByResourceId.set(resourceId, instanceIds);
    }
  }

  return new Map(
    Array.from(instanceIdsByResourceId.entries()).map(([resourceId, instanceIds]) => [resourceId, instanceIds.size]),
  );
}

function mapCloudStackVpcState(state: string | undefined): Network["state"] {
  const normalized = normalizeState(state);
  if (["enabled", "running", "implemented"].includes(normalized)) {
    return "running";
  }

  return "warning";
}

function mapCloudStackIsolatedNetworkState(state: string | undefined): Network["state"] {
  const normalized = normalizeState(state);
  if (["implemented", "setup", "allocated", "enabled", "running"].includes(normalized)) {
    return "running";
  }

  return "warning";
}

function normalizeState(state: string | undefined): string {
  return state?.trim().toLowerCase() ?? "";
}

function buildListVpcsUrl(requestHeaders?: Pick<Headers, "get">): string {
  const params = new URLSearchParams({
    listall: "true",
  });
  const origin = getRequestOrigin(requestHeaders);
  return `${origin}/api/cs/listVPCs?${params.toString()}`;
}

function buildListNetworksUrl(requestHeaders?: Pick<Headers, "get">): string {
  const params = new URLSearchParams({
    listall: "true",
    type: "isolated",
    forvpc: "false",
  });
  const origin = getRequestOrigin(requestHeaders);
  return `${origin}/api/cs/listNetworks?${params.toString()}`;
}

function buildListVirtualMachinesUrl(requestHeaders?: Pick<Headers, "get">): string {
  const params = new URLSearchParams({
    listall: "true",
    details: "nics",
  });
  const origin = getRequestOrigin(requestHeaders);
  return `${origin}/api/cs/listVirtualMachines?${params.toString()}`;
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
