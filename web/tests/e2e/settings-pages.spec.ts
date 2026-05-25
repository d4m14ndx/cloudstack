import { expect, test, type MockCloudStackBff } from "./fixtures/cloudstack-bff";

test.describe("settings surfaces", () => {
  test("settings index links to active and related settings sections", async ({ page }) => {
    await page.goto("/settings");
    const main = page.getByRole("main");

    await expect(main.getByRole("heading", { name: "Settings", exact: true })).toBeVisible();
    await expect(main.getByRole("link", { name: /Profile/ })).toHaveAttribute("href", "/settings/profile");
    await expect(main.getByRole("link", { name: /Security/ })).toHaveAttribute("href", "/settings/security");
    await expect(main.getByRole("link", { name: /API tokens/ })).toHaveAttribute("href", "/settings/api-tokens");
    await expect(main.getByRole("link", { name: /Notifications/ })).toHaveAttribute("href", "/settings/notifications");
    await expect(main.getByRole("link", { name: /Localization/ })).toHaveAttribute("href", "/settings/profile");
    await expect(main.getByRole("link", { name: /Sessions/ })).toHaveAttribute("href", "/settings/security");
    await expect(main.getByRole("link", { name: /Account/ })).toHaveAttribute("href", "/accounts");
    await expect(main.getByText("Available")).toHaveCount(3);
    await expect(main.getByText("Related")).toHaveCount(3);
    await expect(main.getByText("Not configured")).toHaveCount(1);
  });

  test("profile page renders current user identity from listUsers", async ({ page, mockCloudStackBff }) => {
    mockCloudStackBff.use("listUsers", {
      listusersresponse: {
        count: 1,
        user: [
          {
            id: "mock-uuid-alex",
            username: "alex",
            firstname: "Alex",
            lastname: "Kim",
            email: "alex@example.test",
            account: "platform",
            accounttype: 1,
            domain: "ROOT",
            domainid: "mock-uuid-domain-root",
            timezone: "Australia/Perth",
            usersource: "native",
            state: "enabled",
            apikeyaccess: true,
            is2faenabled: true,
          },
        ],
      },
    });

    await page.goto("/settings/profile");
    const main = page.getByRole("main");

    await expect(main.getByRole("heading", { name: "Profile", exact: true })).toBeVisible();
    await expect(main.getByRole("heading", { name: "Alex Kim" })).toBeVisible();
    await expect(main.getByText("alex@example.test")).toBeVisible();
    await expect(main.getByText("Australia/Perth")).toBeVisible();
    await expect(main.getByText("platform")).toBeVisible();
    await expect(main.getByText("Enabled")).toHaveCount(4);

    const listUsersCall = mockCloudStackBff.calls("listUsers").at(-1);
    expect(listUsersCall?.method).toBe("GET");
    expect(listUsersCall?.params.get("id")).toBe("mock-uuid-alex");
    expect(listUsersCall?.params.get("listall")).toBe("true");
    expect(listUsersCall?.params.get("showicon")).toBe("true");
  });

  test("security page renders current user security flags from listUsers", async ({ page, mockCloudStackBff }) => {
    mockCloudStackBff.use("listUsers", {
      listusersresponse: {
        count: 1,
        user: [
          {
            id: "mock-uuid-alex",
            username: "alex",
            usersource: "ldap",
            state: "disabled",
            apikeyaccess: false,
            is2faenabled: true,
            is2famandated: false,
            passwordchangerequired: true,
          },
        ],
      },
    });

    await page.goto("/settings/security");
    const main = page.getByRole("main");

    await expect(main.getByRole("heading", { name: "Security", exact: true })).toBeVisible();
    await expect(main.getByText("Authentication source")).toBeVisible();
    await expect(main.getByText("Ldap")).toBeVisible();
    await expect(main.getByText("User state")).toBeVisible();
    await expect(main.getByText("Disabled")).toHaveCount(3);
    await expect(main.getByText("Required")).toBeVisible();

    const listUsersCall = mockCloudStackBff.calls("listUsers").at(-1);
    expect(listUsersCall?.method).toBe("GET");
    expect(listUsersCall?.params.get("id")).toBe("mock-uuid-alex");
    expect(listUsersCall?.params.get("listall")).toBe("true");
    expect(listUsersCall?.params.get("showicon")).toBe("true");
  });

  test("API token page renders getUserKeys status and can request key generation", async ({
    page,
    mockCloudStackBff,
  }) => {
    mockApiTokenStatus(mockCloudStackBff);
    mockCloudStackBff.use("registerUserKeys", {
      registeruserkeysresponse: {
        userkeys: {
          id: "keypair-generated",
          name: "Generated browser key",
          apikey: "generated-api-key",
          secretkey: "generated-secret-key",
        },
      },
    });

    await page.goto("/settings/api-tokens");

    await expect(page.getByRole("heading", { name: "API tokens", exact: true })).toBeVisible();
    await expect(page.getByText("api-...5678")).toBeVisible();
    await expect(page.getByText("secr...efgh")).toBeVisible();

    const getUserKeysCall = mockCloudStackBff.calls("getUserKeys").at(-1);
    expect(getUserKeysCall?.method).toBe("GET");
    expect(getUserKeysCall?.params.get("id")).toBe("mock-uuid-alex");

    await page.getByRole("button", { name: "Generate key pair" }).click();

    await expect(page.getByRole("status").filter({ hasText: "Generated key pair" })).toBeVisible();
    await expect(page.getByText("generated-api-key")).toBeVisible();
    await expect(page.getByText("generated-secret-key")).toBeVisible();

    const registerUserKeysCall = mockCloudStackBff.calls("registerUserKeys").at(-1);
    expect(registerUserKeysCall?.method).toBe("POST");
    expect(registerUserKeysCall?.json).toEqual({
      id: "mock-uuid-alex",
      name: "CloudStack UI automation",
      description: "Generated from modern CloudStack settings",
    });
  });

  test("API token generation failures are announced without unsafe params", async ({ page, mockCloudStackBff }) => {
    mockApiTokenStatus(mockCloudStackBff);
    mockCloudStackBff.use("registerUserKeys", {
      errorresponse: { errortext: "API key generation is disabled for this account" },
    });

    await page.goto("/settings/api-tokens");
    await page.getByRole("button", { name: "Generate key pair" }).click();

    await expect(page.getByText(
      "Unable to generate API key pair: API key generation is disabled for this account",
    )).toBeVisible();
    expect(mockCloudStackBff.calls("registerUserKeys").at(-1)?.json).toEqual({
      id: "mock-uuid-alex",
      name: "CloudStack UI automation",
      description: "Generated from modern CloudStack settings",
    });
  });
});

const placeholderPages = [
  ["/settings/integrations", "Integrations", "No integrations configured"],
  ["/settings/billing", "Billing", "No billing settings available"],
  ["/settings/notifications", "Notifications", "No notification preferences configured"],
  ["/settings/advanced", "Advanced", "No advanced controls available"],
] as const;

test.describe("settings not-configured pages", () => {
  for (const [path, heading, emptyTitle] of placeholderPages) {
    test(`${path} keeps stable not-configured state`, async ({ page }) => {
      await page.goto(path);

      await expect(page.getByRole("heading", { name: heading, exact: true })).toBeVisible();
      await expect(page.getByText(emptyTitle)).toBeVisible();
      await expect(page.getByRole("main")).toContainText(emptyTitle);
    });
  }
});

function mockApiTokenStatus(mockCloudStackBff: MockCloudStackBff): void {
  mockCloudStackBff.use("getUserKeys", {
    getuserkeysresponse: {
      userkeys: {
        apikeyaccess: true,
        apikey: "api-key-12345678",
        secretkey: "secret-key-abcdefgh",
      },
    },
  });
}
