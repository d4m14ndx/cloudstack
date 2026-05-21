import { expect, test, type MockCloudStackBff } from "./fixtures/cloudstack-bff";

test.describe("network and security smoke coverage", () => {
  test("renders network list/detail and handles public IP actions", async ({ page, mockCloudStackBff }) => {
    installNetworkMocks(mockCloudStackBff);
    page.on("dialog", async (dialog) => {
      if (dialog.type() === "prompt") {
        await dialog.accept("vm-web-01");
        return;
      }
      await dialog.accept();
    });

    await page.goto("/networks");

    await expect(page.getByRole("heading", { name: "Networks" })).toBeVisible();
    await expect(page.getByRole("link", { name: "mgmt" })).toBeVisible();
    await expect(page.getByRole("row", { name: /mgmt.*10\.0\.0\.0\/24.*syd-1.*1.*Running/ })).toBeVisible();

    await page.getByRole("link", { name: "mgmt" }).click();
    await expect(page.getByRole("heading", { name: "mgmt" })).toBeVisible();
    await expect(page.getByText("10.0.0.0/24").first()).toBeVisible();

    await page.getByRole("tab", { name: /Public IPs/ }).click();
    await expect(page.getByRole("cell", { name: "203.0.113.5", exact: true })).toBeVisible();

    await page.getByRole("button", { name: "Acquire IP" }).click();
    await expect(page.getByText("Acquired")).toBeVisible();
    expect(mockCloudStackBff.calls("associateIpAddress").at(-1)?.json).toEqual({ networkid: "n-106" });

    await page.getByRole("button", { name: "Enable static NAT for 203.0.113.5" }).click();
    await expect(page.getByText("Static NAT enabled")).toBeVisible();
    expect(mockCloudStackBff.calls("enableStaticNat").at(-1)?.json).toEqual({
      ipaddressid: "n-106-ip-1",
      networkid: "n-106",
      virtualmachineid: "vm-web-01",
    });
  });

  test("authorizes and revokes security group rules", async ({ page, mockCloudStackBff }) => {
    installSecurityGroupMocks(mockCloudStackBff);

    await page.goto("/security");

    await expect(page.getByRole("heading", { name: "Security groups" })).toBeVisible();
    await expect(page.getByText("default").first()).toBeVisible();
    await expect(page.getByText("203.0.113.0/24").first()).toBeVisible();

    await page.getByLabel("Security group name").fill("api-smoke");
    await page.getByLabel("Security group description").fill("API ingress group");
    await page.getByRole("button", { name: "Create" }).click();
    await expect(page.getByText("Security group created.")).toBeVisible();
    expect(mockCloudStackBff.calls("createSecurityGroup").at(-1)?.json).toEqual({
      account: "platform",
      description: "API ingress group",
      domainid: "d-root",
      name: "api-smoke",
    });

    await page.getByLabel("Rule CIDR").fill("203.0.113.0/24");
    await page.getByLabel("Start port").fill("8443");
    await page.getByLabel("End port").fill("8443");
    await page.getByRole("button", { name: "Add" }).click();
    await expect(page.getByText("Rule authorized.")).toBeVisible();
    expect(mockCloudStackBff.calls("authorizeSecurityGroupIngress").at(-1)?.json).toEqual({
      account: "platform",
      cidrlist: "203.0.113.0/24",
      domainid: "d-root",
      endport: "8443",
      protocol: "tcp",
      securitygroupid: "sg-001",
      startport: "8443",
    });

    await page.getByRole("button", { name: "Revoke ingress rule sg-001-ingress-ssh" }).click();
    await expect(page.getByText("Rule revoked.")).toBeVisible();
    expect(mockCloudStackBff.calls("revokeSecurityGroupIngress").at(-1)?.json).toEqual({ id: "sg-001-ingress-ssh" });
  });
});

