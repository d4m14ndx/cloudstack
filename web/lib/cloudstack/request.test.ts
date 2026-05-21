import assert from "node:assert/strict";
import test from "node:test";

import { buildProxySearchParams, isValidCloudStackCommand, readClientBodyParams } from "./request.ts";

test("isValidCloudStackCommand accepts CloudStack command names only", () => {
  assert.equal(isValidCloudStackCommand("listVirtualMachines"), true);
  assert.equal(isValidCloudStackCommand("deployVirtualMachine2"), true);
  assert.equal(isValidCloudStackCommand("../listVirtualMachines"), false);
  assert.equal(isValidCloudStackCommand("list VirtualMachines"), false);
  assert.equal(isValidCloudStackCommand(""), false);
});

test("buildProxySearchParams strips client sessionkey and forces response json", () => {
  const input = new URLSearchParams({
    account: "alice",
    sessionkey: "client-secret",
    response: "xml",
  });

  const output = buildProxySearchParams("listVirtualMachines", input, "server-secret");

  assert.equal(output.get("command"), "listVirtualMachines");
  assert.equal(output.get("account"), "alice");
  assert.equal(output.get("sessionkey"), "server-secret");
  assert.equal(output.get("response"), "json");
  assert.equal(output.toString().includes("client-secret"), false);
});

test("JSON body params are sanitized before proxying", async () => {
  const request = new Request("http://localhost/api/cs/listVirtualMachines", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      account: "alice",
      sessionkey: "client-secret",
      command: "deleteEverything",
      response: "xml",
    }),
  });

  const bodyParams = await readClientBodyParams(request);
  const output = buildProxySearchParams("listVirtualMachines", bodyParams, "server-secret");

  assert.equal(output.get("command"), "listVirtualMachines");
  assert.equal(output.get("account"), "alice");
  assert.equal(output.get("sessionkey"), "server-secret");
  assert.equal(output.get("response"), "json");
  assert.equal(output.toString().includes("client-secret"), false);
  assert.equal(output.toString().includes("deleteEverything"), false);
});

test("form body params are sanitized before proxying", async () => {
  const request = new Request("http://localhost/api/cs/listVirtualMachines", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      zoneid: "zone-1",
      sessionkey: "client-secret",
      response: "xml",
    }),
  });

  const bodyParams = await readClientBodyParams(request);
  const output = buildProxySearchParams("listVirtualMachines", bodyParams, "server-secret");

  assert.equal(output.get("zoneid"), "zone-1");
  assert.equal(output.get("sessionkey"), "server-secret");
  assert.equal(output.get("response"), "json");
  assert.equal(output.toString().includes("client-secret"), false);
});
