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
package com.cloud.hypervisor.proxmox.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Unit tests for {@link ProxmoxApiClient} without a live Proxmox server. The HTTP layer is not
 * exercised: a test subclass overrides the generic {@code get}/{@code post} verbs (the stable
 * surface all typed helpers are contracted to go through) with canned JSON responses, so only
 * the pure request-shaping and response-parsing logic is under test.
 */
public class ProxmoxApiClientTest {

    private static final String NODE = "pve1";
    private static final String UPID = "UPID:pve1:0003C4F1:00A3B2F1:665F1A2B:qmstart:10005:root@pam:";

    private FakeApiClient client;

    /**
     * ProxmoxApiClient with the generic verbs replaced by canned responses. Uses API token
     * authentication so no ticket login is ever attempted. 192.0.2.1 is TEST-NET-1
     * (RFC 5737); no request must ever be sent to it.
     */
    private static class FakeApiClient extends ProxmoxApiClient {
        private final Map<String, JsonElement> getResponses = new HashMap<>();
        private final Map<String, RuntimeException> getFailures = new HashMap<>();
        private JsonElement postResponse = JsonNull.INSTANCE;
        private String lastPostPath;
        private Map<String, Object> lastPostParams;

        FakeApiClient() {
            super("192.0.2.1", 8006, null, null, "root@pam!cloudstack", "secret", false, 1, 1);
        }

        @Override
        public JsonElement get(String path) {
            RuntimeException failure = getFailures.get(path);
            if (failure != null) {
                throw failure;
            }
            JsonElement response = getResponses.get(path);
            if (response == null) {
                throw new AssertionError("Unexpected GET " + path);
            }
            return response;
        }

        @Override
        public JsonElement post(String path, Map<String, Object> params) {
            lastPostPath = path;
            lastPostParams = params;
            return postResponse;
        }

        void onGet(String path, String json) {
            getResponses.put(path, JsonParser.parseString(json));
        }

        void failGet(String path, RuntimeException e) {
            getFailures.put(path, e);
        }
    }

    private static String encodedUpid() {
        return URLEncoder.encode(UPID, StandardCharsets.UTF_8);
    }

    @Before
    public void setUp() {
        client = new FakeApiClient();
    }

    @After
    public void tearDown() {
        client.close();
    }

    // ---- construction ----

    @Test
    public void testConstructorWithTokenAuthDoesNotConnect() {
        // no canned responses configured: any network-touching call would blow up, so plain
        // construction and close must not perform any request
        ProxmoxApiClient tokenClient = new ProxmoxApiClient("192.0.2.1", 8006, null, null,
                "monitor@pve!cloudstack", "secret", false, 1, 1);
        tokenClient.close();
    }

    @Test
    public void testConstructorWithPasswordAuthDoesNotConnect() {
        // ticket login is lazy (first request), not done at construction
        ProxmoxApiClient passwordClient = new ProxmoxApiClient("192.0.2.1", 8006, "root", "password",
                null, null, false, 1, 1);
        passwordClient.close();
    }

    @Test
    public void testConstructorWithTlsVerificationEnabled() {
        ProxmoxApiClient tlsClient = new ProxmoxApiClient("192.0.2.1", 8006, "root", "password",
                null, null, true, 1, 1);
        tlsClient.close();
    }

    // ---- ProxmoxApiException ----

    @Test
    public void testExceptionCarriesStatusCodeAndMessage() {
        ProxmoxApiException e = new ProxmoxApiException(401, "authentication failure");
        assertEquals(401, e.getStatusCode());
        assertTrue(e.getMessage().contains("authentication failure"));
    }

    @Test
    public void testExceptionCarriesCause() {
        IOException cause = new IOException("connection reset");
        ProxmoxApiException e = new ProxmoxApiException(0, "transport error", cause);
        assertEquals(0, e.getStatusCode());
        assertSame(cause, e.getCause());
    }

    @Test
    public void testExceptionIsCloudRuntimeException() {
        assertTrue(new ProxmoxApiException(500, "boom") instanceof CloudRuntimeException);
    }

    // ---- task handling ----

