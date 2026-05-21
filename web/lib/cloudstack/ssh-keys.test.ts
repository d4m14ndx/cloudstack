import assert from "node:assert/strict";
import test from "node:test";

import { mockSshKeyPairs } from "../mock-data.ts";
import {
  createSshKeyPairFromBff,
  deleteSshKeyPairFromBff,
  getSshKeyPairsFromBff,
  mapCloudStackSshKeyPairToSshKeyPair,
  registerSshKeyPairFromBff,
  sshKeyPairsFromListSshKeyPairsResponse,
} from "./ssh-keys.ts";

test("mapCloudStackSshKeyPairToSshKeyPair maps CloudStack SSH key fields into the UI contract", () => {
  const keyPair = mapCloudStackSshKeyPairToSshKeyPair({
    id: "key-1",
    name: "ops-key",
    fingerprint: "SHA256:abc123",
    account: "platform",
    domain: "ROOT",
    project: "core",
  });

  assert.deepEqual(keyPair, {
    id: "key-1",
    name: "ops-key",
    fingerprint: "SHA256:abc123",
    account: "platform",
    domain: "ROOT",
    project: "core",
  });
});

test("mapCloudStackSshKeyPairToSshKeyPair falls back through minimal CloudStack fields", () => {
  const keyPair = mapCloudStackSshKeyPairToSshKeyPair({
    name: "fallback-key",
  });

  assert.deepEqual(keyPair, {
    id: "fallback-key",
    name: "fallback-key",
    fingerprint: "unknown",
    account: "unknown",
    domain: "unknown",
    project: null,
  });
});

test("mapCloudStackSshKeyPairToSshKeyPair uses project as account fallback", () => {
  const keyPair = mapCloudStackSshKeyPairToSshKeyPair({
    fingerprint: "SHA256:fallback",
    project: "analytics",
  });

  assert.equal(keyPair.id, "SHA256:fallback");
  assert.equal(keyPair.name, "unnamed-key");
  assert.equal(keyPair.account, "analytics");
  assert.equal(keyPair.project, "analytics");
});

test("sshKeyPairsFromListSshKeyPairsResponse maps the CloudStack response envelope", () => {
  const keyPairs = sshKeyPairsFromListSshKeyPairsResponse({
    listsshkeypairsresponse: {
      count: 1,
      sshkeypair: [
        {
          id: "key-2",
          name: "api-key",
          fingerprint: "SHA256:def456",
          account: "engineering",
          domain: "ROOT/eng",
        },
      ],
    },
  });

  assert.equal(keyPairs.length, 1);
  assert.equal(keyPairs[0]?.id, "key-2");
  assert.equal(keyPairs[0]?.name, "api-key");
  assert.equal(keyPairs[0]?.domain, "ROOT/eng");
});

test("getSshKeyPairsFromBff calls the BFF listSSHKeyPairs command and forwards cookies", async () => {
  const previousCsUrl = process.env.CS_URL;
  const previousNextAuthUrl = process.env.NEXTAUTH_URL;
  const previousAppEnv = process.env.NEXT_PUBLIC_APP_ENV;
  process.env.CS_URL = "http://cloudstack.local";
  delete process.env.NEXTAUTH_URL;
  delete process.env.NEXT_PUBLIC_APP_ENV;

  let requestedUrl = "";
  let forwardedCookie: string | undefined;
  const fetchImpl: typeof fetch = async (input, init) => {
    requestedUrl = String(input);
    forwardedCookie = (init?.headers as Record<string, string> | undefined)?.cookie;
    return Response.json({
      listsshkeypairsresponse: {
        sshkeypair: [{ id: "key-3", name: "bff-key", fingerprint: "SHA256:ghi789" }],
      },
    });
  };

  try {
    const keyPairs = await getSshKeyPairsFromBff({
      fetchImpl,
      requestHeaders: new Headers({
        cookie: "cloudstack.session=opaque",
        host: "console.example.test",
        "x-forwarded-proto": "https",
      }),
    });
    const url = new URL(requestedUrl);

    assert.equal(url.origin, "https://console.example.test");
    assert.equal(url.pathname, "/api/cs/listSSHKeyPairs");
    assert.equal(url.searchParams.get("listall"), "true");
    assert.equal(forwardedCookie, "cloudstack.session=opaque");
    assert.equal(keyPairs[0]?.name, "bff-key");
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
    restoreEnv("NEXTAUTH_URL", previousNextAuthUrl);
    restoreEnv("NEXT_PUBLIC_APP_ENV", previousAppEnv);
  }
});

