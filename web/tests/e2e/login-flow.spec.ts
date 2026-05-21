import { expect, test } from "./fixtures/cloudstack-bff";

test.describe("login and logout shell flow", () => {
  test("login page shows disabled SSO options when providers are not configured", async ({ page }) => {
    await page.goto("/login");

    await expect(page.getByRole("heading", { name: "Sign in to CloudStack" })).toBeVisible();
    await expect(page.getByText("Continue with single sign-on through your identity provider.")).toBeVisible();
    await expect(page.getByRole("button", { name: "Continue with Authentik" })).toBeDisabled();
    await expect(page.getByRole("button", { name: "Continue with Microsoft Entra" })).toBeDisabled();
    await expect(page.getByText("Configure an identity provider to enable single sign-on.")).toBeVisible();
  });

  test("operator shell logout link returns to the sign-in route", async ({ page }) => {
    await page.goto("/");

    await page.getByRole("link", { name: "Log out" }).click();

    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole("heading", { name: "Sign in to CloudStack" })).toBeVisible();
  });
});
