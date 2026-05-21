import { mockKubernetesClusters, type KubernetesCluster } from "../mock-data.ts";

export type CloudStackKubernetesCluster = {
  id?: string;
  name?: string;
  kubernetesversionname?: string;
  kubernetesversionid?: string;
  zonename?: string;
  account?: string;
  project?: string;
  controlnodes?: number | string;
  masternodes?: number | string;
  size?: number | string;
  etcdnodes?: number | string;
  state?: string;
  endpoint?: string;
  consoleendpoint?: string;
  ipaddress?: string;
  virtualmachines?: unknown[];
};

export type ListKubernetesClustersResponse = {
  listkubernetesclustersresponse?: {
    count?: number | string;
    kubernetescluster?: CloudStackKubernetesCluster[];
  };
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

function buildListKubernetesClustersUrl(requestHeaders?: Pick<Headers, "get">): string {
  const params = new URLSearchParams({
    listall: "true",
  });
  const origin = getRequestOrigin(requestHeaders);
  return `${origin}/api/cs/listKubernetesClusters?${params.toString()}`;
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
