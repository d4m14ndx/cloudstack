import { mockEvents, mockInstances, mockVolumes, type Event, type Instance, type Volume } from "../mock-data.ts";
import {
  mapVirtualMachineToInstance,
  type CloudStackVirtualMachine,
  type ListVirtualMachinesResponse,
} from "./instances.ts";
import { mapCloudStackVolumeToVolume, type CloudStackVolume, type ListVolumesResponse } from "./volumes.ts";
import { mapCloudStackEventToEvent, type CloudStackEvent, type ListEventsResponse } from "./events.ts";

export type InstanceDetail = {
  instance: Instance;
  identity: {
    id: string;
    name: string;
    displayName: string;
    internalName: string;
    account: string;
    domain: string;
    project: string | null;
    created: string | null;
  };
  placement: {
    zone: string;
    pod: string | null;
    cluster: string | null;
    host: string | null;
    hypervisor: string | null;
  };
  compute: {
    cpu: number;
    cpuSpeedMHz: number | null;
    ramGiB: number;
    offering: string;
    cpuUsage: number;
    memoryUsage: number;
    haEnabled: boolean;
  };
  image: {
    template: string;
    templateDisplayText: string | null;
    iso: string | null;
    serviceOffering: string;
    diskOffering: string | null;
  };
  networking: InstanceNetwork[];
  storage: Volume[];
  activity: Event[];
  securityGroups: InstanceGroup[];
  affinityGroups: InstanceGroup[];
  console: {
    rawState: string | null;
    hostControlState: string | null;
    externalUrl: string | null;
  };
};

export type InstanceNetwork = {
  id: string;
  networkId: string | null;
  name: string;
  ip: string;
  publicIp: string | null;
  gateway: string | null;
  netmask: string | null;
  macAddress: string | null;
  type: string | null;
  isDefault: boolean;
};

export type InstanceGroup = {
  id: string;
  name: string;
  type: string | null;
};

type DetailVirtualMachine = CloudStackVirtualMachine & {
  instancename?: string;
  domain?: string;
  project?: string;
  podname?: string;
  clustername?: string;
  hostname?: string;
  hypervisor?: string;
  cpuspeed?: number | string;
  templatedisplaytext?: string;
  iso?: string;
  isoname?: string;
  diskofferingname?: string;
  haenable?: boolean | string;
  hostcontrolstate?: string;
  details?: Record<string, string | undefined> | Array<{ name?: string; value?: string }>;
  securitygroup?: Array<{ id?: string; name?: string; type?: string }>;
  affinitygroup?: Array<{ id?: string; name?: string; type?: string }>;
  nic?: Array<{
    id?: string;
    networkid?: string;
    networkname?: string;
    ipaddress?: string;
    publicip?: string;
    gateway?: string;
    netmask?: string;
    macaddress?: string;
    type?: string;
    traffictype?: string;
    isdefault?: boolean | string;
  }>;
};

type FetchOptions = {
  fetchImpl?: typeof fetch;
  requestHeaders?: Pick<Headers, "get">;
};

const INSTANCE_DETAILS = "group,nics,stats,secgrp,tmpl,servoff,diskoff,iso,volume,affgrp";

export async function getInstanceDetailFromBff(
  id: string,
  { fetchImpl = fetch, requestHeaders }: FetchOptions = {},
): Promise<InstanceDetail | null> {
  if (process.env.NEXT_PUBLIC_APP_ENV === "mock" || !process.env.CS_URL) {
    return mockDetailFor(id);
  }

  try {
    const [vmResponse, volumesResponse, eventsResponse] = await Promise.all([
      fetchImpl(buildListVirtualMachinesUrl(id, requestHeaders), {
        method: "GET",
        cache: "no-store",
        headers: buildForwardedHeaders(requestHeaders),
      }),
      fetchImpl(buildListVolumesUrl(id, requestHeaders), {
        method: "GET",
        cache: "no-store",
        headers: buildForwardedHeaders(requestHeaders),
      }),
      fetchImpl(buildListEventsUrl(id, requestHeaders), {
        method: "GET",
        cache: "no-store",
        headers: buildForwardedHeaders(requestHeaders),
      }),
    ]);

    if (!vmResponse.ok || !volumesResponse.ok || !eventsResponse.ok) {
      return mockDetailFor(id);
    }

    const vmPayload = (await vmResponse.json()) as ListVirtualMachinesResponse;
    const volumesPayload = (await volumesResponse.json()) as ListVolumesResponse;
    const eventsPayload = (await eventsResponse.json()) as ListEventsResponse;

    if (
      !vmPayload.listvirtualmachinesresponse ||
      !volumesPayload.listvolumesresponse ||
      !eventsPayload.listeventsresponse
    ) {
      return mockDetailFor(id);
    }

    const vm = vmPayload.listvirtualmachinesresponse.virtualmachine?.[0] as DetailVirtualMachine | undefined;
    if (!vm) {
      return mockDetailFor(id);
    }

    return mapVirtualMachineToInstanceDetail(
      vm,
      volumesPayload.listvolumesresponse.volume ?? [],
      eventsPayload.listeventsresponse.event ?? [],
    );
  } catch {
    return mockDetailFor(id);
  }
}

