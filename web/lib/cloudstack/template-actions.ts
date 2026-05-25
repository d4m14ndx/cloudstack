type FetchOptions = {
  fetchImpl?: typeof fetch;
};

type UnsafeClientParams = {
  command?: unknown;
  sessionkey?: unknown;
  response?: unknown;
};

export type DeleteTemplateInput = UnsafeClientParams & {
  id: string;
  expunge?: boolean;
};

export type CopyTemplateInput = UnsafeClientParams & {
  id: string;
  sourceZoneId?: string;
  destZoneId: string;
};

export type UpdateTemplatePermissionsInput = UnsafeClientParams & {
  id: string;
  isPublic?: boolean;
  isFeatured?: boolean;
  isExtractable?: boolean;
};

export type TemplateActionResult =
  | {
      status: "queued";
      jobId: string;
      resourceId?: string;
    }
  | {
      status: "success";
    };

type CloudStackEnvelope = Record<string, unknown>;

export async function deleteTemplate(
  input: DeleteTemplateInput,
  { fetchImpl = fetch }: FetchOptions = {},
): Promise<TemplateActionResult> {
  const body = buildSafeBody({
    id: input.id,
    expunge: input.expunge,
  });
  const payload = await postTemplateAction("deleteTemplate", body, fetchImpl);
  return normalizeAsyncTemplateAction(payload, "deletetemplateresponse", "deleteTemplate");
}

export async function copyTemplate(
  input: CopyTemplateInput,
  { fetchImpl = fetch }: FetchOptions = {},
): Promise<TemplateActionResult> {
  const body = buildSafeBody({
    id: input.id,
    sourcezoneid: input.sourceZoneId,
    destzoneid: input.destZoneId,
  });
  const payload = await postTemplateAction("copyTemplate", body, fetchImpl);
  return normalizeAsyncTemplateAction(payload, "copytemplateresponse", "copyTemplate");
}

export async function updateTemplatePermissions(
  input: UpdateTemplatePermissionsInput,
  { fetchImpl = fetch }: FetchOptions = {},
): Promise<TemplateActionResult> {
  const body = buildSafeBody({
    id: input.id,
    ispublic: input.isPublic,
    isfeatured: input.isFeatured,
    isextractable: input.isExtractable,
  });
  const payload = await postTemplateAction("updateTemplatePermissions", body, fetchImpl);
  const response = readResponseObject(payload, "updatetemplatepermissionsresponse", "updateTemplatePermissions");
  const success = response.success;

  if (success === true || success === "true") {
    return { status: "success" };
  }

  throw new Error("CloudStack updateTemplatePermissions response is missing success flag");
}

async function postTemplateAction(
  command: "deleteTemplate" | "copyTemplate" | "updateTemplatePermissions",
  body: Record<string, string>,
  fetchImpl: typeof fetch,
): Promise<CloudStackEnvelope> {
  const response = await fetchImpl(`/api/cs/${command}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
    cache: "no-store",
  });
  const payload = await readJsonEnvelope(response);

  if (!response.ok) {
    throw new Error(readErrorText(payload) ?? `CloudStack ${command} request failed`);
  }

  const errorText = readErrorText(payload);
  if (errorText) {
    throw new Error(errorText);
  }

  return payload;
}

function normalizeAsyncTemplateAction(
  payload: CloudStackEnvelope,
  responseKey: "deletetemplateresponse" | "copytemplateresponse",
  command: "deleteTemplate" | "copyTemplate",
): TemplateActionResult {
  const response = readResponseObject(payload, responseKey, command);
  const jobId = readString(response.jobid);
  if (!jobId) {
    throw new Error(`CloudStack ${command} response is missing async job id`);
  }

  const resourceId = readString(response.id);
  return {
    status: "queued",
    jobId,
    ...(resourceId ? { resourceId } : {}),
  };
}

function buildSafeBody(params: Record<string, string | boolean | undefined>): Record<string, string> {
  const body: Record<string, string> = {};
  for (const [key, value] of Object.entries(params)) {
    if (key === "command" || key === "sessionkey" || key === "response") {
      continue;
    }

    if (value !== undefined) {
      body[key] = String(value);
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
  const value = payload[key];
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error(`CloudStack ${command} response is malformed`);
  }

  return value as CloudStackEnvelope;
}

function readString(value: unknown): string | undefined {
  return typeof value === "string" && value.length > 0 ? value : undefined;
}

function readErrorText(payload: CloudStackEnvelope): string | undefined {
  const errorResponse = payload.errorresponse;
  if (errorResponse && typeof errorResponse === "object" && !Array.isArray(errorResponse)) {
    const errorText = (errorResponse as CloudStackEnvelope).errortext;
    if (typeof errorText === "string" && errorText.length > 0) {
      return errorText;
    }
  }

  const directError = payload.error;
  return typeof directError === "string" && directError.length > 0 ? directError : undefined;
}
