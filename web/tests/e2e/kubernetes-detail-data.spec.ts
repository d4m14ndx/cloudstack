import { expect, test, type MockCloudStackBff } from "./fixtures/cloudstack-bff";

test.describe("Kubernetes detail page renders CloudStack BFF data", () => {
  test("renders cluster overview, node inventory, networking, scaling, and activity from BFF commands", async ({
    page,
    mockCloudStackBff,
  }) => {
    installKubernetesDetailMocks(mockCloudStackBff);

    await page.goto("/kubernetes/k8s-saffron-detail");

    await expect(page.getByRole("heading", { name: "saffron-ledger-prod" })).toBeVisible();
    await expect(page.getByText("finance-platform · perth-secure-3 · k8s-saffron-detail")).toBeVisible();
    await expect(page.getByText("1.32.7-cs").first()).toBeVisible();
    await expect(page.getByText("CloudManaged").first()).toBeVisible();
    await expect(page.getByText("2 control, 5 worker, 3 etcd")).toBeVisible();
    await expect(page.getByText("saffron-control-plane").first()).toBeVisible();
    await expect(page.getByText("4 min / 12 max")).toBeVisible();

    const overview = page.getByRole("tabpanel", { name: "Overview" });
    await expect(overview).toContainText("Saffron ledger production control plane");
    await expect(overview).toContainText("ROOT/Finance/Saffron");
    await expect(overview).toContainText("ledger-modernisation");
    await expect(overview).toContainText("2026-05-19T12:34:56+0000");
    await expect(overview).toContainText("zone-saffron-3");
    await expect(overview).toContainText("Kubernetes 1.32 channel");
    await expect(overview).toContainText("kv-saffron-132");
    await expect(overview).toContainText("K8s Compute X7");
    await expect(overview).toContainText("Ubuntu 24.04 K8s Hardened");
    await expect(overview).toContainText("saffron-breakglass-key");

    await page.getByRole("tab", { name: /Nodes/ }).click();
    const nodes = page.getByRole("tabpanel", { name: /Nodes/ });
    await expect(nodes.getByRole("row", { name: /saffron-control-01.*Control.*Running.*10\.44\.0\.11.*198\.51\.100\.11.*perth-secure-3.*finance-platform/ })).toBeVisible();
    await expect(nodes.getByRole("row", { name: /saffron-worker-blue.*Worker.*Running.*10\.44\.1\.21.*-.*perth-secure-3.*ledger-modernisation/ })).toBeVisible();
    await expect(nodes.getByRole("row", { name: /saffron-etcd-a.*Etcd.*Running.*10\.44\.2\.31.*198\.51\.100\.31.*perth-secure-3.*finance-platform/ })).toBeVisible();
    await expect(nodes.getByRole("row", { name: /saffron-edge-ext-01.*External.*Stopped.*10\.44\.9\.41.*203\.0\.113\.41.*perth-edge-1.*network-edge/ })).toBeVisible();

    await page.getByRole("tab", { name: /Networking/ }).click();
    const networking = page.getByRole("tabpanel", { name: /Networking/ });
    await expect(networking).toContainText("net-saffron-private");
    await expect(networking).toContainText("https://saffron-api.k8s.example.test:6443");
    await expect(networking).toContainText("https://saffron-console.example.test");
    await expect(networking).toContainText("Cilium Enterprise");
    await expect(networking).toContainText("CloudStack CSI");

    await page.getByRole("tab", { name: /Scaling/ }).click();
    const scaling = page.getByRole("tabpanel", { name: /Scaling/ });
    await expect(scaling).toContainText("Yes");
    await expect(scaling).toContainText("12");
    await expect(scaling).toContainText("External");
    await expect(scaling).toContainText("1");

    await page.getByRole("tab", { name: /Activity/ }).click();
    const activity = page.getByRole("tabpanel", { name: /Activity/ });
    await expect(activity.getByRole("row", { name: /KUBERNETES\.CLUSTER\.UPGRADE/ })).toContainText("2026-05-20T04:25:00+0000");
    await expect(activity.getByRole("row", { name: /KUBERNETES\.CLUSTER\.UPGRADE/ })).toContainText("Warn");
    await expect(activity.getByRole("row", { name: /KUBERNETES\.CLUSTER\.UPGRADE/ })).toContainText("saffron-ledger-prod (KubernetesCluster)");
    await expect(activity.getByRole("row", { name: /KUBERNETES\.CLUSTER\.UPGRADE/ })).toContainText("k8s.release.manager");
    await expect(activity.getByRole("row", { name: /KUBERNETES\.CLUSTER\.UPGRADE/ })).toContainText("Canary control plane upgraded to Saffron 1.32");

    const clusterCall = mockCloudStackBff.calls("listKubernetesClusters").at(-1);
    expect(clusterCall?.method).toBe("GET");
    expect(clusterCall?.params.get("id")).toBe("k8s-saffron-detail");
    expect(clusterCall?.params.get("listall")).toBe("true");

    const versionCall = mockCloudStackBff.calls("listKubernetesSupportedVersions").at(-1);
    expect(versionCall?.method).toBe("GET");
    expect(versionCall?.params.get("id")).toBe("kv-saffron-132");

    const eventsCall = mockCloudStackBff.calls("listEvents").at(-1);
    expect(eventsCall?.method).toBe("GET");
    expect(eventsCall?.params.get("resourceid")).toBe("k8s-saffron-detail");
    expect(eventsCall?.params.get("resourcetype")).toBe("KubernetesCluster");
    expect(eventsCall?.params.get("page")).toBe("1");
    expect(eventsCall?.params.get("pagesize")).toBe("25");
  });
});

