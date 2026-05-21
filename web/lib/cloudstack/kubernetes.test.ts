import assert from "node:assert/strict";
import test from "node:test";

import { mockKubernetesClusters } from "../mock-data.ts";
import {
  getKubernetesClusterDetailFromBff,
  getKubernetesClustersFromBff,
  mapCloudStackKubernetesClusterToKubernetesClusterDetail,
  kubernetesClustersFromListKubernetesClustersResponse,
  mapCloudStackKubernetesClusterToKubernetesCluster,
} from "./kubernetes.ts";

test("mapCloudStackKubernetesClusterToKubernetesCluster maps CloudStack cluster fields into the UI contract", () => {
  const cluster = mapCloudStackKubernetesClusterToKubernetesCluster({
    id: "k8s-1",
    name: "prod-services",
    kubernetesversionname: "1.30.4",
    zonename: "syd-1",
    account: "platform",
    controlnodes: 3,
    size: 4,
    etcdnodes: 3,
    state: "Running",
    endpoint: "https://k8s-prod.example.test",
  });

  assert.deepEqual(cluster, {
    id: "k8s-1",
    name: "prod-services",
    version: "1.30.4",
    zone: "syd-1",
    account: "platform",
    nodes: 10,
    state: "running",
    endpoint: "https://k8s-prod.example.test",
  });
});

test("mapCloudStackKubernetesClusterToKubernetesCluster falls back through minimal CloudStack fields", () => {
  const cluster = mapCloudStackKubernetesClusterToKubernetesCluster({
    id: "k8s-2",
    kubernetesversionid: "kv-1",
    project: "research",
    consoleendpoint: "https://console.example.test",
    state: "Started",
  });

  assert.deepEqual(cluster, {
    id: "k8s-2",
    name: "k8s-2",
    version: "kv-1",
    zone: "unknown",
    account: "research",
    nodes: 0,
    state: "running",
    endpoint: "https://console.example.test",
  });
});

test("mapCloudStackKubernetesClusterToKubernetesCluster normalizes node counts and suspicious states conservatively", () => {
  const scaling = mapCloudStackKubernetesClusterToKubernetesCluster({
    name: "scale-out",
    masternodes: "1",
    size: "2",
    etcdnodes: "3",
    state: "Scaling",
    ipaddress: "203.0.113.10",
  });
  const fromVms = mapCloudStackKubernetesClusterToKubernetesCluster({
    name: "vm-backed",
    state: "RunningWithErrors",
    virtualmachines: [{ id: "vm-1" }, { id: "vm-2" }],
  });
  const unknown = mapCloudStackKubernetesClusterToKubernetesCluster({
    state: "Reconciling",
  });

  assert.equal(scaling.nodes, 6);
  assert.equal(scaling.state, "updating");
  assert.equal(scaling.endpoint, "203.0.113.10");
  assert.equal(fromVms.nodes, 2);
  assert.equal(fromVms.state, "degraded");
  assert.equal(unknown.id, "unknown");
  assert.equal(unknown.name, "unnamed-cluster");
  assert.equal(unknown.state, "degraded");
  assert.equal(unknown.endpoint, "-");
});

test("kubernetesClustersFromListKubernetesClustersResponse maps the CloudStack response envelope", () => {
  const clusters = kubernetesClustersFromListKubernetesClustersResponse({
    listkubernetesclustersresponse: {
      count: 1,
      kubernetescluster: [
        {
          id: "k8s-3",
          name: "api-cluster",
          kubernetesversionname: "1.31.1",
          zonename: "mel-1",
          project: "engineering",
          controlnodes: 1,
          size: 2,
          state: "Upgrading",
        },
      ],
    },
  });

  assert.equal(clusters.length, 1);
  assert.equal(clusters[0]?.name, "api-cluster");
  assert.equal(clusters[0]?.version, "1.31.1");
  assert.equal(clusters[0]?.zone, "mel-1");
  assert.equal(clusters[0]?.account, "engineering");
  assert.equal(clusters[0]?.nodes, 3);
  assert.equal(clusters[0]?.state, "updating");
});

