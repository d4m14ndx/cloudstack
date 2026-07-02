// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.network.routeros.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.network.routeros.rules.RouterOSRule;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for the RouterOS REST client using a scripted fake transport:
 * request construction, response parsing, and the comment-tag idempotency
 * primitives (ensure / remove-by-comment).
 */
public class RouterOSApiClientTest {

    private static final String BASE = "https://10.1.1.1:443/rest";

    private FakeTransport transport;
    private RouterOSApiClient client;

    /** Scripted transport: records every request; replays canned responses per method+url prefix. */
    private static class FakeTransport implements RouterOSHttpTransport {
        final List<Request> requests = new ArrayList<>();
        final Map<String, Response> responses = new LinkedHashMap<>();
        Response defaultResponse = new Response(200, "[]");

        void respond(final String method, final String urlSubstring, final int status, final String body) {
            responses.put(method + " " + urlSubstring, new Response(status, body));
        }

        @Override
        public Response execute(final Request request) throws IOException {
            requests.add(request);
            for (final Map.Entry<String, Response> canned : responses.entrySet()) {
                final int idx = canned.getKey().indexOf(' ');
                final String method = canned.getKey().substring(0, idx);
                final String urlSubstring = canned.getKey().substring(idx + 1);
                if (request.getMethod().equals(method) && request.getUrl().contains(urlSubstring)) {
                    return canned.getValue();
                }
            }
            return defaultResponse;
        }

        Request lastRequest() {
            return requests.get(requests.size() - 1);
        }

        List<Request> requestsOf(final String method) {
            final List<Request> result = new ArrayList<>();
            for (final Request request : requests) {
                if (request.getMethod().equals(method)) {
                    result.add(request);
                }
            }
            return result;
        }

        @Override
        public void close() {
        }
    }

    @Before
    public void setUp() {
        transport = new FakeTransport();
        client = new RouterOSApiClient(BASE, transport);
    }

    private static Map<String, String> params(final String... kv) {
        final Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }

    // ------------------------------------------------------------------
    // Request construction
    // ------------------------------------------------------------------

    @Test
    public void testListBuildsGetWithQueryParameters() {
        client.list("ip/firewall/nat", params("comment", "cs-pf-abc"));
        assertEquals(1, transport.requests.size());
        assertEquals("GET", transport.lastRequest().getMethod());
        assertEquals(BASE + "/ip/firewall/nat?comment=cs-pf-abc", transport.lastRequest().getUrl());
    }

    @Test
    public void testListEncodesQueryValues() {
        client.list("ip/address", params("address", "10.1.1.1/24"));
        assertEquals(BASE + "/ip/address?address=10.1.1.1%2F24", transport.lastRequest().getUrl());
    }

    @Test
    public void testAddUsesPutWithJsonBody() {
        transport.respond("PUT", "/ip/firewall/nat", 201, "{\".id\":\"*1\"}");
        final Map<String, String> result = client.add("ip/firewall/nat", params("chain", "dstnat", "action", "dst-nat"));
        assertEquals("PUT", transport.lastRequest().getMethod());
        assertEquals(BASE + "/ip/firewall/nat", transport.lastRequest().getUrl());
        assertEquals("{\"chain\":\"dstnat\",\"action\":\"dst-nat\"}", transport.lastRequest().getBody());
        assertEquals("*1", result.get(".id"));
    }

    @Test
    public void testRemoveUsesDeleteOnItemUrl() {
        transport.respond("DELETE", "/ip/firewall/nat/*7", 204, "");
        client.remove("ip/firewall/nat", "*7");
        assertEquals("DELETE", transport.lastRequest().getMethod());
        assertEquals(BASE + "/ip/firewall/nat/*7", transport.lastRequest().getUrl().replace("%2A", "*"));
    }

    @Test
    public void testErrorStatusRaisesApiExceptionWithStatusCode() {
        transport.respond("GET", "/system/resource", 401, "{\"error\":401,\"message\":\"Unauthorized\"}");
        try {
            client.systemResource();
            fail("expected RouterOSApiException");
        } catch (final RouterOSApiException e) {
            assertEquals(401, e.getStatusCode());
            assertTrue(e.getMessage().contains("Unauthorized"));
        }
    }

    @Test
    public void testIsReachableTrueOnVersionDocument() {
        transport.respond("GET", "/system/resource", 200, "{\"version\":\"7.15\",\"uptime\":\"1d\"}");
        assertTrue(client.isReachable());
    }

    @Test
    public void testIsReachableFalseOnTransportError() {
        transport.respond("GET", "/system/resource", 503, "busy");
        assertFalse(client.isReachable());
    }

