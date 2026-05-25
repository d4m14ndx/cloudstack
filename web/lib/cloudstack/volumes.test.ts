import assert from "node:assert/strict";
import test from "node:test";

import { mockVolumes } from "../mock-data.ts";
import {
  getVolumesFromBff,
  mapCloudStackVolumeState,
  mapCloudStackVolumeToVolume,
  volumesFromListVolumesResponse,
} from "./volumes.ts";

test("mapCloudStackVolumeToVolume maps CloudStack volume fields into the UI volume contract", () => {
  const volume = mapCloudStackVolumeToVolume({
    id: "vol-1",
    name: "ROOT-123",
    displayname: "db-data",
    size: 10 * 1024 * 1024 * 1024 + 1,
    vmname: "db-primary",
    zonename: "syd-1",
    state: "Ready",
    type: "DATADISK",
    diskofferingname: "Premium NVMe",
  });

  assert.deepEqual(volume, {
    id: "vol-1",
    name: "db-data",
    sizeGiB: 11,
    type: "NVMe",
    attachedTo: "db-primary",
    zone: "syd-1",
    state: "ready",
  });
});

test("mapCloudStackVolumeToVolume falls back through minimal CloudStack fields", () => {
  const volume = mapCloudStackVolumeToVolume({
    name: "archive-001",
    size: "1073741824",
    virtualmachinename: "backup-worker",
    state: "Allocated",
    storage: "cold archive pool",
  });

  assert.equal(volume.id, "archive-001");
  assert.equal(volume.name, "archive-001");
  assert.equal(volume.sizeGiB, 1);
  assert.equal(volume.type, "Cold");
  assert.equal(volume.attachedTo, "backup-worker");
  assert.equal(volume.zone, "unknown");
  assert.equal(volume.state, "ready");
});

test("mapCloudStackVolumeState normalizes CloudStack states for the UI", () => {
  assert.equal(mapCloudStackVolumeState("Ready"), "ready");
  assert.equal(mapCloudStackVolumeState("Detaching"), "detaching");
  assert.equal(mapCloudStackVolumeState("Destroy"), "detaching");
  assert.equal(mapCloudStackVolumeState("Destroyed"), "detaching");
  assert.equal(mapCloudStackVolumeState("Expunging"), "detaching");
  assert.equal(mapCloudStackVolumeState("Expunged"), "detaching");
  assert.equal(mapCloudStackVolumeState("Allocated"), "ready");
  assert.equal(mapCloudStackVolumeState(undefined), "ready");
});

test("volumesFromListVolumesResponse maps the CloudStack response envelope", () => {
  const volumes = volumesFromListVolumesResponse({
    listvolumesresponse: {
      count: 1,
      volume: [
        {
          id: "vol-2",
          name: "worker-root",
          size: 2 * 1024 * 1024 * 1024,
          state: "Ready",
          zonename: "mel-1",
        },
      ],
    },
  });

  assert.equal(volumes.length, 1);
  assert.equal(volumes[0]?.name, "worker-root");
  assert.equal(volumes[0]?.state, "ready");
  assert.equal(volumes[0]?.sizeGiB, 2);
});

test("getVolumesFromBff calls the BFF listVolumes command and forwards cookies", async () => {
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
      listvolumesresponse: {
        volume: [{ id: "vol-3", name: "api-volume", size: 1073741824, state: "Ready" }],
      },
    });
  };

  try {
    const volumes = await getVolumesFromBff({
      fetchImpl,
      requestHeaders: new Headers({
        cookie: "cloudstack.session=opaque",
        host: "console.example.test",
        "x-forwarded-proto": "https",
      }),
    });
    const url = new URL(requestedUrl);

    assert.equal(url.origin, "https://console.example.test");
    assert.equal(url.pathname, "/api/cs/listVolumes");
    assert.equal(url.searchParams.get("listall"), "true");
    assert.equal(forwardedCookie, "cloudstack.session=opaque");
    assert.equal(volumes[0]?.name, "api-volume");
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
    restoreEnv("NEXTAUTH_URL", previousNextAuthUrl);
    restoreEnv("NEXT_PUBLIC_APP_ENV", previousAppEnv);
  }
});

test("getVolumesFromBff returns mock volumes when CloudStack is unavailable", async () => {
  const previousCsUrl = process.env.CS_URL;
  process.env.CS_URL = "http://cloudstack.local";

  try {
    const volumes = await getVolumesFromBff({
      fetchImpl: async () => new Response("{}", { status: 503 }),
    });

    assert.equal(volumes, mockVolumes);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

test("getVolumesFromBff does not call the BFF when CS_URL is absent", async () => {
  const previousCsUrl = process.env.CS_URL;
  delete process.env.CS_URL;
  let called = false;

  try {
    const volumes = await getVolumesFromBff({
      fetchImpl: async () => {
        called = true;
        return Response.json({});
      },
    });

    assert.equal(called, false);
    assert.equal(volumes, mockVolumes);
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
