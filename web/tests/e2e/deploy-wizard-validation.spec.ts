import type { Locator, Page } from "@playwright/test";
import { expect, test, type MockCloudStackBff } from "./fixtures/cloudstack-bff";

test("Deploy Wizard explains and blocks invalid custom disk sizes before review", async ({
  page,
  mockCloudStackBff,
}) => {
  mockDeployWizardValidationCatalog(mockCloudStackBff);

  const dialog = await openDeployWizard(page);
  await dialog.getByLabel("Name", { exact: true }).fill("validation-disk-size-01");
  await dialog.getByRole("button", { name: "Continue" }).click();
  await dialog.getByRole("button", { name: "Continue" }).click();
  await dialog.getByRole("button", { name: "Continue" }).click();

  await dialog.getByRole("button", { name: /Validation expandable disk/i }).click();
  const sizeField = dialog.getByLabel("Size");
  const continueButton = dialog.getByRole("button", { name: "Continue" });

  await expect(sizeField).toHaveAttribute("aria-invalid", "true");
  await expect(dialog.getByText("Enter a whole GiB size greater than 0.")).toBeVisible();
  await expect(continueButton).toBeDisabled();

  await sizeField.fill("0");
  await expect(sizeField).toHaveAttribute("aria-invalid", "true");
  await expect(continueButton).toBeDisabled();

  await sizeField.fill("80");
  await expect(sizeField).not.toHaveAttribute("aria-invalid", "true");
  await expect(dialog.getByText("Enter a whole GiB size greater than 0.")).toBeHidden();
  await expect(continueButton).toBeEnabled();

  expect(mockCloudStackBff.calls("deployVirtualMachine")).toHaveLength(0);
  expect(mockCloudStackBff.calls("queryAsyncJobResult")).toHaveLength(0);
});

test("Deploy Wizard disables launch while submitting and surfaces async job failure", async ({
  page,
  mockCloudStackBff,
}) => {
  mockDeployWizardValidationCatalog(mockCloudStackBff);

  let releaseDeploy: () => void = () => {};
  const deployStarted = new Promise<void>((resolveStarted) => {
    mockCloudStackBff.use("deployVirtualMachine", async () => {
      resolveStarted();
      await new Promise<void>((resolve) => {
        releaseDeploy = resolve;
      });
      return {
        deployvirtualmachineresponse: {
          id: "vm-validation-guard-1",
          jobid: "job-validation-guard-1",
        },
      };
    });
  });
  mockCloudStackBff.use("queryAsyncJobResult", {
    queryasyncjobresultresponse: {
      jobid: "job-validation-guard-1",
      jobstatus: 2,
      jobresultcode: 431,
      jobresult: {
        errortext: "Validation guard rejected the launch request",
      },
    },
  });

  const dialog = await openDeployWizard(page);
  await completeRequiredWizardSteps(dialog, "validation-guard-01");

  const launchButton = dialog.getByRole("button", { name: "Launch instance" });
  await launchButton.click();
  await deployStarted;

  await expect(launchButton).toBeDisabled();
  await expect(dialog.getByRole("status").filter({ hasText: "Submitting deployment" })).toBeVisible();
  expect(mockCloudStackBff.calls("deployVirtualMachine")).toHaveLength(1);

  releaseDeploy();

  await expect(dialog.getByRole("status").filter({ hasText: "Validation guard rejected the launch request" })).toBeVisible();
  await expect(dialog.getByText("Failed").first()).toBeVisible();
  expect(mockCloudStackBff.calls("deployVirtualMachine")).toHaveLength(1);
  expect(mockCloudStackBff.calls("queryAsyncJobResult")).toHaveLength(1);
  expect(mockCloudStackBff.calls("queryAsyncJobResult")[0]?.params.get("jobid")).toBe("job-validation-guard-1");

  const [deployCall] = mockCloudStackBff.calls("deployVirtualMachine");
  expect(deployCall?.json).toMatchObject({
    name: "validation-guard-01",
    displayname: "vm-validation-zone-alpha-preview",
    zoneid: "z-validation-1",
    templateid: "tmpl-validation-linux",
    serviceofferingid: "so-validation-xs",
    diskofferingid: "do-validation-root",
    sshkeypairs: "validation-admin",
    startvm: "true",
    networkids: "net-validation-a",
  });
});