test("getSshKeyPairsFromBff returns mock SSH keys when CloudStack is unavailable", async () => {
  const previousCsUrl = process.env.CS_URL;
  process.env.CS_URL = "http://cloudstack.local";

  try {
    const keyPairs = await getSshKeyPairsFromBff({
      fetchImpl: async () => new Response("{}", { status: 503 }),
    });

    assert.equal(keyPairs, mockSshKeyPairs);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

test("getSshKeyPairsFromBff returns mock SSH keys when the CloudStack envelope is missing", async () => {
  const previousCsUrl = process.env.CS_URL;
  process.env.CS_URL = "http://cloudstack.local";

  try {
    const keyPairs = await getSshKeyPairsFromBff({
      fetchImpl: async () => Response.json({}),
    });

    assert.equal(keyPairs, mockSshKeyPairs);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

test("getSshKeyPairsFromBff does not call the BFF when CS_URL is absent", async () => {
  const previousCsUrl = process.env.CS_URL;
  delete process.env.CS_URL;
  let called = false;

  try {
    const keyPairs = await getSshKeyPairsFromBff({
      fetchImpl: async () => {
        called = true;
        return Response.json({});
      },
    });

    assert.equal(called, false);
    assert.equal(keyPairs, mockSshKeyPairs);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

test("createSshKeyPairFromBff posts only the SSH key name and returns generated key material", async () => {
  const calls: Array<{ url: string; init?: RequestInit }> = [];
  const fetchImpl: typeof fetch = async (input, init) => {
    calls.push({ url: String(input), init });
    return Response.json({
      createsshkeypairresponse: {
        id: "key-4",
        name: "created-key",
        fingerprint: "SHA256:created",
        privatekey: "-----BEGIN RSA PRIVATE KEY-----\nsecret\n-----END RSA PRIVATE KEY-----",
      },
    });
  };

  const result = await createSshKeyPairFromBff(
    { name: "created-key", command: "deleteSSHKeyPair" } as { name: string },
    { fetchImpl },
  );

  assert.equal(calls.length, 1);
  assert.equal(new URL(calls[0]!.url).pathname, "/api/cs/createSSHKeyPair");
  assert.equal(calls[0]!.init?.method, "POST");
  assert.deepEqual(JSON.parse(String(calls[0]!.init?.body)), { name: "created-key" });
  assert.equal(result.name, "created-key");
  assert.equal(result.fingerprint, "SHA256:created");
  assert.match(result.privateKey ?? "", /BEGIN RSA PRIVATE KEY/);
});

test("registerSshKeyPairFromBff posts name and public key and maps the response envelope", async () => {
  let body: unknown;
  const fetchImpl: typeof fetch = async (_input, init) => {
    body = JSON.parse(String(init?.body));
    return Response.json({
      registersshkeypairresponse: {
        id: "key-5",
        name: "registered-key",
        fingerprint: "SHA256:registered",
        account: "platform",
        domain: "ROOT",
      },
    });
  };

  const result = await registerSshKeyPairFromBff(
    {
      name: "registered-key",
      publicKey: "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQC test@example",
    },
    { fetchImpl },
  );

  assert.deepEqual(body, {
    name: "registered-key",
    publickey: "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQC test@example",
  });
  assert.equal(result.id, "key-5");
  assert.equal(result.name, "registered-key");
  assert.equal(result.account, "platform");
});

test("deleteSshKeyPairFromBff posts only the SSH key name and returns the success boolean", async () => {
  let body: unknown;
  const fetchImpl: typeof fetch = async (_input, init) => {
    body = JSON.parse(String(init?.body));
    return Response.json({
      deletesshkeypairresponse: {
        success: true,
      },
    });
  };

  const deleted = await deleteSshKeyPairFromBff(
    { name: "old-key", sessionkey: "client-secret" } as { name: string },
    { fetchImpl },
  );

  assert.deepEqual(body, { name: "old-key" });
  assert.equal(deleted, true);
});

test("deleteSshKeyPairFromBff rejects malformed delete responses", async () => {
  await assert.rejects(
    deleteSshKeyPairFromBff(
      { name: "old-key" },
      {
        fetchImpl: async () =>
          Response.json({
            deletesshkeypairresponse: {
              success: false,
            },
          }),
      },
    ),
    /did not report success/,
  );
});

test("SSH key mutation helpers throw CloudStack response errors", async () => {
  await assert.rejects(
    createSshKeyPairFromBff(
      { name: "duplicate-key" },
      {
        fetchImpl: async () =>
          Response.json(
            { errorresponse: { errortext: "A key pair with that name already exists" } },
            { status: 431 },
          ),
      },
    ),
    /A key pair with that name already exists/,
  );
});

function restoreEnv(name: string, value: string | undefined): void {
  if (value === undefined) {
    delete process.env[name];
  } else {
    process.env[name] = value;
  }
}
