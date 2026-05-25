import {
  mockDashboardMetrics,
  mockDashboardSummary,
  type DashboardMetric,
} from "../mock-data.ts";

export type CloudStackListVirtualMachinesCountResponse = {
  listvirtualmachinesresponse?: {
    count?: number | string;
  };
};

export type CloudStackListHostsCountResponse = {
  listhostsresponse?: {
    count?: number | string;
  };
};

export type CloudStackZone = {
  id?: string;
  name?: string;
  allocationstate?: string;
};

export type CloudStackListZonesResponse = {
  listzonesresponse?: {
    count?: number | string;
    zone?: CloudStackZone[];
  };
};

export type CloudStackCapacity = {
  type?: number | string;
  name?: string;
  capacityallocated?: number | string;
  capacityused?: number | string;
  capacitytotal?: number | string;
};

export type CloudStackListCapacityResponse = {
  listcapacityresponse?: {
    capacity?: CloudStackCapacity[];
  };
};

export type DashboardSummary = typeof mockDashboardSummary;

export type DashboardInventory = {
  summary: DashboardSummary;
  metrics: DashboardMetric[];
  source: "bff" | "mock";
};

type DashboardResponses = {
  allVirtualMachines: CloudStackListVirtualMachinesCountResponse;
  runningVirtualMachines: CloudStackListVirtualMachinesCountResponse;
  hosts: CloudStackListHostsCountResponse;
  zones: CloudStackListZonesResponse;
  capacity: CloudStackListCapacityResponse;
};

type FetchOptions = {
  fetchImpl?: typeof fetch;
  requestHeaders?: Pick<Headers, "get">;
};

type DashboardRequestKey = keyof DashboardResponses;

type DashboardRequest = {
  key: DashboardRequestKey;
  command: string;
  params: Record<string, string>;
};

const DASHBOARD_REQUESTS: DashboardRequest[] = [
  {
    key: "allVirtualMachines",
    command: "listVirtualMachines",
    params: { listall: "true", details: "min", page: "1", pagesize: "1" },
  },
  {
    key: "runningVirtualMachines",
    command: "listVirtualMachines",
    params: { listall: "true", details: "min", state: "Running", page: "1", pagesize: "1" },
  },
  {
    key: "hosts",
    command: "listHosts",
    params: { listall: "true", details: "min", type: "routing", page: "1", pagesize: "1" },
  },
  {
    key: "zones",
    command: "listZones",
    params: {},
  },
  {
    key: "capacity",
    command: "listCapacity",
    params: { fetchlatest: "false" },
  },
];

const MOCK_DASHBOARD_INVENTORY: DashboardInventory = {
  summary: mockDashboardSummary,
  metrics: mockDashboardMetrics,
  source: "mock",
};

export async function getDashboardInventoryFromBff({
  fetchImpl = fetch,
  requestHeaders,
}: FetchOptions = {}): Promise<DashboardInventory> {
  if (process.env.NEXT_PUBLIC_APP_ENV === "mock" || !process.env.CS_URL) {
    return MOCK_DASHBOARD_INVENTORY;
  }

  try {
    const entries = await Promise.all(
      DASHBOARD_REQUESTS.map(async (request) => {
        const response = await fetchImpl(buildBffUrl(request, requestHeaders), {
          method: "GET",
          cache: "no-store",
          headers: buildForwardedHeaders(requestHeaders),
        });

        if (!response.ok) {
          throw new Error(`CloudStack ${request.command} failed with HTTP ${response.status}`);
        }

        return [request.key, await response.json()] as const;
      }),
    );

    return dashboardFromCloudStackResponses(Object.fromEntries(entries) as DashboardResponses);
  } catch {
    return MOCK_DASHBOARD_INVENTORY;
  }
}

