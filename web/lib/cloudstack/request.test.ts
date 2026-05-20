import assert from "node:assert/strict";
import test from "node:test";

import { buildProxySearchParams, isValidCloudStackCommand } from "./request.ts";

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
