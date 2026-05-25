import { defineConfig, devices } from "@playwright/test";

const port = Number(process.env.PLAYWRIGHT_PORT ?? 3000);
const cloudStackMockPort = Number(process.env.PLAYWRIGHT_CLOUDSTACK_MOCK_PORT ?? port + 1000);

process.env.PLAYWRIGHT_CLOUDSTACK_MOCK_PORT = String(cloudStackMockPort);

export default defineConfig({
  testDir: "./tests/e2e",
  fullyParallel: false,
  workers: 1,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 2 : 0,
  reporter: process.env.CI ? "github" : "list",
  use: {
    baseURL: `http://127.0.0.1:${port}`,
    trace: "on-first-retry",
  },
  webServer: {
    command: `npm run dev -- --hostname 127.0.0.1 --port ${port}`,
    url: `http://127.0.0.1:${port}`,
    reuseExistingServer: !process.env.CI,
    env: {
      BFF_DEV_SESSION: "true",
      NEXTAUTH_SECRET: "phase5-browser-smoke-secret",
      NEXTAUTH_URL: `http://127.0.0.1:${port}`,
      CS_URL: `http://127.0.0.1:${cloudStackMockPort}/client/api`,
      PLAYWRIGHT_CLOUDSTACK_MOCK_PORT: String(cloudStackMockPort),
    },
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
});
