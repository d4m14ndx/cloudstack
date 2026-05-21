import assert from "node:assert/strict";
import test from "node:test";

import {
  authorizeSecurityGroupEgress,
  authorizeSecurityGroupIngress,
  createSecurityGroup,
  deleteSecurityGroup,
  revokeSecurityGroupEgress,
  revokeSecurityGroupIngress,
} from "./security-group-actions.ts";

test("createSecurityGroup posts only safe group params and normalizes the response envelope", async () => {
  const calls: FetchCall[] = [];
  const result = await createSecurityGroup(
    {
      name: "web",
      description: "Web tier",
      domainId: "domain-1",
      projectId: "project-1",
      account: "platform",
      unsafe: "ignored",
    },
    { fetchImpl: captureFetch(calls, { createsecuritygroupresponse: { securitygroup: { id: "sg-1", name: "web" } } }) },
  );

  assert.equal(calls.length, 1);
  assert.equal(calls[0]?.url, "/api/cs/createSecurityGroup");
  assert.equal(calls[0]?.method, "POST");
  assert.deepEqual(calls[0]?.body, {
    name: "web",
    description: "Web tier",
    domainid: "domain-1",
    projectid: "project-1",
    account: "platform",
  });
  assert.deepEqual(result, { id: "sg-1", name: "web", ruleId: null, jobId: null, success: true });
});

test("deleteSecurityGroup supports async responses", async () => {
  const calls: FetchCall[] = [];
  const result = await deleteSecurityGroup(
    { id: "sg-1", name: "ignored-by-id", projectId: "project-1" },
    { fetchImpl: captureFetch(calls, { deletesecuritygroupresponse: { jobid: "job-1" } }) },
  );

  assert.deepEqual(calls[0]?.body, { id: "sg-1", projectid: "project-1" });
  assert.deepEqual(result, { id: null, name: null, ruleId: null, jobId: "job-1", success: true });
});

test("authorizeSecurityGroupIngress posts TCP rule params and returns rule id", async () => {
  const calls: FetchCall[] = [];
  const result = await authorizeSecurityGroupIngress(
    {
      securityGroupId: "sg-1",
      protocol: "tcp",
      cidrList: "203.0.113.0/24",
      startPort: 443,
      endPort: 443,
      sessionkey: "ignored",
      response: "ignored",
    },
    {
      fetchImpl: captureFetch(calls, {
        authorizesecuritygroupingressresponse: { securitygroup: { ingressrule: [{ ruleid: "rule-1" }] } },
      }),
    },
  );

  assert.deepEqual(calls[0]?.body, {
    securitygroupid: "sg-1",
    protocol: "tcp",
    cidrlist: "203.0.113.0/24",
    startport: "443",
    endport: "443",
  });
  assert.equal(result.ruleId, "rule-1");
  assert.equal(result.success, true);
});

test("authorizeSecurityGroupEgress posts ICMP rule params", async () => {
  const calls: FetchCall[] = [];
  await authorizeSecurityGroupEgress(
    {
      securityGroupName: "default",
      protocol: "icmp",
      cidrList: "0.0.0.0/0",
      icmpType: 8,
      icmpCode: 0,
    },
    { fetchImpl: captureFetch(calls, { authorizesecuritygroupegressresponse: { jobid: "job-icmp" } }) },
  );

  assert.deepEqual(calls[0]?.body, {
    securitygroupname: "default",
    protocol: "icmp",
    cidrlist: "0.0.0.0/0",
    icmptype: "8",
    icmpcode: "0",
  });
});

test("revoke helpers post rule ids to the matching revoke command", async () => {
  const ingressCalls: FetchCall[] = [];
  const egressCalls: FetchCall[] = [];

  await revokeSecurityGroupIngress(
    { ruleId: "ingress-rule" },
    { fetchImpl: captureFetch(ingressCalls, { revokesecuritygroupingressresponse: { success: true } }) },
  );
  await revokeSecurityGroupEgress(
    { ruleId: "egress-rule" },
    { fetchImpl: captureFetch(egressCalls, { revokesecuritygroupegressresponse: { success: "true" } }) },
  );

  assert.equal(ingressCalls[0]?.url, "/api/cs/revokeSecurityGroupIngress");
  assert.deepEqual(ingressCalls[0]?.body, { id: "ingress-rule" });
  assert.equal(egressCalls[0]?.url, "/api/cs/revokeSecurityGroupEgress");
  assert.deepEqual(egressCalls[0]?.body, { id: "egress-rule" });
});

test("action helpers surface CloudStack and malformed response errors", async () => {
  await assert.rejects(
    () =>
      createSecurityGroup(
        { name: "bad" },
        { fetchImpl: captureFetch([], { errorresponse: { errortext: "Duplicate security group" } }, { ok: false }) },
      ),
    /Duplicate security group/,
  );

  await assert.rejects(
    () => revokeSecurityGroupIngress({ ruleId: "rule-1" }, { fetchImpl: captureFetch([], {}) }),
    /missing revokesecuritygroupingressresponse envelope/,
  );
});

type FetchCall = {
  url: string;
  method?: string;
  body: unknown;
};

function captureFetch(calls: FetchCall[], payload: unknown, options: { ok?: boolean } = {}): typeof fetch {
  return async (input, init) => {
    calls.push({
      url: String(input),
      method: init?.method,
      body: init?.body ? JSON.parse(String(init.body)) : null,
    });
    return Response.json(payload, { status: options.ok === false ? 400 : 200 });
  };
}
