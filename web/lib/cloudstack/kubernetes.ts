import { mockKubernetesClusters, type KubernetesCluster } from "../mock-data.ts";
import { mapCloudStackEventToEvent, type CloudStackEvent, type ListEventsResponse } from "./events.ts";

export type CloudStackKubernetesCluster = {
  id?: string;
  name?: string;
  description?: string;
  kubernetesversionname?: string;
  kubernetesversionid?: string;
  zoneid?: string;
  zonename?: string;
  account?: string;
  domain?: string;
  project?: string;
  created?: string;
  type?: string;
  controlnodes?: number | string;
  masternodes?: number | string;
  size?: number | string;
  etcdnodes?: number | string;
  minsize?: number | string;
  maxsize?: number | string;
  autoscalingenabled?: boolean | string;
  networkid?: string;
  networkname?: string;
  state?: string;
  endpoint?: string;
  consoleendpoint?: string;
  ipaddress?: string;
  cniplugin?: string;
  cni?: string;
  csiplugin?: string;
  csi?: string;
  serviceofferingid?: string;
  serviceofferingname?: string;
  templateid?: string;
  templatename?: string;
  sshkeypair?: string;
  sshkeypairname?: string;
  virtualmachines?: CloudStackKubernetesNode[];
};

export type ListKubernetesClustersResponse = {
  listkubernetesclustersresponse?: {
    count?: number | string;
    kubernetescluster?: CloudStackKubernetesCluster[];
  };
};

export type CloudStackKubernetesNode = {
  id?: string;
  name?: string;
  displayname?: string;
  state?: string;
  ipaddress?: string;
  publicip?: string;
  zonename?: string;
  account?: string;
  isetcdnode?: boolean | string;
  isexternalnode?: boolean | string;
};

export type CloudStackKubernetesSupportedVersion = {
  id?: string;
  name?: string;
  semanticversion?: string;
  kubernetesversion?: string;
};

export type ListKubernetesSupportedVersionsResponse = {
  listkubernetessupportedversionsresponse?: {
    count?: number | string;
    kubernetesversion?: CloudStackKubernetesSupportedVersion[];
  };
};

export type KubernetesNodeRole = "control" | "worker" | "etcd" | "external";

export type KubernetesClusterDetail = {
  cluster: KubernetesCluster;
  summary: {
    id: string;
    name: string;
    description: string | null;
    state: KubernetesCluster["state"];
  };
  identity: {
    account: string;
    domain: string;
    project: string | null;
    created: string | null;
    type: string;
  };
  placement: {
    zone: string;
    zoneId: string | null;
  };
  version: {
    name: string;
    kubernetesVersionId: string | null;
    semanticVersion: string | null;
  };
  nodePools: {
    control: number;
    worker: number;
    etcd: number;
    external: number;
    total: number;
  };
  autoscaling: {
    enabled: boolean;
    min: number | null;
    max: number | null;
  };
  networking: {
    networkId: string | null;
    networkName: string | null;
    endpoint: string;
    consoleEndpoint: string | null;
    cni: string | null;
    csi: string | null;
  };
  offerings: {
    serviceOfferingId: string | null;
    serviceOfferingName: string | null;
    templateId: string | null;
    templateName: string | null;
    sshKeyPair: string | null;
  };
  nodes: KubernetesClusterNode[];
  activity: ReturnType<typeof mapCloudStackEventToEvent>[];
};

export type KubernetesClusterNode = {
  id: string;
  name: string;
  role: KubernetesNodeRole;
  state: string;
  ip: string;
  publicIp: string | null;
  zone: string | null;
  account: string | null;
};

type FetchOptions = {
  fetchImpl?: typeof fetch;
  requestHeaders?: Pick<Headers, "get">;
};

