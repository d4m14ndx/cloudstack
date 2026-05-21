type FetchOptions = {
  fetchImpl?: typeof fetch;
};

type UnsafeClientParams = {
  command?: unknown;
  sessionkey?: unknown;
  response?: unknown;
};

export type AssociateIpAddressInput = UnsafeClientParams &
  (
    | {
        kind: "Isolated";
        networkId: string;
      }
    | {
        kind: "VPC";
        vpcId: string;
      }
  );

export type EnableStaticNatInput = UnsafeClientParams & {
  ipAddressId: string;
  virtualMachineId: string;
  networkId?: string;
};

export type NetworkAsyncActionResult = {
  jobId: string;
  ipAddressId?: string;
};

export type NetworkSyncActionResult = {
  success: true;
};

export type NetworkActionJobResult =
  | { status: "pending"; jobId: string; progress?: number }
  | { status: "success"; jobId: string; ipAddressId?: string; ipAddress?: string }
  | { status: "failed"; jobId: string; resultCode?: number; errorText?: string };

type NetworkActionCommand =
  | "associateIpAddress"
  | "disassociateIpAddress"
  | "enableStaticNat"
  | "disableStaticNat";

type CloudStackEnvelope = Record<string, unknown>;

export async function associateIpAddress(
  input: AssociateIpAddressInput,
  { fetchImpl = fetch }: FetchOptions = {},
): Promise<NetworkAsyncActionResult> {
  const body = input.kind === "VPC"
    ? buildSafeBody({ vpcid: input.vpcId })
    : buildSafeBody({ networkid: input.networkId });
  const payload = await postNetworkAction("associateIpAddress", body, fetchImpl);
  return normalizeAsyncNetworkAction(payload, "associateipaddressresponse", "associateIpAddress");
}

export async function disassociateIpAddress(
  ipAddressId: string,
  { fetchImpl = fetch }: FetchOptions = {},
): Promise<NetworkAsyncActionResult> {
  const payload = await postNetworkAction("disassociateIpAddress", buildSafeBody({ id: ipAddressId }), fetchImpl);
  return normalizeAsyncNetworkAction(payload, "disassociateipaddressresponse", "disassociateIpAddress");
}

export async function enableStaticNat(
  input: EnableStaticNatInput,
  { fetchImpl = fetch }: FetchOptions = {},
): Promise<NetworkSyncActionResult> {
  const payload = await postNetworkAction(
    "enableStaticNat",
    buildSafeBody({
      ipaddressid: input.ipAddressId,
      virtualmachineid: input.virtualMachineId,
      networkid: input.networkId,
    }),
    fetchImpl,
  );
  return normalizeSyncNetworkAction(payload, "enablestaticnatresponse", "enableStaticNat");
}

export async function disableStaticNat(
  ipAddressId: string,
  { fetchImpl = fetch }: FetchOptions = {},
): Promise<NetworkSyncActionResult> {
  const payload = await postNetworkAction("disableStaticNat", buildSafeBody({ ipaddressid: ipAddressId }), fetchImpl);
  return normalizeSyncNetworkAction(payload, "disablestaticnatresponse", "disableStaticNat");
}

export async function queryNetworkActionJobResult(
  jobId: string,
  { fetchImpl = fetch }: FetchOptions = {},
): Promise<NetworkActionJobResult> {
  const params = new URLSearchParams({ jobid: jobId });
  const response = await fetchImpl(`${getRequestOrigin()}/api/cs/queryAsyncJobResult?${params.toString()}`, {
    method: "GET",
    cache: "no-store",
  });
  const payload = await readJsonEnvelope(response);
  if (!response.ok) {
    throw new Error(readErrorText(payload) ?? "CloudStack queryAsyncJobResult request failed");
  }

  const job = readResponseObject(payload, "queryasyncjobresultresponse", "queryAsyncJobResult");
  const returnedJobId = readString(job.jobid);
  if (!returnedJobId) {
    throw new Error("CloudStack queryAsyncJobResult response is missing job id");
  }

  const statusCode = readNonNegativeInteger(job.jobstatus) ?? 0;
  const progress = readNonNegativeInteger(job.jobprocstatus) ?? undefined;
  if (statusCode === 1) {
    const jobResult = readOptionalObject(job.jobresult);
    const publicIpAddress = readOptionalObject(jobResult?.publicipaddress) ?? readOptionalObject(jobResult?.ipaddress);
    const ipAddressId = readString(publicIpAddress?.id);
    const ipAddress = readString(publicIpAddress?.ipaddress);

    return {
      status: "success",
      jobId: returnedJobId,
      ...(ipAddressId ? { ipAddressId } : {}),
      ...(ipAddress ? { ipAddress } : {}),
    };
  }

  if (statusCode === 2) {
    const jobResult = readOptionalObject(job.jobresult);
    const resultCode = readNonNegativeInteger(job.jobresultcode);
    const errorText = readString(jobResult?.errortext);
    return {
      status: "failed",
      jobId: returnedJobId,
      ...(resultCode !== null ? { resultCode } : {}),
      ...(errorText ? { errorText } : {}),
    };
  }

  return {
    status: "pending",
    jobId: returnedJobId,
    ...(progress !== undefined ? { progress } : {}),
  };
}

