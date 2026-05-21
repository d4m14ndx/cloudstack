import assert from "node:assert/strict";
import test from "node:test";

import {
  associateIpAddress,
  disableStaticNat,
  disassociateIpAddress,
  enableStaticNat,
  queryNetworkActionJobResult,
} from "./network-actions.ts";

test("associateIpAddress posts only networkid for isolated networks and returns queued IP details", async () => {
  const requests: Array<{ url: string; init?: RequestInit; body: Record<string, string> }> = [];
  const fetchImpl: typeof fetch = async (input, init) => {
    requests.push({
      url: String(input),
      init,
      body: JSON.parse(String(init?.body)) as Record<string, string>,
    });
    return Response.json({
      associateipaddressresponse: {
        jobid: "job-acquire-1",
        id: "ip-1",
      },
    });
  };

  const result = await associateIpAddress(
    {
      kind: "Isolated",
      networkId: "net-1",
      command: "deleteEverything",
      response: "xml",
      sessionkey: "client-secret",
    } as Parameters<typeof associateIpAddress>[0],
    { fetchImpl },
  );

  assert.deepEqual(result, { jobId: "job-acquire-1", ipAddressId: "ip-1" });
  assert.equal(requests.length, 1);
  assert.equal(new URL(requests[0]!.url).pathname, "/api/cs/associateIpAddress");
  assert.equal(requests[0]!.init?.method, "POST");
  assert.equal((requests[0]!.init?.headers as Record<string, string>)["content-type"], "application/json");
  assert.deepEqual(requests[0]!.body, { networkid: "net-1" });
  assert.equal("vpcid" in requests[0]!.body, false);
  assert.equal("command" in requests[0]!.body, false);
  assert.equal("sessionkey" in requests[0]!.body, false);
  assert.equal("response" in requests[0]!.body, false);
});

test("associateIpAddress posts only vpcid for VPCs", async () => {
  const requests: Array<{ url: string; body: Record<string, string> }> = [];
  const fetchImpl: typeof fetch = async (input, init) => {
    requests.push({
      url: String(input),
      body: JSON.parse(String(init?.body)) as Record<string, string>,
    });
    return Response.json({
      associateipaddressresponse: {
        jobid: "job-vpc-acquire-1",
      },
    });
  };

  const result = await associateIpAddress({ kind: "VPC", vpcId: "vpc-1" }, { fetchImpl });

  assert.deepEqual(result, { jobId: "job-vpc-acquire-1" });
  assert.equal(new URL(requests[0]!.url).pathname, "/api/cs/associateIpAddress");
  assert.deepEqual(requests[0]!.body, { vpcid: "vpc-1" });
  assert.equal("networkid" in requests[0]!.body, false);
});

test("disassociateIpAddress posts only id and returns the async job id", async () => {
  const requests: Array<{ url: string; init?: RequestInit; body: Record<string, string> }> = [];
  const fetchImpl: typeof fetch = async (input, init) => {
    requests.push({
      url: String(input),
      init,
      body: JSON.parse(String(init?.body)) as Record<string, string>,
    });
    return Response.json({
      disassociateipaddressresponse: {
        jobid: "job-release-1",
      },
    });
  };

  const result = await disassociateIpAddress("ip-1", { fetchImpl });

  assert.deepEqual(result, { jobId: "job-release-1" });
  assert.equal(new URL(requests[0]!.url).pathname, "/api/cs/disassociateIpAddress");
  assert.equal(requests[0]!.init?.method, "POST");
  assert.deepEqual(requests[0]!.body, { id: "ip-1" });
});

test("static NAT helpers post safe params and normalize success responses", async () => {
  const requests: Array<{ url: string; init?: RequestInit; body: Record<string, string> }> = [];
  const fetchImpl: typeof fetch = async (input, init) => {
    requests.push({
      url: String(input),
      init,
      body: JSON.parse(String(init?.body)) as Record<string, string>,
    });
    const command = new URL(String(input)).pathname.split("/").at(-1);
    return Response.json({
      [`${command?.toLowerCase()}response`]: {
        success: "true",
      },
    });
  };

  const enabled = await enableStaticNat(
    {
      ipAddressId: "ip-1",
      virtualMachineId: "vm-1",
      networkId: "net-1",
      command: "override",
      response: "xml",
      sessionkey: "secret",
    } as Parameters<typeof enableStaticNat>[0],
    { fetchImpl },
  );
  const disabled = await disableStaticNat("ip-1", { fetchImpl });

  assert.deepEqual(enabled, { success: true });
  assert.deepEqual(disabled, { success: true });
  assert.deepEqual(
    requests.map((request) => new URL(request.url).pathname),
    ["/api/cs/enableStaticNat", "/api/cs/disableStaticNat"],
  );
  assert.deepEqual(
    requests.map((request) => request.init?.method),
    ["POST", "POST"],
  );
  assert.deepEqual(
    requests.map((request) => request.body),
    [
      { ipaddressid: "ip-1", virtualmachineid: "vm-1", networkid: "net-1" },
      { ipaddressid: "ip-1" },
    ],
  );
});

