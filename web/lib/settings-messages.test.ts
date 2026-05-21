import assert from "node:assert/strict";
import test from "node:test";

import messages from "../messages/en.json" with { type: "json" };

const SETTINGS_PAGES = [
  "index",
  "profile",
  "security",
  "notifications",
  "apiTokens",
  "integrations",
  "billing",
  "advanced",
] as const;

function readSettingsMessage(key: string): string | undefined {
  return key.split(".").reduce<unknown>((node, part) => {
    if (!node || typeof node !== "object") {
      return undefined;
    }
    return (node as Record<string, unknown>)[part];
  }, (messages as Record<string, unknown>).Settings) as string | undefined;
}

test("settings pages resolve all English message keys", () => {
  for (const page of SETTINGS_PAGES) {
    assert.equal(typeof readSettingsMessage(`pages.${page}.metadataTitle`), "string", page);
    assert.equal(typeof readSettingsMessage(`pages.${page}.title`), "string", page);
    assert.equal(typeof readSettingsMessage(`pages.${page}.description`), "string", page);
    assert.equal(typeof readSettingsMessage(`pages.${page}.emptyState.title`), "string", page);
    assert.equal(typeof readSettingsMessage(`pages.${page}.emptyState.description`), "string", page);
  }
});
