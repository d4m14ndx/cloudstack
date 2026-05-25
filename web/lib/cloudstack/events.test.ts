import assert from "node:assert/strict";
import test from "node:test";

import { mockEvents } from "../mock-data.ts";
import {
  eventsFromListEventsResponse,
  getEventsFromBff,
  mapCloudStackEventLevel,
  mapCloudStackEventToEvent,
} from "./events.ts";

test("mapCloudStackEventToEvent maps CloudStack event fields into the UI event contract", () => {
  const event = mapCloudStackEventToEvent({
    id: "event-1",
    username: "alex.kim",
    type: "VM.START",
    level: "INFO",
    description: "Started VM",
    account: "platform",
    project: "migration",
    domain: "root",
    resourceid: "vm-1",
    resourcetype: "VirtualMachine",
    resourcename: "web-01",
    created: "2026-05-21T01:23:45+0000",
    state: "Completed",
    parentid: "parent-1",
    archived: false,
  });

  assert.deepEqual(event, {
    timestamp: "2026-05-21T01:23:45+0000",
    level: "info",
    user: "alex.kim",
    action: "VM.START",
    target: "web-01 (VirtualMachine)",
    description: "Started VM",
  });
});

test("mapCloudStackEventToEvent falls back through minimal CloudStack fields", () => {
  const event = mapCloudStackEventToEvent({
    account: "engineering",
    resourceid: "vol-1",
    state: "Completed",
  });

  assert.equal(event.timestamp, "");
  assert.equal(event.level, "info");
  assert.equal(event.user, "engineering");
  assert.equal(event.action, "Completed");
  assert.equal(event.target, "vol-1");
  assert.equal(event.description, "Completed");
});

test("mapCloudStackEventLevel normalizes CloudStack event levels for the UI", () => {
  assert.equal(mapCloudStackEventLevel("INFO"), "info");
  assert.equal(mapCloudStackEventLevel("info"), "info");
  assert.equal(mapCloudStackEventLevel("WARN"), "warn");
  assert.equal(mapCloudStackEventLevel("warning"), "warn");
  assert.equal(mapCloudStackEventLevel("ERROR"), "error");
  assert.equal(mapCloudStackEventLevel("error"), "error");
  assert.equal(mapCloudStackEventLevel("TRACE"), "info");
  assert.equal(mapCloudStackEventLevel(undefined), "info");
});

test("eventsFromListEventsResponse maps the CloudStack response envelope", () => {
  const events = eventsFromListEventsResponse({
    listeventsresponse: {
      count: 1,
      event: [
        {
          username: "sam.rao",
          type: "VOLUME.CREATE",
          level: "WARN",
          resourcetype: "Volume",
          resourcename: "data-warehouse",
          created: "2026-05-21T02:00:00+0000",
        },
      ],
    },
  });

  assert.equal(events.length, 1);
  assert.equal(events[0]?.user, "sam.rao");
  assert.equal(events[0]?.level, "warn");
  assert.equal(events[0]?.target, "data-warehouse (Volume)");
});

test("getEventsFromBff calls the BFF listEvents command and forwards cookies", async () => {
  const previousCsUrl = process.env.CS_URL;
  const previousNextAuthUrl = process.env.NEXTAUTH_URL;
  const previousAppEnv = process.env.NEXT_PUBLIC_APP_ENV;
  process.env.CS_URL = "http://cloudstack.local";
  delete process.env.NEXTAUTH_URL;
  delete process.env.NEXT_PUBLIC_APP_ENV;

  let requestedUrl = "";
  let forwardedCookie: string | undefined;
  let cacheMode: RequestCache | undefined;
  const fetchImpl: typeof fetch = async (input, init) => {
    requestedUrl = String(input);
    forwardedCookie = (init?.headers as Record<string, string> | undefined)?.cookie;
    cacheMode = init?.cache;
    return Response.json({
      listeventsresponse: {
        event: [{ type: "VM.STOP", level: "ERROR", resourceid: "vm-2", created: "2026-05-21T03:00:00+0000" }],
      },
    });
  };

  try {
    const events = await getEventsFromBff({
      fetchImpl,
      requestHeaders: new Headers({
        cookie: "cloudstack.session=opaque",
        host: "console.example.test",
        "x-forwarded-proto": "https",
      }),
    });
    const url = new URL(requestedUrl);

    assert.equal(url.origin, "https://console.example.test");
    assert.equal(url.pathname, "/api/cs/listEvents");
    assert.equal(url.searchParams.get("page"), "1");
    assert.equal(url.searchParams.get("pagesize"), "50");
    assert.equal(forwardedCookie, "cloudstack.session=opaque");
    assert.equal(cacheMode, "no-store");
    assert.equal(events[0]?.action, "VM.STOP");
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
    restoreEnv("NEXTAUTH_URL", previousNextAuthUrl);
    restoreEnv("NEXT_PUBLIC_APP_ENV", previousAppEnv);
  }
});

test("getEventsFromBff returns mock events when CloudStack is unavailable", async () => {
  const previousCsUrl = process.env.CS_URL;
  process.env.CS_URL = "http://cloudstack.local";

  try {
    const events = await getEventsFromBff({
      fetchImpl: async () => new Response("{}", { status: 503 }),
    });

    assert.equal(events, mockEvents);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

test("getEventsFromBff returns mock events when the response envelope is missing", async () => {
  const previousCsUrl = process.env.CS_URL;
  process.env.CS_URL = "http://cloudstack.local";

  try {
    const events = await getEventsFromBff({
      fetchImpl: async () => Response.json({}),
    });

    assert.equal(events, mockEvents);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

test("getEventsFromBff returns mock events when fetch throws", async () => {
  const previousCsUrl = process.env.CS_URL;
  process.env.CS_URL = "http://cloudstack.local";

  try {
    const events = await getEventsFromBff({
      fetchImpl: async () => {
        throw new Error("network unavailable");
      },
    });

    assert.equal(events, mockEvents);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

test("getEventsFromBff does not call the BFF when CS_URL is absent", async () => {
  const previousCsUrl = process.env.CS_URL;
  delete process.env.CS_URL;
  let called = false;

  try {
    const events = await getEventsFromBff({
      fetchImpl: async () => {
        called = true;
        return Response.json({});
      },
    });

    assert.equal(called, false);
    assert.equal(events, mockEvents);
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