test("network action helpers reject CloudStack errors and malformed responses", async () => {
  await assert.rejects(
    associateIpAddress(
      { kind: "Isolated", networkId: "net-1" },
      { fetchImpl: async () => Response.json({ error: "IP limit reached" }, { status: 431 }) },
    ),
    /IP limit reached/,
  );

  await assert.rejects(
    disassociateIpAddress("ip-1", {
      fetchImpl: async () => Response.json({ errorresponse: { errortext: "Cannot release source NAT" } }, { status: 431 }),
    }),
    /Cannot release source NAT/,
  );

  await assert.rejects(
    enableStaticNat(
      { ipAddressId: "ip-1", virtualMachineId: "vm-1" },
      { fetchImpl: async () => Response.json({ enablestaticnatresponse: { errortext: "Rule conflict" } }) },
    ),
    /Rule conflict/,
  );

  await assert.rejects(
    disableStaticNat("ip-1", {
      fetchImpl: async () => Response.json({ disablestaticnatresponse: { errortext: "Static NAT missing" } }),
    }),
    /Static NAT missing/,
  );

  await assert.rejects(
    associateIpAddress(
      { kind: "VPC", vpcId: "vpc-1" },
      { fetchImpl: async () => Response.json({ associateipaddressresponse: {} }) },
    ),
    /missing async job id/,
  );

  await assert.rejects(
    disableStaticNat("ip-1", {
      fetchImpl: async () => Response.json({ disablestaticnatresponse: { success: false } }),
    }),
    /did not report success/,
  );

  await assert.rejects(
    associateIpAddress(
      { kind: "Isolated", networkId: "net-1" },
      { fetchImpl: async () => Response.json(["not", "an", "object"]) },
    ),
    /not valid JSON/,
  );
});

test("queryNetworkActionJobResult normalizes pending, success, and failed async jobs", async () => {
  const payloads = [
    { queryasyncjobresultresponse: { jobid: "job-1", jobstatus: "0", jobprocstatus: "33" } },
    {
      queryasyncjobresultresponse: {
        jobid: "job-1",
        jobstatus: "1",
        jobresult: { publicipaddress: { id: "ip-1", ipaddress: "203.0.113.10" } },
      },
    },
    {
      queryasyncjobresultresponse: {
        jobid: "job-2",
        jobstatus: "2",
        jobresultcode: "530",
        jobresult: { errortext: "Address busy" },
      },
    },
  ];
  const urls: string[] = [];
  const fetchImpl: typeof fetch = async (input) => {
    urls.push(String(input));
    return Response.json(payloads.shift());
  };

  const pending = await queryNetworkActionJobResult("job-1", { fetchImpl });
  const success = await queryNetworkActionJobResult("job-1", { fetchImpl });
  const failed = await queryNetworkActionJobResult("job-2", { fetchImpl });

  assert.deepEqual(pending, { jobId: "job-1", status: "pending", progress: 33 });
  assert.deepEqual(success, {
    jobId: "job-1",
    status: "success",
    ipAddressId: "ip-1",
    ipAddress: "203.0.113.10",
  });
  assert.deepEqual(failed, { jobId: "job-2", status: "failed", resultCode: 530, errorText: "Address busy" });
  assert.equal(new URL(urls[0]!).pathname, "/api/cs/queryAsyncJobResult");
  assert.equal(new URL(urls[0]!).searchParams.get("jobid"), "job-1");
});

test("queryNetworkActionJobResult rejects malformed job responses and CloudStack errors", async () => {
  await assert.rejects(
    queryNetworkActionJobResult("job-1", {
      fetchImpl: async () => Response.json({ errorresponse: { errortext: "Job not found" } }, { status: 431 }),
    }),
    /Job not found/,
  );

  await assert.rejects(
    queryNetworkActionJobResult("job-1", {
      fetchImpl: async () => Response.json({ queryasyncjobresultresponse: {} }),
    }),
    /missing job id/,
  );
});
