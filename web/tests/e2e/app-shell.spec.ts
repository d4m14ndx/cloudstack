import { expect, test } from "./fixtures/cloudstack-bff";

test("renders the operator shell with mocked auth and BFF state", async ({ page, mockCloudStackBff }) => {
  await page.goto("/");

  await expect(page.getByText("Alex Kim")).toBeVisible();
  await expect(page.getByRole("link", { name: "Instances" })).toBeVisible();
  await expect(page.getByRole("heading", { name: /Good (morning|afternoon|evening), Alex/ })).toBeVisible();
  await expect(page.getByText("2 zones online · 3 hosts · 4 instances running across your platform")).toBeVisible();
  await expect(page.getByText("Zones, activity feed, top instances, and quick actions land in Phase 5c.")).toBeVisible();
  expect(mockCloudStackBff.calls("listCapacity")).toHaveLength(1);
  expect(mockCloudStackBff.calls("listVirtualMachines").some((call) => call.params.get("state") === "Running")).toBe(true);
});
