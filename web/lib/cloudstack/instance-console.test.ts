import assert from "node:assert/strict";
import test from "node:test";

import { createConsoleEndpoint } from "./instance-console.ts";

test("createConsoleEndpoint posts virtualmachineid only to the BFF", async () => {
  const requests: Array<{ url: URL; init: RequestInit; body: Record<string, string> }> = [];
  const fetchImpl: typeof fetch = async (input, init = {}) => {
    requests.push({
      url: new URL(String(input)),
      init,
      body: JSON.parse(String(init.body)) as Record<string, string>,
    });
    return Response.json({
      createconsoleendpointresponse: {
        consoleendpoint: {
          success: true,
          url: "https://console.example.test/client/console?token=secret-token",
          websockettoken: "websocket-secret",
        },
      },
    });
  };

  await createConsoleEndpoint("vm-1", { fetchImpl });

  assert.equal(requests.length, 1);
  assert.equal(requests[0]!.url.pathname, "/api/cs/createConsoleEndpoint");
  assert.equal(requests[0]!.init.method, "POST");
  assert.equal(requests[0]!.init.cache, "no-store");
  assert.equal((requests[0]!.init.headers as Record<string, string>)["content-type"], "application/json");
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
            websocket: { token: "websocket-secret" },
            details: "ready",
          },
        },
      }),
  });

  assert.deepEqual(result, {
    url: "https://console.example.test/session",
    websocket: { token: "websocket-secret" },
    details: "ready",
    websocketToken: null,
  });
});

test("createConsoleEndpoint supports legacy string endpoint envelopes", async () => {
  const result = await createConsoleEndpoint("vm-1", {
    fetchImpl: async () =>
      Response.json({
        createconsoleendpointresponse: {
          consoleendpoint: "https://console.example.test/string-endpoint",
          websockettoken: "websocket-secret",
        },
      }),
  });

  assert.deepEqual(result, {
    url: "https://console.example.test/string-endpoint",
    websocketToken: "websocket-secret",
  });
});

test("createConsoleEndpoint surfaces failure details without fabricating a URL", async () => {
  await assert.rejects(
    createConsoleEndpoint("vm-1", {
      fetchImpl: async () =>
        Response.json({
          createconsoleendpointresponse: {
            consoleendpoint: {
              success: false,
              details: "VM is not running",
            },
          },
        }),
    }),
    /VM is not running/,
  );
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

test("createConsoleEndpoint surfaces CloudStack HTTP error text", async () => {
  await assert.rejects(
    createConsoleEndpoint("vm-1", {
      fetchImpl: async () =>
        Response.json(
          {
            errorresponse: {
              errortext: "Console proxy is unavailable",
            },
          },
          { status: 503 },
        ),
    }),
    /Console proxy is unavailable/,
  );
});

test("createConsoleEndpoint does not pass extra sensitive or session parameters in request payload", async () => {
  const requests: Array<{ body: Record<string, string> }> = [];
  const fetchImpl: typeof fetch = async (_input, init = {}) => {
    requests.push({
      body: JSON.parse(String(init.body)) as Record<string, string>,
    });
    return Response.json({
      createconsoleendpointresponse: {
        consoleendpoint: {
          success: true,
          url: "https://console.example.test/session",
          websockettoken: "websocket-secret",
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
