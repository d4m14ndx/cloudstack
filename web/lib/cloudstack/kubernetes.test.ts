import assert from "node:assert/strict";
import test from "node:test";

import { mockKubernetesClusters } from "../mock-data.ts";
import {
  getKubernetesClustersFromBff,
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

function restoreEnv(name: string, value: string | undefined): void {
  if (value === undefined) {
    delete process.env[name];
  } else {
    process.env[name] = value;
  }
}
