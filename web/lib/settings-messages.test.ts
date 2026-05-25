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

const SETTINGS_INDEX_STATES = ["available", "related", "notConfigured"] as const;

const SETTINGS_INDEX_SECTIONS = [
  "profile",
  "security",
  "apiTokens",
  "localization",
  "notifications",
  "sessions",
  "account",
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

    if (page !== "index") {
      assert.equal(typeof readSettingsMessage(`pages.${page}.emptyState.title`), "string", page);
      assert.equal(typeof readSettingsMessage(`pages.${page}.emptyState.description`), "string", page);
    }
  }
});

test("settings index resolves section and state message keys", () => {
  for (const state of SETTINGS_INDEX_STATES) {
    assert.equal(typeof readSettingsMessage(`pages.index.states.${state}`), "string", state);
  }

  for (const section of SETTINGS_INDEX_SECTIONS) {
    assert.equal(typeof readSettingsMessage(`pages.index.sections.${section}.title`), "string", section);
    assert.equal(typeof readSettingsMessage(`pages.index.sections.${section}.description`), "string", section);
  }
});
