type FetchOptions = {
  fetchImpl?: typeof fetch;
};

type CreateConsoleEndpointResponse = {
  createconsoleendpointresponse?: {
    consoleendpoint?: {
      success?: boolean | string;
      url?: string;
      details?: string;
      websocket?: string;
    };
  };
  error?: string;
  errorresponse?: {
    errortext?: string;
  };
  errortext?: string;
};

export type ConsoleEndpointResult =
  | {
      success: true;
      url: string;
      details?: string;
      websocket?: string;
    }
  | {
      success: false;
      details?: string;
    };

export async function createConsoleEndpoint(
  virtualMachineId: string,
  { fetchImpl = fetch }: FetchOptions = {},
): Promise<ConsoleEndpointResult> {
  const response = await fetchImpl(`${getRequestOrigin()}/api/cs/createConsoleEndpoint`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ virtualmachineid: virtualMachineId }),
    cache: "no-store",
  });
  const payload = (await response.json()) as CreateConsoleEndpointResponse;
  if (!response.ok) {
    throw new Error(readErrorText(payload) ?? "CloudStack createConsoleEndpoint request failed");
  }

  const endpoint = payload.createconsoleendpointresponse?.consoleendpoint;
  if (!endpoint) {
    throw new Error("CloudStack createConsoleEndpoint response is missing console endpoint");
  }

  if (isSuccess(endpoint.success)) {
    if (!endpoint.url) {
      throw new Error("CloudStack createConsoleEndpoint response is missing console URL");
    }

    return {
      success: true,
      url: endpoint.url,
      ...(endpoint.details ? { details: endpoint.details } : {}),
      ...(endpoint.websocket ? { websocket: endpoint.websocket } : {}),
    };
  }

  return {
    success: false,
    ...(endpoint.details ? { details: endpoint.details } : {}),
  };
}

function isSuccess(value: boolean | string | undefined): boolean {
  return value === true || value === "true";
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
