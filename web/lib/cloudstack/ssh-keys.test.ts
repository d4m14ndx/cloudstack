import assert from "node:assert/strict";
import test from "node:test";

import { mockSshKeyPairs } from "../mock-data.ts";
import {
  getSshKeyPairsFromBff,
  mapCloudStackSshKeyPairToSshKeyPair,
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

function restoreEnv(name: string, value: string | undefined): void {
  if (value === undefined) {
    delete process.env[name];
  } else {
    process.env[name] = value;
  }
}
