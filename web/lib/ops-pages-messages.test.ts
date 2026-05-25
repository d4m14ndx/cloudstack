import assert from "node:assert/strict";
import test from "node:test";

import messages from "../messages/en.json" with { type: "json" };

const OPS_PAGES = ["billing", "events", "kubernetes", "kubernetesDetail"] as const;

function readCoreMessage(key: string): string | undefined {
  return key.split(".").reduce<unknown>((node, part) => {
    if (!node || typeof node !== "object") {
      return undefined;
    }
    return (node as Record<string, unknown>)[part];
  }, (messages as Record<string, unknown>).Core) as string | undefined;
}

test("operations pages resolve all English message keys", () => {
  for (const page of OPS_PAGES) {
    assert.equal(typeof readCoreMessage(`pages.${page}.metadataTitle`), "string", page);
    assert.equal(typeof readCoreMessage(`pages.${page}.description`), "string", page);
  }

  for (const page of ["billing", "events", "kubernetes"] as const) {
    assert.equal(typeof readCoreMessage(`pages.${page}.title`), "string", page);
    assert.equal(typeof readCoreMessage(`pages.${page}.emptyState.title`), "string", page);
    assert.equal(typeof readCoreMessage(`pages.${page}.emptyState.description`), "string", page);
  }

  assert.equal(typeof readCoreMessage("pages.billing.table.accountProject"), "string");
  assert.equal(typeof readCoreMessage("pages.events.table.description"), "string");
  assert.equal(typeof readCoreMessage("pages.kubernetes.table.endpoint"), "string");
  assert.equal(typeof readCoreMessage("pages.kubernetesDetail.tabs.overview"), "string");
  assert.equal(typeof readCoreMessage("pages.kubernetesDetail.nodes.emptyState.title"), "string");
  assert.equal(typeof readCoreMessage("pages.kubernetesDetail.activity.emptyState.description"), "string");
});
