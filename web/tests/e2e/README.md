# Browser Smoke Tests

Run from `web/`:

```bash
npm run test:e2e
```

The Playwright config starts `next dev` on `127.0.0.1:3000` and enables a
dev-only BFF session. It also points `CS_URL` at a tiny local CloudStack-shaped
HTTP server started by the fixture, so smoke tests do not need a live CloudStack
server, Redis, or an external identity provider. The app shell uses the existing
mock current-user bridge, which renders `Alex Kim` as the logged-in operator.

Use `PLAYWRIGHT_PORT` when running multiple worktrees or repeated local runs:

```bash
PLAYWRIGHT_PORT=3133 npm run test:e2e
```

## Mocking CloudStack Commands

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

`mockCloudStackBff.use(command, handler)` feeds both paths used by the app:

- browser-side `/api/cs/*` route interception for client actions
- server-rendered page fetches that go through the real BFF route and then the
  local CloudStack backend mock

Calls from both paths are captured in `mockCloudStackBff.calls(command)`. POST
assertions can use `call.json`; GET/backend assertions can use
`call.params.get("name")`.

Keep mocked payloads close to CloudStack response envelopes:

- `/api/cs/listVirtualMachines` returns `listvirtualmachinesresponse`.
- `/api/cs/listZones` returns `listzonesresponse`.
- `/api/cs/queryAsyncJobResult` should return `queryasyncjobresultresponse`.

Prefer command-specific responses in each spec when the page or action depends
on particular state. The fixture includes only small dashboard/list defaults, so
missing command mocks fail loudly with HTTP 501 instead of silently falling back
to unrelated data.

## Coverage Patterns

Use BFF-backed page-data specs for server-rendered resource pages. These specs
should seed distinctive CloudStack envelopes, assert rendered table/detail copy,
and check the command/query shape captured in `mockCloudStackBff.calls(...)`.

Use action specs for mutation surfaces. Prefer checking both the safe POST
payload and the user-facing status or alert text, especially around async job
failure paths.

For App Router not-found paths, assert the visible not-found UI and BFF lookup
shape. In the local Next dev harness, client navigations can render the
not-found route while the initial Playwright navigation response still reports
HTTP 200.
