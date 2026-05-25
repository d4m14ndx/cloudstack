export type UserApiTokenSummary = {
  access: "enabled" | "disabled" | "unknown";
  apiKeyMasked: string;
  secretKeyMasked: string;
  hasApiKey: boolean;
  hasSecretKey: boolean;
};

export type GeneratedUserApiToken = {
  id: string | null;
  name: string;
  apiKey: string;
  secretKey: string;
};

type FetchOptions = {
  fetchImpl?: typeof fetch;
  requestHeaders?: Pick<Headers, "get">;
};

type RegisterUserApiTokenInput = {
  userId: string;
  name: string;
  description?: string | null;
  fetchImpl?: typeof fetch;
};

type CloudStackErrorResponse = {
  errortext?: string;
};

type CloudStackUserKeys = {
  id?: string | null;
  name?: string | null;
  apikeyaccess?: boolean | string | number | null;
  apikey?: string | null;
  secretkey?: string | null;
};

type GetUserKeysResponse = {
  getuserkeysresponse?: {
    userkeys?: CloudStackUserKeys | CloudStackUserKeys[];
  };
  errorresponse?: CloudStackErrorResponse;
};

type RegisterUserKeysResponse = {
  registeruserkeysresponse?: (CloudStackUserKeys & {
    userkeys?: CloudStackUserKeys | CloudStackUserKeys[];
  });
  errorresponse?: CloudStackErrorResponse;
};

const UNKNOWN_SUMMARY: UserApiTokenSummary = {
  access: "unknown",
  apiKeyMasked: "Not generated",
  secretKeyMasked: "Not generated",
  hasApiKey: false,
  hasSecretKey: false,
};

const MOCK_SUMMARY: UserApiTokenSummary = {
  access: "enabled",
  apiKeyMasked: maskSecret("mock-api-key-local"),
  secretKeyMasked: maskSecret(null),
  hasApiKey: true,
  hasSecretKey: false,
};

const MOCK_GENERATED_TOKEN: GeneratedUserApiToken = {
  id: "mock-api-token",
  name: "Mock API token",
  apiKey: "mock-generated-api-key",
  secretKey: "mock-generated-secret-key",
};

export function maskSecret(value: string | null | undefined): string {
  if (!value || value.length < 8) {
    return value ? "••••" : "Not generated";
  }

  return `${value.slice(0, 4)}...${value.slice(-4)}`;
}

export async function getUserApiTokenSummaryFromBff(
  userId: string,
  { fetchImpl = fetch, requestHeaders }: FetchOptions = {},
): Promise<UserApiTokenSummary> {
  if (process.env.NEXT_PUBLIC_APP_ENV === "mock" || !process.env.CS_URL) {
    return MOCK_SUMMARY;
  }

  try {
    const response = await fetchImpl(buildGetUserKeysUrl(userId, requestHeaders), {
      method: "GET",
      cache: "no-store",
      headers: buildForwardedHeaders(requestHeaders),
    });

    if (!response.ok) {
      return UNKNOWN_SUMMARY;
    }

    return mapGetUserKeysResponse((await response.json()) as GetUserKeysResponse);
  } catch {
    return UNKNOWN_SUMMARY;
  }
}

export function mapGetUserKeysResponse(response: GetUserKeysResponse): UserApiTokenSummary {
  const userKeys = firstUserKeys(response.getuserkeysresponse?.userkeys);
  if (!userKeys) {
    return UNKNOWN_SUMMARY;
  }

  const apiKey = readNonEmptyString(userKeys.apikey);
  const secretKey = readNonEmptyString(userKeys.secretkey);

  return {
    access: mapApiKeyAccess(userKeys.apikeyaccess),
    apiKeyMasked: maskSecret(apiKey),
    secretKeyMasked: maskSecret(secretKey),
    hasApiKey: Boolean(apiKey),
    hasSecretKey: Boolean(secretKey),
  };
}

export async function registerUserApiToken({
  userId,
  name,
  description,
  fetchImpl = fetch,
}: RegisterUserApiTokenInput): Promise<GeneratedUserApiToken> {
  if (process.env.NEXT_PUBLIC_APP_ENV === "mock") {
    return { ...MOCK_GENERATED_TOKEN, name };
  }

  const payload = await postRegisterUserKeys(buildRegisterUserKeysBody(userId, name, description), fetchImpl);
  return mapRegisterUserKeysResponse(payload);
}

export function mapRegisterUserKeysResponse(response: RegisterUserKeysResponse): GeneratedUserApiToken {
  const error = readCloudStackError(response);
  if (error) {
    throw new Error(error);
  }

  const registerResponse = response.registeruserkeysresponse;
  const userKeys = firstUserKeys(registerResponse?.userkeys) ?? registerResponse;
  const apiKey = readNonEmptyString(userKeys?.apikey);
  const secretKey = readNonEmptyString(userKeys?.secretkey);

  if (!apiKey || !secretKey) {
    throw new Error("CloudStack registerUserKeys response is missing generated key material");
  }

  return {
    id: readNonEmptyString(userKeys?.id),
    name: readNonEmptyString(userKeys?.name) ?? "CloudStack API token",
    apiKey,
    secretKey,
  };
}

function buildGetUserKeysUrl(userId: string, requestHeaders?: Pick<Headers, "get">): string {
  const params = new URLSearchParams({ id: userId });
  return `${getRequestOrigin(requestHeaders)}/api/cs/getUserKeys?${params.toString()}`;
}

function buildRegisterUserKeysBody(
  userId: string,
  name: string,
  description?: string | null,
): Record<string, string> {
  const trimmedDescription = description?.trim();
  const body: Record<string, string> = {
    id: userId,
    name,
  };

  if (trimmedDescription) {
    body.description = trimmedDescription;
  }

  return body;
}

async function postRegisterUserKeys(
  body: Record<string, string>,
  fetchImpl: typeof fetch,
): Promise<RegisterUserKeysResponse> {
  const response = await fetchImpl(`${getRequestOrigin()}/api/cs/registerUserKeys`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
    cache: "no-store",
  });
  const payload = (await response.json()) as RegisterUserKeysResponse;
  const error = readCloudStackError(payload);

  if (!response.ok) {
    throw new Error(error ?? "CloudStack registerUserKeys request failed");
  }

  if (error) {
    throw new Error(error);
  }

  return payload;
}

function firstUserKeys(userKeys: CloudStackUserKeys | CloudStackUserKeys[] | undefined): CloudStackUserKeys | null {
  if (Array.isArray(userKeys)) {
    return userKeys[0] ?? null;
  }

  return userKeys ?? null;
}

function mapApiKeyAccess(value: CloudStackUserKeys["apikeyaccess"]): UserApiTokenSummary["access"] {
  if (value === true || value === "true" || value === "True" || value === 1 || value === "1") {
    return "enabled";
  }

  if (value === false || value === "false" || value === "False" || value === 0 || value === "0") {
    return "disabled";
  }

  return "unknown";
}

function readNonEmptyString(value: string | null | undefined): string | null {
  return value && value.trim() ? value : null;
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

function readCloudStackError(payload: { errorresponse?: CloudStackErrorResponse }): string | null {
  return payload.errorresponse?.errortext ?? null;
}
