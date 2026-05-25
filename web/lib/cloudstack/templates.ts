import { mockTemplates, type Template } from "../mock-data.ts";

export type CloudStackTemplate = {
  id?: string;
  name?: string;
  displaytext?: string;
  ostypename?: string;
  size?: number | string;
  physicalsize?: number | string;
  arch?: string;
  isfeatured?: boolean;
  featured?: boolean;
  ispublic?: boolean;
  hypervisor?: string;
  account?: string;
};

export type ListTemplatesResponse = {
  listtemplatesresponse?: {
    count?: number | string;
    template?: CloudStackTemplate[];
  };
};

type FetchOptions = {
  fetchImpl?: typeof fetch;
  requestHeaders?: Pick<Headers, "get">;
};

export async function getTemplatesFromBff({
  fetchImpl = fetch,
  requestHeaders,
}: FetchOptions = {}): Promise<Template[]> {
  if (process.env.NEXT_PUBLIC_APP_ENV === "mock" || !process.env.CS_URL) {
    return mockTemplates;
  }

  try {
    const response = await fetchImpl(buildListTemplatesUrl(requestHeaders), {
      method: "GET",
      cache: "no-store",
      headers: buildForwardedHeaders(requestHeaders),
    });

    if (!response.ok) {
      return mockTemplates;
    }

    const payload = (await response.json()) as ListTemplatesResponse;
    if (!payload.listtemplatesresponse) {
      return mockTemplates;
    }

    return templatesFromListTemplatesResponse(payload);
  } catch {
    return mockTemplates;
  }
}

export function templatesFromListTemplatesResponse(response: ListTemplatesResponse): Template[] {
  return (response.listtemplatesresponse?.template ?? []).map(mapCloudStackTemplateToTemplate);
}

export function mapCloudStackTemplateToTemplate(template: CloudStackTemplate): Template {
  return {
    id: template.id ?? template.name ?? "unknown",
    name: template.displaytext ?? template.name ?? template.id ?? "unnamed-template",
    os: normalizeOsName(template.ostypename),
    size: formatTemplateSize(template.size ?? template.physicalsize),
    arch: normalizeArch(template.arch),
    featured: Boolean(template.isfeatured ?? template.featured),
    hypervisors: normalizeHypervisors(template.hypervisor),
    account: template.account ?? "unknown",
  };
}

function normalizeOsName(osTypeName: string | undefined): string {
  if (!osTypeName) {
    return "Unknown";
  }

  const firstToken = osTypeName.trim().split(/\s+/)[0];
  return firstToken || "Unknown";
}

function normalizeArch(arch: string | undefined): Template["arch"] {
  const normalized = arch?.toLowerCase();
  if (normalized === "arm64" || normalized === "aarch64") {
    return "arm64";
  }

  return "x86_64";
}

function normalizeHypervisors(hypervisor: string | undefined): string[] {
  if (!hypervisor) {
    return [];
  }

  return hypervisor
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean);
}

function formatTemplateSize(value: number | string | undefined): string {
  const bytes = toPositiveNumber(value);
  if (!bytes) {
    return "0 GB";
  }

  const gib = bytes / 1024 ** 3;
  const formatted = gib >= 100 ? String(Math.round(gib)) : gib.toFixed(1).replace(/\.0$/, "");
  return `${formatted} GB`;
}

function toPositiveNumber(value: number | string | undefined): number | null {
  if (typeof value === "number" && Number.isFinite(value) && value >= 0) {
    return value;
  }

  if (typeof value === "string") {
    const parsed = Number.parseFloat(value);
    return Number.isFinite(parsed) && parsed >= 0 ? parsed : null;
  }

  return null;
}

function buildListTemplatesUrl(requestHeaders?: Pick<Headers, "get">): string {
  const params = new URLSearchParams({
    templatefilter: "executable",
    details: "min",
    showunique: "true",
  });
  const origin = getRequestOrigin(requestHeaders);
  return `${origin}/api/cs/listTemplates?${params.toString()}`;
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
