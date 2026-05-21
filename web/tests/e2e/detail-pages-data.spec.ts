import { expect, test, type MockCloudStackBff } from "./fixtures/cloudstack-bff";

test.describe("detail pages render CloudStack BFF data", () => {
  test("instance detail tabs render BFF-derived identity, networking, storage, and activity", async ({
    page,
    mockCloudStackBff,
  }) => {
    installInstanceDetailMocks(mockCloudStackBff);

    await page.goto("/instances/i-9f3a2b");

    await expect(page.getByRole("heading", { name: "ledger-api-blue" })).toBeVisible();
    await expect(page.getByText("qa-payments · perth-east · i-9f3a2b")).toBeVisible();
    await expect(page.getByText("ledger-api-internal")).toBeVisible();
    await expect(page.getByText("finance-lab")).toBeVisible();
    await expect(page.getByText("perth-pod-7")).toBeVisible();
    await expect(page.getByText("amd-cluster-42")).toBeVisible();
    await expect(page.getByText("Ubuntu 24.04 hardened")).toBeVisible();
    await expect(page.getByText("PCI root NVMe")).toBeVisible();
    await expect(page.getByText("sg-ledger")).toBeVisible();

    await page.getByRole("tab", { name: /Networking/ }).click();
    await expect(page.getByRole("tabpanel", { name: /Networking/ })).toContainText("seg-regulated-42");
    await expect(page.getByRole("tabpanel", { name: /Networking/ })).toContainText("10.42.7.19");
    await expect(page.getByRole("tabpanel", { name: /Networking/ })).toContainText("203.0.113.142");
    await expect(page.getByRole("tabpanel", { name: /Networking/ })).toContainText("02:00:5e:10:42:19");

    await page.getByRole("tab", { name: /Storage/ }).click();
    await expect(page.getByRole("tabpanel", { name: /Storage/ })).toContainText("ledger-blue-root");
    await expect(page.getByRole("tabpanel", { name: /Storage/ })).toContainText("128 GiB");
    await expect(page.getByRole("tabpanel", { name: /Storage/ })).toContainText("NVMe");

    await page.getByRole("tab", { name: /Activity/ }).click();
    await expect(page.getByRole("tabpanel", { name: /Activity/ })).toContainText("VM.MIGRATE");
    await expect(page.getByRole("tabpanel", { name: /Activity/ })).toContainText("ledger-api-blue (UserVm)");
    await expect(page.getByRole("tabpanel", { name: /Activity/ })).toContainText("Live migrated for host maintenance");

    const vmCall = mockCloudStackBff.calls("listVirtualMachines").at(-1);
    expect(vmCall?.params.get("id")).toBe("i-9f3a2b");
    expect(vmCall?.params.get("details")).toContain("nics");
    expect(mockCloudStackBff.calls("listVolumes").at(-1)?.params.get("virtualmachineid")).toBe("i-9f3a2b");
    expect(mockCloudStackBff.calls("listEvents").at(-1)?.params.get("resourcetype")).toBe("UserVm");
  });

  test("network detail tabs render BFF-derived overview, public IP, ACL, and activity data", async ({
    page,
    mockCloudStackBff,
  }) => {
    installNetworkDetailMocks(mockCloudStackBff);

    await page.goto("/networks/n-106");

    const overview = page.getByRole("tabpanel", { name: "Overview" });

    await expect(page.getByRole("heading", { name: "regulated-egress" })).toBeVisible();
    await expect(page.getByText("Isolated · perth-zone-a · n-106")).toBeVisible();
    await expect(page.getByText("10.106.0.0/24").first()).toBeVisible();
    await expect(page.getByText("10.106.0.1").first()).toBeVisible();
    await expect(overview).toContainText("pci.internal");
    await expect(overview).toContainText("platform-payments");
    await expect(overview).toContainText("Payment Shield");
    await expect(overview).toContainText("Tiered isolated offering - no source NAT");

    await page.getByRole("tab", { name: /Public IPs/ }).click();
    await expect(page.getByRole("tabpanel", { name: /Public IPs/ })).toContainText("198.51.100.77");
    await expect(page.getByRole("tabpanel", { name: /Public IPs/ })).toContainText("Source NAT");
    await expect(page.getByRole("tabpanel", { name: /Public IPs/ })).toContainText("ledger-api-blue");
    await expect(page.getByRole("tabpanel", { name: /Public IPs/ })).toContainText("198.51.100.78");
    await expect(page.getByRole("tabpanel", { name: /Public IPs/ })).toContainText("Static NAT");

    await page.getByRole("tab", { name: /ACLs/ }).click();
    await expect(page.getByRole("tabpanel", { name: /ACLs/ })).toContainText("pci-acl-list");
    await expect(page.getByRole("tabpanel", { name: /ACLs/ })).toContainText("450");
    await expect(page.getByRole("tabpanel", { name: /ACLs/ })).toContainText("Deny");
    await expect(page.getByRole("tabpanel", { name: /ACLs/ })).toContainText("8443-8444");
    await expect(page.getByRole("tabpanel", { name: /ACLs/ })).toContainText("198.51.100.0/24");

    await page.getByRole("tab", { name: /Activity/ }).click();
    await expect(page.getByRole("tabpanel", { name: /Activity/ })).toContainText("NETWORK.RESTART");
    await expect(page.getByRole("tabpanel", { name: /Activity/ })).toContainText("regulated-egress (Network)");
    await expect(page.getByRole("tabpanel", { name: /Activity/ })).toContainText("Restarted after ACL policy publish");

    expect(
      mockCloudStackBff.calls("listNetworks").some((call) => call.params.get("id") === "n-106"),
    ).toBe(true);
    expect(mockCloudStackBff.calls("listPublicIpAddresses").at(-1)?.params.get("associatednetworkid")).toBe("n-106");
    expect(mockCloudStackBff.calls("listNetworkACLs").at(-1)?.params.get("networkid")).toBe("n-106");
    expect(mockCloudStackBff.calls("listEvents").at(-1)?.params.get("resourcetype")).toBe("Network");
  });
});

