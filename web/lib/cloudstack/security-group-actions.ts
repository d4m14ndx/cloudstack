type FetchOptions = {
  fetchImpl?: typeof fetch;
};

export type SecurityGroupActionResult = {
  id: string | null;
  name: string | null;
  ruleId: string | null;
  jobId: string | null;
  success: boolean;
};

export type CreateSecurityGroupInput = {
  name: string;
  description?: string;
  account?: string;
  domainId?: string;
  projectId?: string;
} & Record<string, unknown>;

export type DeleteSecurityGroupInput = {
  id?: string;
  name?: string;
  account?: string;
  domainId?: string;
  projectId?: string;
} & Record<string, unknown>;

export type SecurityGroupRuleInput = {
  securityGroupId?: string;
  securityGroupName?: string;
  account?: string;
  domainId?: string;
  projectId?: string;
  protocol: "tcp" | "udp" | "icmp" | string;
  cidrList?: string;
  startPort?: number | string;
  endPort?: number | string;
  icmpType?: number | string;
  icmpCode?: number | string;
} & Record<string, unknown>;

export type RevokeSecurityGroupRuleInput = {
  ruleId: string;
} & Record<string, unknown>;

type CloudStackActionEnvelope = {
  id?: string;
  name?: string;
  jobid?: string;
  success?: boolean | string;
  securitygroup?: {
    id?: string;
    name?: string;
    ingressrule?: Array<{ ruleid?: string }>;
    egressrule?: Array<{ ruleid?: string }>;
  };
};

type CloudStackActionResponse = Record<string, CloudStackActionEnvelope | unknown> & {
  error?: string;
  errorresponse?: {
    errortext?: string;
  };
};

type ActionCommand =
  | "createSecurityGroup"
  | "deleteSecurityGroup"
  | "authorizeSecurityGroupIngress"
  | "authorizeSecurityGroupEgress"
  | "revokeSecurityGroupIngress"
  | "revokeSecurityGroupEgress";

const RESPONSE_ENVELOPES: Record<ActionCommand, string> = {
  createSecurityGroup: "createsecuritygroupresponse",
  deleteSecurityGroup: "deletesecuritygroupresponse",
  authorizeSecurityGroupIngress: "authorizesecuritygroupingressresponse",
  authorizeSecurityGroupEgress: "authorizesecuritygroupegressresponse",
  revokeSecurityGroupIngress: "revokesecuritygroupingressresponse",
  revokeSecurityGroupEgress: "revokesecuritygroupegressresponse",
};

export function createSecurityGroup(
  input: CreateSecurityGroupInput,
  options?: FetchOptions,
): Promise<SecurityGroupActionResult> {
  const params = new URLSearchParams();
  setRequired(params, "name", input.name);
  setOptional(params, "description", input.description);
  setOptional(params, "account", input.account);
  setOptional(params, "domainid", input.domainId);
  setOptional(params, "projectid", input.projectId);
  return postSecurityGroupAction("createSecurityGroup", params, options);
}

export function deleteSecurityGroup(
  input: DeleteSecurityGroupInput,
  options?: FetchOptions,
): Promise<SecurityGroupActionResult> {
  const params = new URLSearchParams();
  if (hasValue(input.id)) {
    setRequired(params, "id", input.id);
  } else {
    setRequired(params, "name", input.name ?? "");
  }
  setOptional(params, "account", input.account);
  setOptional(params, "domainid", input.domainId);
  setOptional(params, "projectid", input.projectId);
  return postSecurityGroupAction("deleteSecurityGroup", params, options);
}

export function authorizeSecurityGroupIngress(
  input: SecurityGroupRuleInput,
  options?: FetchOptions,
): Promise<SecurityGroupActionResult> {
  return postSecurityGroupAction("authorizeSecurityGroupIngress", buildRuleParams(input), options);
}

export function authorizeSecurityGroupEgress(
  input: SecurityGroupRuleInput,
  options?: FetchOptions,
): Promise<SecurityGroupActionResult> {
  return postSecurityGroupAction("authorizeSecurityGroupEgress", buildRuleParams(input), options);
}

export function revokeSecurityGroupIngress(
  input: RevokeSecurityGroupRuleInput,
  options?: FetchOptions,
): Promise<SecurityGroupActionResult> {
  return postSecurityGroupAction("revokeSecurityGroupIngress", buildRevokeParams(input), options);
}

