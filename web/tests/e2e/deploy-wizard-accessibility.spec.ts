import type { Locator } from "@playwright/test";
import { expect, test, type MockCloudStackBff } from "./fixtures/cloudstack-bff";

test("Deploy Wizard keeps keyboard focus in the dialog and announces launch status", async ({
  page,
  mockCloudStackBff,
}) => {
  mockDeployWizardCatalog(mockCloudStackBff);
  mockCloudStackBff.use("deployVirtualMachine", async () => {
    await new Promise((resolve) => setTimeout(resolve, 50));
    return {
      deployvirtualmachineresponse: {
        id: "vm-a11y-1",
        jobid: "job-a11y-1",
      },
    };
  });
  mockCloudStackBff.use("queryAsyncJobResult", {
    queryasyncjobresultresponse: {
      jobid: "job-a11y-1",
      jobstatus: 1,
      jobresult: {
        virtualmachine: {
          id: "vm-a11y-1",
          name: "a11y-vm-01",
          state: "Running",
        },
      },
    },
  });

  await page.goto("/");
  await page.getByRole("button", { name: "Deploy", exact: true }).focus();
  await page.keyboard.press("Enter");

  const dialog = page.getByRole("dialog", { name: "Deploy instance" });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByText("Loading catalog")).toBeHidden();

  const nameField = dialog.getByLabel("Name", { exact: true });
  await expect(nameField).toBeFocused();
  await nameField.fill("a11y-vm-01");

  await page.keyboard.press("Tab");
  await expect(dialog.getByLabel("Display name")).toBeFocused();
  await expectFocusInsideDialog(dialog);

  await page.keyboard.press("Tab");
  await expect(dialog.getByLabel("Project")).toBeFocused();
  await expectFocusInsideDialog(dialog);

  await page.keyboard.press("Tab");
  await expect(dialog.getByRole("button", { name: /a11y-zone/i })).toBeFocused();
  await expectFocusInsideDialog(dialog);

  await page.keyboard.press("Tab");
  await expect(dialog.getByRole("button", { name: /Ubuntu 24\.04 LTS/i })).toBeFocused();
  await expectFocusInsideDialog(dialog);

  await page.keyboard.press("Tab");
  await expect(dialog.getByRole("button", { name: "Continue" })).toBeFocused();
  await page.keyboard.press("Enter");

  await expect(dialog.getByRole("button", { name: /Compute-S/i })).toBeVisible();
  await expectFocusInsideDialog(dialog);

  await dialog.getByRole("button", { name: "Continue" }).click();
  await dialog.getByRole("button", { name: "Continue" }).click();
  await dialog.getByRole("button", { name: "Continue" }).click();
  await dialog.getByRole("button", { name: "Continue" }).click();
  await dialog.getByRole("button", { name: "Launch instance" }).click();

  await expect(dialog.getByRole("status").filter({ hasText: "Submitting deployment" })).toBeVisible();
  await expect(dialog.getByRole("status").filter({ hasText: "a11y-vm-01 Running" })).toBeVisible();
});

async function expectFocusInsideDialog(dialog: Locator): Promise<void> {
  await expect
    .poll(async () => dialog.evaluate((element) => element.contains(document.activeElement)))
    .toBe(true);
}

function mockDeployWizardCatalog(mockCloudStackBff: MockCloudStackBff): void {
  mockCloudStackBff.use("listZones", {
    listzonesresponse: {
      count: 1,
      zone: [{ id: "z-a11y-1", name: "a11y-zone", description: "A11y Region", allocationstate: "Enabled" }],
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
      count: 1,
      diskoffering: [{ id: "do-fixed", name: "Root default", disksize: 42_949_672_960, customized: false }],
    },
  });
  mockCloudStackBff.use("listNetworks", {
    listnetworksresponse: {
      count: 1,
      network: [
        {
          id: "net-a11y",
          name: "a11y-vpc",
          networkcidr: "10.45.0.0/24",
          gateway: "10.45.0.1",
          zonename: "a11y-zone",
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
      project: [{ id: "project-a11y", name: "a11y-project", displaytext: "A11y project" }],
    },
  });
  mockCloudStackBff.use("listAffinityGroups", {
    listaffinitygroupsresponse: {
      count: 1,
      affinitygroup: [{ id: "ag-spread", name: "spread-a11y", type: "host anti-affinity" }],
    },
  });
}
