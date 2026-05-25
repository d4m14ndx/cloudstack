type FetchOptions = {
  fetchImpl?: typeof fetch;
};

export type ConsoleEndpoint = {
  url: string;
  details?: string;
  websocket?: unknown;
  websocketToken: string | null;
};

type CreateConsoleEndpointResponse = {
  createconsoleendpointresponse?: {
    consoleendpoint?:
      | string
      | {
          success?: boolean | string;
          url?: string;
          details?: string;
          websocket?: unknown;
          websockettoken?: string;
        };
    url?: string;
    websockettoken?: string;
  };
  error?: string;
  errorresponse?: {
    errortext?: string;
  };
  errortext?: string;
};

export async function createConsoleEndpoint(
  virtualMachineId: string,
  { fetchImpl = fetch }: FetchOptions = {},
): Promise<ConsoleEndpoint> {
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

  const envelope = payload.createconsoleendpointresponse;
  const endpoint = envelope?.consoleendpoint;
  if (!endpoint) {
    throw new Error("CloudStack createConsoleEndpoint response is missing console endpoint");
  }

  if (typeof endpoint === "object" && isExplicitFailure(endpoint.success)) {
    throw new Error(endpoint.details ?? "CloudStack createConsoleEndpoint request failed");
  }

  const url = typeof endpoint === "string" ? endpoint : endpoint.url ?? envelope?.url;
  if (!url) {
    throw new Error("CloudStack createConsoleEndpoint response is missing console URL");
  }

  return {
    url,
    ...(typeof endpoint === "object" && endpoint.details ? { details: endpoint.details } : {}),
    ...(typeof endpoint === "object" && endpoint.websocket ? { websocket: endpoint.websocket } : {}),
    websocketToken:
      (typeof endpoint === "object" ? endpoint.websockettoken : undefined) ?? envelope?.websockettoken ?? null,
  };
}

function isExplicitFailure(value: boolean | string | undefined): boolean {
  return value === false || value === "false";
}

function readErrorText(payload: CreateConsoleEndpointResponse): string | null {
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