function installInstanceDetailMocks(mockCloudStackBff: MockCloudStackBff): void {
  mockCloudStackBff.use("listVirtualMachines", {
    listvirtualmachinesresponse: {
      count: 1,
      virtualmachine: [
        {
          id: "i-9f3a2b",
          name: "ledger-api-01",
          displayname: "ledger-api-blue",
          instancename: "ledger-api-internal",
          state: "Running",
          zonename: "perth-east",
          podname: "perth-pod-7",
          clustername: "amd-cluster-42",
          hostname: "kvm-host-sapphire",
          hypervisor: "KVM",
          serviceofferingname: "Compute-XL guarded",
          diskofferingname: "PCI root NVMe",
          templatename: "Ubuntu 24.04 hardened",
          templatedisplaytext: "Ubuntu 24.04 CIS hardened image",
          cpunumber: 8,
          cpuspeed: 3400,
          memory: 32768,
          memorykbs: 25165824,
          memorytargetkbs: 33554432,
          cpuused: "37%",
          account: "qa-payments",
          domain: "ROOT/Finance",
          project: "finance-lab",
          created: "2026-05-17T08:15:30+0000",
          haenable: true,
          securitygroup: [{ id: "sg-ledger-id", name: "sg-ledger", type: "shared" }],
          affinitygroup: [{ id: "ag-spread-id", name: "ag-spread", type: "host anti-affinity" }],
          nic: [
            {
              id: "nic-ledger-primary",
              networkid: "n-106",
              networkname: "seg-regulated-42",
              ipaddress: "10.42.7.19",
              publicip: "203.0.113.142",
              gateway: "10.42.7.1",
              netmask: "255.255.255.0",
              macaddress: "02:00:5e:10:42:19",
              type: "Guest",
              isdefault: true,
            },
          ],
        },
      ],
    },
  });
  mockCloudStackBff.use("listVolumes", {
    listvolumesresponse: {
      count: 1,
      volume: [
        {
          id: "vol-ledger-root",
          name: "ledger-root",
          displayname: "ledger-blue-root",
          size: 137_438_953_472,
          type: "ROOT",
          diskofferingname: "NVMe protected tier",
          virtualmachineid: "i-9f3a2b",
          virtualmachinename: "ledger-api-blue",
          zonename: "perth-east",
          state: "Ready",
        },
      ],
    },
  });
  mockCloudStackBff.use("listEvents", {
    listeventsresponse: {
      count: 1,
      event: [
        {
          id: "evt-ledger-migrate",
          username: "ops-auditor",
          type: "VM.MIGRATE",
          level: "INFO",
          resourceid: "i-9f3a2b",
          resourcetype: "UserVm",
          resourcename: "ledger-api-blue",
          created: "2026-05-18T03:20:00+0000",
          description: "Live migrated for host maintenance",
        },
      ],
    },
  });
}

