import { expect, test } from "./fixtures/cloudstack-bff";

const settingsPages = [
  {
    path: "/settings",
    heading: "Settings",
    emptyTitle: "No settings panels available",
    emptyDescription: "This build has no editable settings panels for the current scope.",
  },
  {
    path: "/settings/profile",
    heading: "Profile",
    emptyTitle: "No profile controls available",
    emptyDescription: "This build has no editable personal details or console preferences for the current account.",
  },
  {
    path: "/settings/security",
    heading: "Security",
    emptyTitle: "No security controls available",
    emptyDescription: "This build has no editable password, MFA, or session controls for the current account.",
  },
  {
    path: "/settings/api-tokens",
    heading: "API tokens",
    emptyTitle: "No API tokens configured",
    emptyDescription: "CloudStack did not return scoped automation tokens for the current account.",
  },
  {
    path: "/settings/integrations",
    heading: "Integrations",
    emptyTitle: "No integrations configured",
    emptyDescription: "CloudStack did not return identity, monitoring, or automation integrations for this scope.",
  },
  {
    path: "/settings/billing",
    heading: "Billing",
    emptyTitle: "No billing settings available",
    emptyDescription: "This build has no editable invoice, usage export, or payment settings for the current scope.",
  },
  {
    path: "/settings/notifications",
    heading: "Notifications",
    emptyTitle: "No notification preferences configured",
    emptyDescription: "This build has no editable email, event, or operational alert preferences for the current scope.",
  },
  {
    path: "/settings/advanced",
    heading: "Advanced",
    emptyTitle: "No advanced controls available",
    emptyDescription: "This build has no editable low-level console settings for the current scope.",
  },
] as const;

test.describe("settings placeholder pages", () => {
  for (const settingsPage of settingsPages) {
    test(`${settingsPage.path} shows a stable operational empty state`, async ({ page }) => {
      await page.goto(settingsPage.path);

      await expect(page.getByRole("heading", { name: settingsPage.heading })).toBeVisible();
      await expect(page.getByText(settingsPage.emptyTitle)).toBeVisible();
      await expect(page.getByText(settingsPage.emptyDescription)).toBeVisible();
      await expect(page.getByRole("main")).toContainText(settingsPage.emptyTitle);
    });
  }
});
