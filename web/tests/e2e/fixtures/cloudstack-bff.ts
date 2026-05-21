import { test as base, expect, type Route } from "@playwright/test";

type JsonPrimitive = string | number | boolean | null;
type JsonValue = JsonPrimitive | JsonValue[] | { [key: string]: JsonValue };

export type CloudStackBffRequest = {
  command: string;
  method: string;
  url: URL;
  params: URLSearchParams;
  postData: string | null;
  json: Record<string, JsonValue>;
};

export type CloudStackBffCall = CloudStackBffRequest;

export type CloudStackBffHandler = JsonValue | ((request: CloudStackBffRequest) => JsonValue | Promise<JsonValue>);

export type MockCloudStackBff = {
  calls(command?: string): CloudStackBffCall[];
  reset(): void;
  use(command: string, handler: CloudStackBffHandler): void;
};

type CloudStackBffFixture = {
  mockCloudStackBff: MockCloudStackBff;
};

const DEFAULT_COMMANDS: Record<string, CloudStackBffHandler> = {
  listVirtualMachines: ({ params }) => ({
    listvirtualmachinesresponse: {
      count: params.get("state") === "Running" ? 4 : 7,
      virtualmachine: [
        {
          id: "vm-smoke-1",
          name: "smoke-web-01",
          displayname: "smoke-web-01",
          state: "Running",
          zonename: "zone-a",
          serviceofferingname: "Small Instance",
          templatename: "Debian 12",
          cpunumber: 2,
          memory: 4096,
          account: "admin",
          nic: [{ ipaddress: "10.0.0.14", networkname: "guest-net", isdefault: true }],
        },
      ],
    },
  }),
  listHosts: {
    listhostsresponse: {
      count: 3,
      host: [{ id: "host-smoke-1", name: "kvm-host-01", state: "Up", type: "Routing" }],
    },
  },
  listZones: {
    listzonesresponse: {
      count: 2,
      zone: [
        { id: "zone-a", name: "Zone A", allocationstate: "Enabled" },
        { id: "zone-b", name: "Zone B", allocationstate: "Enabled" },
      ],
    },
  },
  listCapacity: {
    listcapacityresponse: {
      capacity: [
        { type: 90, name: "CPU_CORE", capacityallocated: 32, capacitytotal: 96 },
        { type: 0, name: "MEMORY", capacityallocated: 17179869184, capacitytotal: 68719476736 },
        { type: 2, name: "STORAGE", capacityused: 1099511627776, capacitytotal: 4398046511104 },
      ],
    },
  },
};

export const test = base.extend<CloudStackBffFixture>({
  mockCloudStackBff: async ({ page }, use) => {
    const handlers = new Map<string, CloudStackBffHandler>(Object.entries(DEFAULT_COMMANDS));
    const calls: CloudStackBffCall[] = [];

    await page.route("**/api/cs/*", async (route) => {
      await fulfillCloudStackRoute(route, handlers, calls);
    });

    await use({
      calls(command) {
        return command ? calls.filter((call) => call.command === command) : [...calls];
      },
      reset() {
        calls.length = 0;
        handlers.clear();
        for (const [command, handler] of Object.entries(DEFAULT_COMMANDS)) {
          handlers.set(command, handler);
        }
      },
      use(command, handler) {
        handlers.set(command, handler);
      },
    });
  },
});

export { expect };

async function fulfillCloudStackRoute(
  route: Route,
  handlers: Map<string, CloudStackBffHandler>,
  calls: CloudStackBffCall[],
): Promise<void> {
  const request = route.request();
  const url = new URL(request.url());
  const command = decodeURIComponent(url.pathname.split("/").pop() ?? "");
  const bffRequest = {
    command,
    method: request.method(),
    url,
    params: url.searchParams,
    postData: request.postData(),
    json: readJsonBody(request.postData()),
  };
  calls.push(bffRequest);

  const handler = handlers.get(command);
  if (!handler) {
    await route.fulfill({
      status: 501,
      contentType: "application/json",
      body: JSON.stringify({ error: `No mocked CloudStack BFF response for ${command}` }),
    });
    return;
  }

  const json = typeof handler === "function" ? await handler(bffRequest) : handler;
  await route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify(json),
  });
}

function readJsonBody(postData: string | null): Record<string, JsonValue> {
  if (!postData) {
    return {};
  }

  try {
    const parsed = JSON.parse(postData) as unknown;
    return isJsonRecord(parsed) ? parsed : {};
  } catch {
    return {};
  }
}

function isJsonRecord(value: unknown): value is Record<string, JsonValue> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}
