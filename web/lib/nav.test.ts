import assert from "node:assert/strict";
import test from "node:test";

import { NAV_SECTION_DEFINITIONS } from "./nav-data.ts";
import messages from "../messages/en.json" with { type: "json" };

function readNavigationMessage(key: string): string | undefined {
  return key.split(".").reduce<unknown>((node, part) => {
    if (!node || typeof node !== "object") {
      return undefined;
    }
    return (node as Record<string, unknown>)[part];
  }, messages.Navigation) as string | undefined;
}

test("navigation config resolves all English message keys without changing routes", () => {
  const routes = NAV_SECTION_DEFINITIONS.flatMap((section) => section.items.map((item) => item.href));

  assert.deepEqual(routes, [
    "/",
    "/instances",
    "/networks",
    "/volumes",
    "/templates",
    "/kubernetes",
    "/events",
    "/accounts",
    "/ssh-keys",
    "/security",
    "/infrastructure",
    "/domains",
    "/billing",
  ]);

  for (const section of NAV_SECTION_DEFINITIONS) {
    assert.equal(typeof readNavigationMessage(section.titleKey), "string", section.titleKey);

    for (const item of section.items) {
      assert.equal(typeof readNavigationMessage(item.labelKey), "string", item.labelKey);
      if (item.badgeKey) {
        assert.equal(typeof readNavigationMessage(item.badgeKey), "string", item.badgeKey);
      }
    }
  }
});