export async function getKubernetesClustersFromBff({
  fetchImpl = fetch,
  requestHeaders,
}: FetchOptions = {}): Promise<KubernetesCluster[]> {
  if (process.env.NEXT_PUBLIC_APP_ENV === "mock" || !process.env.CS_URL) {
    return mockKubernetesClusters;
  }

  try {
    const response = await fetchImpl(buildListKubernetesClustersUrl(requestHeaders), {
      method: "GET",
      cache: "no-store",
      headers: buildForwardedHeaders(requestHeaders),
    });

    if (!response.ok) {
      return mockKubernetesClusters;
    }

    const payload = (await response.json()) as ListKubernetesClustersResponse;
    if (!payload.listkubernetesclustersresponse) {
      return mockKubernetesClusters;
    }

    return kubernetesClustersFromListKubernetesClustersResponse(payload);
  } catch {
    return mockKubernetesClusters;
  }
}

export async function getKubernetesClusterDetailFromBff(
  id: string,
  { fetchImpl = fetch, requestHeaders }: FetchOptions = {},
): Promise<KubernetesClusterDetail | null> {
  if (process.env.NEXT_PUBLIC_APP_ENV === "mock" || !process.env.CS_URL) {
    return mockDetailFor(id);
  }

  try {
    const clusterResponse = await fetchImpl(buildListKubernetesClustersUrl(requestHeaders, id), {
      method: "GET",
      cache: "no-store",
      headers: buildForwardedHeaders(requestHeaders),
    });

    if (!clusterResponse.ok) {
      return mockDetailFor(id);
    }

    const clusterPayload = (await clusterResponse.json()) as ListKubernetesClustersResponse;
    if (!clusterPayload.listkubernetesclustersresponse) {
      return mockDetailFor(id);
    }

    const cluster = clusterPayload.listkubernetesclustersresponse.kubernetescluster?.[0];
    if (!cluster) {
      return mockDetailFor(id);
    }

    const [versions, events] = await Promise.all([
      cluster.kubernetesversionid
        ? fetchSupportedVersions(fetchImpl, cluster.kubernetesversionid, requestHeaders)
        : Promise.resolve([]),
      fetchKubernetesEvents(fetchImpl, id, requestHeaders),
    ]);

    return mapCloudStackKubernetesClusterToKubernetesClusterDetail(cluster, { kubernetesversion: versions }, events);
  } catch {
    return mockDetailFor(id);
  }
}

export function kubernetesClustersFromListKubernetesClustersResponse(
  response: ListKubernetesClustersResponse,
): KubernetesCluster[] {
  return (response.listkubernetesclustersresponse?.kubernetescluster ?? []).map(
    mapCloudStackKubernetesClusterToKubernetesCluster,
  );
}

export function mapCloudStackKubernetesClusterToKubernetesCluster(
  cluster: CloudStackKubernetesCluster,
): KubernetesCluster {
  return {
    id: cluster.id ?? cluster.name ?? "unknown",
    name: cluster.name ?? cluster.id ?? "unnamed-cluster",
    version: cluster.kubernetesversionname ?? cluster.kubernetesversionid ?? "unknown",
    zone: cluster.zonename ?? "unknown",
    account: cluster.account ?? cluster.project ?? "unknown",
    nodes: readClusterNodeCount(cluster),
    state: mapCloudStackKubernetesClusterState(cluster.state),
    endpoint: cluster.endpoint ?? cluster.consoleendpoint ?? cluster.ipaddress ?? "-",
  };
}

