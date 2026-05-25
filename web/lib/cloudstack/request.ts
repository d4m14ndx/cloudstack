const COMMAND_PATTERN = /^[A-Za-z][A-Za-z0-9]{0,127}$/;
const PARAM_NAME_PATTERN = /^[A-Za-z0-9_.\[\]-]{1,128}$/;

export function isValidCloudStackCommand(command: string): boolean {
  return COMMAND_PATTERN.test(command);
}

export function assertValidCloudStackCommand(command: string): void {
  if (!isValidCloudStackCommand(command)) {
    throw new Error("Invalid CloudStack command name");
  }
}

export function appendSafeClientParams(target: URLSearchParams, source: URLSearchParams): void {
  for (const [key, value] of source.entries()) {
    const normalizedKey = key.toLowerCase();
    if (normalizedKey === "sessionkey" || normalizedKey === "command" || normalizedKey === "response") {
      continue;
    }

    if (!PARAM_NAME_PATTERN.test(key)) {
      throw new Error("Invalid CloudStack parameter name");
    }

    target.append(key, value);
  }
}

export function buildProxySearchParams(
  command: string,
  clientParams: URLSearchParams,
  cloudstackSessionkey: string,
): URLSearchParams {
  assertValidCloudStackCommand(command);

  const params = new URLSearchParams();
  params.set("command", command);
  appendSafeClientParams(params, clientParams);
  params.set("sessionkey", cloudstackSessionkey);
  params.set("response", "json");
  return params;
}

export async function readClientBodyParams(request: Request): Promise<URLSearchParams> {
  const contentType = request.headers.get("content-type") ?? "";
  if (!contentType) {
    return new URLSearchParams();
  }

  if (contentType.includes("application/json")) {
    const body = (await request.json()) as unknown;
    if (!body || typeof body !== "object" || Array.isArray(body)) {
      throw new Error("Invalid JSON body");
    }

    const params = new URLSearchParams();
    for (const [key, value] of Object.entries(body)) {
      if (Array.isArray(value)) {
        for (const item of value) {
          params.append(key, stringifyParamValue(item));
        }
      } else if (value !== undefined && value !== null) {
        params.append(key, stringifyParamValue(value));
      }
    }
    return params;
  }

  if (contentType.includes("application/x-www-form-urlencoded") || contentType.includes("multipart/form-data")) {
    const formData = await request.formData();
    const params = new URLSearchParams();
    for (const [key, value] of formData.entries()) {
      params.append(key, typeof value === "string" ? value : value.name);
    }
    return params;
  }

  throw new Error("Unsupported request content type");
}

function stringifyParamValue(value: unknown): string {
  if (typeof value === "string") {
    return value;
  }

  if (typeof value === "number" || typeof value === "boolean" || typeof value === "bigint") {
    return String(value);
  }

  throw new Error("Invalid CloudStack parameter value");
}
