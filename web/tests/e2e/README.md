# Browser Smoke Tests

Run from `web/`:

```bash
npm run test:e2e
```

The Playwright config starts `next dev` on `127.0.0.1:3000` with
`NEXT_PUBLIC_APP_ENV=mock`, so smoke tests do not need a live CloudStack server,
Redis, or an external identity provider. The app shell uses the existing mock
current-user bridge, which renders `Alex Kim` as the logged-in operator.

## Mocking `/api/cs/*`

Import the local fixture instead of importing directly from `@playwright/test`:

```ts
import { expect, test } from "./fixtures/cloudstack-bff";

test("loads instances", async ({ page, mockCloudStackBff }) => {
  mockCloudStackBff.use("listVirtualMachines", {
    listvirtualmachinesresponse: {
      count: 1,
      virtualmachine: [{ id: "vm-1", name: "vm-1", state: "Running" }],
    },
  });

  await page.goto("/instances");
  await expect(page.getByText("vm-1")).toBeVisible();
  expect(mockCloudStackBff.calls("listVirtualMachines")).toHaveLength(1);
});
```

Keep mocked payloads close to CloudStack response envelopes:

- `/api/cs/listVirtualMachines` returns `listvirtualmachinesresponse`.
- `/api/cs/listZones` returns `listzonesresponse`.
- `/api/cs/queryAsyncJobResult` should return `queryasyncjobresultresponse`.

Prefer command-specific responses in each spec when the page or action depends
on particular state. The fixture includes only small dashboard/list defaults so
missing command mocks fail loudly with HTTP 501.
