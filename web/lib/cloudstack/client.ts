import crypto from "node:crypto";

import { buildProxySearchParams } from "./request";

export type CloudStackClientConfig = {
  baseUrl: string;
  serviceApiKey?: string | null;
  serviceSecretKey?: string | null;
  fetchImpl?: typeof fetch;
};

export type MintCloudStackSessionInput = {
  username: string;
  domain: string;
};

export type MintCloudStackSessionResult = {
  sessionkey: string;
  userid: string;
  username: string;
  domainid: string;
  domain: string;
  role: string;
  roleid?: string;
  expiresat: string;
  expiresAt: number;
  type?: number;
};

type CloudStackError = {
  errorcode?: number;
  errortext?: string;
};

export class CloudStackClient {
  private readonly endpoint: string;
  private readonly serviceApiKey: string | null;
  private readonly serviceSecretKey: string | null;
  private readonly fetchImpl: typeof fetch;

  public constructor(config: CloudStackClientConfig) {
    const base = config.baseUrl.replace(/\/$/, "");
    this.endpoint = `${base}/client/api`;
    this.serviceApiKey = config.serviceApiKey ?? null;
    this.serviceSecretKey = config.serviceSecretKey ?? null;
    this.fetchImpl = config.fetchImpl ?? fetch;
  }

  public async mintUserSessionToken(input: MintCloudStackSessionInput): Promise<MintCloudStackSessionResult> {
    if (!this.serviceApiKey || !this.serviceSecretKey) {
      throw new Error("CloudStack service-account credentials are not configured");
    }

    const params = new URLSearchParams({
      command: "createUserSessionToken",
      username: input.username,
      domain: input.domain,
      apiKey: this.serviceApiKey,
      response: "json",
    });
    params.set("signature", signCloudStackParams(params, this.serviceSecretKey));

    const response = await this.fetchImpl(this.endpoint, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: params.toString(),
      cache: "no-store",
    });

    const json = (await response.json()) as unknown;
    if (!response.ok) {
      throwCloudStackError(json, response.status);
    }

    const envelope = json as { createusersessiontokenresponse?: Partial<MintCloudStackSessionResult> };
    const payload = envelope.createusersessiontokenresponse;
    if (!payload?.sessionkey || !payload.expiresat) {
      throw new Error("CloudStack createUserSessionToken returned an invalid response");
    }

    return {
      sessionkey: payload.sessionkey,
      userid: payload.userid ?? "",
      username: payload.username ?? input.username,
      domainid: payload.domainid ?? "",
      domain: payload.domain ?? input.domain,
      role: payload.role ?? "USER",
      roleid: payload.roleid,
      expiresat: payload.expiresat,
      expiresAt: Date.parse(payload.expiresat),
      type: payload.type,
    };
  }

  public async proxyCommand(
    command: string,
    clientParams: URLSearchParams,
    cloudstackSessionkey: string,
    method: "GET" | "POST",
  ): Promise<Response> {
    const params = buildProxySearchParams(command, clientParams, cloudstackSessionkey);

    if (method === "GET") {
      return this.fetchImpl(`${this.endpoint}?${params.toString()}`, {
        method: "GET",
        cache: "no-store",
      });
    }

    return this.fetchImpl(this.endpoint, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: params.toString(),
      cache: "no-store",
    });
  }
}

export function signCloudStackParams(params: URLSearchParams, secretKey: string): string {
  const signingString = [...params.entries()]
    .filter(([key]) => key.toLowerCase() !== "signature")
    .map(([key, value]) => [key.toLowerCase(), value.toLowerCase()] as const)
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(value).replace(/%20/g, "+")}`)
    .join("&");

  return crypto.createHmac("sha1", secretKey).update(signingString).digest("base64");
}

function throwCloudStackError(json: unknown, fallbackStatus: number): never {
  const envelope = json as { errorresponse?: CloudStackError };
  const errortext = envelope.errorresponse?.errortext;
  throw new Error(errortext ? `CloudStack API error ${fallbackStatus}: ${errortext}` : `CloudStack API error ${fallbackStatus}`);
}
