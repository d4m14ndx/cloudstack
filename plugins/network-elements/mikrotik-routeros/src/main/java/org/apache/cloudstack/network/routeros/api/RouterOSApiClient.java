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

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.network.routeros.rules.RouterOSRule;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Minimal typed client for the RouterOS v7 REST API (https://host/rest).
 *
 * Every object written by the CloudStack plugin carries a comment starting
 * with {@code cs-}; the comment is the source of truth for idempotency:
 * <ul>
 *   <li>apply = {@link #ensureRules(String, List)}: ensure the desired object set
 *       tagged with a comment is present (replacing a stale set),</li>
 *   <li>revoke = {@link #removeByComment(String, String...)}: remove every object
 *       carrying the comment.</li>
 * </ul>
 */
public class RouterOSApiClient {

    protected static final Logger LOGGER = LogManager.getLogger(RouterOSApiClient.class);

    public static final String ID_FIELD = ".id";
    public static final String COMMENT_FIELD = "comment";

    public static final String PATH_IP_ADDRESS = "ip/address";
    public static final String PATH_FIREWALL_FILTER = "ip/firewall/filter";
    public static final String PATH_FIREWALL_NAT = "ip/firewall/nat";
    public static final String PATH_FIREWALL_MANGLE = "ip/firewall/mangle";
    public static final String PATH_ROUTE = "ip/route";
    public static final String PATH_DHCP_SERVER = "ip/dhcp-server";
    public static final String PATH_DHCP_NETWORK = "ip/dhcp-server/network";
    public static final String PATH_DHCP_LEASE = "ip/dhcp-server/lease";
    public static final String PATH_INTERFACE = "interface";
    public static final String PATH_SYSTEM_RESOURCE = "system/resource";
    public static final String PATH_SYSTEM_IDENTITY = "system/identity";
    public static final String PATH_USER = "user";

    /** Paths that may hold comment-tagged rules written by the plugin. */
    public static final String[] RULE_PATHS = {PATH_FIREWALL_FILTER, PATH_FIREWALL_NAT, PATH_FIREWALL_MANGLE, PATH_IP_ADDRESS, PATH_ROUTE, PATH_DHCP_LEASE};

    private final String baseUrl;
    private final RouterOSHttpTransport transport;

    public RouterOSApiClient(final String baseUrl, final RouterOSHttpTransport transport) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.transport = transport;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    // ------------------------------------------------------------------
    // Generic REST verbs
    // ------------------------------------------------------------------

    public List<Map<String, String>> list(final String path, final Map<String, String> query) {
        final StringBuilder url = new StringBuilder(baseUrl).append('/').append(path);
        if (query != null && !query.isEmpty()) {
            url.append('?');
            boolean first = true;
            for (final Map.Entry<String, String> entry : query.entrySet()) {
                if (!first) {
                    url.append('&');
                }
                url.append(urlEncode(entry.getKey())).append('=').append(urlEncode(entry.getValue()));
                first = false;
            }
        }
        final String body = execute("GET", url.toString(), null);
        return parseObjectList(body);
    }

    public Map<String, String> get(final String path) {
        final String body = execute("GET", baseUrl + '/' + path, null);
        return parseObject(body);
    }

    public Map<String, String> add(final String path, final Map<String, String> params) {
        final String body = execute("PUT", baseUrl + '/' + path, toJson(params));
        return parseObject(body);
    }

    public void update(final String path, final String id, final Map<String, String> params) {
        execute("PATCH", baseUrl + '/' + path + '/' + urlEncode(id), toJson(params));
    }

    public void remove(final String path, final String id) {
        execute("DELETE", baseUrl + '/' + path + '/' + urlEncode(id), null);
    }

    // ------------------------------------------------------------------
    // Comment-tag idempotency primitives
    // ------------------------------------------------------------------

    public List<Map<String, String>> listByComment(final String path, final String comment) {
        return list(path, Collections.singletonMap(COMMENT_FIELD, comment));
    }

    /**
     * Ensure exactly the desired set of objects tagged with {@code comment}
     * exists. If the currently present set already matches, this is a no-op;
     * otherwise the stale set is removed and the desired set is (re)created in
     * order.
     *
     * @return true if any change was written to the device.
     */
    public boolean ensureRules(final String comment, final List<RouterOSRule> desired) {
        boolean changed = false;
        final Map<String, List<RouterOSRule>> desiredByPath = new LinkedHashMap<>();
        for (final RouterOSRule rule : desired) {
            desiredByPath.computeIfAbsent(rule.getPath(), k -> new ArrayList<>()).add(rule);
        }
        for (final Map.Entry<String, List<RouterOSRule>> entry : desiredByPath.entrySet()) {
            final String path = entry.getKey();
            final List<RouterOSRule> rules = entry.getValue();
            final List<Map<String, String>> existing = listByComment(path, comment);
            if (matches(existing, rules)) {
                LOGGER.debug("RouterOS objects tagged [{}] on {} already up to date ({} object(s))", comment, path, rules.size());
                continue;
            }
            for (final Map<String, String> current : existing) {
                remove(path, current.get(ID_FIELD));
            }
            for (final RouterOSRule rule : rules) {
                final Map<String, String> orphan = findAdoptableMatch(path, rule);
                if (orphan != null) {
                    LOGGER.debug("Adopting pre-existing untagged RouterOS object {} on {} as [{}]", orphan.get(ID_FIELD), path, comment);
                    update(path, orphan.get(ID_FIELD), Collections.singletonMap(COMMENT_FIELD, comment));
                } else {
                    add(path, rule.getParams());
                }
            }
            changed = true;
        }
        return changed;
    }

    /**
     * An object created outside the plugin (console or guest-agent bootstrap) that is
     * field-for-field what we are about to create must be adopted rather than re-added:
     * RouterOS rejects duplicates of unique objects such as /ip/address entries.
     * Dynamic entries cannot be modified and are never adopted.
     */
    protected Map<String, String> findAdoptableMatch(final String path, final RouterOSRule rule) {
        for (final Map<String, String> candidate : list(path, null)) {
            final String candidateComment = candidate.get(COMMENT_FIELD);
            if (candidateComment != null && !candidateComment.isEmpty()) {
                continue;
            }
            if ("true".equals(candidate.get("dynamic"))) {
                continue;
            }
            boolean same = true;
            for (final Map.Entry<String, String> param : rule.getParams().entrySet()) {
                if (RouterOSRule.PLACE_BEFORE.equals(param.getKey()) || COMMENT_FIELD.equals(param.getKey())) {
                    continue;
                }
                if (!param.getValue().equals(candidate.get(param.getKey()))) {
                    same = false;
                    break;
                }
            }
            if (same) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Remove every object carrying {@code comment} from the given paths (all
     * known rule paths when none are given).
     *
     * @return the number of objects removed.
     */
    public int removeByComment(final String comment, final String... paths) {
        final String[] searchPaths = paths == null || paths.length == 0 ? RULE_PATHS : paths;
        int removed = 0;
        for (final String path : searchPaths) {
            for (final Map<String, String> item : listByComment(path, comment)) {
                remove(path, item.get(ID_FIELD));
                removed++;
            }
        }
        return removed;
    }

    /**
     * Remove every object whose comment starts with {@code commentPrefix} from
     * the given path. RouterOS query parameters are exact-match, so this lists
     * the collection and filters client side.
     *
     * @return the number of objects removed.
     */
    public int removeByCommentPrefix(final String path, final String commentPrefix) {
        int removed = 0;
        for (final Map<String, String> item : list(path, null)) {
            final String comment = item.get(COMMENT_FIELD);
            if (comment != null && comment.startsWith(commentPrefix)) {
                remove(path, item.get(ID_FIELD));
                removed++;
            }
        }
        return removed;
    }

    protected boolean matches(final List<Map<String, String>> existing, final List<RouterOSRule> desired) {
        if (existing.size() != desired.size()) {
            return false;
        }
        for (int i = 0; i < desired.size(); i++) {
            final Map<String, String> want = desired.get(i).getParams();
            final Map<String, String> have = existing.get(i);
            for (final Map.Entry<String, String> param : want.entrySet()) {
                if (RouterOSRule.PLACE_BEFORE.equals(param.getKey())) {
                    continue; // positional hint, not persisted verbatim
                }
                if (!param.getValue().equals(have.get(param.getKey()))) {
                    return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Typed helpers
    // ------------------------------------------------------------------

    public void addIpAddress(final String iface, final String cidrAddress, final String comment) {
        final Map<String, String> params = new LinkedHashMap<>();
        params.put("address", cidrAddress);
        params.put("interface", iface);
        params.put(COMMENT_FIELD, comment);
        ensureRules(comment, Collections.singletonList(new RouterOSRule(PATH_IP_ADDRESS, params)));
    }

    public int removeIpAddressesByComment(final String comment) {
        return removeByComment(comment, PATH_IP_ADDRESS);
    }

    public Map<String, String> addNatRule(final Map<String, String> params) {
        return add(PATH_FIREWALL_NAT, params);
    }

    public int removeNatRulesByComment(final String comment) {
        return removeByComment(comment, PATH_FIREWALL_NAT);
    }

    public Map<String, String> addFilterRule(final Map<String, String> params) {
        return add(PATH_FIREWALL_FILTER, params);
    }

    public int removeFilterRulesByComment(final String comment) {
        return removeByComment(comment, PATH_FIREWALL_FILTER);
    }

    /**
     * Configure a DHCP server instance for a guest interface. The server hands
     * out only static leases (address-pool=static-only): CloudStack owns IP
     * allocation, the plugin registers a lease per NIC.
     */
    public void setDhcpServer(final String serverName, final String iface, final String networkCidr, final String gateway, final String dns, final String comment) {
        final Map<String, String> serverParams = new LinkedHashMap<>();
        serverParams.put("name", serverName);
        serverParams.put("interface", iface);
        serverParams.put("address-pool", "static-only");
        serverParams.put("lease-time", "1d");
        serverParams.put(COMMENT_FIELD, comment);
        ensureRules(comment, Collections.singletonList(new RouterOSRule(PATH_DHCP_SERVER, serverParams)));

        final Map<String, String> networkParams = new LinkedHashMap<>();
        networkParams.put("address", networkCidr);
        networkParams.put("gateway", gateway);
        if (dns != null && !dns.isEmpty()) {
            networkParams.put("dns-server", dns);
        }
        networkParams.put(COMMENT_FIELD, comment);
        final List<Map<String, String>> existing = listByComment(PATH_DHCP_NETWORK, comment);
        if (!matches(existing, Collections.singletonList(new RouterOSRule(PATH_DHCP_NETWORK, networkParams)))) {
            for (final Map<String, String> current : existing) {
                remove(PATH_DHCP_NETWORK, current.get(ID_FIELD));
            }
            add(PATH_DHCP_NETWORK, networkParams);
        }
    }

    public int removeDhcpServerByComment(final String comment) {
        return removeByComment(comment, PATH_DHCP_LEASE, PATH_DHCP_NETWORK, PATH_DHCP_SERVER);
    }

    public void addDhcpLease(final String serverName, final String address, final String macAddress, final String comment) {
        final Map<String, String> params = new LinkedHashMap<>();
        params.put("address", address);
        params.put("mac-address", macAddress);
        params.put("server", serverName);
        params.put(COMMENT_FIELD, comment);
        ensureRules(comment, Collections.singletonList(new RouterOSRule(PATH_DHCP_LEASE, params)));
    }

    public int removeDhcpLeasesByComment(final String comment) {
        return removeByComment(comment, PATH_DHCP_LEASE);
    }

    public void addStaticRoute(final String dstCidr, final String gateway, final String comment) {
        final Map<String, String> params = new LinkedHashMap<>();
        params.put("dst-address", dstCidr);
        params.put("gateway", gateway);
        params.put(COMMENT_FIELD, comment);
        ensureRules(comment, Collections.singletonList(new RouterOSRule(PATH_ROUTE, params)));
    }

    public int removeStaticRoutesByComment(final String comment) {
        return removeByComment(comment, PATH_ROUTE);
    }

    public List<Map<String, String>> listInterfaces() {
        return list(PATH_INTERFACE, null);
    }

    /**
     * @return the /system/resource document; also serves as the health check.
     */
    public Map<String, String> systemResource() {
        return get(PATH_SYSTEM_RESOURCE);
    }

    public boolean isReachable() {
        try {
            final Map<String, String> resource = systemResource();
            return resource != null && resource.containsKey("version");
        } catch (final RuntimeException e) {
            // RouterOSApiException on transport/HTTP errors, but also
            // JsonParseException if a device answers 2xx with a malformed body;
            // either way it is simply "not reachable / not ready".
            LOGGER.debug("RouterOS API at {} not reachable: {}", baseUrl, e.getMessage());
            return false;
        }
    }

    public void setIdentity(final String name) {
        execute("POST", baseUrl + '/' + PATH_SYSTEM_IDENTITY + "/set", toJson(Collections.singletonMap("name", name)));
    }

    public void setUserPassword(final String user, final String newPassword) {
        final List<Map<String, String>> users = list(PATH_USER, Collections.singletonMap("name", user));
        if (users.isEmpty()) {
            throw new RouterOSApiException("RouterOS user '" + user + "' not found on " + baseUrl);
        }
        update(PATH_USER, users.get(0).get(ID_FIELD), Collections.singletonMap("password", newPassword));
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    protected String execute(final String method, final String url, final String body) {
        try {
            final RouterOSHttpTransport.Response response = transport.execute(new RouterOSHttpTransport.Request(method, url, body));
            if (response.getStatus() >= 400) {
                throw new RouterOSApiException(response.getStatus(),
                        String.format("RouterOS API call %s %s failed with status %d: %s", method, url, response.getStatus(), response.getBody()));
            }
            return response.getBody();
        } catch (final IOException e) {
            throw new RouterOSApiException(String.format("RouterOS API call %s %s failed: %s", method, url, e.getMessage()), e);
        }
    }

    protected static String toJson(final Map<String, String> params) {
        final JsonObject json = new JsonObject();
        for (final Map.Entry<String, String> entry : params.entrySet()) {
            json.addProperty(entry.getKey(), entry.getValue());
        }
        return json.toString();
    }

    protected static List<Map<String, String>> parseObjectList(final String body) {
        final List<Map<String, String>> result = new ArrayList<>();
        if (body == null || body.trim().isEmpty()) {
            return result;
        }
        final JsonElement root = parseJson(body);
        if (root.isJsonArray()) {
            final JsonArray array = root.getAsJsonArray();
            for (final JsonElement element : array) {
                if (element.isJsonObject()) {
                    result.add(toStringMap(element.getAsJsonObject()));
                }
            }
        } else if (root.isJsonObject()) {
            result.add(toStringMap(root.getAsJsonObject()));
        }
        return result;
    }

    protected static Map<String, String> parseObject(final String body) {
        if (body == null || body.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        final JsonElement root = parseJson(body);
        if (root.isJsonObject()) {
            return toStringMap(root.getAsJsonObject());
        }
        return root.isJsonArray() && root.getAsJsonArray().size() > 0 && root.getAsJsonArray().get(0).isJsonObject()
                ? toStringMap(root.getAsJsonArray().get(0).getAsJsonObject())
                : Collections.emptyMap();
    }

    /**
     * Parse a RouterOS REST body, wrapping malformed JSON in a
     * {@link RouterOSApiException} so it does not escape as a raw
     * {@link JsonParseException} from callers that only guard against
     * RouterOSApiException.
     */
    private static JsonElement parseJson(final String body) {
        try {
            return JsonParser.parseString(body);
        } catch (final JsonParseException e) {
            throw new RouterOSApiException("RouterOS API returned a malformed JSON body: " + e.getMessage(), e);
        }
    }

    private static Map<String, String> toStringMap(final JsonObject object) {
        final Map<String, String> map = new LinkedHashMap<>();
        for (final Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (entry.getValue().isJsonPrimitive()) {
                map.put(entry.getKey(), entry.getValue().getAsString());
            } else {
                map.put(entry.getKey(), entry.getValue().toString());
            }
        }
        return map;
    }

    private static String urlEncode(final String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (final UnsupportedEncodingException e) {
            throw new RouterOSApiException("Failed to URL-encode " + value, e);
        }
    }
}