function installNetworkDetailMocks(mockCloudStackBff: MockCloudStackBff): void {
  mockCloudStackBff.use("listVPCs", { listvpcsresponse: { count: 0, vpc: [] } });
  mockCloudStackBff.use("listNetworks", {
    listnetworksresponse: {
      count: 1,
      network: [
        {
          id: "n-106",
          name: "regulated-egress",
          displaytext: "regulated egress segment",
          type: "Isolated",
          traffictype: "Guest",
          networkcidr: "10.106.0.0/24",
          gateway: "10.106.0.1",
          netmask: "255.255.255.0",
          networkdomain: "pci.internal",
          zonename: "perth-zone-a",
          state: "Implemented",
          account: "platform-payments",
          domain: "ROOT/Platform",
          project: "Payment Shield",
          networkofferingname: "Tiered isolated offering - no source NAT",
          canusefordeploy: true,
          restartrequired: true,
        },
      ],
    },
  });
  mockCloudStackBff.use("listVirtualMachines", {
    listvirtualmachinesresponse: {
      count: 3,
      virtualmachine: [
        { id: "vm-ledger", name: "ledger-api-blue", nic: [{ networkid: "n-106" }] },
        { id: "vm-reporting", name: "reporting-api-02", nic: [{ networkid: "n-106" }, { networkid: "n-106" }] },
        { id: "vm-other", name: "unrelated-cache", nic: [{ networkid: "n-other" }] },
      ],
    },
  });
  mockCloudStackBff.use("listPublicIpAddresses", {
    listpublicipaddressesresponse: {
      count: 2,
      publicipaddress: [
        {
          id: "ip-reg-src",
          ipaddress: "198.51.100.77",
          state: "Allocated",
          issourcenat: true,
          isstaticnat: false,
          associatednetworkname: "regulated-egress",
          virtualmachinename: "ledger-api-blue",
        },
        {
          id: "ip-reg-static",
          ipaddress: "198.51.100.78",
          state: "Allocated",
          issourcenat: false,
          isstaticnat: true,
          associatednetworkname: "regulated-egress",
          virtualmachinename: "reporting-api-02",
        },
      ],
    },
  });
  mockCloudStackBff.use("listNetworkACLLists", {
    listnetworkacllistsresponse: {
      count: 1,
      networkacllist: [{ id: "acl-pci", name: "pci-acl-list", description: "PCI controlled ingress" }],
    },
  });
  mockCloudStackBff.use("listNetworkACLs", {
    listnetworkaclsresponse: {
      count: 1,
      networkacl: [
        {
          id: "acl-rule-deny-admin",
          aclid: "acl-pci",
          number: "450",
          action: "Deny",
          protocol: "tcp",
          startport: "8443",
          endport: "8444",
          cidrlist: "198.51.100.0/24",
          traffictype: "Ingress",
          state: "Active",
        },
      ],
    },
  });
  mockCloudStackBff.use("listEvents", {
    listeventsresponse: {
      count: 1,
      event: [
        {
          id: "evt-network-restart",
          username: "network-operator",
          type: "NETWORK.RESTART",
          level: "WARN",
          resourceid: "n-106",
          resourcetype: "Network",
          resourcename: "regulated-egress",
          created: "2026-05-18T05:45:00+0000",
          description: "Restarted after ACL policy publish",
        },
      ],
    },
  });
}