test("getKubernetesClustersFromBff calls the BFF listKubernetesClusters command and forwards cookies", async () => {
  const previousCsUrl = process.env.CS_URL;
  const previousNextAuthUrl = process.env.NEXTAUTH_URL;
  const previousAppEnv = process.env.NEXT_PUBLIC_APP_ENV;
  process.env.CS_URL = "http://cloudstack.local";
  delete process.env.NEXTAUTH_URL;
  delete process.env.NEXT_PUBLIC_APP_ENV;

  let requestedUrl = "";
  let forwardedCookie: string | undefined;
  const fetchImpl: typeof fetch = async (input, init) => {
    requestedUrl = String(input);
    forwardedCookie = (init?.headers as Record<string, string> | undefined)?.cookie;
    return Response.json({
      listkubernetesclustersresponse: {
        kubernetescluster: [{ id: "k8s-4", name: "api-cluster", state: "Running" }],
      },
    });
  };

  try {
    const clusters = await getKubernetesClustersFromBff({
      fetchImpl,
      requestHeaders: new Headers({
        cookie: "cloudstack.session=opaque",
        host: "console.example.test",
        "x-forwarded-proto": "https",
      }),
    });
    const url = new URL(requestedUrl);

    assert.equal(url.origin, "https://console.example.test");
    assert.equal(url.pathname, "/api/cs/listKubernetesClusters");
    assert.equal(url.searchParams.get("listall"), "true");
    assert.equal(forwardedCookie, "cloudstack.session=opaque");
    assert.equal(clusters[0]?.name, "api-cluster");
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
    restoreEnv("NEXTAUTH_URL", previousNextAuthUrl);
    restoreEnv("NEXT_PUBLIC_APP_ENV", previousAppEnv);
  }
});