export function mapCloudStackKubernetesClusterToKubernetesClusterDetail(
  cluster: CloudStackKubernetesCluster,
  versionsResponse: { kubernetesversion?: CloudStackKubernetesSupportedVersion[] } = {},
  events: CloudStackEvent[] = [],
): KubernetesClusterDetail {
  const mappedCluster = mapCloudStackKubernetesClusterToKubernetesCluster(cluster);
  const version = versionsResponse.kubernetesversion?.find((candidate) => candidate.id === cluster.kubernetesversionid);
  const nodePools = readClusterNodePools(cluster);

  return {
    cluster: mappedCluster,
    summary: {
      id: mappedCluster.id,
      name: mappedCluster.name,
      description: cluster.description ?? null,
      state: mappedCluster.state,
    },
    identity: {
      account: cluster.account ?? cluster.project ?? "unknown",
      domain: cluster.domain ?? "unknown",
      project: cluster.project ?? null,
      created: cluster.created ?? null,
      type: cluster.type ?? "unknown",
    },
    placement: {
      zone: cluster.zonename ?? mappedCluster.zone,
      zoneId: cluster.zoneid ?? null,
    },
    version: {
      name: cluster.kubernetesversionname ?? version?.name ?? cluster.kubernetesversionid ?? "unknown",
      kubernetesVersionId: cluster.kubernetesversionid ?? null,
      semanticVersion: version?.semanticversion ?? version?.kubernetesversion ?? null,
    },
    nodePools,
    autoscaling: {
      enabled: readBoolean(cluster.autoscalingenabled),
      min: readPositiveInteger(cluster.minsize),
      max: readPositiveInteger(cluster.maxsize),
    },
    networking: {
      networkId: cluster.networkid ?? null,
      networkName: cluster.networkname ?? null,
      endpoint: cluster.endpoint ?? cluster.consoleendpoint ?? cluster.ipaddress ?? "-",
      consoleEndpoint: cluster.consoleendpoint ?? null,
      cni: cluster.cniplugin ?? cluster.cni ?? null,
      csi: cluster.csiplugin ?? cluster.csi ?? null,
    },
    offerings: {
      serviceOfferingId: cluster.serviceofferingid ?? null,
      serviceOfferingName: cluster.serviceofferingname ?? null,
      templateId: cluster.templateid ?? null,
      templateName: cluster.templatename ?? null,
      sshKeyPair: cluster.sshkeypair ?? cluster.sshkeypairname ?? null,
    },
    nodes: (cluster.virtualmachines ?? []).map(mapKubernetesNode),
    activity: events.map(mapCloudStackEventToEvent),
  };
}

export function mapCloudStackKubernetesClusterState(
  state: string | undefined,
): KubernetesCluster["state"] {
  const normalized = state?.toLowerCase().replace(/[\s_-]/g, "") ?? "";

  switch (normalized) {
    case "running":
    case "started":
      return "running";
    case "creating":
    case "starting":
    case "scaling":
    case "upgrading":
    case "stopping":
    case "stopped":
    case "updating":
      return "updating";
    case "error":
    case "failed":
    case "alert":
    case "destroyed":
    case "expunging":
    case "runningwitherrors":
    case "stoppedwitherrors":
      return "degraded";
    default:
      return "degraded";
  }
}

function readClusterNodeCount(cluster: CloudStackKubernetesCluster): number {
  const controlNodes = readPositiveInteger(cluster.controlnodes ?? cluster.masternodes);
  const workerNodes = readPositiveInteger(cluster.size);
  const etcdNodes = readPositiveInteger(cluster.etcdnodes);

  if (controlNodes !== null || workerNodes !== null || etcdNodes !== null) {
    return (controlNodes ?? 0) + (workerNodes ?? 0) + (etcdNodes ?? 0);
  }

  return cluster.virtualmachines?.length ?? 0;
}