async function openDeployWizard(page: Page): Promise<Locator> {
  await page.goto("/");
  await page.getByRole("button", { name: "Deploy", exact: true }).click();

  const dialog = page.getByRole("dialog", { name: "Deploy instance" });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByText("Loading catalog")).toBeHidden();
  return dialog;
}

async function completeRequiredWizardSteps(dialog: Locator, name: string): Promise<void> {
  await dialog.getByLabel("Name", { exact: true }).fill(name);
  await dialog.getByRole("button", { name: "Continue" }).click();
  await dialog.getByRole("button", { name: "Continue" }).click();
  await dialog.getByRole("button", { name: "Continue" }).click();
  await dialog.getByRole("button", { name: "Continue" }).click();
  await dialog.getByRole("button", { name: "Continue" }).click();
  await expect(dialog.getByText(name)).toBeVisible();
}

function mockDeployWizardValidationCatalog(mockCloudStackBff: MockCloudStackBff): void {
  mockCloudStackBff.use("listZones", {
    listzonesresponse: {
      count: 1,
      zone: [
        {
          id: "z-validation-1",
          name: "validation-zone-alpha",
          description: "Validation Alpha",
          allocationstate: "Enabled",
        },
      ],
    },
  });
  mockCloudStackBff.use("listTemplates", {
    listtemplatesresponse: {
      count: 1,
      template: [
        {
          id: "tmpl-validation-linux",
          name: "validation-linux",
          displaytext: "Validation Linux 2026",
          ostypename: "Linux",
          size: 2_000_000_000,
          arch: "x86_64",
          account: "validation",
        },
      ],
    },
  });
  mockCloudStackBff.use("listServiceOfferings", {
    listserviceofferingsresponse: {
      count: 1,
      serviceoffering: [
        {
          id: "so-validation-xs",
          name: "Validation Compute XS",
          displaytext: "Validation compute",
          cpunumber: 1,
          memory: 1024,
        },
      ],
    },
  });
  mockCloudStackBff.use("listDiskOfferings", {
    listdiskofferingsresponse: {
      count: 2,
      diskoffering: [
        { id: "do-validation-root", name: "Validation root disk", disksize: 32_212_254_720, customized: false },
        { id: "do-validation-custom", name: "Validation expandable disk", iscustomized: true },
      ],
    },
  });
  mockCloudStackBff.use("listNetworks", {
    listnetworksresponse: {
      count: 1,
      network: [
        {
          id: "net-validation-a",
          name: "validation-net-a",
          networkcidr: "10.55.0.0/24",
          gateway: "10.55.0.1",
          zonename: "validation-zone-alpha",
          state: "Implemented",
        },
      ],
    },
  });
  mockCloudStackBff.use("listSecurityGroups", {
    listsecuritygroupsresponse: {
      count: 1,
      securitygroup: [{ id: "sg-validation-default", name: "validation-default", description: "Validation SG" }],
    },
  });
  mockCloudStackBff.use("listSSHKeyPairs", {
    listsshkeypairsresponse: {
      count: 1,
      sshkeypair: [{ id: "ssh-validation-admin", name: "validation-admin" }],
    },
  });
  mockCloudStackBff.use("listProjects", {
    listprojectsresponse: {
      count: 0,
      project: [],
    },
  });
  mockCloudStackBff.use("listAffinityGroups", {
    listaffinitygroupsresponse: {
      count: 0,
      affinitygroup: [],
    },
  });
}
