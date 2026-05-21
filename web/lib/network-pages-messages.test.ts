import assert from "node:assert/strict";
import test from "node:test";

import messages from "../messages/en.json" with { type: "json" };

const REQUIRED_CORE_MESSAGES = [
  "pages.networks.metadataTitle",
  "pages.networks.title",
  "pages.networks.description",
  "pages.networks.emptyState.title",
  "pages.networks.emptyState.description",
  "pages.networkDetail.metadataTitle",
  "pages.networkDetail.metrics.cidr",
  "pages.networkDetail.tabs.overview",
  "pages.networkDetail.emptyState.tiers.title",
  "pages.networkDetail.emptyState.publicIps.description",
  "pages.networkDetail.emptyState.aclLists.title",
  "pages.networkDetail.emptyState.activity.description",
  "pages.securityGroups.metadataTitle",
  "pages.securityGroups.title",
  "pages.securityGroups.description",
  "pages.securityGroups.emptyState.title",
  "pages.securityGroups.emptyState.description",
] as const;

function readCoreMessage(key: string): string | undefined {
  return key.split(".").reduce<unknown>((node, part) => {
    if (!node || typeof node !== "object") {
      return undefined;
    }
    return (node as Record<string, unknown>)[part];
  }, (messages as Record<string, unknown>).Core) as string | undefined;
}

test("network and security pages resolve all English message keys", () => {
  for (const key of REQUIRED_CORE_MESSAGES) {
    assert.equal(typeof readCoreMessage(key), "string", key);
  }
});