function installNetworkMocks(mockCloudStackBff: MockCloudStackBff): void {
  mockCloudStackBff.use("listVPCs", { listvpcsresponse: { count: 0, vpc: [] } });
  mockCloudStackBff.use("listNetworks", ({ params }) => ({
    listnetworksresponse: {
      count: 1,
      network: [
        {
          id: "net-web",
          name: "frontend-net",
          displaytext: "frontend-net",
          type: "Isolated",
          traffictype: "Guest",
          cidr: "10.44.0.0/24",
          gateway: "10.44.0.1",
          netmask: "255.255.255.0",
          zonename: "syd-1",
          state: "Implemented",
          networkofferingname: "DefaultIsolatedNetworkOfferingWithSourceNatService",
          ...(params.get("id") ? { account: "platform", domain: "ROOT" } : {}),
        },
      ],
    },
  }));
  mockCloudStackBff.use("listVirtualMachines", {
    listvirtualmachinesresponse: {
      count: 2,
      virtualmachine: [
        { id: "vm-web-01", name: "web-01", nic: [{ networkid: "net-web" }] },
        { id: "vm-web-02", name: "web-02", nic: [{ networkid: "net-web" }] },
      ],
    },
  });
  mockCloudStackBff.use("listPublicIpAddresses", {
    listpublicipaddressesresponse: {
      count: 2,
      publicipaddress: [
        {
          id: "ip-free",
          ipaddress: "198.51.100.44",
          state: "Allocated",
          issourcenat: false,
          isstaticnat: false,
          associatednetworkname: "frontend-net",
        },
        {
          id: "ip-static",
          ipaddress: "198.51.100.45",
          state: "Allocated",
          issourcenat: false,
          isstaticnat: true,
          associatednetworkname: "frontend-net",
          virtualmachinename: "web-01",
        },
      ],
    },
  });
  mockCloudStackBff.use("listNetworkACLLists", {
    listnetworkacllistsresponse: { count: 1, networkacllist: [{ id: "acl-web", name: "frontend-acl" }] },
  });
  mockCloudStackBff.use("listNetworkACLs", {
    listnetworkaclsresponse: {
      count: 1,
      networkacl: [
        {
          id: "acl-rule-https",
          aclid: "acl-web",
          number: "100",
          action: "Allow",
          protocol: "tcp",
          startport: "443",
          endport: "443",
          cidrlist: "0.0.0.0/0",
          traffictype: "Ingress",
          state: "Active",
        },
      ],
    },
  });
  mockCloudStackBff.use("listEvents", { listeventsresponse: { count: 0, event: [] } });
  mockCloudStackBff.use("associateIpAddress", { associateipaddressresponse: { jobid: "job-ip-new", id: "ip-new" } });
  mockCloudStackBff.use("enableStaticNat", { enablestaticnatresponse: { success: true } });
  mockCloudStackBff.use("queryAsyncJobResult", ({ params }) => ({
    queryasyncjobresultresponse: {
      jobid: params.get("jobid") ?? "job-ip-new",
      jobstatus: 1,
      jobresult: { publicipaddress: { id: "ip-new", ipaddress: "198.51.100.46" } },
    },
  }));
}

function installSecurityGroupMocks(mockCloudStackBff: MockCloudStackBff): void {
  mockCloudStackBff.use("listSecurityGroups", {
    listsecuritygroupsresponse: {
      count: 1,
      securitygroup: [
        {
          id: "sg-web",
          name: "web-smoke",
          description: "Browser smoke security group",
          account: "platform",
          domainid: "domain-root",
          domain: "ROOT",
          virtualmachinecount: 1,
          ingressrule: [
            {
              ruleid: "rule-ssh",
              protocol: "TCP",
              startport: "22",
              endport: "22",
              cidr: "203.0.113.0/24",
            },
          ],
          egressrule: [],
        },
      ],
    },
  });
  mockCloudStackBff.use("createSecurityGroup", {
    createsecuritygroupresponse: { securitygroup: { id: "sg-api", name: "api-smoke" } },
  });
  mockCloudStackBff.use("authorizeSecurityGroupIngress", {
    authorizesecuritygroupingressresponse: { securitygroup: { id: "sg-web" } },
  });
  mockCloudStackBff.use("revokeSecurityGroupIngress", {
    revokesecuritygroupingressresponse: { success: true },
  });
}
