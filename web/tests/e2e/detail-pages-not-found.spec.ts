import { expect, test } from "./fixtures/cloudstack-bff";

test.describe("detail pages handle missing BFF resources", () => {
  test("unknown instance id renders the app not-found route", async ({ page, mockCloudStackBff }) => {
    mockCloudStackBff.use("listVirtualMachines", { listvirtualmachinesresponse: { count: 0, virtualmachine: [] } });
    mockCloudStackBff.use("listVolumes", { listvolumesresponse: { count: 0, volume: [] } });
    mockCloudStackBff.use("listEvents", { listeventsresponse: { count: 0, event: [] } });

    await page.goto("/instances/i-does-not-exist");

    await expect(page.locator("body")).toContainText("This page could not be found");

    const vmCall = mockCloudStackBff.calls("listVirtualMachines").at(-1);
    expect(vmCall?.params.get("id")).toBe("i-does-not-exist");
    expect(vmCall?.params.get("details")).toContain("nics");
    expect(mockCloudStackBff.calls("listVolumes").at(-1)?.params.get("virtualmachineid")).toBe("i-does-not-exist");
    expect(mockCloudStackBff.calls("listEvents").at(-1)?.params.get("resourcetype")).toBe("UserVm");
  });

  test("unknown network id renders the app not-found route", async ({ page, mockCloudStackBff }) => {
    mockCloudStackBff.use("listVPCs", { listvpcsresponse: { count: 0, vpc: [] } });
    mockCloudStackBff.use("listNetworks", { listnetworksresponse: { count: 0, network: [] } });

    await page.goto("/networks/n-does-not-exist");

    await expect(page.locator("body")).toContainText("This page could not be found");

    expect(mockCloudStackBff.calls("listVPCs").at(-1)?.params.get("id")).toBe("n-does-not-exist");
    const networkCall = mockCloudStackBff.calls("listNetworks").at(-1);
    expect(networkCall?.params.get("id")).toBe("n-does-not-exist");
    expect(networkCall?.params.get("type")).toBe("all");
  });

  test("unknown Kubernetes cluster id renders the app not-found route", async ({ page, mockCloudStackBff }) => {
    mockCloudStackBff.use("listKubernetesClusters", {
      listkubernetesclustersresponse: { count: 0, kubernetescluster: [] },
    });

    await page.goto("/kubernetes/k8s-does-not-exist");

    await expect(page.locator("body")).toContainText("This page could not be found");

    const clusterCall = mockCloudStackBff.calls("listKubernetesClusters").at(-1);
    expect(clusterCall?.params.get("id")).toBe("k8s-does-not-exist");
    expect(clusterCall?.params.get("listall")).toBe("true");
    expect(mockCloudStackBff.calls("listKubernetesSupportedVersions")).toHaveLength(0);
  });
});
