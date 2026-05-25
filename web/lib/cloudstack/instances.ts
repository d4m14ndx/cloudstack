import { mockInstances, type Instance } from "../mock-data.ts";

export type CloudStackVirtualMachine = {
  id?: string;
  name?: string;
  displayname?: string;
  templatename?: string;
  serviceofferingname?: string;
  cpunumber?: number | string;
  memory?: number | string;
  state?: string;
  ipaddress?: string;
  publicip?: string;
  zonename?: string;
  networkname?: string;
  account?: string;
  cpuused?: string;
  memorykbs?: number | string;
  memorytargetkbs?: number | string;
  created?: string;
  nic?: Array<{
    ipaddress?: string;
    networkname?: string;
    isdefault?: boolean;
    publicip?: string;
  }>;
};

export type ListVirtualMachinesResponse = {
  listvirtualmachinesresponse?: {
    count?: number;
    virtualmachine?: CloudStackVirtualMachine[];
  };
};

type FetchOptions = {
  fetchImpl?: typeof fetch;
  requestHeaders?: Pick<Headers, "get">;
};

const LIST_VIRTUAL_MACHINES_DETAILS = "group,nics,stats,tmpl,servoff";

export async function getInstancesFromBff({
  fetchImpl = fetch,
  requestHeaders,
}: FetchOptions = {}): Promise<Instance[]> {
  if (process.env.NEXT_PUBLIC_APP_ENV === "mock" || !process.env.CS_URL) {
    return mockInstances;
  }

  try {
    const response = await fetchImpl(buildListVirtualMachinesUrl(requestHeaders), {
      method: "GET",
      cache: "no-store",
      headers: buildForwardedHeaders(requestHeaders),
    });

    if (!response.ok) {
      return mockInstances;
    }

    const payload = (await response.json()) as ListVirtualMachinesResponse;
    if (!payload.listvirtualmachinesresponse) {
      return mockInstances;
    }

    return instancesFromListVirtualMachinesResponse(payload);
  } catch {
    return mockInstances;
  }
}

export async function listVirtualMachinesFromBff(fetchImpl: typeof fetch = fetch): Promise<Instance[]> {
  const params = new URLSearchParams({
    listall: "true",
    details: LIST_VIRTUAL_MACHINES_DETAILS,
  });
  const response = await fetchImpl(`/api/cs/listVirtualMachines?${params.toString()}`, {
    method: "GET",
    cache: "no-store",
  });

  if (!response.ok) {
    throw new Error(`CloudStack listVirtualMachines failed with HTTP ${response.status}`);
  }

  return instancesFromListVirtualMachinesResponse((await response.json()) as ListVirtualMachinesResponse);
}

export function instancesFromListVirtualMachinesResponse(response: ListVirtualMachinesResponse): Instance[] {
  return (response.listvirtualmachinesresponse?.virtualmachine ?? []).map(mapVirtualMachineToInstance);
}

export function mapVirtualMachineToInstance(vm: CloudStackVirtualMachine): Instance {
  const defaultNic = vm.nic?.find((nic) => nic.isdefault) ?? vm.nic?.[0];
  const cpu = toPositiveNumber(vm.cpunumber) ?? 0;
  const ramMiB = toPositiveNumber(vm.memory) ?? 0;
  const memoryKiB = toPositiveNumber(vm.memorykbs);
  const memoryTargetKiB = toPositiveNumber(vm.memorytargetkbs);

  return {
    id: vm.id ?? vm.name ?? "unknown",
    name: vm.displayname ?? vm.name ?? vm.id ?? "unnamed-instance",
    template: vm.templatename ?? "Unknown template",
    offering: vm.serviceofferingname ?? "Unknown offering",
    cpu,
    ram: Math.round(ramMiB / 1024),
    state: mapVirtualMachineState(vm.state),
    ip: vm.ipaddress ?? defaultNic?.ipaddress ?? "-",
    publicIp: vm.publicip ?? defaultNic?.publicip ?? null,
    zone: vm.zonename ?? "unknown",
    network: defaultNic?.networkname ?? vm.networkname ?? "unknown",
    uptime: vm.state === "Running" && vm.created ? "running" : null,
    account: vm.account ?? "unknown",
    cpuUsage: parsePercent(vm.cpuused) ?? 0,
    memUsage: calculateMemoryUsage(memoryKiB, memoryTargetKiB),
  };
}

export function mapVirtualMachineState(state: string | undefined): Instance["state"] {
  switch (state?.toLowerCase()) {
    case "running":
      return "running";
    case "stopped":
      return "stopped";
    case "starting":
    case "startingmigrate":
    case "migrating":
    case "stopping":
      return "starting";
    case "error":
    case "destroyed":
    case "expunging":
    case "unknown":
      return "error";
    default:
      return "stopped";
  }
}

function toPositiveNumber(value: number | string | undefined): number | null {
  if (typeof value === "number" && Number.isFinite(value) && value >= 0) {
    return value;
  }

  if (typeof value === "string") {
    const parsed = Number.parseFloat(value);
    return Number.isFinite(parsed) && parsed >= 0 ? parsed : null;
  }

  return null;
}

function parsePercent(value: string | undefined): number | null {
  if (!value) {
    return null;
  }

  const parsed = toPositiveNumber(value.replace("%", ""));
  return parsed === null ? null : Math.round(parsed);
}

function calculateMemoryUsage(memoryKiB: number | null, memoryTargetKiB: number | null): number {
  if (!memoryKiB || !memoryTargetKiB) {
    return 0;
  }

  return Math.round((memoryKiB / memoryTargetKiB) * 100);
}

function buildListVirtualMachinesUrl(requestHeaders?: Pick<Headers, "get">): string {
  const params = new URLSearchParams({
    listall: "true",
    details: LIST_VIRTUAL_MACHINES_DETAILS,
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
