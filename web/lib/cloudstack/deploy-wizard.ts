import {
  mockDeployWizardCatalog,
  type AffinityGroup,
  type DeployWizardCatalog,
  type DiskOffering,
  type Project,
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

export type CloudStackProject = {
  id?: string;
  name?: string;
  displaytext?: string;
  account?: string;
  domain?: string;
  domainpath?: string;
  state?: string;
};

export type CloudStackAffinityGroup = {
  id?: string;
  name?: string;
  type?: string;
  description?: string;
  displaytext?: string;
  account?: string;
  domain?: string;
  domainpath?: string;
  project?: string;
  projectid?: string;
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

export type ListProjectsResponse = {
  listprojectsresponse?: {
    count?: number | string;
    project?: CloudStackProject[];
  };
};

export type ListAffinityGroupsResponse = {
  listaffinitygroupsresponse?: {
    count?: number | string;
    affinitygroup?: CloudStackAffinityGroup[];
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
  projects: ListProjectsResponse;
  affinityGroups: ListAffinityGroupsResponse;
};

type FetchOptions = {
  fetchImpl?: typeof fetch;
};

export type DeployWizardLaunchInput = {
  name: string;
  displayName?: string;
  zoneId: string;
  templateId: string;
  serviceOfferingId: string;
  networkId?: string;
  projectId?: string;
  affinityGroupIds?: string[];
  ipAddress?: string;
  diskOfferingId?: string;
  diskOfferingCustomized?: boolean;
  diskOfferingSizeGiB?: number;
  securityGroupId?: string;
  sshKeyPairName?: string;
  userData?: string;
  startVm?: boolean;
};

export type DeployWizardLaunchResult = {
  jobId: string;
  virtualMachineId?: string;
};

export type DeployWizardJobStatus = "pending" | "success" | "failed";

export type DeployWizardJobResult = {
  jobId: string;
  status: DeployWizardJobStatus;
  progress?: number;
  resultCode?: number;
  errorText?: string;
  virtualMachineId?: string;
  virtualMachineName?: string;
  virtualMachineState?: string;
};

type DeployVirtualMachineResponse = {
  deployvirtualmachineresponse?: {
    id?: string;
    jobid?: string;
  };
  error?: string;
  errorresponse?: {
    errortext?: string;
  };
};

type QueryAsyncJobResultResponse = {
  queryasyncjobresultresponse?: {
    jobid?: string;
    jobstatus?: number | string;
    jobprocstatus?: number | string;
    jobresultcode?: number | string;
    jobresult?: {
      errortext?: string;
      virtualmachine?: {
        id?: string;
        name?: string;
        state?: string;
      };
    };
  };
  error?: string;
  errorresponse?: {
    errortext?: string;
  };
};

const ENDPOINTS = [
  ["zones", "/api/cs/listZones"],
  ["templates", "/api/cs/listTemplates?templatefilter=executable&details=min&showunique=true"],
  ["serviceOfferings", "/api/cs/listServiceOfferings"],
  ["diskOfferings", "/api/cs/listDiskOfferings"],
  ["networks", "/api/cs/listNetworks?listall=true"],
  ["securityGroups", "/api/cs/listSecurityGroups?listall=true"],
  ["sshKeyPairs", "/api/cs/listSSHKeyPairs"],
  ["projects", "/api/cs/listProjects?listall=true"],
  ["affinityGroups", "/api/cs/listAffinityGroups?listall=true"],
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
    projects: (responses.projects.listprojectsresponse?.project ?? []).map(mapCloudStackProjectToDeployWizardProject),
    affinityGroups: (responses.affinityGroups.listaffinitygroupsresponse?.affinitygroup ?? []).map(
      mapCloudStackAffinityGroupToDeployWizardAffinityGroup,
    ),
  };
}

export function buildDeployVirtualMachineParams(input: DeployWizardLaunchInput): URLSearchParams {
  const params = new URLSearchParams();
  setRequiredParam(params, "name", input.name);
  setOptionalParam(params, "displayname", input.displayName);
  setRequiredParam(params, "zoneid", input.zoneId);
  setRequiredParam(params, "templateid", input.templateId);
  setRequiredParam(params, "serviceofferingid", input.serviceOfferingId);
  setOptionalParam(params, "projectid", input.projectId);
  setOptionalCsvParam(params, "affinitygroupids", input.affinityGroupIds);
  if (input.diskOfferingCustomized) {
    setRequiredParam(params, "diskofferingid", input.diskOfferingId ?? "");
    params.set("size", String(readPositiveInteger(input.diskOfferingSizeGiB, "custom disk size")));
  } else {
    setOptionalParam(params, "diskofferingid", input.diskOfferingId);
  }
  setOptionalParam(params, "sshkeypairs", input.sshKeyPairName);
  setOptionalParam(params, "userdata", encodeUserData(input.userData));
  params.set("startvm", String(input.startVm ?? true));

  if (input.networkId) {
    params.set("networkids", input.networkId);
    setOptionalParam(params, "ipaddress", input.ipAddress);
  } else {
    setOptionalParam(params, "securitygroupids", input.securityGroupId);
  }

  return params;
}

export async function deployVirtualMachineFromWizard(
  input: DeployWizardLaunchInput,
  { fetchImpl = fetch }: FetchOptions = {},
): Promise<DeployWizardLaunchResult> {
  const params = buildDeployVirtualMachineParams(input);
  const response = await fetchImpl(`${getRequestOrigin()}/api/cs/deployVirtualMachine`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(Object.fromEntries(params.entries())),
    cache: "no-store",
  });
  const payload = (await response.json()) as DeployVirtualMachineResponse;
  if (!response.ok) {
    throw new Error(readErrorText(payload) ?? "CloudStack deployVirtualMachine request failed");
  }

  const deployResponse = payload.deployvirtualmachineresponse;
  if (!deployResponse?.jobid) {
    throw new Error("CloudStack deployVirtualMachine response is missing async job id");
  }

  return {
    jobId: deployResponse.jobid,
    ...(deployResponse.id ? { virtualMachineId: deployResponse.id } : {}),
  };
}

export async function queryDeployWizardJobResult(
  jobId: string,
  { fetchImpl = fetch }: FetchOptions = {},
): Promise<DeployWizardJobResult> {
  const params = new URLSearchParams({ jobid: jobId });
  const response = await fetchImpl(`${getRequestOrigin()}/api/cs/queryAsyncJobResult?${params.toString()}`, {
    method: "GET",
    cache: "no-store",
  });
  const payload = (await response.json()) as QueryAsyncJobResultResponse;
  if (!response.ok) {
    throw new Error(readErrorText(payload) ?? "CloudStack queryAsyncJobResult request failed");
  }

  const job = payload.queryasyncjobresultresponse;
  if (!job?.jobid) {
    throw new Error("CloudStack queryAsyncJobResult response is missing job id");
  }

  const statusCode = readNonNegativeInteger(job.jobstatus) ?? 0;
  const progress = readNonNegativeInteger(job.jobprocstatus) ?? undefined;
  if (statusCode === 1) {
    return {
      jobId: job.jobid,
      status: "success",
      ...(job.jobresult?.virtualmachine?.id ? { virtualMachineId: job.jobresult.virtualmachine.id } : {}),
      ...(job.jobresult?.virtualmachine?.name ? { virtualMachineName: job.jobresult.virtualmachine.name } : {}),
      ...(job.jobresult?.virtualmachine?.state ? { virtualMachineState: job.jobresult.virtualmachine.state } : {}),
    };
  }

  if (statusCode === 2) {
    const resultCode = readNonNegativeInteger(job.jobresultcode);
    return {
      jobId: job.jobid,
      status: "failed",
      ...(resultCode !== null ? { resultCode } : {}),
      ...(job.jobresult?.errortext ? { errorText: job.jobresult.errortext } : {}),
    };
  }

  return {
    jobId: job.jobid,
    status: "pending",
    ...(progress !== undefined ? { progress } : {}),
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

export function mapCloudStackProjectToDeployWizardProject(project: CloudStackProject): Project {
  const id = project.id ?? project.name ?? "unknown";

  return {
    id,
    name: project.name ?? project.displaytext ?? id,
    displayText: project.displaytext ?? project.name ?? id,
    account: project.account ?? "unknown",
    domain: project.domainpath ?? project.domain ?? "unknown",
    state: project.state ?? "unknown",
  };
}

export function mapCloudStackAffinityGroupToDeployWizardAffinityGroup(
  group: CloudStackAffinityGroup,
): AffinityGroup {
  const id = group.id ?? group.name ?? "unknown";

  return {
    id,
    name: group.name ?? group.displaytext ?? id,
    type: group.type ?? "unknown",
    description: group.description ?? group.displaytext ?? group.name ?? id,
    account: group.account ?? "unknown",
    domain: group.domainpath ?? group.domain ?? "unknown",
    project: group.project ?? null,
    projectId: group.projectid ?? null,
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

function readPositiveInteger(value: number | undefined, label: string): number {
  if (typeof value === "number" && Number.isInteger(value) && value > 0) {
    return value;
  }

  throw new Error(`Missing or invalid Deploy Wizard value: ${label}`);
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

function setRequiredParam(params: URLSearchParams, key: string, value: string): void {
  const trimmed = value.trim();
  if (!trimmed) {
    throw new Error(`Missing required Deploy Wizard value: ${key}`);
  }
  params.set(key, trimmed);
}

function setOptionalParam(params: URLSearchParams, key: string, value: string | null | undefined): void {
  const trimmed = value?.trim();
  if (trimmed) {
    params.set(key, trimmed);
  }
}

function setOptionalCsvParam(params: URLSearchParams, key: string, values: string[] | null | undefined): void {
  const trimmedValues = (values ?? []).map((value) => value.trim()).filter(Boolean);
  if (trimmedValues.length > 0) {
    params.set(key, trimmedValues.join(","));
  }
}

function encodeUserData(value: string | null | undefined): string | undefined {
  if (!value?.trim()) {
    return undefined;
  }

  const bytes = new TextEncoder().encode(value);
  let binary = "";
  for (let index = 0; index < bytes.length; index += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(index, index + 0x8000));
  }
  return btoa(binary);
}

function readErrorText(payload: DeployVirtualMachineResponse | QueryAsyncJobResultResponse): string | undefined {
  return payload.error ?? payload.errorresponse?.errortext;
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
