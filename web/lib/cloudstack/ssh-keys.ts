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

export type CreateSshKeyPairResponse = {
  createsshkeypairresponse?: CloudStackSshKeyPair & {
    privatekey?: string;
  };
  errorresponse?: CloudStackErrorResponse;
};

export type RegisterSshKeyPairResponse = {
  registersshkeypairresponse?: CloudStackSshKeyPair;
  errorresponse?: CloudStackErrorResponse;
};

export type DeleteSshKeyPairResponse = {
  deletesshkeypairresponse?: {
    success?: boolean | string;
  };
  errorresponse?: CloudStackErrorResponse;
};

export type CreateSshKeyPairResult = SshKeyPair & {
  privateKey: string | null;
};

export type CreateSshKeyPairInput = {
  name: string;
};

export type RegisterSshKeyPairInput = {
  name: string;
  publicKey: string;
};

export type DeleteSshKeyPairInput = {
  name: string;
};

type FetchOptions = {
  fetchImpl?: typeof fetch;
  requestHeaders?: Pick<Headers, "get">;
};

type CloudStackErrorResponse = {
  errortext?: string;
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

export async function createSshKeyPairFromBff(
  input: CreateSshKeyPairInput,
  { fetchImpl = fetch }: FetchOptions = {},
): Promise<CreateSshKeyPairResult> {
  const payload = await postSshKeyCommand<CreateSshKeyPairResponse>(
    "createSSHKeyPair",
    { name: input.name },
    fetchImpl,
  );
  const keyPair = payload.createsshkeypairresponse;
  if (!keyPair) {
    throw new Error("CloudStack createSSHKeyPair response is missing key pair details");
  }

  return {
    ...mapCloudStackSshKeyPairToSshKeyPair(keyPair),
    privateKey: keyPair.privatekey ?? null,
  };
}

export async function registerSshKeyPairFromBff(
  input: RegisterSshKeyPairInput,
  { fetchImpl = fetch }: FetchOptions = {},
): Promise<SshKeyPair> {
  const payload = await postSshKeyCommand<RegisterSshKeyPairResponse>(
    "registerSSHKeyPair",
    { name: input.name, publickey: input.publicKey },
    fetchImpl,
  );
  const keyPair = payload.registersshkeypairresponse;
  if (!keyPair) {
    throw new Error("CloudStack registerSSHKeyPair response is missing key pair details");
  }

  return mapCloudStackSshKeyPairToSshKeyPair(keyPair);
}

export async function deleteSshKeyPairFromBff(
  input: DeleteSshKeyPairInput,
  { fetchImpl = fetch }: FetchOptions = {},
): Promise<boolean> {
  const payload = await postSshKeyCommand<DeleteSshKeyPairResponse>(
    "deleteSSHKeyPair",
    { name: input.name },
    fetchImpl,
  );
  const success = payload.deletesshkeypairresponse?.success;
  if (success === true || success === "true") {
    return true;
  }

  throw new Error("CloudStack deleteSSHKeyPair response did not report success");
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
  if (!requestHeaders && typeof window !== "undefined") {
    return window.location.origin;
  }

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

async function postSshKeyCommand<T extends { errorresponse?: CloudStackErrorResponse }>(
  command: "createSSHKeyPair" | "registerSSHKeyPair" | "deleteSSHKeyPair",
  body: Record<string, string>,
  fetchImpl: typeof fetch,
): Promise<T> {
  const response = await fetchImpl(`${getRequestOrigin()}/api/cs/${command}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
    cache: "no-store",
  });
  const payload = (await response.json()) as T;
  if (!response.ok) {
    throw new Error(readCloudStackError(payload) ?? `CloudStack ${command} request failed`);
  }

  return payload;
}

function readCloudStackError(payload: { errorresponse?: CloudStackErrorResponse }): string | null {
  return payload.errorresponse?.errortext ?? null;
}