    // ------------------------------------------------------------------
    // ensureRules idempotency
    // ------------------------------------------------------------------

    private RouterOSRule natRule(final String comment) {
        return new RouterOSRule(RouterOSApiClient.PATH_FIREWALL_NAT,
                params("chain", "dstnat", "action", "dst-nat", "dst-address", "203.0.113.10", "comment", comment));
    }

    @Test
    public void testEnsureRulesAddsWhenNothingExists() {
        transport.respond("GET", "/ip/firewall/nat?comment=cs-pf-1", 200, "[]");
        final boolean changed = client.ensureRules("cs-pf-1", Arrays.asList(natRule("cs-pf-1")));
        assertTrue(changed);
        assertEquals(1, transport.requestsOf("PUT").size());
        assertEquals(0, transport.requestsOf("DELETE").size());
    }

    @Test
    public void testEnsureRulesIsNoOpWhenDesiredStateAlreadyPresent() {
        transport.respond("GET", "/ip/firewall/nat?comment=cs-pf-1", 200,
                "[{\".id\":\"*3\",\"chain\":\"dstnat\",\"action\":\"dst-nat\",\"dst-address\":\"203.0.113.10\",\"comment\":\"cs-pf-1\",\"dynamic\":\"false\"}]");
        final boolean changed = client.ensureRules("cs-pf-1", Arrays.asList(natRule("cs-pf-1")));
        assertFalse(changed);
        assertEquals(0, transport.requestsOf("PUT").size());
        assertEquals(0, transport.requestsOf("DELETE").size());
    }

    @Test
    public void testEnsureRulesReplacesStaleObjects() {
        transport.respond("GET", "/ip/firewall/nat?comment=cs-pf-1", 200,
                "[{\".id\":\"*3\",\"chain\":\"dstnat\",\"action\":\"dst-nat\",\"dst-address\":\"198.51.100.99\",\"comment\":\"cs-pf-1\"}]");
        final boolean changed = client.ensureRules("cs-pf-1", Arrays.asList(natRule("cs-pf-1")));
        assertTrue(changed);
        assertEquals(1, transport.requestsOf("DELETE").size());
        assertTrue(transport.requestsOf("DELETE").get(0).getUrl().contains("/ip/firewall/nat/"));
        assertEquals(1, transport.requestsOf("PUT").size());
    }

    @Test
    public void testEnsureRulesReplacesWhenCountDiffers() {
        transport.respond("GET", "/ip/firewall/nat?comment=cs-snat-5", 200,
                "[{\".id\":\"*3\",\"chain\":\"dstnat\",\"action\":\"dst-nat\",\"dst-address\":\"203.0.113.10\",\"comment\":\"cs-snat-5\"}]");
        final RouterOSRule dstNat = new RouterOSRule(RouterOSApiClient.PATH_FIREWALL_NAT,
                params("chain", "dstnat", "action", "dst-nat", "dst-address", "203.0.113.10", "comment", "cs-snat-5"));
        final RouterOSRule srcNat = new RouterOSRule(RouterOSApiClient.PATH_FIREWALL_NAT,
                params("chain", "srcnat", "action", "src-nat", "src-address", "10.1.1.10", "comment", "cs-snat-5"));
        final boolean changed = client.ensureRules("cs-snat-5", Arrays.asList(dstNat, srcNat));
        assertTrue(changed);
        assertEquals(1, transport.requestsOf("DELETE").size());
        assertEquals(2, transport.requestsOf("PUT").size());
    }

    @Test
    public void testEnsureRulesHandlesMultiplePathsIndependently() {
        transport.respond("GET", "/ip/firewall/mangle?comment=cs-fw-9", 200, "[]");
        transport.respond("GET", "/ip/firewall/filter?comment=cs-fw-9", 200,
                "[{\".id\":\"*8\",\"chain\":\"forward\",\"action\":\"accept\",\"comment\":\"cs-fw-9\"}]");
        final RouterOSRule mangle = new RouterOSRule(RouterOSApiClient.PATH_FIREWALL_MANGLE,
                params("chain", "prerouting", "action", "mark-connection", "comment", "cs-fw-9"));
        final RouterOSRule filter = new RouterOSRule(RouterOSApiClient.PATH_FIREWALL_FILTER,
                params("chain", "forward", "action", "accept", "comment", "cs-fw-9"));
        assertTrue(client.ensureRules("cs-fw-9", Arrays.asList(mangle, filter)));
        // filter side matched -> untouched; mangle side added
        assertEquals(1, transport.requestsOf("PUT").size());
        assertTrue(transport.requestsOf("PUT").get(0).getUrl().endsWith("/ip/firewall/mangle"));
        assertEquals(0, transport.requestsOf("DELETE").size());
    }

