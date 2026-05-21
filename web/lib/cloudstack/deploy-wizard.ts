import {
  mockDeployWizardCatalog,
  type DeployWizardCatalog,
  type DiskOffering,
  type ServiceOffering,
  type Zone,
} from "../mock-data.ts";
import {
  mapCloudStackIsolatedNetworkToNetwork,
  type ListNetworksResponse,
} from "./networks.ts";
import {
  mapCloudStackSecurityGroupToSecurityGroup,
  type ListSecurityGroupsResponse,
} from "./security-groups.ts";
import {
  mapCloudStackSshKeyPairToSshKeyPair,
  type ListSshKeyPairsResponse,
} from "./ssh-keys.ts";
import {
  mapCloudStackTemplateToTemplate,
  type ListTemplatesResponse,
} from "./templates.ts";

export type CloudStackZone = {
  id?: string;
  name?: string;
  description?: string;
  allocationstate?: string;
};

export type CloudStackServiceOffering = {
  id?: string;
  name?: string;
  displaytext?: string;
  cpunumber?: number | string;
  cpu?: number | string;
  memory?: number | string;
};

export type CloudStackDiskOffering = {
  id?: string;
  name?: string;
  displaytext?: string;
  disksize?: number | string;
  storagetype?: string;
  customized?: boolean | string;
  iscustomized?: boolean | string;
};

export type ListZonesResponse = {
  listzonesresponse?: {
    count?: number | string;
    zone?: CloudStackZone[];
  };
};

export type ListServiceOfferingsResponse = {
  listserviceofferingsresponse?: {
    count?: number | string;
    serviceoffering?: CloudStackServiceOffering[];
  };
};

export type ListDiskOfferingsResponse = {
  listdiskofferingsresponse?: {
    count?: number | string;
    diskoffering?: CloudStackDiskOffering[];
  };
};

export type DeployWizardCatalogResponses = {
  zones: ListZonesResponse;
  templates: ListTemplatesResponse;
  serviceOfferings: ListServiceOfferingsResponse;
  diskOfferings: ListDiskOfferingsResponse;
  networks: ListNetworksResponse;
  securityGroups: ListSecurityGroupsResponse;
  sshKeyPairs: ListSshKeyPairsResponse;
};

type FetchOptions = {
  fetchImpl?: typeof fetch;
};

const ENDPOINTS = [
  ["zones", "/api/cs/listZones"],
  ["templates", "/api/cs/listTemplates?templatefilter=executable&details=min&showunique=true"],
  ["serviceOfferings", "/api/cs/listServiceOfferings"],
  ["diskOfferings", "/api/cs/listDiskOfferings"],
  ["networks", "/api/cs/listNetworks?listall=true"],
  ["securityGroups", "/api/cs/listSecurityGroups?listall=true"],
  ["sshKeyPairs", "/api/cs/listSSHKeyPairs"],
] as const;

export async function getDeployWizardCatalogFromBff({
  fetchImpl = fetch,
}: FetchOptions = {}): Promise<DeployWizardCatalog> {
  if (process.env.NEXT_PUBLIC_APP_ENV === "mock" || !process.env.CS_URL) {
    return mockDeployWizardCatalog;
  }

  try {
    const responses = await Promise.all(
      ENDPOINTS.map(([, path]) =>
        fetchImpl(`${getRequestOrigin()}${path}`, {
          method: "GET",
          cache: "no-store",
        }),
      ),
    );

    if (responses.some((response) => !response.ok)) {
      return mockDeployWizardCatalog;
    }

    const payloads = await Promise.all(responses.map((response) => response.json()));
    const catalogResponses = Object.fromEntries(
      ENDPOINTS.map(([key], index) => [key, payloads[index]]),
    ) as DeployWizardCatalogResponses;

    if (!hasRequiredEnvelopes(catalogResponses)) {
      return mockDeployWizardCatalog;
    }

    return deployWizardCatalogFromResponses(catalogResponses);
  } catch {
    return mockDeployWizardCatalog;
  }
}

