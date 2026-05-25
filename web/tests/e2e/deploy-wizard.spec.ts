import { expect, test, type MockCloudStackBff } from "./fixtures/cloudstack-bff";

const catalogCommands = [
  "listZones",
  "listTemplates",
  "listServiceOfferings",
  "listDiskOfferings",
  "listNetworks",
  "listSecurityGroups",
  "listSSHKeyPairs",
  "listProjects",
  "listAffinityGroups",
] as const;

test("Deploy Wizard loads catalog choices and submits a valid launch", async ({ page, mockCloudStackBff }) => {
  await page.goto("/");
  mockCloudStackBff.reset();
  mockDeployWizardCatalog(mockCloudStackBff);
  mockCloudStackBff.use("deployVirtualMachine", async () => {
    await new Promise((resolve) => setTimeout(resolve, 50));
    return {
      deployvirtualmachineresponse: {
        id: "vm-smoke-1",
        jobid: "job-smoke-1",
      },
    };
  });
  mockCloudStackBff.use("queryAsyncJobResult", {
    queryasyncjobresultresponse: {
      jobid: "job-smoke-1",
      jobstatus: 1,
      jobresult: {
        virtualmachine: {
          id: "vm-smoke-1",
          name: "smoke-vm-01",
          state: "Running",
        },
      },
    },
  });
  await page.getByRole("button", { name: "Deploy", exact: true }).click();

  const dialog = page.getByRole("dialog", { name: "Deploy instance" });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByText("Loading catalog")).toBeHidden();

  await expect(dialog.getByLabel("Name", { exact: true })).toHaveValue("vm-smoke-zone-preview");
  await expect(dialog.getByRole("button", { name: /smoke-zone/i })).toBeVisible();
  await expect(dialog.getByRole("button", { name: /Ubuntu 24\.04 LTS/i })).toBeVisible();

  expect(catalogCommands.map((command) => mockCloudStackBff.calls(command).length)).toEqual(catalogCommands.map(() => 1));
  for (const command of catalogCommands) {
    expect(mockCloudStackBff.calls(command)[0]?.method).toBe("GET");
  }
  expect(mockCloudStackBff.calls("deployVirtualMachine")).toHaveLength(0);
  expect(mockCloudStackBff.calls("queryAsyncJobResult")).toHaveLength(0);

  await dialog.getByLabel("Name", { exact: true }).fill("smoke-vm-01");
  await dialog.getByLabel("Project").selectOption("project-smoke");
  await dialog.getByRole("button", { name: "Continue" }).click();

  await expect(dialog.getByRole("button", { name: /Compute-S/i })).toBeVisible();
  await dialog.getByRole("button", { name: "Continue" }).click();

  await expect(dialog.getByRole("button", { name: /smoke-vpc/i })).toBeVisible();
  await dialog.getByRole("button", { name: "Continue" }).click();

  await dialog.getByRole("button", { name: /Custom data disk/i }).click();
  await dialog.getByLabel("Size").fill("75");
  await dialog.getByRole("button", { name: "Continue" }).click();

  await dialog.getByLabel("SSH key").selectOption("ssh-platform");
  await dialog.getByLabel("Affinity group").selectOption("ag-spread");
  await dialog.getByLabel("Start after deploy").click();
  await dialog.getByLabel("User data").fill("#cloud-config\npackages:\n  - nginx");
  await dialog.getByRole("button", { name: "Continue" }).click();

  await expect(dialog.getByText("smoke-vm-01")).toBeVisible();
  await expect(dialog.getByText("smoke-project")).toBeVisible();
  await expect(dialog.getByText("Custom data disk")).toBeVisible();
  await expect(dialog.getByText("75 GiB")).toBeVisible();
  await expect(dialog.getByText("platform-admin")).toBeVisible();
  await expect(dialog.getByText("spread-smoke")).toBeVisible();
  await expect(dialog.getByRole("cell", { name: "No", exact: true })).toBeVisible();

  await dialog.getByRole("button", { name: "Launch instance" }).click();

  await expect(dialog.getByText("Submitting deployment")).toBeVisible();
  await expect(dialog.getByText("smoke-vm-01 Running")).toBeVisible();
  await expect(dialog.getByText("Launched").first()).toBeVisible();

  const [deployCall] = mockCloudStackBff.calls("deployVirtualMachine");
  expect(deployCall?.method).toBe("POST");
  expect(deployCall?.json).toEqual({
    name: "smoke-vm-01",
    displayname: "vm-smoke-zone-preview",
    zoneid: "z-smoke-1",
    templateid: "tmpl-ubuntu",
    serviceofferingid: "so-small",
    projectid: "project-smoke",
    affinitygroupids: "ag-spread",
    diskofferingid: "do-custom",
    size: "75",
    sshkeypairs: "platform-admin",
    userdata: "I2Nsb3VkLWNvbmZpZwpwYWNrYWdlczoKICAtIG5naW54",
    startvm: "false",
    networkids: "net-smoke",
  });
  expect(mockCloudStackBff.calls("queryAsyncJobResult")[0]?.params.get("jobid")).toBe("job-smoke-1");
});

