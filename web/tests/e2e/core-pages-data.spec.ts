import { expect, test } from "./fixtures/cloudstack-bff";

test.describe("core admin list pages render BFF data", () => {
  test("renders accounts returned by listAccounts", async ({ page, mockCloudStackBff }) => {
    mockCloudStackBff.use("listAccounts", {
      listaccountsresponse: {
        count: 2,
        account: [
          {
            id: "acct-bff-admin",
            name: "bff-admin-lab",
            domainpath: "ROOT/BFF Lab",
            roletype: "Admin",
            usercount: "3",
            vmtotal: "8",
            state: "enabled",
          },
          {
            id: "acct-bff-service",
            name: "bff-metering-svc",
            domain: "ROOT/Services",
            rolename: "service-account",
            user: [{ id: "svc-user" }],
            vmrunning: "4",
            vmstopped: "5",
            state: "locked",
          },
        ],
      },
    });

    await page.goto("/accounts");

    await expect(page.getByRole("heading", { name: "Accounts" })).toBeVisible();
    await expect(page.getByText("2 accounts, 4 users, and 17 instances across your scope")).toBeVisible();
    await expect(page.getByRole("row", { name: /bff-admin-lab.*ROOT\/BFF Lab.*Admin.*3.*8.*Active/ })).toBeVisible();
    await expect(page.getByRole("row", { name: /bff-metering-svc.*ROOT\/Services.*Service.*1.*9.*Disabled/ })).toBeVisible();

    const calls = mockCloudStackBff.calls("listAccounts");
    expect(calls).toHaveLength(1);
    expect(calls[0]?.params.get("listall")).toBe("true");
    expect(calls[0]?.params.get("details")).toBe("min");
  });

  test("renders domains returned by listDomains", async ({ page, mockCloudStackBff }) => {
    mockCloudStackBff.use("listDomains", {
      listdomainsresponse: {
        count: 2,
        domain: [
          {
            id: "domain-bff-root-child",
            name: "bff-root-child",
            path: "ROOT/BFF-Domain",
            parentdomainname: "ROOT",
            level: "1",
            haschild: "true",
            state: "Active",
            vmtotal: "11",
            projecttotal: "2",
            networktotal: "3",
            vpctotal: "1",
          },
          {
            id: "domain-bff-sandbox",
            name: "bff-sandbox",
            path: "ROOT/BFF-Domain/bff-sandbox",
            parentdomainname: "BFF-Domain",
            level: "2",
            haschild: false,
            state: "Disabled",
            projecttotal: "1",
          },
        ],
      },
    });

    await page.goto("/domains");

    await expect(page.getByRole("heading", { name: "Domains" })).toBeVisible();
    await expect(page.getByText("2 domains with 11 instances, 3 projects, and 4 networks")).toBeVisible();
    await expect(page.getByRole("row", { name: /bff-root-child.*ROOT\/BFF-Domain.*ROOT.*1.*11.*2.*4.*Has children.*Active/ })).toBeVisible();
    await expect(page.getByRole("row", { name: /bff-sandbox.*ROOT\/BFF-Domain\/bff-sandbox.*BFF-Domain.*2.*0.*1.*0.*Inactive/ })).toBeVisible();

    const calls = mockCloudStackBff.calls("listDomains");
    expect(calls).toHaveLength(1);
    expect(calls[0]?.params.get("listall")).toBe("true");
    expect(calls[0]?.params.get("details")).toBe("min");
  });

  test("renders infrastructure hosts returned by listHosts", async ({ page, mockCloudStackBff }) => {
    mockCloudStackBff.use("listHosts", {
      listhostsresponse: {
        count: 2,
        host: [
          {
            id: "host-bff-kvm-1",
            name: "bff-kvm-edge-01",
            zonename: "perth-1",
            clustername: "kvm-blue",
            state: "Up",
            resourcestate: "Enabled",
            cpuallocatedpercentage: "37.4%",
            memoryallocatedpercentage: "68.2%",
            virtualmachinecount: "14",
            hypervisor: "KVM",
          },
          {
            id: "host-bff-hyperv-2",
            name: "bff-hyperv-maint-02",
            zonename: "perth-2",
            clustername: "hyperv-gold",
            state: "Up",
            resourcestate: "PrepareForMaintenance",
            cpuallocatedpercentage: "5",
            memoryallocatedpercentage: "12",
            instances: "1",
            hypervisor: "Hyper-V",
          },
        ],
      },
    });

    await page.goto("/infrastructure");

    await expect(page.getByRole("heading", { name: "Infrastructure" })).toBeVisible();
    await expect(page.getByText("2 routing hosts across your scope, 15 instances placed")).toBeVisible();
    await expect(page.getByRole("row", { name: /bff-kvm-edge-01.*host-bff-kvm-1.*Up.*perth-1.*kvm-blue.*KVM.*37%.*68%.*14/ })).toBeVisible();
    await expect(page.getByRole("row", { name: /bff-hyperv-maint-02.*host-bff-hyperv-2.*Maintenance.*perth-2.*hyperv-gold.*Hyper-V.*5%.*12%.*1/ })).toBeVisible();

    const calls = mockCloudStackBff.calls("listHosts");
    expect(calls).toHaveLength(1);
    expect(calls[0]?.params.get("type")).toBe("Routing");
    expect(calls[0]?.params.get("details")).toBe("capacity");
  });
});