function readClusterNodePools(cluster: CloudStackKubernetesCluster): KubernetesClusterDetail["nodePools"] {
  const control = readPositiveInteger(cluster.controlnodes ?? cluster.masternodes);
  const worker = readPositiveInteger(cluster.size);
  const etcd = readPositiveInteger(cluster.etcdnodes);
  const explicitTotal = [control, worker, etcd].some((count) => count !== null)
    ? (control ?? 0) + (worker ?? 0) + (etcd ?? 0)
    : null;
  const vmRoles = (cluster.virtualmachines ?? []).map(classifyKubernetesNodeRole);

  return {
    control: control ?? vmRoles.filter((role) => role === "control").length,
    worker: worker ?? vmRoles.filter((role) => role === "worker").length,
    etcd: etcd ?? vmRoles.filter((role) => role === "etcd").length,
    external: vmRoles.filter((role) => role === "external").length,
    total: explicitTotal ?? vmRoles.length,
  };
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

function readBoolean(value: boolean | string | undefined): boolean {
  if (typeof value === "boolean") {
    return value;
  }

  return value?.toLowerCase() === "true";
}

function mapKubernetesNode(node: CloudStackKubernetesNode, index: number): KubernetesClusterNode {
  return {
    id: node.id ?? node.name ?? `node-${index}`,
    name: node.displayname ?? node.name ?? node.id ?? `node-${index + 1}`,
    role: classifyKubernetesNodeRole(node),
    state: node.state ?? "unknown",
    ip: node.ipaddress ?? "-",
    publicIp: node.publicip ?? null,
    zone: node.zonename ?? null,
    account: node.account ?? null,
  };
}

function classifyKubernetesNodeRole(node: CloudStackKubernetesNode): KubernetesNodeRole {
  if (readBoolean(node.isetcdnode)) {
    return "etcd";
  }

  if (readBoolean(node.isexternalnode)) {
    return "external";
  }

  const name = `${node.name ?? ""} ${node.displayname ?? ""}`.toLowerCase();
  if (/(^|[-_\s])(control|master)([-_\s]|$)/.test(name)) {
    return "control";
  }

  return "worker";
}

async function fetchSupportedVersions(
  fetchImpl: typeof fetch,
  id: string,
  requestHeaders?: Pick<Headers, "get">,
): Promise<CloudStackKubernetesSupportedVersion[]> {
  try {
    const response = await fetchImpl(buildListKubernetesSupportedVersionsUrl(id, requestHeaders), {
      method: "GET",
      cache: "no-store",
      headers: buildForwardedHeaders(requestHeaders),
    });

    if (!response.ok) {
      return [];
    }

    const payload = (await response.json()) as ListKubernetesSupportedVersionsResponse;
    return payload.listkubernetessupportedversionsresponse?.kubernetesversion ?? [];
  } catch {
    return [];
  }
}

async function fetchKubernetesEvents(
  fetchImpl: typeof fetch,
  id: string,
  requestHeaders?: Pick<Headers, "get">,
): Promise<CloudStackEvent[]> {
  try {
    const response = await fetchImpl(buildListEventsUrl(id, requestHeaders), {
      method: "GET",
      cache: "no-store",
      headers: buildForwardedHeaders(requestHeaders),
    });

    if (!response.ok) {
      return [];
    }

    const payload = (await response.json()) as ListEventsResponse;
    return payload.listeventsresponse?.event ?? [];
  } catch {
    return [];
  }
}

function mockDetailFor(id: string): KubernetesClusterDetail | null {
  const cluster = mockKubernetesClusters.find((candidate) => candidate.id === id);
  if (!cluster) {
    return null;
  }

  return {
    cluster,
    summary: {
      id: cluster.id,
      name: cluster.name,
      description: null,
      state: cluster.state,
    },
    identity: {
      account: cluster.account,
      domain: "mock",
      project: null,
      created: null,
      type: "mock",
    },
    placement: {
      zone: cluster.zone,
      zoneId: null,
    },
    version: {
      name: cluster.version,
      kubernetesVersionId: null,
      semanticVersion: cluster.version,
    },
    nodePools: {
      control: 0,
      worker: cluster.nodes,
      etcd: 0,
      external: 0,
      total: cluster.nodes,
    },
    autoscaling: {
      enabled: false,
      min: null,
      max: null,
    },
    networking: {
      networkId: null,
      networkName: null,
      endpoint: cluster.endpoint,
      consoleEndpoint: null,
      cni: null,
      csi: null,
    },
    offerings: {
      serviceOfferingId: null,
      serviceOfferingName: null,
      templateId: null,
      templateName: null,
      sshKeyPair: null,
    },
    nodes: [],
    activity: [],
  };
}

function buildListKubernetesClustersUrl(requestHeaders?: Pick<Headers, "get">, id?: string): string {
  const params = new URLSearchParams({
    listall: "true",
  });
  if (id) {
    params.set("id", id);
  }
  const origin = getRequestOrigin(requestHeaders);
  return `${origin}/api/cs/listKubernetesClusters?${params.toString()}`;
}

function buildListKubernetesSupportedVersionsUrl(id: string, requestHeaders?: Pick<Headers, "get">): string {
  const params = new URLSearchParams({ id });
  return `${getRequestOrigin(requestHeaders)}/api/cs/listKubernetesSupportedVersions?${params.toString()}`;
}

function buildListEventsUrl(id: string, requestHeaders?: Pick<Headers, "get">): string {
  const params = new URLSearchParams({
    resourceid: id,
    resourcetype: "KubernetesCluster",
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