    @Test
    public void testEnsureRulesIgnoresPlaceBeforeWhenComparing() {
        transport.respond("GET", "/ip/firewall/filter?comment=cs-acl-x", 200,
                "[{\".id\":\"*4\",\"chain\":\"forward\",\"action\":\"accept\",\"comment\":\"cs-acl-x\"}]");
        final RouterOSRule rule = new RouterOSRule(RouterOSApiClient.PATH_FIREWALL_FILTER,
                params("chain", "forward", "action", "accept", "comment", "cs-acl-x")).withPlaceBefore("*9");
        assertFalse(client.ensureRules("cs-acl-x", Arrays.asList(rule)));
    }

    // ------------------------------------------------------------------
    // removeByComment
    // ------------------------------------------------------------------

    @Test
    public void testRemoveByCommentDeletesEveryMatchOnGivenPaths() {
        transport.respond("GET", "/ip/firewall/nat?comment=cs-pf-2", 200,
                "[{\".id\":\"*1\",\"comment\":\"cs-pf-2\"},{\".id\":\"*2\",\"comment\":\"cs-pf-2\"}]");
        final int removed = client.removeByComment("cs-pf-2", RouterOSApiClient.PATH_FIREWALL_NAT);
        assertEquals(2, removed);
        assertEquals(2, transport.requestsOf("DELETE").size());
    }

    @Test
    public void testRemoveByCommentSearchesAllRulePathsByDefault() {
        client.removeByComment("cs-fw-3");
        assertEquals(RouterOSApiClient.RULE_PATHS.length, transport.requestsOf("GET").size());
    }

    @Test
    public void testRemoveByCommentPrefixFiltersClientSide() {
        transport.respond("GET", "/ip/firewall/filter", 200,
                "[{\".id\":\"*1\",\"comment\":\"cs-acl-net1-item-a\"},{\".id\":\"*2\",\"comment\":\"cs-acl-net1-default-in\"},{\".id\":\"*3\",\"comment\":\"other\"},{\".id\":\"*4\"}]");
        final int removed = client.removeByCommentPrefix(RouterOSApiClient.PATH_FIREWALL_FILTER, "cs-acl-net1-item-");
        assertEquals(1, removed);
        assertEquals(1, transport.requestsOf("DELETE").size());
        assertTrue(transport.requestsOf("DELETE").get(0).getUrl().contains("/ip/firewall/filter/"));
    }

    // ------------------------------------------------------------------
    // Typed helpers
    // ------------------------------------------------------------------

    @Test
    public void testSetUserPasswordLooksUpUserThenPatches() {
        transport.respond("GET", "/user?name=admin", 200, "[{\".id\":\"*1\",\"name\":\"admin\"}]");
        transport.respond("PATCH", "/user/*1", 200, "{}");
        client.setUserPassword("admin", "s3cret");
        final List<RouterOSHttpTransport.Request> patches = transport.requestsOf("PATCH");
        assertEquals(1, patches.size());
        assertEquals("{\"password\":\"s3cret\"}", patches.get(0).getBody());
    }

    @Test
    public void testSetUserPasswordFailsWhenUserMissing() {
        transport.respond("GET", "/user?name=admin", 200, "[]");
        try {
            client.setUserPassword("admin", "s3cret");
            fail("expected RouterOSApiException");
        } catch (final RouterOSApiException expected) {
            assertTrue(expected.getMessage().contains("admin"));
        }
    }

    @Test
    public void testSetIdentityPostsToSetEndpoint() {
        client.setIdentity("cs-router-1");
        assertEquals("POST", transport.lastRequest().getMethod());
        assertEquals(BASE + "/system/identity/set", transport.lastRequest().getUrl());
        assertEquals("{\"name\":\"cs-router-1\"}", transport.lastRequest().getBody());
    }

    @Test
    public void testAddDhcpLeaseIsIdempotentByNicComment() {
        transport.respond("GET", "/ip/dhcp-server/lease?comment=cs-nic-n1", 200,
                "[{\".id\":\"*5\",\"address\":\"10.1.1.50\",\"mac-address\":\"02:00:00:00:00:01\",\"server\":\"cs-net1\",\"comment\":\"cs-nic-n1\"}]");
        client.addDhcpLease("cs-net1", "10.1.1.50", "02:00:00:00:00:01", "cs-nic-n1");
        assertEquals(0, transport.requestsOf("PUT").size());
    }
}
