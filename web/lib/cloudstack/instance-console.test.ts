import assert from "node:assert/strict";
import test from "node:test";

import { createConsoleEndpoint } from "./instance-console.ts";

test("createConsoleEndpoint posts only the VM id and returns the nested console endpoint", async () => {
  const requests: Array<{ url: URL; init: RequestInit }> = [];
  const fetchImpl: typeof fetch = async (input, init = {}) => {
    requests.push({ url: new URL(String(input)), init });
    return Response.json({
      createconsoleendpointresponse: {
        consoleendpoint: {
          url: "https://console.example.test/client/console?token=secret-token",
          success: true,
          websockettoken: "websocket-secret",
        },
      },
    });
  };

  const result = await createConsoleEndpoint("vm-1", { fetchImpl });

  assert.deepEqual(result, {
    url: "https://console.example.test/client/console?token=secret-token",
    websocketToken: "websocket-secret",
  });
  assert.equal(requests.length, 1);
  assert.equal(requests[0]!.url.pathname, "/api/cs/createConsoleEndpoint");
  assert.equal(requests[0]!.init.method, "POST");
  assert.equal(requests[0]!.init.cache, "no-store");
  assert.equal((requests[0]!.init.headers as Record<string, string>)["content-type"], "application/json");
  assert.deepEqual(JSON.parse(String(requests[0]!.init.body)), { virtualmachineid: "vm-1" });
});

test("createConsoleEndpoint surfaces CloudStack failure text without fabricating a URL", async () => {
  const fetchImpl: typeof fetch = async () =>
    Response.json(
      {
        errorresponse: {
          errortext: "Console proxy is unavailable",
        },
      },
      { status: 503 },
    );

  await assert.rejects(
    () => createConsoleEndpoint("vm-1", { fetchImpl }),
    /Console proxy is unavailable/,
  );
});

test("createConsoleEndpoint rejects malformed responses", async () => {
  const fetchImpl: typeof fetch = async () =>
    Response.json({
      createconsoleendpointresponse: {
        websockettoken: "websocket-secret",
      },
    });

  await assert.rejects(
    () => createConsoleEndpoint("vm-1", { fetchImpl }),
    /missing console endpoint/,
  );
});

test("createConsoleEndpoint reports unsuccessful endpoint details", async () => {
  const fetchImpl: typeof fetch = async () =>
    Response.json({
      createconsoleendpointresponse: {
        consoleendpoint: {
          success: false,
          details: "No console proxy is available",
        },
      },
    });

  await assert.rejects(
    () => createConsoleEndpoint("vm-1", { fetchImpl }),
    /No console proxy is available/,
  );
});
