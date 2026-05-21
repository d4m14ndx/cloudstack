import { mockSecurityGroups, type SecurityGroup, type SecurityGroupRule } from "../mock-data.ts";

export type CloudStackSecurityGroupRule = {
  ruleid?: string;
  protocol?: string;
  startport?: number | string;
  endport?: number | string;
  icmptype?: number | string;
  icmpcode?: number | string;
  cidr?: string;
  securitygroupname?: string;
  account?: string;
};

export type CloudStackSecurityGroup = {
  id?: string;
  name?: string;
  description?: string;
  account?: string;
  domainid?: string;
  domain?: string;
  domainpath?: string;
  projectid?: string;
  project?: string;
  ingressrule?: CloudStackSecurityGroupRule[];
  egressrule?: CloudStackSecurityGroupRule[];
  virtualmachinecount?: number | string;
  virtualmachineids?: string[] | string;
};

export type ListSecurityGroupsResponse = {
  listsecuritygroupsresponse?: {
    count?: number | string;
    securitygroup?: CloudStackSecurityGroup[];
  };
};

type FetchOptions = {
  fetchImpl?: typeof fetch;
  requestHeaders?: Pick<Headers, "get">;
};

export async function getSecurityGroupsFromBff({
  fetchImpl = fetch,
  requestHeaders,
}: FetchOptions = {}): Promise<SecurityGroup[]> {
  if (process.env.NEXT_PUBLIC_APP_ENV === "mock" || !process.env.CS_URL) {
    return mockSecurityGroups;
  }

  try {
    const response = await fetchImpl(buildListSecurityGroupsUrl(requestHeaders), {
      method: "GET",
      cache: "no-store",
      headers: buildForwardedHeaders(requestHeaders),
    });

    if (!response.ok) {
      return mockSecurityGroups;
    }

    const payload = (await response.json()) as ListSecurityGroupsResponse;
    if (!payload.listsecuritygroupsresponse) {
      return mockSecurityGroups;
    }

    return securityGroupsFromListSecurityGroupsResponse(payload);
  } catch {
    return mockSecurityGroups;
  }
}

export function securityGroupsFromListSecurityGroupsResponse(response: ListSecurityGroupsResponse): SecurityGroup[] {
  return (response.listsecuritygroupsresponse?.securitygroup ?? []).map(mapCloudStackSecurityGroupToSecurityGroup);
}

export function mapCloudStackSecurityGroupToSecurityGroup(group: CloudStackSecurityGroup): SecurityGroup {
  const id = group.id ?? group.name ?? "unknown";

  return {
    id,
    name: group.name ?? group.id ?? "unnamed-security-group",
    description: group.description ?? "-",
    account: group.account ?? group.project ?? "unknown",
    domain: group.domain ?? group.domainpath ?? "unknown",
    domainId: group.domainid ?? null,
    domainPath: group.domainpath ?? group.domain ?? "unknown",
    project: group.project ?? null,
    projectId: group.projectid ?? null,
    ingressRules: (group.ingressrule ?? []).map(mapCloudStackSecurityGroupRuleToSecurityGroupRule),
    egressRules: (group.egressrule ?? []).map(mapCloudStackSecurityGroupRuleToSecurityGroupRule),
    instances: readNonNegativeInteger(group.virtualmachinecount) ?? readVirtualMachineIds(group.virtualmachineids).length,
    instanceIds: readVirtualMachineIds(group.virtualmachineids),
    isDefault: normalize(group.name) === "default",
  };
}

function mapCloudStackSecurityGroupRuleToSecurityGroupRule(rule: CloudStackSecurityGroupRule): SecurityGroupRule {
  const protocol = (rule.protocol ?? "any").toUpperCase();
  const range = protocol === "ICMP" ? formatIcmpRange(rule) : formatPortRange(rule);
  const source = formatRuleSource(rule);

  return {
    id: rule.ruleid ?? buildRuleId(protocol, range, source),
    protocol,
    range,
    source,
  };
}

function formatPortRange(rule: CloudStackSecurityGroupRule): string {
  const startPort = stringifyNumber(rule.startport);
  const endPort = stringifyNumber(rule.endport);

  if (!startPort && !endPort) {
    return "all";
  }

  if (!endPort || startPort === endPort) {
    return startPort ?? endPort ?? "all";
  }

  if (!startPort) {
    return endPort;
  }

  return `${startPort}-${endPort}`;
}

function formatIcmpRange(rule: CloudStackSecurityGroupRule): string {
  return `type ${stringifyNumber(rule.icmptype) ?? "any"} / code ${stringifyNumber(rule.icmpcode) ?? "any"}`;
}

function formatRuleSource(rule: CloudStackSecurityGroupRule): string {
  if (rule.cidr) {
    return rule.cidr;
  }

  if (rule.securitygroupname) {
    return rule.account ? `${rule.account}/${rule.securitygroupname}` : rule.securitygroupname;
  }

  return "any";
}

function buildRuleId(protocol: string, range: string, source: string): string {
  const normalizedRange = protocol === "ICMP" ? "icmp" : range;
  return `${protocol.toLowerCase()}-${normalizedRange}-${source}`;
}

function readVirtualMachineIds(value: string[] | string | undefined): string[] {
  if (Array.isArray(value)) {
    return value.filter(Boolean);
  }

  if (typeof value === "string") {
    return value
      .split(",")
      .map((id) => id.trim())
      .filter(Boolean);
  }

  return [];
}

function readNonNegativeInteger(value: number | string | undefined): number | null {
  if (typeof value === "number" && Number.isFinite(value) && value >= 0) {
    return Math.floor(value);
  }

  if (typeof value === "string") {
    const parsed = Number.parseInt(value, 10);
    return Number.isFinite(parsed) && parsed >= 0 ? parsed : null;
  }

  return null;
}

function stringifyNumber(value: number | string | undefined): string | null {
  if (typeof value === "number" && Number.isFinite(value)) {
    return String(value);
  }

  if (typeof value === "string" && value.trim()) {
    return value.trim();
  }

  return null;
}

function normalize(value: string | undefined): string {
  return value?.trim().toLowerCase() ?? "";
}

function buildListSecurityGroupsUrl(requestHeaders?: Pick<Headers, "get">): string {
  const params = new URLSearchParams({
    listall: "true",
  });
  const origin = getRequestOrigin(requestHeaders);
  return `${origin}/api/cs/listSecurityGroups?${params.toString()}`;
}

function getRequestOrigin(requestHeaders?: Pick<Headers, "get">): string {
  if (process.env.NEXTAUTH_URL) {
    return process.env.NEXTAUTH_URL.replace(/\/$/, "");
  }

  const host = requestHeaders?.get("x-forwarded-host") ?? requestHeaders?.get("host") ?? "localhost:3000";
  const protocol = requestHeaders?.get("x-forwarded-proto") ?? (host.startsWith("localhost") ? "http" : "https");
  return `${protocol}://${host}`;
}

function buildForwardedHeaders(requestHeaders?: Pick<Headers, "get">): HeadersInit | undefined {
  const cookie = requestHeaders?.get("cookie");
  return cookie ? { cookie } : undefined;
}