export function deployWizardCatalogFromResponses(responses: DeployWizardCatalogResponses): DeployWizardCatalog {
  return {
    zones: (responses.zones.listzonesresponse?.zone ?? []).map(mapCloudStackZoneToDeployWizardZone),
    templates: (responses.templates.listtemplatesresponse?.template ?? []).map(mapCloudStackTemplateToTemplate),
    serviceOfferings: (responses.serviceOfferings.listserviceofferingsresponse?.serviceoffering ?? []).map(
      mapCloudStackServiceOfferingToDeployWizardServiceOffering,
    ),
    diskOfferings: (responses.diskOfferings.listdiskofferingsresponse?.diskoffering ?? []).map(
      mapCloudStackDiskOfferingToDeployWizardDiskOffering,
    ),
    networks: (responses.networks.listnetworksresponse?.network ?? []).map((network) =>
      mapCloudStackIsolatedNetworkToNetwork(network, new Map()),
    ),
    securityGroups: (responses.securityGroups.listsecuritygroupsresponse?.securitygroup ?? []).map(
      mapCloudStackSecurityGroupToSecurityGroup,
    ),
    sshKeyPairs: (responses.sshKeyPairs.listsshkeypairsresponse?.sshkeypair ?? []).map(
      mapCloudStackSshKeyPairToSshKeyPair,
    ),
  };
}

export function mapCloudStackZoneToDeployWizardZone(zone: CloudStackZone): Zone {
  const id = zone.id ?? zone.name ?? "unknown";

  return {
    id,
    name: zone.name ?? zone.description ?? id,
    region: zone.description ?? zone.name ?? "unknown",
    state: normalizeZoneState(zone.allocationstate),
    hosts: 0,
    pods: 0,
    instances: 0,
    cpuPct: 0,
    memPct: 0,
    storagePct: 0,
  };
}

export function mapCloudStackServiceOfferingToDeployWizardServiceOffering(
  offering: CloudStackServiceOffering,
): ServiceOffering {
  const id = offering.id ?? offering.name ?? "unknown";

  return {
    id,
    name: offering.name ?? offering.displaytext ?? id,
    cpu: readNonNegativeInteger(offering.cpunumber ?? offering.cpu) ?? 0,
    ram: Math.round((readNonNegativeInteger(offering.memory) ?? 0) / 1024),
    description: offering.displaytext ?? offering.name ?? id,
  };
}

export function mapCloudStackDiskOfferingToDeployWizardDiskOffering(
  offering: CloudStackDiskOffering,
): DiskOffering {
  const id = offering.id ?? offering.name ?? "unknown";

  return {
    id,
    name: offering.name ?? offering.displaytext ?? id,
    sizeGiB: bytesToGiB(offering.disksize),
    customized: readBoolean(offering.customized ?? offering.iscustomized),
    type: offering.storagetype ?? "shared",
  };
}

function hasRequiredEnvelopes(responses: DeployWizardCatalogResponses): boolean {
  return Boolean(
    responses.zones.listzonesresponse &&
      responses.templates.listtemplatesresponse &&
      responses.serviceOfferings.listserviceofferingsresponse &&
      responses.diskOfferings.listdiskofferingsresponse &&
      responses.networks.listnetworksresponse &&
      responses.securityGroups.listsecuritygroupsresponse &&
      responses.sshKeyPairs.listsshkeypairsresponse,
  );
}

function normalizeZoneState(state: string | undefined): Zone["state"] {
  return state?.trim().toLowerCase() === "disabled" ? "maintenance" : "enabled";
}

function bytesToGiB(value: number | string | undefined): number | null {
  const bytes = readNonNegativeNumber(value);
  if (bytes === null) {
    return null;
  }

  return Math.round(bytes / 1024 ** 3);
}

function readNonNegativeInteger(value: number | string | undefined): number | null {
  const number = readNonNegativeNumber(value);
  return number === null ? null : Math.floor(number);
}

function readNonNegativeNumber(value: number | string | undefined): number | null {
  if (typeof value === "number" && Number.isFinite(value) && value >= 0) {
    return value;
  }

  if (typeof value === "string") {
    const parsed = Number.parseFloat(value);
    return Number.isFinite(parsed) && parsed >= 0 ? parsed : null;
  }

  return null;
}

function readBoolean(value: boolean | string | undefined): boolean {
  if (typeof value === "boolean") {
    return value;
  }

  return value?.trim().toLowerCase() === "true";
}

function getRequestOrigin(): string {
  if (process.env.NEXTAUTH_URL) {
    return process.env.NEXTAUTH_URL.replace(/\/$/, "");
  }

  if (typeof window !== "undefined") {
    return window.location.origin;
  }

  return "http://localhost:3000";
}
