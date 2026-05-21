import assert from "node:assert/strict";
import test from "node:test";

import {
  deleteVolumeFromBff,
  detachVolumeFromBff,
  queryVolumeActionJobResult,
} from "./volume-actions.ts";

test("detachVolumeFromBff posts only the volume id and returns the async job id", async () => {
  const requests: Array<{ url: string; init?: RequestInit; body: Record<string, string> }> = [];
  const fetchImpl: typeof fetch = async (input, init) => {
    requests.push({
      url: String(input),
      init,
      body: JSON.parse(String(init?.body)) as Record<string, string>,
    });
    return Response.json({
      detachvolumeresponse: {
        jobid: "job-detach-1",
        id: "vol-1",
      },
    });
  };

  const result = await detachVolumeFromBff("vol-1", { fetchImpl });

  assert.deepEqual(result, { jobId: "job-detach-1", volumeId: "vol-1" });
  assert.equal(requests.length, 1);
  assert.equal(new URL(requests[0]!.url).pathname, "/api/cs/detachVolume");
  assert.equal(requests[0]!.init?.method, "POST");
  assert.equal((requests[0]!.init?.headers as Record<string, string>)["content-type"], "application/json");
  assert.deepEqual(requests[0]!.body, { id: "vol-1" });
  assert.equal("command" in requests[0]!.body, false);
  assert.equal("sessionkey" in requests[0]!.body, false);
  assert.equal("response" in requests[0]!.body, false);
});

test("deleteVolumeFromBff posts only the volume id and normalizes synchronous success", async () => {
  const requests: Array<{ url: string; init?: RequestInit; body: Record<string, string> }> = [];
  const fetchImpl: typeof fetch = async (input, init) => {
    requests.push({
      url: String(input),
      init,
      body: JSON.parse(String(init?.body)) as Record<string, string>,
    });
    return Response.json({
      deletevolumeresponse: {
        success: true,
      },
    });
  };

  const result = await deleteVolumeFromBff("vol-2", { fetchImpl });

  assert.deepEqual(result, { success: true });
  assert.equal(new URL(requests[0]!.url).pathname, "/api/cs/deleteVolume");
  assert.equal(requests[0]!.init?.method, "POST");
  assert.deepEqual(requests[0]!.body, { id: "vol-2" });
});

test("volume action helpers reject failed or malformed responses", async () => {
  await assert.rejects(
    detachVolumeFromBff("vol-1", {
      fetchImpl: async () => Response.json({ error: "detach denied" }, { status: 403 }),
    }),
    /detach denied/,
  );

  await assert.rejects(
    deleteVolumeFromBff("vol-2", {
      fetchImpl: async () => Response.json({ errorresponse: { errortext: "Volume is attached" } }, { status: 431 }),
    }),
    /Volume is attached/,
  );

  await assert.rejects(
    detachVolumeFromBff("vol-1", {
      fetchImpl: async () => Response.json({ detachvolumeresponse: {} }),
    }),
    /missing async job id/,
  );

  await assert.rejects(
    deleteVolumeFromBff("vol-2", {
      fetchImpl: async () => Response.json({ deletevolumeresponse: { success: false } }),
    }),
    /did not report success/,
  );
});

test("queryVolumeActionJobResult normalizes pending, success, and failed async jobs", async () => {
  const payloads = [
    { queryasyncjobresultresponse: { jobid: "job-1", jobstatus: 0, jobprocstatus: 25 } },
    { queryasyncjobresultresponse: { jobid: "job-1", jobstatus: 1, jobresult: { volume: { id: "vol-1" } } } },
    {
      queryasyncjobresultresponse: {
        jobid: "job-2",
        jobstatus: 2,
        jobresultcode: 530,
        jobresult: { errortext: "Volume busy" },
      },
    },
  ];
  const urls: string[] = [];
  const fetchImpl: typeof fetch = async (input) => {
    urls.push(String(input));
    return Response.json(payloads.shift());
  };

  const pending = await queryVolumeActionJobResult("job-1", { fetchImpl });
  const success = await queryVolumeActionJobResult("job-1", { fetchImpl });
  const failed = await queryVolumeActionJobResult("job-2", { fetchImpl });

  assert.deepEqual(pending, { jobId: "job-1", status: "pending", progress: 25 });
  assert.deepEqual(success, { jobId: "job-1", status: "success", volumeId: "vol-1" });
  assert.deepEqual(failed, { jobId: "job-2", status: "failed", resultCode: 530, errorText: "Volume busy" });
  assert.equal(new URL(urls[0]!).pathname, "/api/cs/queryAsyncJobResult");
  assert.equal(new URL(urls[0]!).searchParams.get("jobid"), "job-1");
});