export function mapVirtualMachineToInstanceDetail(
  vm: DetailVirtualMachine,
  volumes: CloudStackVolume[] = [],
  events: CloudStackEvent[] = [],
): InstanceDetail {
  const instance = mapVirtualMachineToInstance(vm);

  return {
    instance,
    identity: {
      id: vm.id ?? instance.id,
      name: vm.name ?? instance.name,
      displayName: vm.displayname ?? instance.name,
      internalName: vm.instancename ?? vm.name ?? instance.id,
      account: vm.account ?? instance.account,
      domain: vm.domain ?? "unknown",
      project: vm.project ?? null,
      created: vm.created ?? null,
    },
    placement: {
      zone: vm.zonename ?? instance.zone,
      pod: vm.podname ?? null,
      cluster: vm.clustername ?? null,
      host: vm.hostname ?? null,
      hypervisor: vm.hypervisor ?? null,
    },
    compute: {
      cpu: instance.cpu,
      cpuSpeedMHz: toPositiveNumber(vm.cpuspeed),
      ramGiB: instance.ram,
      offering: instance.offering,
      cpuUsage: instance.cpuUsage,
      memoryUsage: instance.memUsage,
      haEnabled: toBoolean(vm.haenable),
    },
    image: {
      template: instance.template,
      templateDisplayText: vm.templatedisplaytext ?? null,
      iso: vm.isoname ?? vm.iso ?? null,
      serviceOffering: instance.offering,
      diskOffering: vm.diskofferingname ?? null,
    },
    networking: (vm.nic ?? []).map(mapNic),
    storage: volumes.map(mapCloudStackVolumeToVolume),
    activity: events.map(mapCloudStackEventToEvent),
    securityGroups: (vm.securitygroup ?? []).map(mapGroup),
    affinityGroups: (vm.affinitygroup ?? []).map(mapGroup),
    console: {
      rawState: vm.state ?? null,
      hostControlState: vm.hostcontrolstate ?? null,
      externalUrl: readExternalConsoleUrl(vm.details),
    },
  };
}

function mockDetailFor(id: string): InstanceDetail | null {
  const instance = mockInstances.find((candidate) => candidate.id === id);
  if (!instance) {
    return null;
  }

  return {
    instance,
    identity: {
      id: instance.id,
      name: instance.name,
      displayName: instance.name,
      internalName: instance.id,
      account: instance.account,
      domain: "mock",
      project: null,
      created: null,
    },
    placement: {
      zone: instance.zone,
      pod: null,
      cluster: null,
      host: null,
      hypervisor: null,
    },
    compute: {
      cpu: instance.cpu,
      cpuSpeedMHz: null,
      ramGiB: instance.ram,
      offering: instance.offering,
      cpuUsage: instance.cpuUsage,
      memoryUsage: instance.memUsage,
      haEnabled: false,
    },
    image: {
      template: instance.template,
      templateDisplayText: null,
      iso: null,
      serviceOffering: instance.offering,
      diskOffering: null,
    },
    networking: [
      {
        id: `${instance.id}-nic-0`,
        networkId: null,
        name: instance.network,
        ip: instance.ip,
        publicIp: instance.publicIp,
        gateway: null,
        netmask: null,
        macAddress: null,
        type: null,
        isDefault: true,
      },
    ],
    storage: mockVolumes.filter((volume) => volume.attachedTo === instance.name),
    activity: mockEvents.filter((event) => event.target.includes(instance.id) || event.target.includes(instance.name)),
    securityGroups: [],
    affinityGroups: [],
    console: {
      rawState: instance.state,
      hostControlState: null,
      externalUrl: null,
    },
  };
}

function mapNic(nic: NonNullable<DetailVirtualMachine["nic"]>[number], index: number): InstanceNetwork {
  return {
    id: nic.id ?? `${nic.networkid ?? nic.networkname ?? "network"}-${index}`,
    networkId: nic.networkid ?? null,
    name: nic.networkname ?? nic.networkid ?? "unknown",
    ip: nic.ipaddress ?? "-",
    publicIp: nic.publicip ?? null,
    gateway: nic.gateway ?? null,
    netmask: nic.netmask ?? null,
    macAddress: nic.macaddress ?? null,
    type: nic.type ?? nic.traffictype ?? null,
    isDefault: toBoolean(nic.isdefault),
  };
}

function mapGroup(group: { id?: string; name?: string; type?: string }, index: number): InstanceGroup {
  return {
    id: group.id ?? group.name ?? `group-${index}`,
    name: group.name ?? group.id ?? "unknown",
    type: group.type ?? null,
  };
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

function toBoolean(value: boolean | string | undefined): boolean {
  if (typeof value === "boolean") {
    return value;
  }

  return value?.toLowerCase() === "true";
}

function readExternalConsoleUrl(details: DetailVirtualMachine["details"]): string | null {
  if (!details) {
    return null;
  }

  if (Array.isArray(details)) {
    return details.find((detail) => detail.name === "External:console_url")?.value ?? null;
  }

  return details["External:console_url"] ?? null;
}

function buildListVirtualMachinesUrl(id: string, requestHeaders?: Pick<Headers, "get">): string {
  const params = new URLSearchParams({
    id,
    listall: "true",
    details: INSTANCE_DETAILS,
  });
  return `${getRequestOrigin(requestHeaders)}/api/cs/listVirtualMachines?${params.toString()}`;
}

function buildListVolumesUrl(id: string, requestHeaders?: Pick<Headers, "get">): string {
  const params = new URLSearchParams({
    virtualmachineid: id,
    listall: "true",
  });
  return `${getRequestOrigin(requestHeaders)}/api/cs/listVolumes?${params.toString()}`;
}

function buildListEventsUrl(id: string, requestHeaders?: Pick<Headers, "get">): string {
  const params = new URLSearchParams({
    resourceid: id,
    resourcetype: "UserVm",
    page: "1",
    pagesize: "25",
  });
  return `${getRequestOrigin(requestHeaders)}/api/cs/listEvents?${params.toString()}`;
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