export function dashboardFromCloudStackResponses(responses: DashboardResponses): DashboardInventory {
  const totalInstances = readCount(responses.allVirtualMachines.listvirtualmachinesresponse?.count);
  const runningInstances = readCount(responses.runningVirtualMachines.listvirtualmachinesresponse?.count);
  const totalHosts = readCount(responses.hosts.listhostsresponse?.count);
  const zones = responses.zones.listzonesresponse?.zone;
  const totalZones = zones?.length ?? readCount(responses.zones.listzonesresponse?.count);
  const onlineZones = zones
    ? zones.filter((zone) => zone.allocationstate?.toLowerCase() === "enabled").length
    : totalZones;

  const capacityRows = responses.capacity.listcapacityresponse?.capacity;
  if (
    totalInstances === null ||
    runningInstances === null ||
    totalHosts === null ||
    totalZones === null ||
    onlineZones === null ||
    !capacityRows
  ) {
    throw new Error("Missing dashboard inventory fields");
  }

  const cpuCapacity = getRequiredCapacity(capacityRows, "CPU_CORE", 90);
  const memoryCapacity = getRequiredCapacity(capacityRows, "MEMORY", 0);
  const storageCapacity = getRequiredCapacity(capacityRows, "STORAGE", 2);
  const cpuAllocated = readCapacityValue(cpuCapacity.capacityallocated ?? cpuCapacity.capacityused);
  const cpuTotal = readCapacityValue(cpuCapacity.capacitytotal);
  const memoryAllocated = readCapacityValue(memoryCapacity.capacityallocated ?? memoryCapacity.capacityused);
  const memoryTotal = readCapacityValue(memoryCapacity.capacitytotal);
  const storageUsed = readCapacityValue(storageCapacity.capacityused);
  const storageTotal = readCapacityValue(storageCapacity.capacitytotal);

  if (
    cpuAllocated === null ||
    cpuTotal === null ||
    memoryAllocated === null ||
    memoryTotal === null ||
    storageUsed === null ||
    storageTotal === null
  ) {
    throw new Error("Missing dashboard capacity fields");
  }

  const memoryDisplay = formatBytes(memoryAllocated);
  const memoryTotalDisplay = formatBytes(memoryTotal);
  const storageDisplay = formatBytes(storageUsed);
  const storageTotalDisplay = formatBytes(storageTotal);

  return {
    source: "bff",
    summary: {
      onlineZones,
      totalZones,
      totalHosts,
      runningInstances,
    },
    metrics: [
      metric("Instances running", String(runningInstances), `/ ${totalInstances}`, "Running now", runningInstances),
      metric("vCPUs allocated", String(Math.round(cpuAllocated)), `/ ${Math.round(cpuTotal)}`, utilizationDelta(cpuAllocated, cpuTotal), cpuAllocated),
      metric(
        "Memory allocated",
        memoryDisplay.value,
        ` ${memoryDisplay.unit}`,
        utilizationDelta(memoryAllocated, memoryTotal),
        memoryAllocated,
        `/ ${memoryTotalDisplay.value} ${memoryTotalDisplay.unit}`,
      ),
      metric(
        "Storage used",
        storageDisplay.value,
        ` ${storageDisplay.unit}`,
        utilizationDelta(storageUsed, storageTotal),
        storageUsed,
        `/ ${storageTotalDisplay.value} ${storageTotalDisplay.unit}`,
      ),
    ],
  };
}

function metric(
  label: DashboardMetric["label"],
  value: string,
  denom: string,
  delta: string,
  seriesValue: number,
  suffix?: string,
): DashboardMetric {
  const mockMetric = mockDashboardMetrics.find((candidate) => candidate.label === label);
  return {
    label,
    value,
    denom,
    suffix,
    delta,
    series: flatSeries(seriesValue),
    color: mockMetric?.color ?? "var(--accent)",
  };
}

function buildBffUrl(request: DashboardRequest, requestHeaders?: Pick<Headers, "get">): string {
  const params = new URLSearchParams(request.params);
  const origin = getRequestOrigin(requestHeaders);
  const query = params.toString();
  return `${origin}/api/cs/${request.command}${query ? `?${query}` : ""}`;
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

function readCount(value: number | string | undefined): number | null {
  const parsed = readCapacityValue(value);
  return parsed === null ? null : Math.round(parsed);
}

function readCapacityValue(value: number | string | undefined): number | null {
  if (typeof value === "number" && Number.isFinite(value) && value >= 0) {
    return value;
  }

  if (typeof value === "string") {
    const parsed = Number.parseFloat(value);
    return Number.isFinite(parsed) && parsed >= 0 ? parsed : null;
  }

  return null;
}

function getRequiredCapacity(
  capacityRows: CloudStackCapacity[],
  name: string,
  type: number,
): CloudStackCapacity {
  const capacity = capacityRows.find((row) => {
    const normalizedName = row.name?.toUpperCase();
    const parsedType = readCapacityValue(row.type);
    return normalizedName === name || parsedType === type;
  });

  if (!capacity) {
    throw new Error(`Missing ${name} capacity`);
  }

  return capacity;
}

function formatBytes(bytes: number): { value: string; unit: "GiB" | "TiB" } {
  const tebibytes = bytes / 1024 ** 4;
  if (tebibytes >= 1) {
    return { value: formatNumber(tebibytes), unit: "TiB" };
  }

  return { value: formatNumber(bytes / 1024 ** 3), unit: "GiB" };
}

function formatNumber(value: number): string {
  return value >= 100 ? String(Math.round(value)) : value.toFixed(1).replace(/\.0$/, "");
}

function utilizationDelta(used: number, total: number): string {
  if (total <= 0) {
    return "0% of capacity";
  }

  return `${Math.round((used / total) * 100)}% of capacity`;
}

function flatSeries(value: number): number[] {
  return Array.from({ length: 7 }, () => value);
}