export function revokeSecurityGroupEgress(
  input: RevokeSecurityGroupRuleInput,
  options?: FetchOptions,
): Promise<SecurityGroupActionResult> {
  return postSecurityGroupAction("revokeSecurityGroupEgress", buildRevokeParams(input), options);
}

async function postSecurityGroupAction(
  command: ActionCommand,
  params: URLSearchParams,
  { fetchImpl = fetch }: FetchOptions = {},
): Promise<SecurityGroupActionResult> {
  const response = await fetchImpl(`${getRequestOrigin()}/api/cs/${command}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(Object.fromEntries(params.entries())),
    cache: "no-store",
  });
  const payload = (await response.json()) as CloudStackActionResponse;
  const errorText = readErrorText(payload);
  if (!response.ok || errorText) {
    throw new Error(errorText ?? `CloudStack ${command} request failed`);
  }

  return normalizeActionResult(command, payload);
}

function normalizeActionResult(command: ActionCommand, payload: CloudStackActionResponse): SecurityGroupActionResult {
  const envelopeName = RESPONSE_ENVELOPES[command];
  const envelope = payload[envelopeName];
  if (!isActionEnvelope(envelope)) {
    throw new Error(`CloudStack ${command} response is missing ${envelopeName} envelope`);
  }

  const securityGroup = envelope.securitygroup;
  const ruleId = securityGroup?.ingressrule?.[0]?.ruleid ?? securityGroup?.egressrule?.[0]?.ruleid ?? null;
  const id = envelope.id ?? securityGroup?.id ?? null;
  const name = envelope.name ?? securityGroup?.name ?? null;
  const jobId = envelope.jobid ?? null;

  return {
    id,
    name,
    ruleId,
    jobId,
    success: readSuccess(envelope.success) || Boolean(jobId || id || name || ruleId),
  };
}

function buildRuleParams(input: SecurityGroupRuleInput): URLSearchParams {
  const params = new URLSearchParams();
  if (hasValue(input.securityGroupId)) {
    setRequired(params, "securitygroupid", input.securityGroupId);
  } else {
    setRequired(params, "securitygroupname", input.securityGroupName ?? "");
  }
  setOptional(params, "account", input.account);
  setOptional(params, "domainid", input.domainId);
  setOptional(params, "projectid", input.projectId);

  const protocol = input.protocol.trim().toLowerCase();
  setRequired(params, "protocol", protocol);
  setOptional(params, "cidrlist", input.cidrList);

  if (protocol === "icmp") {
    setOptional(params, "icmptype", input.icmpType);
    setOptional(params, "icmpcode", input.icmpCode);
  } else if (protocol === "tcp" || protocol === "udp") {
    setOptional(params, "startport", input.startPort);
    setOptional(params, "endport", input.endPort);
  }

  return params;
}

function buildRevokeParams(input: RevokeSecurityGroupRuleInput): URLSearchParams {
  const params = new URLSearchParams();
  setRequired(params, "id", input.ruleId);
  return params;
}

function setRequired(params: URLSearchParams, key: string, value: string | number | undefined): void {
  const normalized = normalizeParamValue(value);
  if (!normalized) {
    throw new Error(`Missing required security group value: ${key}`);
  }
  params.set(key, normalized);
}

function setOptional(params: URLSearchParams, key: string, value: string | number | undefined): void {
  const normalized = normalizeParamValue(value);
  if (normalized) {
    params.set(key, normalized);
  }
}

function normalizeParamValue(value: string | number | undefined): string | null {
  if (typeof value === "number" && Number.isFinite(value)) {
    return String(value);
  }

  const trimmed = typeof value === "string" ? value.trim() : "";
  return trimmed ? trimmed : null;
}

function hasValue(value: string | undefined): boolean {
  return Boolean(value?.trim());
}

function isActionEnvelope(value: unknown): value is CloudStackActionEnvelope {
  return Boolean(value && typeof value === "object" && !Array.isArray(value));
}

function readSuccess(value: boolean | string | undefined): boolean {
  return value === true || (typeof value === "string" && value.trim().toLowerCase() === "true");
}

function readErrorText(payload: CloudStackActionResponse): string | undefined {
  return payload.error ?? payload.errorresponse?.errortext;
}

function getRequestOrigin(): string {
  if (process.env.NEXTAUTH_URL) {
    return process.env.NEXTAUTH_URL.replace(/\/$/, "");
  }

  if (typeof window !== "undefined") {
    return window.location.origin;
  }

  return "";
}
