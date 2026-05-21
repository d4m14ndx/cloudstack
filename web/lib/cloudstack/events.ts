import { mockEvents, type Event, type EventLevel } from "../mock-data.ts";

export type CloudStackEvent = {
  id?: string;
  username?: string;
  type?: string;
  level?: string;
  description?: string;
  account?: string;
  project?: string;
  domain?: string;
  resourceid?: string;
  resourcetype?: string;
  resourcename?: string;
  created?: string;
  state?: string;
  parentid?: string;
  archived?: boolean | string;
};

export type ListEventsResponse = {
  listeventsresponse?: {
    count?: number;
    event?: CloudStackEvent[];
  };
};

type FetchOptions = {
  fetchImpl?: typeof fetch;
  requestHeaders?: Pick<Headers, "get">;
};

export async function getEventsFromBff({
  fetchImpl = fetch,
  requestHeaders,
}: FetchOptions = {}): Promise<Event[]> {
  if (process.env.NEXT_PUBLIC_APP_ENV === "mock" || !process.env.CS_URL) {
    return mockEvents;
  }

  try {
    const response = await fetchImpl(buildListEventsUrl(requestHeaders), {
      method: "GET",
      cache: "no-store",
      headers: buildForwardedHeaders(requestHeaders),
    });

    if (!response.ok) {
      return mockEvents;
    }

    const payload = (await response.json()) as ListEventsResponse;
    if (!payload.listeventsresponse) {
      return mockEvents;
    }

    return eventsFromListEventsResponse(payload);
  } catch {
    return mockEvents;
  }
}

export function eventsFromListEventsResponse(response: ListEventsResponse): Event[] {
  return (response.listeventsresponse?.event ?? []).map(mapCloudStackEventToEvent);
}

export function mapCloudStackEventToEvent(event: CloudStackEvent): Event {
  const action = event.type ?? event.state ?? "EVENT";

  return {
    timestamp: event.created ?? "",
    level: mapCloudStackEventLevel(event.level),
    user: event.username ?? event.account ?? event.project ?? "system",
    action,
    target: formatTarget(event),
    description: event.description ?? action,
  };
}

export function mapCloudStackEventLevel(level: string | undefined): EventLevel {
  switch (level?.toLowerCase()) {
    case "warn":
    case "warning":
      return "warn";
    case "error":
      return "error";
    case "info":
    default:
      return "info";
  }
}

function formatTarget(event: CloudStackEvent): string {
  const resourceName = event.resourcename;
  const resourceType = event.resourcetype;

  if (resourceName && resourceType) {
    return `${resourceName} (${resourceType})`;
  }

  return resourceName ?? event.resourceid ?? resourceType ?? "-";
}

function buildListEventsUrl(requestHeaders?: Pick<Headers, "get">): string {
  const params = new URLSearchParams({
    page: "1",
    pagesize: "50",
  });
  const origin = getRequestOrigin(requestHeaders);
  return `${origin}/api/cs/listEvents?${params.toString()}`;
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
