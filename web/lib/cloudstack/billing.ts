import { mockBillingQuotaSummaries, type BillingQuotaSummary } from "../mock-data.ts";

export type CloudStackQuotaSummary = {
  accountid?: string;
  account?: string;
  domainid?: string;
  domain?: string;
  balance?: number | string;
  state?: string;
  quota?: number | string;
  startdate?: string;
  enddate?: string;
  currency?: string;
  quotaenabled?: boolean | string;
  projectid?: string;
  project?: string;
  projectname?: string;
  accountremoved?: boolean | string;
  projectremoved?: boolean | string;
  domainremoved?: boolean | string;
};

export type QuotaSummaryResponse = {
  quotasummaryresponse?: {
    count?: number | string;
    summary?: CloudStackQuotaSummary[];
  };
};

type FetchOptions = {
  fetchImpl?: typeof fetch;
  requestHeaders?: Pick<Headers, "get">;
};

export async function getBillingQuotaSummariesFromBff({
  fetchImpl = fetch,
  requestHeaders,
}: FetchOptions = {}): Promise<BillingQuotaSummary[]> {
  if (process.env.NEXT_PUBLIC_APP_ENV === "mock" || !process.env.CS_URL) {
    return mockBillingQuotaSummaries;
  }

  try {
    const response = await fetchImpl(buildQuotaSummaryUrl(requestHeaders), {
      method: "GET",
      cache: "no-store",
      headers: buildForwardedHeaders(requestHeaders),
    });

    if (!response.ok) {
      return mockBillingQuotaSummaries;
    }

    const payload = (await response.json()) as QuotaSummaryResponse;
    if (!payload.quotasummaryresponse) {
      return mockBillingQuotaSummaries;
    }

    return billingQuotaSummariesFromQuotaSummaryResponse(payload);
  } catch {
    return mockBillingQuotaSummaries;
  }
}

export function billingQuotaSummariesFromQuotaSummaryResponse(
  response: QuotaSummaryResponse,
): BillingQuotaSummary[] {
  return (response.quotasummaryresponse?.summary ?? []).map(mapCloudStackQuotaSummaryToBillingQuotaSummary);
}

export function mapCloudStackQuotaSummaryToBillingQuotaSummary(
  summary: CloudStackQuotaSummary,
): BillingQuotaSummary {
  const projectName = summary.projectname ?? summary.project;

  return {
    id:
      summary.projectid ??
      summary.accountid ??
      `${summary.domainid ?? summary.domain ?? "unknown"}:${summary.account ?? summary.project ?? "unknown"}`,
    name: projectName ? `Project: ${projectName}` : summary.account ?? "unknown",
    domain: summary.domain ?? "unknown",
    accountState: normalizeAccountState(summary.state),
    quotaState: readQuotaEnabled(summary.quotaenabled) ? "enabled" : "disabled",
    lifecycle: readRemoved(summary) ? "removed" : "active",
    balance: displayValue(summary.balance),
    periodUsage: displayValue(summary.quota),
    currency: summary.currency ?? "",
    period: `${summary.startdate ?? "-"} to ${summary.enddate ?? "-"}`,
  };
}

function normalizeAccountState(state: string | undefined): string {
  const normalized = state?.trim().toLowerCase();
  return normalized || "unknown";
}

function readQuotaEnabled(value: boolean | string | undefined): boolean {
  if (typeof value === "boolean") {
    return value;
  }

  const normalized = value?.trim().toLowerCase();
  return normalized === "true" || normalized === "enabled";
}

function readRemoved(summary: CloudStackQuotaSummary): boolean {
  return (
    readTrue(summary.accountremoved) ||
    readTrue(summary.projectremoved) ||
    readTrue(summary.domainremoved)
  );
}

function readTrue(value: boolean | string | undefined): boolean {
  if (typeof value === "boolean") {
    return value;
  }

  return value?.trim().toLowerCase() === "true";
}

function displayValue(value: number | string | undefined): string {
  if (value === undefined) {
    return "";
  }

  return String(value);
}

function buildQuotaSummaryUrl(requestHeaders?: Pick<Headers, "get">): string {
  const params = new URLSearchParams({
    listall: "true",
    accountstatetoshow: "ACTIVE",
    page: "1",
    pagesize: "50",
  });
  const origin = getRequestOrigin(requestHeaders);
  return `${origin}/api/cs/quotaSummary?${params.toString()}`;
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
