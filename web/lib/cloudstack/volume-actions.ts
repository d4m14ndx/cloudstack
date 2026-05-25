type FetchOptions = {
  fetchImpl?: typeof fetch;
};

type DetachVolumeResponse = {
  detachvolumeresponse?: {
    id?: string;
    jobid?: string;
  };
  error?: string;
  errorresponse?: {
    errortext?: string;
  };
  errortext?: string;
};

type DeleteVolumeResponse = {
  deletevolumeresponse?: {
    success?: boolean | string;
  };
  error?: string;
  errorresponse?: {
    errortext?: string;
  };
  errortext?: string;
};

type QueryAsyncJobResultResponse = {
  queryasyncjobresultresponse?: {
    jobid?: string;
    jobstatus?: number | string;
    jobprocstatus?: number | string;
    jobresultcode?: number | string;
    jobresult?: {
      errortext?: string;
      volume?: {
        id?: string;
      };
    };
  };
  error?: string;
  errorresponse?: {
    errortext?: string;
  };
  errortext?: string;
};

export type DetachVolumeResult = {
  jobId: string;
  volumeId?: string;
};

export type DeleteVolumeResult = {
  success: true;
};

export type VolumeActionJobResult =
  | { status: "pending"; jobId: string; progress?: number }
  | { status: "success"; jobId: string; volumeId?: string }
  | { status: "failed"; jobId: string; resultCode?: number; errorText?: string };

export async function detachVolumeFromBff(
  volumeId: string,
  { fetchImpl = fetch }: FetchOptions = {},
): Promise<DetachVolumeResult> {
  const payload = await postVolumeAction<DetachVolumeResponse>("detachVolume", volumeId, fetchImpl);
  const detachResponse = payload.detachvolumeresponse;
  if (!detachResponse?.jobid) {
    throw new Error("CloudStack detachVolume response is missing async job id");
  }

  return {
    jobId: detachResponse.jobid,
    ...(detachResponse.id ? { volumeId: detachResponse.id } : {}),
  };
}

export async function deleteVolumeFromBff(
  volumeId: string,
  { fetchImpl = fetch }: FetchOptions = {},
): Promise<DeleteVolumeResult> {
  const payload = await postVolumeAction<DeleteVolumeResponse>("deleteVolume", volumeId, fetchImpl);
  if (!isSuccess(payload.deletevolumeresponse?.success)) {
    throw new Error("CloudStack deleteVolume response did not report success");
  }

  return { success: true };
}

export async function queryVolumeActionJobResult(
  jobId: string,
  { fetchImpl = fetch }: FetchOptions = {},
): Promise<VolumeActionJobResult> {
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
      status: "success",
      jobId: job.jobid,
      ...(job.jobresult?.volume?.id ? { volumeId: job.jobresult.volume.id } : {}),
    };
  }

  if (statusCode === 2) {
    const resultCode = readNonNegativeInteger(job.jobresultcode);
    return {
      status: "failed",
      jobId: job.jobid,
      ...(resultCode !== null ? { resultCode } : {}),
      ...(job.jobresult?.errortext ? { errorText: job.jobresult.errortext } : {}),
    };
  }

  return {
    status: "pending",
    jobId: job.jobid,
    ...(progress !== undefined ? { progress } : {}),
  };
}

async function postVolumeAction<T>(
  command: "detachVolume" | "deleteVolume",
  volumeId: string,
  fetchImpl: typeof fetch,
): Promise<T> {
  const response = await fetchImpl(`${getRequestOrigin()}/api/cs/${command}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ id: volumeId }),
    cache: "no-store",
  });
  const payload = (await response.json()) as T & { error?: string; errorresponse?: { errortext?: string }; errortext?: string };
  if (!response.ok) {
    throw new Error(readErrorText(payload) ?? `CloudStack ${command} request failed`);
  }

  return payload;
}

function isSuccess(value: boolean | string | undefined): boolean {
  return value === true || value === "true";
}

function readNonNegativeInteger(value: number | string | undefined): number | null {
  if (typeof value === "number" && Number.isInteger(value) && value >= 0) {
    return value;
  }

  if (typeof value === "string" && /^\d+$/.test(value)) {
    return Number.parseInt(value, 10);
  }

  return null;
}

function readErrorText(payload: { error?: string; errorresponse?: { errortext?: string }; errortext?: string }): string | null {
  return payload.error ?? payload.errorresponse?.errortext ?? payload.errortext ?? null;
}

function getRequestOrigin(): string {
  if (typeof window !== "undefined") {
    return window.location.origin;
  }

  if (process.env.NEXTAUTH_URL) {
    return process.env.NEXTAUTH_URL.replace(/\/$/, "");
  }

  return "http://localhost:3000";
}
