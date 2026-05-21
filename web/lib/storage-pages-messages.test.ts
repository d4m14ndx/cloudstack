import assert from "node:assert/strict";
import test from "node:test";

import messages from "../messages/en.json" with { type: "json" };

const STORAGE_PAGES = ["volumes", "templates", "sshKeys"] as const;

const PAGE_KEYS = [
  "metadataTitle",
  "title",
  "description",
  "emptyState.title",
  "emptyState.description",
] as const;

const PAGE_SPECIFIC_KEYS: Record<(typeof STORAGE_PAGES)[number], readonly string[]> = {
  volumes: [
    "badges.total",
    "badges.attached",
    "badges.unattached",
    "badges.detaching",
    "table.volume",
    "table.state",
    "table.zone",
    "table.tier",
    "table.size",
    "table.attachedTo",
    "table.actions",
    "units.gib",
  ],
  templates: [
    "badges.total",
    "badges.featured",
    "badges.x86",
    "badges.arm",
    "table.template",
    "table.os",
    "table.arch",
    "table.hypervisors",
    "table.size",
    "table.account",
    "table.featured",
    "table.actions",
    "labels.featuredYes",
    "labels.featuredNo",
  ],
  sshKeys: [
    "badges.total",
    "badges.accounts",
    "badges.domains",
    "badges.projectScoped",
    "table.keyPair",
    "table.fingerprint",
    "table.account",
    "table.domain",
    "table.project",
    "table.actions",
  ],
};

function readCoreMessage(key: string): string | undefined {
  return key.split(".").reduce<unknown>((node, part) => {
    if (!node || typeof node !== "object") {
      return undefined;
    }
    return (node as Record<string, unknown>)[part];
  }, (messages as Record<string, unknown>).Core) as string | undefined;
}

test("storage, image, and SSH key pages resolve all English message keys", () => {
  for (const page of STORAGE_PAGES) {
    for (const key of [...PAGE_KEYS, ...PAGE_SPECIFIC_KEYS[page]]) {
      assert.equal(typeof readCoreMessage(`pages.${page}.${key}`), "string", `${page}.${key}`);
    }
  }
});