async function postNetworkAction(
  command: NetworkActionCommand,
  body: Record<string, string>,
  fetchImpl: typeof fetch,
): Promise<CloudStackEnvelope> {
  const response = await fetchImpl(`${getRequestOrigin()}/api/cs/${command}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
    cache: "no-store",
  });
  const payload = await readJsonEnvelope(response);
  if (!response.ok) {
    throw new Error(readErrorText(payload) ?? `CloudStack ${command} request failed`);
  }

  const errorText = readErrorText(payload, `${command.toLowerCase()}response`);
  if (errorText) {
    throw new Error(errorText);
  }

  return payload;
}

function normalizeAsyncNetworkAction(
  payload: CloudStackEnvelope,
  responseKey: "associateipaddressresponse" | "disassociateipaddressresponse",
  command: "associateIpAddress" | "disassociateIpAddress",
): NetworkAsyncActionResult {
  const response = readResponseObject(payload, responseKey, command);
  const jobId = readString(response.jobid);
  if (!jobId) {
    throw new Error(`CloudStack ${command} response is missing async job id`);
  }

  const ipAddressId = readString(response.id);
  return {
    jobId,
    ...(ipAddressId ? { ipAddressId } : {}),
  };
}

function normalizeSyncNetworkAction(
  payload: CloudStackEnvelope,
  responseKey: "enablestaticnatresponse" | "disablestaticnatresponse",
  command: "enableStaticNat" | "disableStaticNat",
): NetworkSyncActionResult {
  const response = readResponseObject(payload, responseKey, command);
  const errorText = readString(response.errortext);
  if (errorText) {
    throw new Error(errorText);
  }

  if (isSuccess(response.success)) {
    return { success: true };
  }

  throw new Error(`CloudStack ${command} response did not report success`);
}

function buildSafeBody(params: Record<string, string | undefined>): Record<string, string> {
  const body: Record<string, string> = {};
  for (const [key, value] of Object.entries(params)) {
    if (key === "command" || key === "sessionkey" || key === "response") {
      continue;
    }

    if (value !== undefined) {
      body[key] = value;
    }
  }
  return body;
}

async function readJsonEnvelope(response: Response): Promise<CloudStackEnvelope> {
  try {
    const payload = (await response.json()) as unknown;
    if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
      throw new Error("not an object");
    }
    return payload as CloudStackEnvelope;
  } catch {
    throw new Error("CloudStack response is not valid JSON");
  }
}

function readResponseObject(payload: CloudStackEnvelope, key: string, command: string): CloudStackEnvelope {
  const response = payload[key];
  if (!response || typeof response !== "object" || Array.isArray(response)) {
    throw new Error(`CloudStack ${command} response is malformed`);
  }

  return response as CloudStackEnvelope;
}

function readOptionalObject(value: unknown): CloudStackEnvelope | undefined {
  return value && typeof value === "object" && !Array.isArray(value) ? value as CloudStackEnvelope : undefined;
}

function isSuccess(value: unknown): boolean {
  return value === true || value === "true";
}

function readString(value: unknown): string | undefined {
  return typeof value === "string" && value.length > 0 ? value : undefined;
}

function readNonNegativeInteger(value: unknown): number | null {
  if (typeof value === "number" && Number.isFinite(value) && value >= 0) {
    return Math.floor(value);
  }

  if (typeof value === "string") {
    const parsed = Number.parseFloat(value);
    return Number.isFinite(parsed) && parsed >= 0 ? Math.floor(parsed) : null;
  }

  return null;
}

function readErrorText(payload: CloudStackEnvelope, commandResponseKey?: string): string | undefined {
  const directError = readString(payload.error);
  if (directError) {
    return directError;
  }

  const errorResponse = readOptionalObject(payload.errorresponse);
  const errorResponseText = readString(errorResponse?.errortext);
  if (errorResponseText) {
    return errorResponseText;
  }

  if (commandResponseKey) {
    const commandResponse = readOptionalObject(payload[commandResponseKey]);
    return readString(commandResponse?.errortext);
  }

  return undefined;
}

function getRequestOrigin(): string {
  if (process.env.NEXTAUTH_URL) {
    return process.env.NEXTAUTH_URL.replace(/\/$/, "");
  }

  if (typeof window !== "undefined") {
    return window.location.origin;
  }

  return "http://localhost:3000";
}
