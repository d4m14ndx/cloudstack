type FetchOptions = {
  fetchImpl?: typeof fetch;
};

export type ConsoleEndpoint = {
  url: string;
  websocketToken: string | null;
};

type CreateConsoleEndpointResponse = {
  createconsoleendpointresponse?: {
    consoleendpoint?: string | {
      url?: string;
      success?: boolean;
      details?: string;
      websockettoken?: string;
    };
    url?: string;
    websockettoken?: string;
  };
  error?: string;
  errorresponse?: {
    errortext?: string;
  };
};

export async function createConsoleEndpoint(
  id: string,
  { fetchImpl = fetch }: FetchOptions = {},
): Promise<ConsoleEndpoint> {
  const response = await fetchImpl(`${getRequestOrigin()}/api/cs/createConsoleEndpoint`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ virtualmachineid: id }),
    cache: "no-store",
  });
  const payload = (await response.json()) as CreateConsoleEndpointResponse;

  if (!response.ok) {
    throw new Error(readErrorText(payload) ?? "CloudStack createConsoleEndpoint request failed");
  }

  const endpoint = payload.createconsoleendpointresponse;
  const consoleEndpoint = endpoint?.consoleendpoint;
  if (typeof consoleEndpoint === "object" && consoleEndpoint.success === false) {
    throw new Error(consoleEndpoint.details ?? "CloudStack createConsoleEndpoint request failed");
  }

  const url = typeof consoleEndpoint === "string" ? consoleEndpoint : consoleEndpoint?.url ?? endpoint?.url;
  if (!url) {
    throw new Error("CloudStack createConsoleEndpoint response is missing console endpoint");
  }

  return {
    url,
    websocketToken: (typeof consoleEndpoint === "object" ? consoleEndpoint.websockettoken : undefined) ?? endpoint?.websockettoken ?? null,
  };
}

function readErrorText(payload: CreateConsoleEndpointResponse): string | undefined {
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
