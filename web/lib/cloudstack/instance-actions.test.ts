import assert from "node:assert/strict";
import test from "node:test";

import {
  destroyVirtualMachine,
  queryInstanceActionJobResult,
  rebootVirtualMachine,
  startVirtualMachine,
  stopVirtualMachine,
} from "./instance-actions.ts";

test("VM lifecycle helpers POST only safe JSON parameters to command-specific BFF routes", async () => {
  const requests: Array<{ url: string; init?: RequestInit; body: Record<string, unknown> }> = [];
  const fetchImpl: typeof fetch = async (input, init) => {
    requests.push({
      url: String(input),
      init,
      body: JSON.parse(String(init?.body)) as Record<string, unknown>,
    });
    const command = new URL(String(input)).pathname.split("/").at(-1);
    return Response.json({
      [`${command?.toLowerCase()}response`]: {
        jobid: `${command}-job`,
      },
    });
  };

  await startVirtualMachine("vm-1", { fetchImpl });
  await stopVirtualMachine("vm-2", { fetchImpl });
  await rebootVirtualMachine("vm-3", { fetchImpl });
  await destroyVirtualMachine("vm-4", { fetchImpl });
  await destroyVirtualMachine("vm-5", { expunge: true, fetchImpl });

  assert.deepEqual(
    requests.map((request) => new URL(request.url).pathname),
    [
      "/api/cs/startVirtualMachine",
      "/api/cs/stopVirtualMachine",
      "/api/cs/rebootVirtualMachine",
      "/api/cs/destroyVirtualMachine",
      "/api/cs/destroyVirtualMachine",
    ],
  );
  assert.deepEqual(
    requests.map((request) => request.init?.method),
    ["POST", "POST", "POST", "POST", "POST"],
  );
  assert.deepEqual(
    requests.map((request) => (request.init?.headers as Record<string, string>)["content-type"]),
    ["application/json", "application/json", "application/json", "application/json", "application/json"],
  );
  assert.deepEqual(
    requests.map((request) => request.body),
    [{ id: "vm-1" }, { id: "vm-2" }, { id: "vm-3" }, { id: "vm-4" }, { id: "vm-5", expunge: "true" }],
  );
  for (const request of requests) {
    assert.equal("sessionkey" in request.body, false);
    assert.equal("command" in request.body, false);
    assert.equal("response" in request.body, false);
  }
});

test("VM lifecycle helpers return async job ids and reject failed or malformed responses", async () => {
  const started = await startVirtualMachine("vm-1", {
    fetchImpl: async () =>
      Response.json({
        startvirtualmachineresponse: {
          jobid: "job-1",
        },
      }),
  });

  assert.deepEqual(started, { jobId: "job-1" });

  await assert.rejects(
    stopVirtualMachine("vm-1", {
      fetchImpl: async () => Response.json({ errorresponse: { errortext: "VM is already stopping" } }, { status: 431 }),
    }),
    /VM is already stopping/,
  );

  await assert.rejects(
    rebootVirtualMachine("vm-1", {
      fetchImpl: async () => Response.json({ rebootvirtualmachineresponse: {} }),
    }),
    /missing async job id/,
  );
});

test("queryInstanceActionJobResult normalizes pending, success, and failed async jobs", async () => {
  const payloads = [
    { queryasyncjobresultresponse: { jobid: "job-1", jobstatus: "0", jobprocstatus: "25" } },
    {
      queryasyncjobresultresponse: {
        jobid: "job-1",
        jobstatus: "1",
        jobresult: { virtualmachine: { id: "vm-1", name: "web-01", state: "Stopped" } },
      },
    },
    {
      queryasyncjobresultresponse: {
        jobid: "job-2",
        jobstatus: "2",
        jobresultcode: "530",
        jobresult: { errortext: "Operation unavailable" },
      },
    },
  ];
  const urls: string[] = [];
  const fetchImpl: typeof fetch = async (input) => {
    urls.push(String(input));
    return Response.json(payloads.shift());
  };

  const pending = await queryInstanceActionJobResult("job-1", { fetchImpl });
  const success = await queryInstanceActionJobResult("job-1", { fetchImpl });
  const failed = await queryInstanceActionJobResult("job-2", { fetchImpl });

  assert.deepEqual(pending, { jobId: "job-1", status: "pending", progress: 25 });
  assert.deepEqual(success, {
    jobId: "job-1",
    status: "success",
    virtualMachineId: "vm-1",
    virtualMachineName: "web-01",
    virtualMachineState: "Stopped",
  });
  assert.deepEqual(failed, {
    jobId: "job-2",
    status: "failed",
    resultCode: 530,
    errorText: "Operation unavailable",
  });
  assert.equal(new URL(urls[0]!).pathname, "/api/cs/queryAsyncJobResult");
  assert.equal(new URL(urls[0]!).searchParams.get("jobid"), "job-1");
});