    @Test
    public void testPostTaskReturnsUpid() {
        client.postResponse = new JsonPrimitive(UPID);
        String upid = client.postTask("/nodes/" + NODE + "/qemu/10005/status/start", null);
        assertEquals(UPID, upid);
        assertEquals("/nodes/" + NODE + "/qemu/10005/status/start", client.lastPostPath);
    }

    @Test
    public void testPostTaskWithoutUpidThrows() {
        client.postResponse = new JsonPrimitive("ok");
        try {
            client.postTask("/nodes/" + NODE + "/qemu/10005/status/start", null);
            fail("expected ProxmoxApiException");
        } catch (ProxmoxApiException e) {
            assertEquals(0, e.getStatusCode());
            assertTrue(e.getMessage().contains("/nodes/" + NODE + "/qemu/10005/status/start"));
        }
    }

    @Test
    public void testWaitForTaskReturnsOnOkExit() {
        client.onGet("/nodes/" + NODE + "/tasks/" + encodedUpid() + "/status",
                "{\"status\":\"stopped\",\"exitstatus\":\"OK\"}");
        client.waitForTask(NODE, UPID, 1000);
    }

    @Test
    public void testWaitForTaskThrowsOnFailedExitWithLogTail() {
        client.onGet("/nodes/" + NODE + "/tasks/" + encodedUpid() + "/status",
                "{\"status\":\"stopped\",\"exitstatus\":\"storage 'local-lvm' is full\"}");
        client.onGet("/nodes/" + NODE + "/tasks/" + encodedUpid() + "/log?start=0&limit=1000",
                "[{\"n\":1,\"t\":\"starting task\"},{\"n\":2,\"t\":\"no space left on device\"}]");
        try {
            client.waitForTask(NODE, UPID, 1000);
            fail("expected ProxmoxApiException");
        } catch (ProxmoxApiException e) {
            assertEquals(0, e.getStatusCode());
            assertTrue(e.getMessage().contains("storage 'local-lvm' is full"));
            assertTrue(e.getMessage().contains("no space left on device"));
        }
    }

    @Test
    public void testWaitForTaskTimesOutOnRunningTask() {
        client.onGet("/nodes/" + NODE + "/tasks/" + encodedUpid() + "/status",
                "{\"status\":\"running\"}");
        try {
            // zero timeout: the deadline check fires after the first poll, before any sleep
            client.waitForTask(NODE, UPID, 0);
            fail("expected ProxmoxApiException");
        } catch (ProxmoxApiException e) {
            assertEquals(0, e.getStatusCode());
            assertTrue(e.getMessage().contains("Timed out"));
        }
    }

    @Test
    public void testShutdownVmReturnsFalseInsteadOfThrowing() {
        client.postResponse = new JsonPrimitive("not-a-upid");
        assertFalse(client.shutdownVm(NODE, 10005, 30000));
    }

    @Test
    public void testShutdownVmSendsGracefulParametersAndReturnsTrue() {
        client.postResponse = new JsonPrimitive(UPID);
        client.onGet("/nodes/" + NODE + "/tasks/" + encodedUpid() + "/status",
                "{\"status\":\"stopped\",\"exitstatus\":\"OK\"}");

        assertTrue(client.shutdownVm(NODE, 10005, 30000));

        assertEquals("/nodes/" + NODE + "/qemu/10005/status/shutdown", client.lastPostPath);
        assertEquals(Long.valueOf(30L), client.lastPostParams.get("timeout"));
        assertEquals(Boolean.FALSE, client.lastPostParams.get("forceStop"));
    }

    // ---- typed GET helpers (response parsing) ----

    @Test
    public void testGetVersion() {
        client.onGet("/version", "{\"version\":\"8.2.4\",\"release\":\"8.2\"}");
        assertEquals("8.2.4", client.getVersion());
    }

    @Test
    public void testGetNodeNames() {
        client.onGet("/nodes", "[{\"node\":\"pve1\"},{\"node\":\"pve2\"},{\"status\":\"unknown\"}]");
        assertEquals(2, client.getNodeNames().size());
        assertTrue(client.getNodeNames().contains("pve1"));
        assertTrue(client.getNodeNames().contains("pve2"));
    }

