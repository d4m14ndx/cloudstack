import assert from "node:assert/strict";
import test from "node:test";

import messages from "../messages/en.json" with { type: "json" };

const INSTANCE_KEYS = [
  "metadataTitle",
  "title",
  "description",
  "badges.running",
  "badges.stopped",
  "badges.attention",
  "table.instance",
  "table.state",
  "table.zone",
  "table.network",
  "table.ip",
  "table.offering",
  "table.cpu",
  "table.ram",
  "table.usage",
  "table.account",
  "table.actions",
  "usage.cpu",
  "usage.memory",
  "emptyState.title",
  "emptyState.description",
] as const;

const INSTANCE_DETAIL_KEYS = [
  "metadataTitle",
  "metrics.vcpu",
  "metrics.memory",
  "metrics.memoryUsage",
  "metrics.network",
  "metrics.storage",
  "metrics.volume",
  "metrics.volumes",
  "tabs.overview",
  "tabs.networking",
  "tabs.storage",
  "tabs.activity",
  "tabs.console",
  "sections.identity",
  "sections.placement",
  "sections.compute",
  "sections.image",
  "sections.console",
  "sections.networking",
  "sections.storage",
  "sections.activity",
  "labels.displayName",
  "labels.internalName",
  "labels.account",
  "labels.domain",
  "labels.project",
  "labels.created",
  "labels.zone",
  "labels.pod",
  "labels.cluster",
  "labels.host",
  "labels.hypervisor",
  "labels.ha",
  "labels.offering",
  "labels.cpu",
  "labels.cpuSpeed",
  "labels.cpuUsage",
  "labels.memory",
  "labels.memoryUsage",
  "labels.template",
  "labels.templateText",
  "labels.iso",
  "labels.serviceOffering",
  "labels.diskOffering",
  "labels.securityGroups",
  "labels.instance",
  "labels.state",
  "values.enabled",
  "values.disabled",
  "values.vcpu",
  "values.gib",
  "values.speedMHz",
  "networking.table.network",
  "networking.table.role",
  "networking.table.privateIp",
  "networking.table.publicIp",
  "networking.table.gateway",
  "networking.table.netmask",
  "networking.table.mac",
  "networking.badges.default",
  "networking.badges.nic",
  "networking.emptyState.title",
  "networking.emptyState.description",
  "storage.table.volume",
  "storage.table.state",
  "storage.table.zone",
  "storage.table.tier",
  "storage.table.size",
  "storage.table.attachedTo",
  "storage.emptyState.title",
  "storage.emptyState.description",
  "activity.table.time",
  "activity.table.level",
  "activity.table.action",
  "activity.table.target",
  "activity.table.user",
  "activity.table.description",
  "activity.emptyState.title",
  "activity.emptyState.description",
] as const;

function readComputeMessage(key: string): string | undefined {
  return key.split(".").reduce<unknown>((node, part) => {
    if (!node || typeof node !== "object") {
      return undefined;
    }
    return (node as Record<string, unknown>)[part];
  }, (messages as Record<string, unknown>).Compute) as string | undefined;
}

test("compute pages resolve all English message keys", () => {
  for (const key of INSTANCE_KEYS) {
    assert.equal(typeof readComputeMessage(`pages.instances.${key}`), "string", key);
  }

  for (const key of INSTANCE_DETAIL_KEYS) {
    assert.equal(typeof readComputeMessage(`pages.instanceDetail.${key}`), "string", key);
  }
});
