import assert from "node:assert/strict";
import test from "node:test";

import {
  copyTemplate,
  deleteTemplate,
  updateTemplatePermissions,
} from "./template-actions.ts";

test("deleteTemplate posts safe JSON params and normalizes async job responses", async () => {
  const requests: Array<{ url: string; init?: RequestInit; body: Record<string, string> }> = [];
  const fetchImpl: typeof fetch = async (input, init) => {
    requests.push({
      url: String(input),
      init,
      body: JSON.parse(String(init?.body)) as Record<string, string>,
    });
    return Response.json({
      deletetemplateresponse: {
        jobid: "job-delete-1",
        id: "tmpl-1",
      },
    });
  };

  const result = await deleteTemplate(
    {
      id: "tmpl-1",
      expunge: true,
      command: "deleteEverything",
      response: "xml",
      sessionkey: "client-secret",
    } as Parameters<typeof deleteTemplate>[0],
    { fetchImpl },
  );

  assert.deepEqual(result, { status: "queued", jobId: "job-delete-1", resourceId: "tmpl-1" });
  assert.equal(requests.length, 1);
  assert.equal(new URL(requests[0]!.url, "http://localhost").pathname, "/api/cs/deleteTemplate");
  assert.equal(requests[0]!.init?.method, "POST");
  assert.equal((requests[0]!.init?.headers as Record<string, string>)["content-type"], "application/json");
  assert.deepEqual(requests[0]!.body, {
    id: "tmpl-1",
    expunge: "true",
  });
});

test("copyTemplate posts id and zone params and returns the queued job id", async () => {
  const requests: Array<{ url: string; body: Record<string, string> }> = [];
  const fetchImpl: typeof fetch = async (input, init) => {
    requests.push({
      url: String(input),
      body: JSON.parse(String(init?.body)) as Record<string, string>,
    });
    return Response.json({
      copytemplateresponse: {
        jobid: "job-copy-1",
      },
    });
  };

  const result = await copyTemplate(
    { id: "tmpl-1", sourceZoneId: "zone-a", destZoneId: "zone-b" },
    { fetchImpl },
  );

  assert.deepEqual(result, { status: "queued", jobId: "job-copy-1" });
  assert.equal(new URL(requests[0]!.url, "http://localhost").pathname, "/api/cs/copyTemplate");
  assert.deepEqual(requests[0]!.body, {
    id: "tmpl-1",
    sourcezoneid: "zone-a",
    destzoneid: "zone-b",
  });
});

test("updateTemplatePermissions posts visibility flags and normalizes success responses", async () => {
  const requests: Array<{ url: string; body: Record<string, string> }> = [];
  const fetchImpl: typeof fetch = async (input, init) => {
    requests.push({
      url: String(input),
      body: JSON.parse(String(init?.body)) as Record<string, string>,
    });
    return Response.json({
      updatetemplatepermissionsresponse: {
        success: "true",
      },
    });
  };

  const result = await updateTemplatePermissions(
    { id: "tmpl-1", isPublic: true, isFeatured: false, isExtractable: true },
    { fetchImpl },
  );

  assert.deepEqual(result, { status: "success" });
  assert.equal(new URL(requests[0]!.url, "http://localhost").pathname, "/api/cs/updateTemplatePermissions");
  assert.deepEqual(requests[0]!.body, {
    id: "tmpl-1",
    ispublic: "true",
    isfeatured: "false",
    isextractable: "true",
  });
});

test("template action helpers reject CloudStack errors and malformed responses", async () => {
  await assert.rejects(
    deleteTemplate(
      { id: "tmpl-1" },
      {
        fetchImpl: async () =>
          Response.json(
            { errorresponse: { errortext: "Template is currently in use" } },
            { status: 431 },
          ),
      },
    ),
    /Template is currently in use/,
  );

  await assert.rejects(
    copyTemplate(
      { id: "tmpl-1", destZoneId: "zone-b" },
      { fetchImpl: async () => Response.json({ copytemplateresponse: {} }) },
    ),
    /missing async job id/,
  );

  await assert.rejects(
    updateTemplatePermissions(
      { id: "tmpl-1", isPublic: true },
      { fetchImpl: async () => Response.json({ updatetemplatepermissionsresponse: {} }) },
    ),
    /missing success flag/,
  );
});