    @Test
    public void testGetClusterNameFromClusterEntry() {
        client.onGet("/cluster/status",
                "[{\"type\":\"node\",\"name\":\"pve1\"},{\"type\":\"cluster\",\"name\":\"homelab\"}]");
        assertEquals("homelab", client.getClusterName());
    }

    @Test
    public void testGetClusterNameStandaloneFallsBackToNodeName() {
        client.onGet("/cluster/status", "[{\"type\":\"node\",\"name\":\"pve1\"}]");
        assertEquals("pve1", client.getClusterName());
    }

    @Test
    public void testIsQuorate() {
        client.onGet("/cluster/status",
                "[{\"type\":\"cluster\",\"name\":\"homelab\",\"quorate\":1}]");
        assertTrue(client.isQuorate());
    }

    @Test
    public void testIsNotQuorate() {
        client.onGet("/cluster/status",
                "[{\"type\":\"cluster\",\"name\":\"homelab\",\"quorate\":0}]");
        assertFalse(client.isQuorate());
    }

    @Test
    public void testStandaloneNodeIsAlwaysQuorate() {
        client.onGet("/cluster/status", "[{\"type\":\"node\",\"name\":\"pve1\"}]");
        assertTrue(client.isQuorate());
    }

    @Test
    public void testFindNodeOfVm() {
        client.onGet("/cluster/resources?type=vm",
                "[{\"vmid\":10004,\"node\":\"pve2\",\"name\":\"i-2-4-VM\"}," +
                "{\"vmid\":10005,\"node\":\"pve1\",\"name\":\"i-2-5-VM\"}]");
        assertEquals("pve1", client.findNodeOfVm(10005));
        assertNull(client.findNodeOfVm(10099));
    }

    @Test
    public void testFindVmidByName() {
        client.onGet("/cluster/resources?type=vm",
                "[{\"vmid\":10004,\"node\":\"pve2\",\"name\":\"i-2-4-VM\"}," +
                "{\"vmid\":10005,\"node\":\"pve1\",\"name\":\"i-2-5-VM\"}]");
        assertEquals(Integer.valueOf(10005), client.findVmidByName("i-2-5-VM"));
        assertNull(client.findVmidByName("missing-vm"));
        assertNull(client.findVmidByName(null));
    }

    @Test
    public void testGetNextVmid() {
        client.onGet("/cluster/nextid", "\"100\"");
        assertEquals(100, client.getNextVmid());
    }

    @Test
    public void testGetVolumePath() {
        client.onGet("/nodes/" + NODE + "/storage/local-lvm/content/local-lvm:vm-10005-disk-0",
                "{\"path\":\"/dev/pve/vm-10005-disk-0\"}");
        assertEquals("/dev/pve/vm-10005-disk-0", client.getVolumePath(NODE, "local-lvm:vm-10005-disk-0"));
    }

    @Test
    public void testGetVolumePathRejectsVolidWithoutStorageSeparator() {
        try {
            client.getVolumePath(NODE, "not-a-volid");
            fail("expected ProxmoxApiException");
        } catch (ProxmoxApiException e) {
            assertEquals(0, e.getStatusCode());
            assertTrue(e.getMessage().contains("not-a-volid"));
        }
    }

    @Test
    public void testGetNodeNetworkAddressReturnsNullOnNotFound() {
        client.failGet("/nodes/" + NODE + "/network/vmbr0", new ProxmoxApiException(404, "no such interface"));
        assertNull(client.getNodeNetworkAddress(NODE, "vmbr0"));
    }

    @Test
    public void testGetNodeNetworkAddressPropagatesOtherErrors() {
        client.failGet("/nodes/" + NODE + "/network/vmbr0", new ProxmoxApiException(500, "internal error"));
        try {
            client.getNodeNetworkAddress(NODE, "vmbr0");
            fail("expected ProxmoxApiException");
        } catch (ProxmoxApiException e) {
            assertEquals(500, e.getStatusCode());
        }
    }

    @Test
    public void testGetNodeNetworkAddressReturnsAddress() {
        client.onGet("/nodes/" + NODE + "/network/vmbr0",
                "{\"iface\":\"vmbr0\",\"address\":\"10.1.1.11\"}");
        assertEquals("10.1.1.11", client.getNodeNetworkAddress(NODE, "vmbr0"));
    }
}
