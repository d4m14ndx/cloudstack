import { expect, test, type MockCloudStackBff } from "./fixtures/cloudstack-bff";

test.describe("operational pages render CloudStack BFF data", () => {
  test("billing renders quotaSummary account and project rows", async ({ page, mockCloudStackBff }) => {
    mockBillingQuotaSummaries(mockCloudStackBff);

    await page.goto("/billing");

    await expect(page.getByRole("heading", { name: "Billing quota summary" })).toBeVisible();

    const accountRow = page.getByRole("row", { name: /ops-ledger-account/ });
    await expect(accountRow).toContainText("ROOT/smoke");
    await expect(accountRow).toContainText("Active");
    await expect(accountRow).toContainText("Enabled");
    await expect(accountRow).toContainText("AUD 9876.5432");
    await expect(accountRow).toContainText("AUD 123.4500");
    await expect(accountRow).toContainText("2026-05-01 to 2026-05-31");

    const projectRow = page.getByRole("row", { name: /Project: gpu-quota-smoke/ });
    await expect(projectRow).toContainText("proj-quota-smoke");
    await expect(projectRow).toContainText("ROOT/projects");
    await expect(projectRow).toContainText("Disabled");
    await expect(projectRow).toContainText("Removed");
    await expect(projectRow).toContainText("NZD -12.50");
    await expect(projectRow).toContainText("NZD 77.7000");

    const calls = mockCloudStackBff.calls("quotaSummary");
    expect(calls).toHaveLength(1);
    expect(calls[0]?.method).toBe("GET");
    expect(calls[0]?.params.get("listall")).toBe("true");
    expect(calls[0]?.params.get("accountstatetoshow")).toBe("ACTIVE");
    expect(calls[0]?.params.get("page")).toBe("1");
    expect(calls[0]?.params.get("pagesize")).toBe("50");
  });

  test("events renders a distinctive listEvents row", async ({ page, mockCloudStackBff }) => {
    mockCloudStackBff.use("listEvents", {
      listeventsresponse: {
        count: 1,
        event: [
          {
            id: "event-smoke-ops-42",
            username: "smoke.operator",
            type: "VM.MIGRATE",
            level: "ERROR",
            description: "Migrated smoke VM to host-canary-7",
            resourcetype: "VirtualMachine",
            resourcename: "ops-e2e-vm",
            created: "2026-05-21T09:15:00+0000",
          },
        ],
      },
    });

    await page.goto("/events");

    await expect(page.getByRole("heading", { name: "Events" })).toBeVisible();
    const eventRow = page.getByRole("row", { name: /VM\.MIGRATE/ });
    await expect(eventRow).toContainText("2026-05-21T09:15:00+0000");
    await expect(eventRow).toContainText("Error");
    await expect(eventRow).toContainText("ops-e2e-vm (VirtualMachine)");
    await expect(eventRow).toContainText("smoke.operator");
    await expect(eventRow).toContainText("Migrated smoke VM to host-canary-7");

    const calls = mockCloudStackBff.calls("listEvents");
    expect(calls).toHaveLength(1);
    expect(calls[0]?.method).toBe("GET");
    expect(calls[0]?.params.get("page")).toBe("1");
    expect(calls[0]?.params.get("pagesize")).toBe("50");
  });

  test("kubernetes renders a distinctive listKubernetesClusters row", async ({ page, mockCloudStackBff }) => {
    mockCloudStackBff.use("listKubernetesClusters", {
      listkubernetesclustersresponse: {
        count: 1,
        kubernetescluster: [
          {
            id: "k8s-smoke-ops",
            name: "smoke-ops-cluster",
            kubernetesversionname: "1.31.2",
            zonename: "perth-1",
            account: "ops-platform",
            controlnodes: 1,
            size: 3,
            etcdnodes: 1,
            state: "Upgrading",
            endpoint: "https://smoke-k8s.example.test",
          },
        ],
      },
    });

    await page.goto("/kubernetes");

    await expect(page.getByRole("heading", { name: "Kubernetes" })).toBeVisible();
    const clusterRow = page.getByRole("row", { name: /smoke-ops-cluster/ });
    await expect(clusterRow).toContainText("k8s-smoke-ops");
    await expect(clusterRow).toContainText("1.31.2");
    await expect(clusterRow).toContainText("perth-1");
    await expect(clusterRow).toContainText("ops-platform");
    await expect(clusterRow).toContainText("5");
    await expect(clusterRow).toContainText("Updating");
    await expect(clusterRow).toContainText("https://smoke-k8s.example.test");

    const calls = mockCloudStackBff.calls("listKubernetesClusters");
    expect(calls).toHaveLength(1);
    expect(calls[0]?.method).toBe("GET");
    expect(calls[0]?.params.get("listall")).toBe("true");
  });
});

function mockBillingQuotaSummaries(mockCloudStackBff: MockCloudStackBff): void {
  mockCloudStackBff.use("quotaSummary", {
    quotasummaryresponse: {
      count: 2,
      summary: [
        {
          accountid: "acc-quota-smoke",
          account: "ops-ledger-account",
          domain: "ROOT/smoke",
          balance: "9876.5432",
          state: "ACTIVE",
          quota: "123.4500",
          startdate: "2026-05-01",
          enddate: "2026-05-31",
          currency: "AUD",
          quotaenabled: "true",
        },
        {
          projectid: "proj-quota-smoke",
          projectname: "gpu-quota-smoke",
          account: "engineering",
          domain: "ROOT/projects",
          balance: "-12.50",
          state: "DISABLED",
          quota: "77.7000",
          startdate: "2026-04-01",
          enddate: "2026-04-30",
          currency: "NZD",
          quotaenabled: "Enabled",
          projectremoved: "true",
        },
      ],
    },
  });
}
