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

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLContext;

import org.apache.http.HttpHeaders;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.TrustAllStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Thin, thread-safe client for the Proxmox VE REST API (https://host:port/api2/json).
 *
 * Authentication is either via an API token (Authorization: PVEAPIToken=&lt;id&gt;=&lt;secret&gt;
 * header, preferred) or via ticket authentication (POST /access/ticket, PVEAuthCookie cookie plus
 * CSRFPreventionToken header on write verbs). Tickets are proactively refreshed when older than
 * 90 minutes and a request that fails with 401 is retried exactly once after re-authentication.
 *
 * All request bodies are form-encoded (application/x-www-form-urlencoded, UTF-8); boolean
 * parameters are encoded as "1"/"0" and null-valued parameters are skipped. Responses are JSON;
 * the PVE payload is unwrapped from the {"data": ...} envelope. Non-2xx responses raise
 * {@link ProxmoxApiException} carrying the HTTP status code and the PVE error body.
 *
 * Instances are safe for concurrent use from multiple management server threads: the underlying
 * HttpClient is thread-safe and ticket refresh is synchronized.
 */
public class ProxmoxApiClient {

    protected Logger logger = LogManager.getLogger(getClass());

    private static final long TASK_POLL_INTERVAL_MS = 2000L;
    private static final int TASK_LOG_TAIL_LINES = 10;
    private static final long TICKET_MAX_AGE_MS = 90L * 60L * 1000L;
    private static final long TICKET_FORCED_RELOGIN_GRACE_MS = 5000L;
    private static final long SHUTDOWN_TASK_GRACE_MS = 10000L;
    private static final long CONFIG_UPDATE_TASK_TIMEOUT_MS = 300000L;
    private static final String UPID_PREFIX = "UPID:";

    private final String host;
    private final String baseUrl;
    private final String username;
    private final String password;
    private final String tokenId;
    private final String tokenSecret;
    private final CloseableHttpClient httpClient;

    private final Object authLock = new Object();
    private volatile String ticket;
    private volatile String csrfToken;
    private volatile long ticketCreatedMs;

    public ProxmoxApiClient(String host, int port, String username, String password,
                            String tokenId, String tokenSecret, boolean verifyTls,
                            int connectTimeoutSec, int readTimeoutSec) {
        this.host = host;
        this.baseUrl = "https://" + host + ":" + port + "/api2/json";
        this.username = normalizeUsername(username);
        this.password = password;
        this.tokenId = tokenId;
        this.tokenSecret = tokenSecret;

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(connectTimeoutSec * 1000)
                .setConnectionRequestTimeout(connectTimeoutSec * 1000)
                .setSocketTimeout(readTimeoutSec * 1000)
                .build();
        HttpClientBuilder builder = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .setMaxConnPerRoute(16)
                .setMaxConnTotal(32);
        if (!verifyTls) {
            try {
                SSLContext sslContext = SSLContexts.custom().loadTrustMaterial(null, TrustAllStrategy.INSTANCE).build();
                builder.setSSLContext(sslContext);
                builder.setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE);
            } catch (GeneralSecurityException e) {
                throw new CloudRuntimeException("Failed to initialize trust-all SSL context for Proxmox API client for host " + host, e);
            }
        }
        this.httpClient = builder.build();
        logger.debug("Created Proxmox API client for {} using {} authentication", baseUrl, tokenId != null ? "API token" : "ticket");
    }

    public void close() {
        try {
            httpClient.close();
        } catch (IOException e) {
            logger.warn("Failed to close HTTP client for Proxmox API at {}: {}", host, e.getMessage());
        }
    }

    public JsonElement get(String path) {
        return api("GET", path, null);
    }

    public JsonElement post(String path, Map<String, Object> params) {
        return api("POST", path, params);
    }

    public JsonElement put(String path, Map<String, Object> params) {
        return api("PUT", path, params);
    }

    public JsonElement delete(String path) {
        return api("DELETE", path, null);
    }

    /**
     * POSTs to a task-spawning endpoint and returns the UPID of the spawned task.
     */
    public String postTask(String path, Map<String, Object> params) {
        JsonElement data = post(path, params);
        String upid = extractUpid(data);
        if (upid == null) {
            throw new ProxmoxApiException(0, "POST " + path + " on " + host + " did not return a task UPID (got: " + data + ")");
        }
        return upid;
    }

    /**
     * Polls the status of a PVE task every 2 seconds until it finishes. Throws
     * {@link ProxmoxApiException} if the task exit status is not "OK" (including the tail of the
     * task log) or if the task does not finish within timeoutMs.
     */
    public void waitForTask(String node, String upid, long timeoutMs) {
        String encodedUpid = urlEncode(upid);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            JsonObject status = get("/nodes/" + node + "/tasks/" + encodedUpid + "/status").getAsJsonObject();
            String state = stringMember(status, "status");
            if (!"running".equals(state)) {
                String exitStatus = stringMember(status, "exitstatus");
                if (!"OK".equals(exitStatus)) {
                    throw new ProxmoxApiException(0, "Proxmox task " + upid + " on node " + node + " failed with exit status [" + exitStatus +
                            "]. Task log tail:\n" + getTaskLogTail(node, encodedUpid));
                }
                return;
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new ProxmoxApiException(0, "Timed out after " + timeoutMs + " ms waiting for Proxmox task " + upid + " on node " + node);
            }
            try {
                Thread.sleep(TASK_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProxmoxApiException(0, "Interrupted while waiting for Proxmox task " + upid + " on node " + node, e);
            }
        }
    }

    public String getVersion() {
        return stringMember(get("/version").getAsJsonObject(), "version");
    }

    public List<String> getNodeNames() {
        List<String> names = new ArrayList<String>();
        JsonArray nodes = get("/nodes").getAsJsonArray();
        for (JsonElement element : nodes) {
            String name = stringMember(element.getAsJsonObject(), "node");
            if (name != null) {
                names.add(name);
            }
        }
        return names;
    }

    public JsonObject getNodeStatus(String node) {
        return get("/nodes/" + node + "/status").getAsJsonObject();
    }

    public JsonArray getClusterResources(String type) {
        String path = "/cluster/resources" + (type != null ? "?type=" + type : "");
        return get(path).getAsJsonArray();
    }

    /**
     * Lists the qemu VMs of a single node with their live status. Unlike
     * {@link #getClusterResources}, which serves pvestatd-cached data that can lag
     * reality by ~10 seconds, this endpoint checks the QEMU pidfiles on the node.
     */
    public JsonArray listNodeVms(String node) {
        return get("/nodes/" + node + "/qemu").getAsJsonArray();
    }

    /**
     * Returns the corosync cluster name, or the node name when this is a standalone node.
     */
    public String getClusterName() {
        String nodeName = null;
        JsonArray items = get("/cluster/status").getAsJsonArray();
        for (JsonElement element : items) {
            JsonObject item = element.getAsJsonObject();
            String type = stringMember(item, "type");
            if ("cluster".equals(type)) {
                return stringMember(item, "name");
            }
            if ("node".equals(type) && nodeName == null) {
                nodeName = stringMember(item, "name");
            }
        }
        return nodeName;
    }

    public String findNodeOfVm(int vmid) {
        JsonArray vms = getClusterResources("vm");
        for (JsonElement element : vms) {
            JsonObject vm = element.getAsJsonObject();
            if (vm.has("vmid") && vm.get("vmid").getAsInt() == vmid) {
                return stringMember(vm, "node");
            }
        }
        return null;
    }

    public Integer findVmidByName(String name) {
        JsonArray vms = getClusterResources("vm");
        for (JsonElement element : vms) {
            JsonObject vm = element.getAsJsonObject();
            if (name != null && name.equals(stringMember(vm, "name")) && vm.has("vmid")) {
                return Integer.valueOf(vm.get("vmid").getAsInt());
            }
        }
        return null;
    }

    public JsonObject getVmStatus(String node, int vmid) {
        return get("/nodes/" + node + "/qemu/" + vmid + "/status/current").getAsJsonObject();
    }

    public JsonObject getVmConfig(String node, int vmid) {
        return get("/nodes/" + node + "/qemu/" + vmid + "/config?current=1").getAsJsonObject();
    }

    public void createVm(String node, int vmid, Map<String, Object> config, long timeoutMs) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("vmid", vmid);
        if (config != null) {
            params.putAll(config);
        }
        String upid = postTask("/nodes/" + node + "/qemu", params);
        waitForTask(node, upid, timeoutMs);
    }

    /**
     * Applies configuration changes to a VM with synchronous semantics: when PVE spawns a
     * background task for the update (POST .../config is the asynchronous API variant), the
     * task is awaited before returning.
     */
    public void setVmConfig(String node, int vmid, Map<String, Object> config) {
        JsonElement data = post("/nodes/" + node + "/qemu/" + vmid + "/config", config);
        String upid = extractUpid(data);
        if (upid != null) {
            waitForTask(node, upid, CONFIG_UPDATE_TASK_TIMEOUT_MS);
        }
    }

    public void startVm(String node, int vmid, long timeoutMs) {
        String upid = postTask("/nodes/" + node + "/qemu/" + vmid + "/status/start", null);
        waitForTask(node, upid, timeoutMs);
    }

    public void stopVm(String node, int vmid, long timeoutMs) {
        String upid = postTask("/nodes/" + node + "/qemu/" + vmid + "/status/stop", null);
        waitForTask(node, upid, timeoutMs);
    }

    /**
     * Requests a graceful guest shutdown (no forced stop). Returns false instead of throwing when
     * the shutdown task fails or does not finish in time.
     */
    public boolean shutdownVm(String node, int vmid, long timeoutMs) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("timeout", timeoutMs / 1000);
        params.put("forceStop", false);
        try {
            String upid = postTask("/nodes/" + node + "/qemu/" + vmid + "/status/shutdown", params);
            waitForTask(node, upid, timeoutMs + SHUTDOWN_TASK_GRACE_MS);
            return true;
        } catch (ProxmoxApiException e) {
            logger.warn("Graceful shutdown of vmid {} on node {} did not complete: {}", vmid, node, e.getMessage());
            return false;
        }
    }

    public void rebootVm(String node, int vmid, long timeoutMs) {
        String upid = postTask("/nodes/" + node + "/qemu/" + vmid + "/status/reboot", null);
        waitForTask(node, upid, timeoutMs);
    }

    public void resetVm(String node, int vmid, long timeoutMs) {
        String upid = postTask("/nodes/" + node + "/qemu/" + vmid + "/status/reset", null);
        waitForTask(node, upid, timeoutMs);
    }

    public void destroyVm(String node, int vmid, long timeoutMs) {
        runDeleteTask(node, "/nodes/" + node + "/qemu/" + vmid + "?purge=1&destroy-unreferenced-disks=0", timeoutMs);
    }

    public void migrateVm(String node, int vmid, String targetNode, boolean online,
                          boolean withLocalDisks, long timeoutMs) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("target", targetNode);
        params.put("online", online);
        params.put("with-local-disks", withLocalDisks);
        String upid = postTask("/nodes/" + node + "/qemu/" + vmid + "/migrate", params);
        waitForTask(node, upid, timeoutMs);
    }

    /**
     * Resizes a VM disk. When absolute is false the size is a delta ("+&lt;bytes&gt;"), otherwise
     * the new absolute size in bytes.
     */
    public void resizeDisk(String node, int vmid, String disk, long deltaOrAbsoluteBytes, boolean absolute) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("disk", disk);
        params.put("size", absolute ? String.valueOf(deltaOrAbsoluteBytes) : "+" + deltaOrAbsoluteBytes);
        put("/nodes/" + node + "/qemu/" + vmid + "/resize", params);
    }

    public void createSnapshot(String node, int vmid, String snapName, String description,
                               boolean withVmState, long timeoutMs) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("snapname", snapName);
        params.put("description", description);
        params.put("vmstate", withVmState);
        String upid = postTask("/nodes/" + node + "/qemu/" + vmid + "/snapshot", params);
        waitForTask(node, upid, timeoutMs);
    }

    public void deleteSnapshot(String node, int vmid, String snapName, long timeoutMs) {
        runDeleteTask(node, "/nodes/" + node + "/qemu/" + vmid + "/snapshot/" + snapName, timeoutMs);
    }

    public void rollbackSnapshot(String node, int vmid, String snapName, long timeoutMs) {
        String upid = postTask("/nodes/" + node + "/qemu/" + vmid + "/snapshot/" + snapName + "/rollback", null);
        waitForTask(node, upid, timeoutMs);
    }

    public JsonArray listSnapshots(String node, int vmid) {
        return get("/nodes/" + node + "/qemu/" + vmid + "/snapshot").getAsJsonArray();
    }

    public JsonArray listStorage(String node) {
        return get("/nodes/" + node + "/storage").getAsJsonArray();
    }

    public JsonObject getStorageStatus(String node, String storage) {
        return get("/nodes/" + node + "/storage/" + storage + "/status").getAsJsonObject();
    }

    public JsonArray listStorageContent(String node, String storage, String content) {
        String path = "/nodes/" + node + "/storage/" + storage + "/content" + (content != null ? "?content=" + content : "");
        return get(path).getAsJsonArray();
    }

    /**
     * Allocates a disk image on a storage. The size is sent as a bare kibibyte value (rounded
     * up) — PVE's API treats unsuffixed sizes as KiB and rejects a "K" suffix. Returns the
     * volid of the new volume.
     */
    public String allocDiskImage(String node, String storage, int ownerVmid,
                                 String filename, long sizeBytes, String format) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("vmid", ownerVmid);
        params.put("filename", filename);
        // PVE's content-alloc 'size' is denominated in KiB and only accepts M/G suffixes
        // (a "K" suffix fails the API's regex validation) — send a bare KiB value.
        params.put("size", String.valueOf((sizeBytes + 1023) / 1024));
        params.put("format", format);
        JsonElement data = post("/nodes/" + node + "/storage/" + storage + "/content", params);
        if (data == null || data.isJsonNull()) {
            throw new ProxmoxApiException(0, "Disk image allocation on storage " + storage + " of node " + node + " did not return a volid");
        }
        return data.getAsString();
    }

    public void freeVolume(String node, String storage, String volid, long timeoutMs) {
        runDeleteTask(node, "/nodes/" + node + "/storage/" + storage + "/content/" + volid, timeoutMs);
    }

    /**
     * Returns the filesystem path of a volume, or null when the storage type does not expose one.
     */
    public String getVolumePath(String node, String volid) {
        JsonElement data = get("/nodes/" + node + "/storage/" + storageOf(volid) + "/content/" + volid);
        if (data != null && data.isJsonObject()) {
            return stringMember(data.getAsJsonObject(), "path");
        }
        return null;
    }

    /**
     * Moves a disk to another owner VM (targetVmid != null, keeps the same disk slot) or to
     * another storage (targetStorage != null, converts to qcow2).
     */
    public void moveDisk(String node, int vmid, String disk, Integer targetVmid,
                         String targetStorage, boolean deleteSource, long timeoutMs) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("disk", disk);
        if (targetVmid != null) {
            params.put("target-vmid", targetVmid);
            params.put("target-disk", disk);
        }
        if (targetStorage != null) {
            params.put("storage", targetStorage);
            params.put("format", "qcow2");
        }
        params.put("delete", deleteSource);
        String upid = postTask("/nodes/" + node + "/qemu/" + vmid + "/move_disk", params);
        waitForTask(node, upid, timeoutMs);
    }

    public void unlinkDisk(String node, int vmid, String idList, boolean force) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("idlist", idList);
        params.put("force", force);
        put("/nodes/" + node + "/qemu/" + vmid + "/unlink", params);
    }

    /**
     * Creates a VNC proxy ticket for a VM. Returns the PVE response object
     * ({ticket, port, user, ...}).
     */
    public JsonObject vncProxy(String node, int vmid) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("websocket", false);
        params.put("generate-password", false);
        return post("/nodes/" + node + "/qemu/" + vmid + "/vncproxy", params).getAsJsonObject();
    }

    public String monitorCommand(String node, int vmid, String command) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("command", command);
        return post("/nodes/" + node + "/qemu/" + vmid + "/monitor", params).getAsString();
    }

    public int getNextVmid() {
        return Integer.parseInt(get("/cluster/nextid").getAsString());
    }

    /**
     * Returns whether the cluster is quorate. A standalone node (no cluster entry in
     * /cluster/status) is always considered quorate.
     */
    public boolean isQuorate() {
        JsonArray items = get("/cluster/status").getAsJsonArray();
        for (JsonElement element : items) {
            JsonObject item = element.getAsJsonObject();
            if ("cluster".equals(stringMember(item, "type"))) {
                return item.has("quorate") && !item.get("quorate").isJsonNull() && item.get("quorate").getAsInt() == 1;
            }
        }
        return true;
    }

    /**
     * Returns the configured address of a node network interface, or null when the interface does
     * not exist or has no address.
     */
    public String getNodeNetworkAddress(String node, String iface) {
        JsonElement data;
        try {
            data = get("/nodes/" + node + "/network/" + iface);
        } catch (ProxmoxApiException e) {
            if (e.getStatusCode() == HttpStatus.SC_NOT_FOUND) {
                return null;
            }
            throw e;
        }
        if (data != null && data.isJsonObject()) {
            return stringMember(data.getAsJsonObject(), "address");
        }
        return null;
    }

    private JsonElement api(String method, String path, Map<String, Object> params) {
        if (tokenId == null) {
            ensureTicket(false);
        }
        HttpResult result = execute(method, path, params, true);
        if (result.statusCode == HttpStatus.SC_UNAUTHORIZED && tokenId == null) {
            logger.debug("Received 401 from Proxmox API at {} for {} {}, re-authenticating and retrying once", host, method, path);
            ensureTicket(true);
            result = execute(method, path, params, true);
        }
        return unwrapData(result, method, path);
    }

    private HttpResult execute(String method, String path, Map<String, Object> params, boolean authenticate) {
        HttpRequestBase request = buildRequest(method, path, params, authenticate);
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            HttpResult result = new HttpResult();
            result.statusCode = response.getStatusLine().getStatusCode();
            result.reasonPhrase = response.getStatusLine().getReasonPhrase();
            result.body = response.getEntity() != null ? EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8) : "";
            return result;
        } catch (IOException e) {
            throw new ProxmoxApiException(0, "Proxmox API " + method + " " + path + " on " + host + " failed: " + e.getMessage(), e);
        } finally {
            request.releaseConnection();
        }
    }

    private HttpRequestBase buildRequest(String method, String path, Map<String, Object> params, boolean authenticate) {
        String url = baseUrl + path;
        HttpRequestBase request;
        if ("GET".equals(method)) {
            request = new HttpGet(url);
        } else if ("DELETE".equals(method)) {
            request = new HttpDelete(url);
        } else if ("POST".equals(method)) {
            HttpPost post = new HttpPost(url);
            post.setEntity(new UrlEncodedFormEntity(toFormParams(params), StandardCharsets.UTF_8));
            request = post;
        } else if ("PUT".equals(method)) {
            HttpPut put = new HttpPut(url);
            put.setEntity(new UrlEncodedFormEntity(toFormParams(params), StandardCharsets.UTF_8));
            request = put;
        } else {
            throw new CloudRuntimeException("Unsupported HTTP method for Proxmox API: " + method);
        }
        if (authenticate) {
            if (tokenId != null) {
                request.addHeader(HttpHeaders.AUTHORIZATION, "PVEAPIToken=" + tokenId + "=" + tokenSecret);
            } else {
                request.addHeader("Cookie", "PVEAuthCookie=" + ticket);
                if (!"GET".equals(method)) {
                    request.addHeader("CSRFPreventionToken", csrfToken);
                }
            }
        }
        return request;
    }

    private static List<NameValuePair> toFormParams(Map<String, Object> params) {
        List<NameValuePair> pairs = new ArrayList<NameValuePair>();
        if (params == null) {
            return pairs;
        }
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            String encoded;
            if (value instanceof Boolean) {
                encoded = ((Boolean)value).booleanValue() ? "1" : "0";
            } else {
                encoded = String.valueOf(value);
            }
            pairs.add(new BasicNameValuePair(entry.getKey(), encoded));
        }
        return pairs;
    }

    private JsonElement unwrapData(HttpResult result, String method, String path) {
        if (result.statusCode < 200 || result.statusCode > 299) {
            throw new ProxmoxApiException(result.statusCode, "Proxmox API " + method + " " + path + " on " + host + " returned HTTP " +
                    result.statusCode + " (" + result.reasonPhrase + "): " + result.body);
        }
        if (result.body == null || result.body.isEmpty()) {
            return JsonNull.INSTANCE;
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(result.body);
        } catch (JsonParseException e) {
            throw new ProxmoxApiException(result.statusCode, "Failed to parse Proxmox API response for " + method + " " + path + " on " + host, e);
        }
        if (root.isJsonObject() && root.getAsJsonObject().has("data")) {
            return root.getAsJsonObject().get("data");
        }
        return JsonNull.INSTANCE;
    }

    /**
     * Ensures a valid authentication ticket, logging in when there is none yet, the current one is
     * older than 90 minutes, or force is set (after a 401). A forced refresh is skipped when
     * another thread re-authenticated moments ago.
     */
    private void ensureTicket(boolean force) {
        synchronized (authLock) {
            long age = System.currentTimeMillis() - ticketCreatedMs;
            if (ticket != null) {
                if (!force && age < TICKET_MAX_AGE_MS) {
                    return;
                }
                if (force && age < TICKET_FORCED_RELOGIN_GRACE_MS) {
                    return;
                }
            }
            login();
        }
    }

    private void login() {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("username", username);
        params.put("password", password);
        HttpResult result = execute("POST", "/access/ticket", params, false);
        JsonElement data = unwrapData(result, "POST", "/access/ticket");
        if (data == null || !data.isJsonObject()) {
            throw new ProxmoxApiException(result.statusCode, "Unexpected response from Proxmox ticket login on " + host);
        }
        JsonObject obj = data.getAsJsonObject();
        ticket = stringMember(obj, "ticket");
        csrfToken = stringMember(obj, "CSRFPreventionToken");
        ticketCreatedMs = System.currentTimeMillis();
        logger.debug("Authenticated to Proxmox API at {} as {}", host, username);
    }

    private void runDeleteTask(String node, String path, long timeoutMs) {
        JsonElement data = delete(path);
        String upid = extractUpid(data);
        if (upid != null) {
            waitForTask(node, upid, timeoutMs);
        }
    }

    private static String extractUpid(JsonElement data) {
        if (data != null && data.isJsonPrimitive()) {
            String value = data.getAsString();
            if (value.startsWith(UPID_PREFIX)) {
                return value;
            }
        }
        return null;
    }

    private String getTaskLogTail(String node, String encodedUpid) {
        try {
            JsonArray lines = get("/nodes/" + node + "/tasks/" + encodedUpid + "/log?start=0&limit=1000").getAsJsonArray();
            StringBuilder tail = new StringBuilder();
            for (int i = Math.max(0, lines.size() - TASK_LOG_TAIL_LINES); i < lines.size(); i++) {
                String text = stringMember(lines.get(i).getAsJsonObject(), "t");
                if (text != null) {
                    if (tail.length() > 0) {
                        tail.append('\n');
                    }
                    tail.append(text);
                }
            }
            return tail.toString();
        } catch (RuntimeException e) {
            logger.debug("Could not fetch Proxmox task log on node {}: {}", node, e.getMessage());
            return "<task log unavailable>";
        }
    }

    private static String storageOf(String volid) {
        int idx = volid != null ? volid.indexOf(':') : -1;
        if (idx <= 0) {
            throw new ProxmoxApiException(0, "Invalid Proxmox volid, expected <storage>:<volume>: " + volid);
        }
        return volid.substring(0, idx);
    }

    private static String stringMember(JsonObject obj, String member) {
        if (obj != null && obj.has(member) && !obj.get(member).isJsonNull()) {
            return obj.get(member).getAsString();
        }
        return null;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String normalizeUsername(String username) {
        if (username != null && !username.contains("@")) {
            return username + "@pam";
        }
        return username;
    }

    private static class HttpResult {
        int statusCode;
        String reasonPhrase;
        String body;
    }
}
