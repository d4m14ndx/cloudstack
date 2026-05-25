import assert from "node:assert/strict";
import test from "node:test";

import messages from "../messages/en.json" with { type: "json" };

const CORE_PAGES = ["overview", "accounts", "domains", "infrastructure"] as const;

function readCoreMessage(key: string): string | undefined {
  return key.split(".").reduce<unknown>((node, part) => {
    if (!node || typeof node !== "object") {
      return undefined;
    }
    return (node as Record<string, unknown>)[part];
  }, (messages as Record<string, unknown>).Core) as string | undefined;
}

test("core pages resolve all English message keys", () => {
  for (const page of CORE_PAGES) {
    assert.equal(typeof readCoreMessage(`pages.${page}.metadataTitle`), "string", page);
    assert.equal(typeof readCoreMessage(`pages.${page}.title`), "string", page);
    assert.equal(typeof readCoreMessage(`pages.${page}.description`), "string", page);
    assert.equal(typeof readCoreMessage(`pages.${page}.emptyState.description`), "string", page);
  }

  for (const page of ["accounts", "domains", "infrastructure"] as const) {
    assert.equal(typeof readCoreMessage(`pages.${page}.emptyState.title`), "string", page);
  }

  assert.equal(typeof readCoreMessage("pages.overview.greeting.morning"), "string");
  assert.equal(typeof readCoreMessage("pages.overview.greeting.afternoon"), "string");
  assert.equal(typeof readCoreMessage("pages.overview.greeting.evening"), "string");
  assert.equal(typeof readCoreMessage("pages.overview.actions.exportReport"), "string");
  assert.equal(typeof readCoreMessage("pages.overview.actions.deployInstance"), "string");
});
