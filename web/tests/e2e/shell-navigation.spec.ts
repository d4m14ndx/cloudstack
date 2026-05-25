import { expect, test } from "./fixtures/cloudstack-bff";

const commandPaletteShortcut = process.platform === "darwin" ? "Meta+K" : "Control+K";

test.describe("application shell navigation", () => {
  test("exposes stable shell landmarks and navigates through sidebar and topbar controls", async ({ page }) => {
    await page.goto("/");

    await expect(page.getByRole("complementary", { name: "CloudStack sidebar" })).toBeVisible();
    await expect(page.getByRole("navigation", { name: "Primary navigation" })).toBeVisible();
    await expect(page.getByRole("banner", { name: "Application topbar" })).toBeVisible();
    await expect(page.getByRole("main")).toBeVisible();
    await expect(page.getByRole("button", { name: "Open command palette" })).toBeVisible();

    await page.getByRole("link", { name: "Instances" }).click();
    await expect(page).toHaveURL(/\/instances$/);
    await expect(page.getByRole("heading", { name: "Instances" })).toBeVisible();

    await page.getByRole("link", { name: "Networks" }).click();
    await expect(page).toHaveURL(/\/networks$/);
    await expect(page.getByRole("heading", { name: "Networks" })).toBeVisible();

    await page.getByRole("link", { name: "API" }).click();
    await expect(page).toHaveURL(/\/settings\/api-tokens$/);
    await expect(page.getByRole("heading", { name: "API tokens" })).toBeVisible();
  });

  test("opens the command palette from the keyboard and selects a searched route", async ({ page }) => {
    await page.goto("/");

    await page.keyboard.press(commandPaletteShortcut);

    const palette = page.getByRole("dialog", { name: "Command palette" });
    await expect(palette).toBeVisible();
    await palette.getByPlaceholder("Search resources, accounts, events...").fill("Billing");
    await page.keyboard.press("Enter");

    await expect(page).toHaveURL(/\/billing$/);
    await expect(page.getByRole("heading", { name: "Billing quota summary" })).toBeVisible();
  });
});