function installKubernetesDetailMocks(mockCloudStackBff: MockCloudStackBff): void {
  mockCloudStackBff.use("listKubernetesClusters", {
    listkubernetesclustersresponse: {
      count: 1,
      kubernetescluster: [
        {
          id: "k8s-saffron-detail",
          name: "saffron-ledger-prod",
          description: "Saffron ledger production control plane",
          state: "Running",
          account: "finance-platform",
          domain: "ROOT/Finance/Saffron",
          project: "ledger-modernisation",
          created: "2026-05-19T12:34:56+0000",
          type: "CloudManaged",
          zonename: "perth-secure-3",
          zoneid: "zone-saffron-3",
          kubernetesversionname: "Kubernetes 1.32 channel",
          kubernetesversionid: "kv-saffron-132",
          controlnodes: "2",
          size: "5",
          etcdnodes: "3",
          minsize: "4",
          maxsize: "12",
          autoscalingenabled: "true",
          networkid: "net-saffron-private",
          networkname: "saffron-control-plane",
          endpoint: "https://saffron-api.k8s.example.test:6443",
          consoleendpoint: "https://saffron-console.example.test",
          cniplugin: "Cilium Enterprise",
          csiplugin: "CloudStack CSI",
          serviceofferingid: "so-saffron-x7",
          serviceofferingname: "K8s Compute X7",
          templateid: "tmpl-k8s-hardened-2404",
          templatename: "Ubuntu 24.04 K8s Hardened",
          sshkeypair: "saffron-breakglass-key",
          virtualmachines: [
            {
              id: "vm-saffron-control-01",
              name: "saffron-control-01",
              displayname: "saffron-control-01",
              state: "Running",
              ipaddress: "10.44.0.11",
              publicip: "198.51.100.11",
              zonename: "perth-secure-3",
              account: "finance-platform",
            },
            {
              id: "vm-saffron-worker-blue",
              name: "saffron-worker-blue",
              displayname: "saffron-worker-blue",
              state: "Running",
              ipaddress: "10.44.1.21",
              zonename: "perth-secure-3",
              account: "ledger-modernisation",
            },
            {
              id: "vm-saffron-etcd-a",
              name: "saffron-etcd-a",
              displayname: "saffron-etcd-a",
              isetcdnode: "true",
              state: "Running",
              ipaddress: "10.44.2.31",
              publicip: "198.51.100.31",
              zonename: "perth-secure-3",
              account: "finance-platform",
            },
            {
              id: "vm-saffron-edge-ext-01",
              name: "saffron-edge-ext-01",
              displayname: "saffron-edge-ext-01",
              isexternalnode: true,
              state: "Stopped",
              ipaddress: "10.44.9.41",
              publicip: "203.0.113.41",
              zonename: "perth-edge-1",
              account: "network-edge",
            },
          ],
        },
      ],
    },
  });
  mockCloudStackBff.use("listKubernetesSupportedVersions", {
    listkubernetessupportedversionsresponse: {
      count: 1,
      kubernetesversion: [
        {
          id: "kv-saffron-132",
          name: "Kubernetes 1.32 channel",
          semanticversion: "1.32.7-cs",
        },
      ],
    },
  });
  mockCloudStackBff.use("listEvents", {
    listeventsresponse: {
      count: 1,
      event: [
        {
          id: "evt-saffron-upgrade",
          username: "k8s.release.manager",
          type: "KUBERNETES.CLUSTER.UPGRADE",
          level: "WARN",
          resourceid: "k8s-saffron-detail",
          resourcetype: "KubernetesCluster",
          resourcename: "saffron-ledger-prod",
          created: "2026-05-20T04:25:00+0000",
          description: "Canary control plane upgraded to Saffron 1.32",
        },
      ],
    },
  });
}
