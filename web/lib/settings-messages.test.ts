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

const PROFILE_SETTINGS_MESSAGE_KEYS = [
  "pages.profile.fields.username",
  "pages.profile.fields.email",
  "pages.profile.fields.account",
  "pages.profile.fields.domain",
  "pages.profile.fields.timezone",
  "pages.profile.fields.source",
  "pages.profile.summary.title",
  "pages.profile.summary.role",
  "pages.profile.summary.state",
  "pages.profile.summary.apiKeyAccess",
  "pages.profile.summary.twoFactor",
  "pages.profile.states.enabled",
  "pages.profile.states.disabled",
  "pages.profile.apiKeyAccess.enabled",
  "pages.profile.apiKeyAccess.disabled",
  "pages.profile.apiKeyAccess.unknown",
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

test("settings profile resolves operational message keys", () => {
  for (const key of PROFILE_SETTINGS_MESSAGE_KEYS) {
    assert.equal(typeof readSettingsMessage(key), "string", key);
  }
});
