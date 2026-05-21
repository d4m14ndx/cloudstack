import { expect, test } from "./fixtures/cloudstack-bff";

test.describe("SSH key and template action surfaces", () => {
  test("SSH keys page creates, registers, and deletes key pairs with safe POST payloads", async ({
    page,
    mockCloudStackBff,
  }) => {
    mockCloudStackBff.use("createSSHKeyPair", {
      createsshkeypairresponse: {
        id: "ssh-created-smoke",
        name: "ops-smoke",
        fingerprint: "SHA256:opssmoke",
        account: "platform",
        domain: "root",
        privatekey: "-----BEGIN RSA PRIVATE KEY-----\nsmoke-private-key\n-----END RSA PRIVATE KEY-----",
      },
    });
    mockCloudStackBff.use("registerSSHKeyPair", {
      registersshkeypairresponse: {
        id: "ssh-imported-smoke",
        name: "imported-smoke",
        fingerprint: "SHA256:importedsmoke",
        account: "platform",
        domain: "root",
      },
    });
    mockCloudStackBff.use("deleteSSHKeyPair", {
      deletesshkeypairresponse: { success: true },
    });
    page.on("dialog", (dialog) => dialog.accept());

    await page.goto("/ssh-keys");

    await expect(page.getByRole("heading", { name: "SSH keys" })).toBeVisible();
    await expect(page.getByRole("row", { name: /platform-admin/ })).toContainText("platform-admin");

    await page.getByLabel("Name").fill("ops-smoke");
    await page.getByRole("button", { name: "Create SSH key" }).click();

    await expect(page.getByText("Created ops-smoke")).toBeVisible();
    await expect(page.getByText("smoke-private-key")).toBeVisible();
    const createCall = mockCloudStackBff.calls("createSSHKeyPair").at(-1);
    expect(createCall?.method).toBe("POST");
    expect(createCall?.json).toEqual({ name: "ops-smoke" });

    await page.getByRole("tab", { name: "Register" }).click();
    await page.getByLabel("Name").fill("imported-smoke");
    await page.getByLabel("Public key").fill("ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQCsmoke imported-smoke");
    await page.getByRole("button", { name: "Register SSH key" }).click();

    await expect(page.getByText("Registered imported-smoke")).toBeVisible();
    const registerCall = mockCloudStackBff.calls("registerSSHKeyPair").at(-1);
    expect(registerCall?.method).toBe("POST");
    expect(registerCall?.json).toEqual({
      name: "imported-smoke",
      publickey: "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQCsmoke imported-smoke",
    });

    await page.getByRole("button", { name: "Delete SSH key platform-admin" }).click();

    await expect(page.getByText("Deleted platform-admin")).toBeVisible();
    const deleteCall = mockCloudStackBff.calls("deleteSSHKeyPair").at(-1);
    expect(deleteCall?.method).toBe("POST");
    expect(deleteCall?.json).toEqual({ name: "platform-admin" });
  });

  test("templates page copies, updates featured permission, and deletes with safe POST payloads", async ({
    page,
    mockCloudStackBff,
  }) => {
    mockCloudStackBff.use("copyTemplate", {
      copytemplateresponse: { id: "t-copy-smoke", jobid: "job-copy-template" },
    });
    mockCloudStackBff.use("updateTemplatePermissions", {
      updatetemplatepermissionsresponse: { success: true },
    });
    mockCloudStackBff.use("deleteTemplate", {
      deletetemplateresponse: { id: "t-004", jobid: "job-delete-template" },
    });
    page.on("dialog", async (dialog) => {
      if (dialog.type() === "prompt") {
        await dialog.accept("z-dest-smoke");
        return;
      }
      await dialog.accept();
    });

    await page.goto("/templates");

    await expect(page.getByRole("heading", { name: "Templates" })).toBeVisible();
    await expect(page.getByRole("row", { name: /Rocky Linux 9/ })).toContainText("Rocky Linux 9");

    await page.getByRole("button", { name: "Copy Rocky Linux 9" }).click();

    await expect(page.getByText("Copy queued")).toBeVisible();
    const copyCall = mockCloudStackBff.calls("copyTemplate").at(-1);
    expect(copyCall?.method).toBe("POST");
    expect(copyCall?.json).toEqual({
      id: "t-004",
      destzoneid: "z-dest-smoke",
    });

    await page.getByRole("switch", { name: "Set Rocky Linux 9 featured permission" }).click();

    await expect(page.getByText("Featured permission updated")).toBeVisible();
    const permissionCall = mockCloudStackBff.calls("updateTemplatePermissions").at(-1);
    expect(permissionCall?.method).toBe("POST");
    expect(permissionCall?.json).toEqual({
      id: "t-004",
      isfeatured: "true",
    });

    await page.getByRole("button", { name: "Delete Rocky Linux 9" }).click();

    await expect(page.getByText("Delete queued")).toBeVisible();
    const deleteCall = mockCloudStackBff.calls("deleteTemplate").at(-1);
    expect(deleteCall?.method).toBe("POST");
    expect(deleteCall?.json).toEqual({ id: "t-004" });
  });
});
