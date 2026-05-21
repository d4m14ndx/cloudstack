import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";

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
          id: "i-9f3a2b",
          name: "web-prod-01",
          displayname: "web-prod-01",
          state: "Running",
          zonename: "syd-1",
          serviceofferingname: "Compute-M",
          templatename: "Ubuntu 22.04 LTS",
          cpunumber: 4,
          memory: 8192,
          account: "admin",
          domain: "ROOT",
          created: "2026-04-01T00:00:00+0000",
          nic: [{ ipaddress: "10.1.0.14", networkname: "prod-vpc", isdefault: true, publicip: "203.0.113.41" }],
        },
        {
          id: "i-1e9d4c",
          name: "worker-01",
          displayname: "worker-01",
          state: "Stopped",
          zonename: "syd-1",
          serviceofferingname: "Compute-S",
          templatename: "Debian 12",
          cpunumber: 2,
          memory: 4096,
          account: "data-team",
          domain: null,
          created: null,
          nic: [{ ipaddress: "10.1.3.11", networkname: "prod-vpc", isdefault: true, publicip: null }],
        },
        {
          id: "i-7a1d6e",
          name: "exp-llama",
          displayname: "exp-llama",
          state: "Error",
          zonename: "syd-2",
          serviceofferingname: "GPU-A100",
          templatename: "Ubuntu 24.04 + CUDA",
          cpunumber: 16,
          memory: 131072,
          account: "research",
          domain: null,
          created: null,
          nic: [{ ipaddress: "10.5.0.3", networkname: "ml-vpc", isdefault: true, publicip: null }],
        },
      ],
    },
  }),
  listVolumes: {
    listvolumesresponse: {
      count: 2,
      volume: [
        {
          id: "v-9d3a",
          name: "web-prod-01-root",
          size: 42_949_672_960,
          type: "ROOT",
          virtualmachineid: "i-9f3a2b",
          virtualmachinename: "web-prod-01",
          zonename: "syd-1",
          state: "Ready",
        },
        {
          id: "v-1a4b",
          name: "backups-archive",
          size: 4_398_046_511_104,
          type: "DATADISK",
          zonename: "syd-1",
          state: "Ready",
        },
      ],
    },
  },
  listEvents: { listeventsresponse: { count: 0, event: [] } },
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
    const backendServer = await startCloudStackBackendServer(handlers, calls);

    await page.route("**/api/cs/*", async (route) => {
      await fulfillCloudStackRoute(route, handlers, calls);
    });

    try {
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
    } finally {
      await closeServer(backendServer);
    }
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
  const bffRequest: CloudStackBffRequest = {
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

async function startCloudStackBackendServer(
  handlers: Map<string, CloudStackBffHandler>,
  calls: CloudStackBffCall[],
): Promise<Server> {
  const server = createServer((request, response) => {
    void fulfillCloudStackBackendRequest(request, response, handlers, calls);
  });
  const port = readCloudStackMockPort();

  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(port, "127.0.0.1", () => {
      server.off("error", reject);
      resolve();
    });
  });

  return server;
}

async function fulfillCloudStackBackendRequest(
  request: IncomingMessage,
  response: ServerResponse,
  handlers: Map<string, CloudStackBffHandler>,
  calls: CloudStackBffCall[],
): Promise<void> {
  const url = new URL(request.url ?? "/", `http://${request.headers.host ?? "127.0.0.1"}`);
  const postData = await readRequestBody(request);
  const params = new URLSearchParams(url.searchParams);
  if (postData && request.method !== "GET") {
    for (const [key, value] of new URLSearchParams(postData).entries()) {
      params.append(key, value);
    }
  }

  const command = params.get("command") ?? "";
  const bffRequest: CloudStackBffRequest = {
    command,
    method: request.method ?? "GET",
    url,
    params,
    postData,
    json: readJsonBody(postData),
  };
  calls.push(bffRequest);

  const handler = handlers.get(command);
  if (!handler) {
    writeJsonResponse(response, 501, { error: `No mocked CloudStack backend response for ${command}` });
    return;
  }

  const json = typeof handler === "function" ? await handler(bffRequest) : handler;
  writeJsonResponse(response, 200, json);
}

function writeJsonResponse(response: ServerResponse, statusCode: number, json: JsonValue): void {
  response.writeHead(statusCode, { "content-type": "application/json" });
  response.end(JSON.stringify(json));
}

async function readRequestBody(request: IncomingMessage): Promise<string | null> {
  const chunks: Buffer[] = [];
  for await (const chunk of request) {
    chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
  }
  return chunks.length ? Buffer.concat(chunks).toString("utf8") : null;
}

async function closeServer(server: Server): Promise<void> {
  await new Promise<void>((resolve, reject) => {
    server.close((error) => {
      if (error) {
        reject(error);
        return;
      }
      resolve();
    });
  });
}

function readCloudStackMockPort(): number {
  const port = Number(process.env.PLAYWRIGHT_CLOUDSTACK_MOCK_PORT ?? Number(process.env.PLAYWRIGHT_PORT ?? 3000) + 1000);
  return Number.isFinite(port) && port > 0 ? port : 4000;
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
