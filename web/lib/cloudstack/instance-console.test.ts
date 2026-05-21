import assert from "node:assert/strict";
import test from "node:test";

import { createConsoleEndpoint } from "./instance-console.ts";

test("createConsoleEndpoint posts virtualmachineid only to the BFF", async () => {
  const requests: Array<{ url: string; init?: RequestInit; body: Record<string, string> }> = [];
  const fetchImpl: typeof fetch = async (input, init) => {
    requests.push({
      url: String(input),
      init,
      body: JSON.parse(String(init?.body)) as Record<string, string>,
    });
    return Response.json({
      createconsoleendpointresponse: {
        consoleendpoint: {
          success: true,
          url: "wss://console.example.test/token",
        },
      },
    });
  };

  await createConsoleEndpoint("vm-1", { fetchImpl });

  assert.equal(requests.length, 1);
  assert.equal(new URL(requests[0]!.url).pathname, "/api/cs/createConsoleEndpoint");
  assert.equal(requests[0]!.init?.method, "POST");
  assert.equal((requests[0]!.init?.headers as Record<string, string>)["content-type"], "application/json");
  assert.deepEqual(requests[0]!.body, { virtualmachineid: "vm-1" });
});

test("createConsoleEndpoint normalizes successful URL responses", async () => {
  const result = await createConsoleEndpoint("vm-1", {
    fetchImpl: async () =>
      Response.json({
        createconsoleendpointresponse: {
          consoleendpoint: {
            success: "true",
            url: "https://console.example.test/session",
            websocket: "wss://console.example.test/session",
            details: "ready",
          },
        },
      }),
  });

  assert.deepEqual(result, {
    success: true,
    url: "https://console.example.test/session",
    websocket: "wss://console.example.test/session",
    details: "ready",
  });
});

test("createConsoleEndpoint returns failure details without a URL", async () => {
  const result = await createConsoleEndpoint("vm-1", {
    fetchImpl: async () =>
      Response.json({
        createconsoleendpointresponse: {
          consoleendpoint: {
            success: false,
            details: "VM is not running",
          },
        },
      }),
  });

  assert.deepEqual(result, {
    success: false,
    details: "VM is not running",
  });
  assert.equal("url" in result, false);
});

test("createConsoleEndpoint rejects malformed successful responses that omit url", async () => {
  await assert.rejects(
    createConsoleEndpoint("vm-1", {
      fetchImpl: async () =>
        Response.json({
          createconsoleendpointresponse: {
            consoleendpoint: {
              success: true,
              details: "ready",
            },
          },
        }),
    }),
    /missing console URL/,
  );
});

test("createConsoleEndpoint does not pass extra sensitive or session parameters in request payload", async () => {
  const requests: Array<{ body: Record<string, string> }> = [];
  const fetchImpl: typeof fetch = async (_input, init) => {
    requests.push({
      body: JSON.parse(String(init?.body)) as Record<string, string>,
    });
    return Response.json({
      createconsoleendpointresponse: {
        consoleendpoint: {
          success: true,
          url: "https://console.example.test/session",
          websocket: "wss://console.example.test/session",
        },
      },
    });
  };

  await createConsoleEndpoint("vm-1", { fetchImpl });

  assert.deepEqual(requests[0]!.body, { virtualmachineid: "vm-1" });
  assert.equal("sessionkey" in requests[0]!.body, false);
  assert.equal("command" in requests[0]!.body, false);
  assert.equal("response" in requests[0]!.body, false);
  assert.equal("url" in requests[0]!.body, false);
  assert.equal("websocket" in requests[0]!.body, false);
  assert.equal("token" in requests[0]!.body, false);
});
