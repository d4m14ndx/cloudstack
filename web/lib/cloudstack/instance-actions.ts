type FetchOptions = {
  fetchImpl?: typeof fetch;
};

export type InstanceActionInput = {
  id: string;
};

export type DestroyVirtualMachineOptions = FetchOptions & {
  expunge?: boolean;
};

export type InstanceActionLaunchResult = {
  jobId: string;
};

export type InstanceActionJobStatus = "pending" | "success" | "failed";

export type InstanceActionJobResult = {
  jobId: string;
  status: InstanceActionJobStatus;
  progress?: number;
  resultCode?: number;
  errorText?: string;
  virtualMachineId?: string;
  virtualMachineName?: string;
  virtualMachineState?: string;
};

type InstanceActionCommand =
  | "startVirtualMachine"
  | "stopVirtualMachine"
  | "rebootVirtualMachine"
  | "destroyVirtualMachine";

type InstanceActionResponse = {
  error?: string;
  errorresponse?: {
    errortext?: string;
  };
} & Record<string, { jobid?: string } | undefined>;

type QueryAsyncJobResultResponse = {
  queryasyncjobresultresponse?: {
    jobid?: string;
    jobstatus?: number | string;
    jobprocstatus?: number | string;
    jobresultcode?: number | string;
    jobresult?: {
      errortext?: string;
      virtualmachine?: {
        id?: string;
        name?: string;
        state?: string;
      };
    };
  };
  error?: string;
  errorresponse?: {
    errortext?: string;
  };
};

export async function startVirtualMachine(
  id: string,
  { fetchImpl = fetch }: FetchOptions = {},
): Promise<InstanceActionLaunchResult> {
  return postVirtualMachineAction("startVirtualMachine", { id }, fetchImpl);
}

export async function stopVirtualMachine(
  id: string,
  { fetchImpl = fetch }: FetchOptions = {},
): Promise<InstanceActionLaunchResult> {
  return postVirtualMachineAction("stopVirtualMachine", { id }, fetchImpl);
}

export async function rebootVirtualMachine(
  id: string,
  { fetchImpl = fetch }: FetchOptions = {},
): Promise<InstanceActionLaunchResult> {
  return postVirtualMachineAction("rebootVirtualMachine", { id }, fetchImpl);
}

export async function destroyVirtualMachine(
  id: string,
  { expunge = false, fetchImpl = fetch }: DestroyVirtualMachineOptions = {},
): Promise<InstanceActionLaunchResult> {
  return postVirtualMachineAction(
    "destroyVirtualMachine",
    {
      id,
      ...(expunge ? { expunge: "true" } : {}),
    },
    fetchImpl,
  );
}

export async function queryInstanceActionJobResult(
  jobId: string,
  { fetchImpl = fetch }: FetchOptions = {},
): Promise<InstanceActionJobResult> {
  const params = new URLSearchParams({ jobid: jobId });
  const response = await fetchImpl(`${getRequestOrigin()}/api/cs/queryAsyncJobResult?${params.toString()}`, {
    method: "GET",
    cache: "no-store",
  });
  const payload = (await response.json()) as QueryAsyncJobResultResponse;
  if (!response.ok) {
    throw new Error(readErrorText(payload) ?? "CloudStack queryAsyncJobResult request failed");
  }

  const job = payload.queryasyncjobresultresponse;
  if (!job?.jobid) {
    throw new Error("CloudStack queryAsyncJobResult response is missing job id");
  }

  const statusCode = readNonNegativeInteger(job.jobstatus) ?? 0;
  const progress = readNonNegativeInteger(job.jobprocstatus) ?? undefined;
  if (statusCode === 1) {
    return {
      jobId: job.jobid,
      status: "success",
      ...(job.jobresult?.virtualmachine?.id ? { virtualMachineId: job.jobresult.virtualmachine.id } : {}),
      ...(job.jobresult?.virtualmachine?.name ? { virtualMachineName: job.jobresult.virtualmachine.name } : {}),
      ...(job.jobresult?.virtualmachine?.state ? { virtualMachineState: job.jobresult.virtualmachine.state } : {}),
    };
  }

  if (statusCode === 2) {
    const resultCode = readNonNegativeInteger(job.jobresultcode);
    return {
      jobId: job.jobid,
      status: "failed",
      ...(resultCode !== null ? { resultCode } : {}),
      ...(job.jobresult?.errortext ? { errorText: job.jobresult.errortext } : {}),
    };
  }

  return {
    jobId: job.jobid,
    status: "pending",
    ...(progress !== undefined ? { progress } : {}),
  };
}

async function postVirtualMachineAction(
  command: InstanceActionCommand,
  body: InstanceActionInput & { expunge?: "true" },
  fetchImpl: typeof fetch,
): Promise<InstanceActionLaunchResult> {
  const response = await fetchImpl(`${getRequestOrigin()}/api/cs/${command}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
    cache: "no-store",
  });
  const payload = (await response.json()) as InstanceActionResponse;
  if (!response.ok) {
    throw new Error(readErrorText(payload) ?? `CloudStack ${command} request failed`);
  }

  const actionResponse = payload[`${command.toLowerCase()}response`];
  if (!actionResponse?.jobid) {
    throw new Error(`CloudStack ${command} response is missing async job id`);
  }

  return { jobId: actionResponse.jobid };
}

function readNonNegativeInteger(value: number | string | undefined): number | null {
  if (typeof value === "number" && Number.isFinite(value) && value >= 0) {
    return Math.floor(value);
  }

  if (typeof value === "string") {
    const parsed = Number.parseFloat(value);
    return Number.isFinite(parsed) && parsed >= 0 ? Math.floor(parsed) : null;
  }

  return null;
}

function readErrorText(payload: InstanceActionResponse | QueryAsyncJobResultResponse): string | undefined {
  return payload.error ?? payload.errorresponse?.errortext;
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