test("getKubernetesClustersFromBff returns mock clusters when CloudStack is unavailable", async () => {
  const previousCsUrl = process.env.CS_URL;
  process.env.CS_URL = "http://cloudstack.local";

  try {
    const clusters = await getKubernetesClustersFromBff({
      fetchImpl: async () => new Response("{}", { status: 503 }),
    });

    assert.equal(clusters, mockKubernetesClusters);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

test("getKubernetesClustersFromBff returns mock clusters when the envelope is malformed", async () => {
  const previousCsUrl = process.env.CS_URL;
  process.env.CS_URL = "http://cloudstack.local";

  try {
    const clusters = await getKubernetesClustersFromBff({
      fetchImpl: async () => Response.json({ listvirtualmachinesresponse: { virtualmachine: [] } }),
    });

    assert.equal(clusters, mockKubernetesClusters);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

test("getKubernetesClustersFromBff does not call the BFF when CS_URL is absent", async () => {
  const previousCsUrl = process.env.CS_URL;
  delete process.env.CS_URL;
  let called = false;

  try {
    const clusters = await getKubernetesClustersFromBff({
      fetchImpl: async () => {
        called = true;
        return Response.json({});
      },
    });

    assert.equal(called, false);
    assert.equal(clusters, mockKubernetesClusters);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

test("mapCloudStackKubernetesClusterToKubernetesClusterDetail maps a full CloudStack cluster detail response", () => {
  const detail = mapCloudStackKubernetesClusterToKubernetesClusterDetail(
    {
      id: "k8s-10",
      name: "prod-services",
      description: "Production shared services",
      state: "Running",
      account: "platform",
      domain: "ROOT/platform",
      project: "shared",
      created: "2026-05-19T01:02:03+0000",
      type: "CloudManaged",
      zonename: "syd-1",
      zoneid: "zone-1",
      kubernetesversionname: "v1.30.4",
      kubernetesversionid: "kv-1304",
      controlnodes: "3",
      masternodes: "2",
      size: "4",
      etcdnodes: "3",
      minsize: "3",
      maxsize: "9",
      autoscalingenabled: "true",
      networkid: "net-1",
      networkname: "prod-vpc",
      endpoint: "https://api.k8s.example.test",
      consoleendpoint: "https://console.k8s.example.test",
      cniplugin: "Cilium",
      csiplugin: "CloudStack CSI",
      serviceofferingname: "K8s medium",
      serviceofferingid: "so-1",
      templateid: "tmpl-1",
      templatename: "Ubuntu 24.04 Kubernetes",
      sshkeypair: "ops-key",
      virtualmachines: [
        { id: "vm-cp", name: "prod-control-01", state: "Running", ipaddress: "10.1.0.10" },
        { id: "vm-worker", name: "prod-worker-01", state: "Running", ipaddress: "10.1.0.20" },
        { id: "vm-etcd", name: "prod-data-01", isetcdnode: true, state: "Running" },
        { id: "vm-external", name: "external-lb-01", isexternalnode: "true", state: "Stopped" },
        { id: "vm-master", name: "prod-master-02", state: "Running" },
      ],
    },
    {
      kubernetesversion: [{ id: "kv-1304", name: "Kubernetes 1.30.4", semanticversion: "1.30.4" }],
    },
    [
      {
        id: "evt-1",
        type: "KUBERNETES.CLUSTER.SCALE",
        level: "INFO",
        description: "Scaled cluster",
        created: "2026-05-20T00:00:00+0000",
        username: "admin",
        resourceid: "k8s-10",
        resourcename: "prod-services",
        resourcetype: "KubernetesCluster",
      },
    ],
  );

  assert.equal(detail.cluster.id, "k8s-10");
  assert.equal(detail.cluster.nodes, 10);
  assert.equal(detail.summary.description, "Production shared services");
  assert.equal(detail.identity.domain, "ROOT/platform");
  assert.equal(detail.identity.project, "shared");
  assert.equal(detail.identity.type, "CloudManaged");
  assert.equal(detail.placement.zoneId, "zone-1");
  assert.equal(detail.version.semanticVersion, "1.30.4");
  assert.deepEqual(detail.nodePools, { control: 3, worker: 4, etcd: 3, external: 1, total: 10 });
  assert.deepEqual(detail.autoscaling, { enabled: true, min: 3, max: 9 });
  assert.equal(detail.networking.networkName, "prod-vpc");
  assert.equal(detail.networking.cni, "Cilium");
  assert.equal(detail.offerings.serviceOfferingName, "K8s medium");
  assert.equal(detail.offerings.templateName, "Ubuntu 24.04 Kubernetes");
  assert.deepEqual(
    detail.nodes.map((node) => node.role),
    ["control", "worker", "etcd", "external", "control"],
  );
  assert.equal(detail.activity[0]?.action, "KUBERNETES.CLUSTER.SCALE");
});

test("getKubernetesClusterDetailFromBff calls detail and enrichment commands with forwarded cookie and no-store cache", async () => {
  const previousCsUrl = process.env.CS_URL;
  const previousNextAuthUrl = process.env.NEXTAUTH_URL;
  const previousAppEnv = process.env.NEXT_PUBLIC_APP_ENV;
  process.env.CS_URL = "http://cloudstack.local";
  delete process.env.NEXTAUTH_URL;
  delete process.env.NEXT_PUBLIC_APP_ENV;

  const requests: Array<{ url: URL; cookie?: string; cache?: RequestCache }> = [];
  const fetchImpl: typeof fetch = async (input, init) => {
    const url = new URL(String(input));
    requests.push({
      url,
      cookie: (init?.headers as Record<string, string> | undefined)?.cookie,
      cache: init?.cache,
    });

    if (url.pathname.endsWith("/listKubernetesClusters")) {
      return Response.json({
        listkubernetesclustersresponse: {
          kubernetescluster: [
            { id: "k8s-11", name: "detail-cluster", state: "Running", kubernetesversionid: "kv-1" },
          ],
        },
      });
    }

    if (url.pathname.endsWith("/listKubernetesSupportedVersions")) {
      return Response.json({
        listkubernetessupportedversionsresponse: {
          kubernetesversion: [{ id: "kv-1", semanticversion: "1.31.1" }],
        },
      });
    }

    return Response.json({
      listeventsresponse: {
        event: [{ id: "evt-11", type: "KUBERNETES.CLUSTER.CREATE", level: "INFO" }],
      },
    });
  };

  try {
    const detail = await getKubernetesClusterDetailFromBff("k8s-11", {
      fetchImpl,
      requestHeaders: new Headers({
        cookie: "cloudstack.session=opaque",
        host: "console.example.test",
        "x-forwarded-proto": "https",
      }),
    });

    assert.equal(detail?.cluster.name, "detail-cluster");
    assert.equal(requests.length, 3);
    assert.deepEqual(
      requests.map((request) => request.url.pathname),
      [
        "/api/cs/listKubernetesClusters",
        "/api/cs/listKubernetesSupportedVersions",
        "/api/cs/listEvents",
      ],
    );
    assert.equal(requests[0]?.url.searchParams.get("id"), "k8s-11");
    assert.equal(requests[0]?.url.searchParams.get("listall"), "true");
    assert.equal(requests[1]?.url.searchParams.get("id"), "kv-1");
    assert.equal(requests[2]?.url.searchParams.get("resourceid"), "k8s-11");
    assert.equal(requests[2]?.url.searchParams.get("resourcetype"), "KubernetesCluster");
    assert.equal(requests[2]?.url.searchParams.get("page"), "1");
    assert.equal(requests[2]?.url.searchParams.get("pagesize"), "25");
    assert.ok(requests.every((request) => request.cookie === "cloudstack.session=opaque"));
    assert.ok(requests.every((request) => request.cache === "no-store"));
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
    restoreEnv("NEXTAUTH_URL", previousNextAuthUrl);
    restoreEnv("NEXT_PUBLIC_APP_ENV", previousAppEnv);
  }
});

test("getKubernetesClusterDetailFromBff returns main detail when enrichment fails", async () => {
  const previousCsUrl = process.env.CS_URL;
  process.env.CS_URL = "http://cloudstack.local";

  try {
    const detail = await getKubernetesClusterDetailFromBff("k8s-12", {
      fetchImpl: async (input) => {
        const url = new URL(String(input));
        if (url.pathname.endsWith("/listKubernetesClusters")) {
          return Response.json({
            listkubernetesclustersresponse: {
              kubernetescluster: [{ id: "k8s-12", name: "resilient-cluster", state: "Running", kubernetesversionid: "kv-12" }],
            },
          });
        }

        return new Response("unavailable", { status: 503 });
      },
    });

    assert.equal(detail?.cluster.name, "resilient-cluster");
    assert.equal(detail?.version.semanticVersion, null);
    assert.deepEqual(detail?.activity, []);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

test("getKubernetesClusterDetailFromBff derives matching mock detail when CS_URL is absent", async () => {
  const previousCsUrl = process.env.CS_URL;
  delete process.env.CS_URL;
  let called = false;

  try {
    const detail = await getKubernetesClusterDetailFromBff(mockKubernetesClusters[0]!.id, {
      fetchImpl: async () => {
        called = true;
        return Response.json({});
      },
    });

    assert.equal(called, false);
    assert.equal(detail?.cluster, mockKubernetesClusters[0]);
    assert.equal(detail?.nodePools.total, mockKubernetesClusters[0]!.nodes);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

test("getKubernetesClusterDetailFromBff falls back to matching mock detail on non-OK, malformed, and thrown responses", async () => {
  const previousCsUrl = process.env.CS_URL;
  const id = mockKubernetesClusters[0]!.id;
  process.env.CS_URL = "http://cloudstack.local";

  try {
    const nonOk = await getKubernetesClusterDetailFromBff(id, {
      fetchImpl: async () => new Response("unavailable", { status: 503 }),
    });
    const malformed = await getKubernetesClusterDetailFromBff(id, {
      fetchImpl: async () => Response.json({ listvirtualmachinesresponse: { virtualmachine: [] } }),
    });
    const thrown = await getKubernetesClusterDetailFromBff(id, {
      fetchImpl: async () => {
        throw new Error("network down");
      },
    });

    assert.equal(nonOk?.cluster, mockKubernetesClusters[0]);
    assert.equal(malformed?.cluster, mockKubernetesClusters[0]);
    assert.equal(thrown?.cluster, mockKubernetesClusters[0]);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

test("getKubernetesClusterDetailFromBff returns null for an unknown cluster id", async () => {
  const previousCsUrl = process.env.CS_URL;
  process.env.CS_URL = "http://cloudstack.local";

  try {
    const detail = await getKubernetesClusterDetailFromBff("missing-k8s", {
      fetchImpl: async () => Response.json({ listkubernetesclustersresponse: { kubernetescluster: [] } }),
    });

    assert.equal(detail, null);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

function restoreEnv(name: string, value: string | undefined): void {
  if (value === undefined) {
    delete process.env[name];
  } else {
    process.env[name] = value;
  }
}
