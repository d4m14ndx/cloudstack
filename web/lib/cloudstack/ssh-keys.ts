import { mockSshKeyPairs, type SshKeyPair } from "../mock-data.ts";

export type CloudStackSshKeyPair = {
  id?: string;
  name?: string;
  account?: string;
  domainid?: string;
  domain?: string;
  projectid?: string;
  project?: string;
  fingerprint?: string;
};

export type ListSshKeyPairsResponse = {
  listsshkeypairsresponse?: {
    count?: number | string;
    sshkeypair?: CloudStackSshKeyPair[];
  };
};

type FetchOptions = {
  fetchImpl?: typeof fetch;
  requestHeaders?: Pick<Headers, "get">;
};

export async function getSshKeyPairsFromBff({
  fetchImpl = fetch,
  requestHeaders,
}: FetchOptions = {}): Promise<SshKeyPair[]> {
  if (process.env.NEXT_PUBLIC_APP_ENV === "mock" || !process.env.CS_URL) {
    return mockSshKeyPairs;
  }

  try {
    const response = await fetchImpl(buildListSshKeyPairsUrl(requestHeaders), {
      method: "GET",
      cache: "no-store",
      headers: buildForwardedHeaders(requestHeaders),
    });

    if (!response.ok) {
      return mockSshKeyPairs;
    }

    const payload = (await response.json()) as ListSshKeyPairsResponse;
    if (!payload.listsshkeypairsresponse) {
      return mockSshKeyPairs;
    }

    return sshKeyPairsFromListSshKeyPairsResponse(payload);
  } catch {
    return mockSshKeyPairs;
  }
}

export function sshKeyPairsFromListSshKeyPairsResponse(response: ListSshKeyPairsResponse): SshKeyPair[] {
  return (response.listsshkeypairsresponse?.sshkeypair ?? []).map(mapCloudStackSshKeyPairToSshKeyPair);
}

export function mapCloudStackSshKeyPairToSshKeyPair(keyPair: CloudStackSshKeyPair): SshKeyPair {
  return {
    id: keyPair.id ?? keyPair.name ?? keyPair.fingerprint ?? "unknown",
    name: keyPair.name ?? keyPair.id ?? "unnamed-key",
    fingerprint: keyPair.fingerprint ?? "unknown",
    account: keyPair.account ?? keyPair.project ?? "unknown",
    domain: keyPair.domain ?? "unknown",
    project: keyPair.project ?? null,
  };
}

function buildListSshKeyPairsUrl(requestHeaders?: Pick<Headers, "get">): string {
  const params = new URLSearchParams({
    listall: "true",
  });
  const origin = getRequestOrigin(requestHeaders);
  return `${origin}/api/cs/listSSHKeyPairs?${params.toString()}`;
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
