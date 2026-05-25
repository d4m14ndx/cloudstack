import { mockVolumes, type Volume } from "../mock-data.ts";

export type CloudStackVolume = {
  id?: string;
  name?: string;
  displayname?: string;
  size?: number | string;
  vmname?: string;
  virtualmachinename?: string;
  zonename?: string;
  state?: string;
  type?: string;
  diskofferingname?: string;
  storage?: string;
};

export type ListVolumesResponse = {
  listvolumesresponse?: {
    count?: number;
    volume?: CloudStackVolume[];
  };
};

type FetchOptions = {
  fetchImpl?: typeof fetch;
  requestHeaders?: Pick<Headers, "get">;
};

export async function getVolumesFromBff({
  fetchImpl = fetch,
  requestHeaders,
}: FetchOptions = {}): Promise<Volume[]> {
  if (process.env.NEXT_PUBLIC_APP_ENV === "mock" || !process.env.CS_URL) {
    return mockVolumes;
  }

  try {
    const response = await fetchImpl(buildListVolumesUrl(requestHeaders), {
      method: "GET",
      cache: "no-store",
      headers: buildForwardedHeaders(requestHeaders),
    });

    if (!response.ok) {
      return mockVolumes;
    }

    const payload = (await response.json()) as ListVolumesResponse;
    if (!payload.listvolumesresponse) {
      return mockVolumes;
    }

    return volumesFromListVolumesResponse(payload);
  } catch {
    return mockVolumes;
  }
}

export function volumesFromListVolumesResponse(response: ListVolumesResponse): Volume[] {
  return (response.listvolumesresponse?.volume ?? []).map(mapCloudStackVolumeToVolume);
}

export function mapCloudStackVolumeToVolume(volume: CloudStackVolume): Volume {
  return {
    id: volume.id ?? volume.name ?? "unknown",
    name: volume.displayname ?? volume.name ?? volume.id ?? "unnamed-volume",
    sizeGiB: bytesToGiB(volume.size),
    type: inferStorageTier(volume),
    attachedTo: volume.vmname ?? volume.virtualmachinename ?? null,
    zone: volume.zonename ?? "unknown",
    state: mapCloudStackVolumeState(volume.state),
  };
}

export function mapCloudStackVolumeState(state: string | undefined): Volume["state"] {
  const normalized = state?.toLowerCase() ?? "";

  if (normalized === "ready") {
    return "ready";
  }

  if (
    normalized.includes("detach") ||
    normalized.includes("destroy") ||
    normalized.includes("expung")
  ) {
    return "detaching";
  }

  return "ready";
}

function bytesToGiB(value: number | string | undefined): number {
  const bytes = toPositiveNumber(value);
  if (bytes === null || bytes === 0) {
    return 0;
  }

  return Math.ceil(bytes / 1024 / 1024 / 1024);
}

function inferStorageTier(volume: CloudStackVolume): Volume["type"] {
  const haystack = [volume.diskofferingname, volume.storage].filter(Boolean).join(" ").toLowerCase();

  if (haystack.includes("nvme")) {
    return "NVMe";
  }

  if (haystack.includes("cold") || haystack.includes("archive")) {
    return "Cold";
  }

  return "SSD";
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

function buildListVolumesUrl(requestHeaders?: Pick<Headers, "get">): string {
  const params = new URLSearchParams({ listall: "true" });
  const origin = getRequestOrigin(requestHeaders);
  return `${origin}/api/cs/listVolumes?${params.toString()}`;
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