function mockDeployWizardCatalog(mockCloudStackBff: MockCloudStackBff): void {
  mockCloudStackBff.use("listZones", {
    listzonesresponse: {
      count: 1,
      zone: [{ id: "z-smoke-1", name: "smoke-zone", description: "Smoke Region", allocationstate: "Enabled" }],
    },
  });
  mockCloudStackBff.use("listTemplates", {
    listtemplatesresponse: {
      count: 1,
      template: [
        {
          id: "tmpl-ubuntu",
          name: "ubuntu-24.04",
          displaytext: "Ubuntu 24.04 LTS",
          ostypename: "Ubuntu",
          size: 3_000_000_000,
          arch: "x86_64",
          account: "admin",
        },
      ],
    },
  });
  mockCloudStackBff.use("listServiceOfferings", {
    listserviceofferingsresponse: {
      count: 1,
      serviceoffering: [{ id: "so-small", name: "Compute-S", displaytext: "Small compute", cpunumber: 1, memory: 2048 }],
    },
  });
  mockCloudStackBff.use("listDiskOfferings", {
    listdiskofferingsresponse: {
      count: 2,
      diskoffering: [
        { id: "do-fixed", name: "Root default", disksize: 42_949_672_960, customized: false },
        { id: "do-custom", name: "Custom data disk", iscustomized: true },
      ],
    },
  });
  mockCloudStackBff.use("listNetworks", {
    listnetworksresponse: {
      count: 1,
      network: [
        {
          id: "net-smoke",
          name: "smoke-vpc",
          networkcidr: "10.44.0.0/24",
          gateway: "10.44.0.1",
          zonename: "smoke-zone",
          state: "Implemented",
        },
      ],
    },
  });
  mockCloudStackBff.use("listSecurityGroups", {
    listsecuritygroupsresponse: {
      count: 1,
      securitygroup: [{ id: "sg-default", name: "default", description: "Default security group" }],
    },
  });
  mockCloudStackBff.use("listSSHKeyPairs", {
    listsshkeypairsresponse: {
      count: 1,
      sshkeypair: [{ id: "ssh-platform", name: "platform-admin" }],
    },
  });
  mockCloudStackBff.use("listProjects", {
    listprojectsresponse: {
      count: 1,
      project: [{ id: "project-smoke", name: "smoke-project", displaytext: "Smoke project" }],
    },
  });
  mockCloudStackBff.use("listAffinityGroups", {
    listaffinitygroupsresponse: {
      count: 1,
      affinitygroup: [{ id: "ag-spread", name: "spread-smoke", type: "host anti-affinity" }],
    },
  });
}
