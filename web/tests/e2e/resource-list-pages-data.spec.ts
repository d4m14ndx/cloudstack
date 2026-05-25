import { expect, test } from "./fixtures/cloudstack-bff";

test.describe("resource list pages render BFF data", () => {
  test("renders volumes returned by listVolumes", async ({ page, mockCloudStackBff }) => {
    mockCloudStackBff.use("listVolumes", {
      listvolumesresponse: {
        count: 2,
        volume: [
          {
            id: "vol-bff-root-01",
            name: "root-bff-prod",
            size: 53_687_091_200,
            vmname: "vm-bff-web-01",
            zonename: "perth-1",
            state: "Ready",
            diskofferingname: "NVMe Gold",
          },
          {
            id: "vol-bff-archive-02",
            displayname: "archive-bff-ledger",
            size: "2199023255552",
            zonename: "perth-2",
            state: "Expunging",
            storage: "cold-archive",
          },
        ],
      },
    });

    await page.goto("/volumes");

    await expect(page.getByRole("heading", { name: "Volumes" })).toBeVisible();
    await expect(page.getByText("2 block storage volumes across your scope, 1 currently attached")).toBeVisible();
    await expect(page.getByRole("row", { name: /root-bff-prod.*vol-bff-root-01.*Ready.*perth-1.*NVMe.*50 GiB.*vm-bff-web-01/ })).toBeVisible();
    await expect(page.getByRole("row", { name: /archive-bff-ledger.*vol-bff-archive-02.*Detaching.*perth-2.*Cold.*2048 GiB.*-/ })).toBeVisible();

    const calls = mockCloudStackBff.calls("listVolumes");
    expect(calls).toHaveLength(1);
    expect(calls[0]?.params.get("listall")).toBe("true");
  });

  test("renders templates returned by listTemplates", async ({ page, mockCloudStackBff }) => {
    mockCloudStackBff.use("listTemplates", {
      listtemplatesresponse: {
        count: 2,
        template: [
          {
            id: "tmpl-bff-ubuntu",
            displaytext: "Ubuntu BFF hardened",
            ostypename: "Ubuntu 24.04 LTS",
            size: 68_719_476_736,
            arch: "x86_64",
            isfeatured: true,
            hypervisor: "KVM,VMware",
            account: "platform",
          },
          {
            id: "tmpl-bff-arm",
            name: "AlmaLinux BFF arm",
            ostypename: "AlmaLinux 9",
            physicalsize: "12884901888",
            arch: "aarch64",
            featured: false,
            hypervisor: "KVM",
            account: "edge",
          },
        ],
      },
    });

    await page.goto("/templates");

    await expect(page.getByRole("heading", { name: "Templates" })).toBeVisible();
    await expect(page.getByText("2 deployable templates across your scope, 1 featured")).toBeVisible();
    await expect(page.getByRole("row", { name: /Ubuntu BFF hardened.*tmpl-bff-ubuntu.*Ubuntu.*x86_64.*KVM.*VMware.*64 GB.*platform.*Yes/ })).toBeVisible();
    await expect(page.getByRole("row", { name: /AlmaLinux BFF arm.*tmpl-bff-arm.*AlmaLinux.*arm64.*KVM.*12 GB.*edge.*No/ })).toBeVisible();

    const calls = mockCloudStackBff.calls("listTemplates");
    expect(calls).toHaveLength(1);
    expect(calls[0]?.params.get("templatefilter")).toBe("executable");
    expect(calls[0]?.params.get("showunique")).toBe("true");
  });

  test("renders security groups returned by listSecurityGroups", async ({ page, mockCloudStackBff }) => {
    mockCloudStackBff.use("listSecurityGroups", {
      listsecuritygroupsresponse: {
        count: 2,
        securitygroup: [
          {
            id: "sg-bff-default",
            name: "default",
            description: "Default BFF group",
            account: "admin",
            domainpath: "ROOT/Admin",
            ingressrule: [
              { ruleid: "ing-bff-ssh", protocol: "tcp", startport: 22, endport: 22, cidr: "10.10.0.0/16" },
              { ruleid: "ing-bff-icmp", protocol: "icmp", icmptype: 8, icmpcode: 0, cidr: "10.10.0.0/16" },
            ],
            egressrule: [{ ruleid: "eg-bff-all", protocol: "tcp", startport: 443, endport: 443, cidr: "0.0.0.0/0" }],
            virtualmachinecount: "3",
          },
          {
            id: "sg-bff-app",
            name: "bff-app-tier",
            description: "App tier access",
            account: "apps",
            domainpath: "ROOT/Apps",
            project: "payments",
            ingressrule: [{ ruleid: "ing-bff-web", protocol: "tcp", startport: 8443, endport: 8443, securitygroupname: "default", account: "admin" }],
            egressrule: [],
            virtualmachineids: "vm-1,vm-2",
          },
        ],
      },
    });

    await page.goto("/security");

    await expect(page.getByRole("heading", { name: "Security groups" })).toBeVisible();
    await expect(page.getByText("2 security groups across 2 accounts and 2 domains")).toBeVisible();
    await expect(page.getByRole("row", { name: /default.*sg-bff-default.*Default BFF group.*admin.*ROOT\/Admin.*TCP 22 from 10\.10\.0\.0\/16.*\+1 more.*TCP 443 from 0\.0\.0\.0\/0.*3.*Default/ })).toBeVisible();
    await expect(page.getByRole("row", { name: /bff-app-tier.*sg-bff-app.*App tier access.*apps.*ROOT\/Apps.*payments.*TCP 8443 from admin\/default.*-.*2.*Custom.*Project/ })).toBeVisible();

    const calls = mockCloudStackBff.calls("listSecurityGroups");
    expect(calls).toHaveLength(1);
    expect(calls[0]?.params.get("listall")).toBe("true");
  });

  test("renders SSH key pairs returned by listSSHKeyPairs", async ({ page, mockCloudStackBff }) => {
    mockCloudStackBff.use("listSSHKeyPairs", {
      listsshkeypairsresponse: {
        count: 2,
        sshkeypair: [
          {
            id: "ssh-bff-admin",
            name: "bff-admin-key",
            fingerprint: "SHA256:bff-admin-fingerprint",
            account: "admin",
            domain: "ROOT/Admin",
          },
          {
            id: "ssh-bff-project",
            name: "bff-project-key",
            fingerprint: "SHA256:bff-project-fingerprint",
            account: "apps",
            domain: "ROOT/Apps",
            project: "payments",
          },
        ],
      },
    });

    await page.goto("/ssh-keys");

    await expect(page.getByRole("heading", { name: "SSH keys" })).toBeVisible();
    await expect(page.getByText("2 SSH key pairs across 2 accounts and 2 domains")).toBeVisible();
    await expect(page.getByRole("row", { name: /bff-admin-key.*ssh-bff-admin.*SHA256:bff-admin-fingerprint.*admin.*ROOT\/Admin.*-/ })).toBeVisible();
    await expect(page.getByRole("row", { name: /bff-project-key.*ssh-bff-project.*SHA256:bff-project-fingerprint.*apps.*ROOT\/Apps.*payments/ })).toBeVisible();

    const calls = mockCloudStackBff.calls("listSSHKeyPairs");
    expect(calls).toHaveLength(1);
    expect(calls[0]?.params.get("listall")).toBe("true");
  });
});
