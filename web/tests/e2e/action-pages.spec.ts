import { expect, test } from "./fixtures/cloudstack-bff";

test.describe("action-heavy CloudStack pages", () => {
  test("instances list exposes lifecycle controls and stop posts a safe payload", async ({ page, mockCloudStackBff }) => {
    mockCloudStackBff.use("stopVirtualMachine", {
      stopvirtualmachineresponse: { jobid: "job-stop-vm" },
    });
    mockCloudStackBff.use("queryAsyncJobResult", ({ params }) => ({
      queryasyncjobresultresponse: {
        jobid: params.get("jobid") ?? "job-stop-vm",
        jobstatus: 1,
        jobresult: {
          virtualmachine: { id: "i-9f3a2b", name: "web-prod-01", state: "Stopped" },
        },
      },
    }));

    await page.goto("/instances");

    await expect(page.getByRole("heading", { name: "Instances" })).toBeVisible();
    await expect(page.getByRole("row", { name: /web-prod-01/ })).toContainText("web-prod-01");
    await expect(page.getByRole("button", { name: "Stop web-prod-01" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Reboot web-prod-01" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Start worker-01" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Destroy exp-llama" })).toBeVisible();

    await page.getByRole("button", { name: "Stop web-prod-01" }).click();

    await expect(page.getByRole("status").filter({ hasText: "Stop complete · Stopped" })).toBeVisible();
    const stopCall = mockCloudStackBff.calls("stopVirtualMachine").at(-1);
    expect(stopCall?.method).toBe("POST");
    expect(stopCall?.json).toEqual({ id: "i-9f3a2b" });
  });

  test("instance detail tabs render and console action state is available", async ({ page }) => {
    await page.goto("/instances/i-9f3a2b");

    await expect(page.getByRole("heading", { name: "web-prod-01" })).toBeVisible();
    await expect(page.getByRole("tab", { name: "Overview" })).toHaveAttribute("aria-selected", "true");

    await page.getByRole("tab", { name: /Networking/ }).click();
    await expect(page.getByRole("tabpanel", { name: /Networking/ })).toContainText("prod-vpc");

    await page.getByRole("tab", { name: /Storage/ }).click();
    await expect(page.getByRole("tabpanel", { name: /Storage/ })).toContainText("web-prod-01-root");

    await page.getByRole("tab", { name: /Activity/ }).click();
    await expect(page.getByRole("tabpanel", { name: /Activity/ })).toBeVisible();

    await page.getByRole("tab", { name: "Console" }).click();
    await expect(page.getByRole("tabpanel", { name: "Console" })).toContainText("Console access is issued on demand.");
    await expect(page.getByRole("button", { name: "Open console for web-prod-01" })).toBeEnabled();
  });

  test("instance lifecycle failures are announced as alerts", async ({ page, mockCloudStackBff }) => {
    mockCloudStackBff.use("stopVirtualMachine", {
      stopvirtualmachineresponse: { jobid: "job-stop-denied" },
    });
    mockCloudStackBff.use("queryAsyncJobResult", ({ params }) => ({
      queryasyncjobresultresponse: {
        jobid: params.get("jobid") ?? "job-stop-denied",
        jobstatus: 2,
        jobresult: { errortext: "Denied by policy" },
      },
    }));

    await page.goto("/instances");

    await page.getByRole("button", { name: "Stop web-prod-01" }).click();

    await expect(page.getByRole("alert").filter({ hasText: "Denied by policy" })).toBeVisible();
  });

  test("volumes page covers detach and delete flows", async ({ page, mockCloudStackBff }) => {
    mockCloudStackBff.use("detachVolume", {
      detachvolumeresponse: { id: "v-9d3a", jobid: "job-detach-volume" },
    });
    mockCloudStackBff.use("deleteVolume", {
      deletevolumeresponse: { success: true },
    });
    mockCloudStackBff.use("queryAsyncJobResult", ({ params }) => ({
      queryasyncjobresultresponse: {
        jobid: params.get("jobid") ?? "job-detach-volume",
        jobstatus: 1,
        jobresult: { volume: { id: "v-9d3a" } },
      },
    }));
    page.on("dialog", (dialog) => dialog.accept());

    await page.goto("/volumes");

    await expect(page.getByRole("heading", { name: "Volumes" })).toBeVisible();
    await expect(page.getByRole("row", { name: /web-prod-01-root/ })).toContainText("web-prod-01-root");
    await expect(page.getByRole("row", { name: /backups-archive/ })).toContainText("backups-archive");

    await page.getByRole("button", { name: "Detach web-prod-01-root" }).click();
    await expect(page.getByRole("status").filter({ hasText: "Detached" })).toBeVisible();
    const detachCall = mockCloudStackBff.calls("detachVolume").at(-1);
    expect(detachCall?.method).toBe("POST");
    expect(detachCall?.json).toEqual({ id: "v-9d3a" });

    await page.getByRole("button", { name: "Delete backups-archive" }).click();
    await expect(page.getByRole("status").filter({ hasText: "Deleted" })).toBeVisible();
    const deleteCall = mockCloudStackBff.calls("deleteVolume").at(-1);
    expect(deleteCall?.method).toBe("POST");
    expect(deleteCall?.json).toEqual({ id: "v-1a4b" });
  });
});
