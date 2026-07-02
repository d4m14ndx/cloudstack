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
package com.cloud.hypervisor.proxmox.discoverer;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.commons.lang3.StringUtils;

import com.cloud.agent.api.StartupCommand;
import com.cloud.agent.api.StartupRoutingCommand;
import com.cloud.alert.AlertManager;
import com.cloud.dc.ClusterDetailsDao;
import com.cloud.dc.ClusterVO;
import com.cloud.exception.DiscoveryException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.proxmox.api.ProxmoxApiClient;
import com.cloud.hypervisor.proxmox.api.ProxmoxApiException;
import com.cloud.hypervisor.proxmox.resource.ProxmoxResource;
import com.cloud.resource.Discoverer;
import com.cloud.resource.DiscovererBase;
import com.cloud.resource.ResourceStateAdapter;
import com.cloud.resource.ServerResource;
import com.cloud.resource.UnableDeleteHostException;
import com.cloud.utils.crypt.DBEncryptionUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Discovers Proxmox VE nodes for a CloudStack cluster. One {@link ProxmoxResource} is created
 * per PVE node (VMware direct-connect model). The URL points at any node of the PVE cluster
 * (https://&lt;node&gt;:8006); the discoverer enumerates all cluster nodes through the PVE API.
 *
 * Credentials: the API username may be given as "user" (realm @pam assumed), "user@realm", or
 * an API token id "user@realm!tokenname" with the token secret passed as the password. SSH
 * credentials for node shell access default to root with the same password and can be
 * overridden with URL query parameters, e.g. ?sshuser=root&amp;sshpassword=secret&amp;sshport=22.
 * Optional resource tunables (vmid.base, default.bridge, verify.tls, task.timeout.sec) may also
 * be passed as URL query parameters.
 */
public class ProxmoxServerDiscoverer extends DiscovererBase implements Discoverer, ResourceStateAdapter {

    private static final int DEFAULT_API_PORT = 8006;
    private static final String DEFAULT_SSH_USERNAME = "root";
    private static final String DEFAULT_SSH_PORT = "22";
    private static final String DEFAULT_REALM_SUFFIX = "@pam";
    private static final int CONNECT_TIMEOUT_SEC = 30;
    private static final int READ_TIMEOUT_SEC = 120;

    private static final String[] PASSTHROUGH_URL_PARAMS = {"vmid.base", "default.bridge", "verify.tls", "task.timeout.sec"};

    @Inject
    private ClusterDetailsDao _clusterDetailsDao;
    @Inject
    private AlertManager _alertMgr;

    public ProxmoxServerDiscoverer() {
        logger.info("ProxmoxServerDiscoverer is constructed");
    }

    @Override
    public Map<? extends ServerResource, Map<String, String>> find(long dcId, Long podId, Long clusterId, URI uri, String username, String password,
            List<String> hostTags) throws DiscoveryException {

        if (logger.isInfoEnabled()) {
            logger.info("Discover Proxmox host(s). dc: {}, pod: {}, cluster: {}, uri host: {}", dcId, podId, clusterId, uri.getHost());
        }

        if (podId == null) {
            logger.info("No pod is assigned, assuming that it is not for Proxmox and skipping to the next discoverer");
            return null;
        }

        if (clusterId == null) {
            logger.info("No cluster is assigned, assuming that it is not for Proxmox and skipping to the next discoverer");
            return null;
        }

        ClusterVO cluster = _clusterDao.findById(clusterId);
        if (cluster == null || cluster.getHypervisorType() != HypervisorType.Proxmox) {
            logger.info("Invalid cluster id or cluster is not for Proxmox hypervisors");
            return null;
        }

        if (StringUtils.isBlank(uri.getHost())) {
            throw new InvalidParameterValueException("A host must be specified in the URL when adding a Proxmox cluster, url: " + uri);
        }

        if (StringUtils.isBlank(username) || StringUtils.isBlank(password)) {
            throw new InvalidParameterValueException("Please provide the Proxmox VE API username and password to add this cluster to the zone");
        }

        Map<String, String> urlParams = parseUrlParameters(uri);

        // API credential semantics: user, user@realm, or API token user@realm!tokenname (with
        // the token secret passed as the password).
        String apiUsername = null;
        String apiPassword = null;
        String tokenId = null;
        String tokenSecret = null;
        if (username.indexOf('!') >= 0) {
            tokenId = normalizeTokenId(username);
            tokenSecret = password;
        } else {
            apiUsername = normalizeUsername(username);
            apiPassword = password;
        }

        String sshUsername = urlParams.getOrDefault("sshuser", DEFAULT_SSH_USERNAME);
        String sshPassword = urlParams.get("sshpassword");
        if (sshPassword == null && tokenId == null) {
            // by default assume the node root password equals the API password
            sshPassword = password;
        }
        String sshPort = urlParams.getOrDefault("sshport", DEFAULT_SSH_PORT);

        int apiPort = uri.getPort() > 0 ? uri.getPort() : DEFAULT_API_PORT;
        String apiHost = uri.getHost();

        ProxmoxApiClient client = null;
        try {
            client = new ProxmoxApiClient(apiHost, apiPort, apiUsername, apiPassword, tokenId, tokenSecret, false, CONNECT_TIMEOUT_SEC, READ_TIMEOUT_SEC);

            String version = client.getVersion();
            String clusterName = client.getClusterName();
            List<String> nodeNames = client.getNodeNames();
            logger.info("Connected to Proxmox VE {} cluster '{}' at {}:{}, {} node(s) found", version, clusterName, apiHost, apiPort,
                    nodeNames == null ? 0 : nodeNames.size());

            if (nodeNames == null || nodeNames.isEmpty()) {
                throw new DiscoveryException("No Proxmox VE nodes found behind " + apiHost + ":" + apiPort);
            }

            // Persist cluster level access details so credentials survive management server
            // restarts. The details DAO encrypts the value of the "password" key.
            Map<String, String> clusterDetails = _clusterDetailsDao.findDetails(clusterId);
            clusterDetails.put("url", "https://" + apiHost + ":" + apiPort);
            clusterDetails.put("username", tokenId != null ? tokenId : apiUsername);
            clusterDetails.put("password", password);
            _clusterDetailsDao.persist(clusterId, clusterDetails);

            Map<ProxmoxResource, Map<String, String>> resources = new HashMap<>();
            for (String nodeName : nodeNames) {
                String guid = UUID.nameUUIDFromBytes(("Proxmox:" + clusterName + ":" + nodeName).getBytes(StandardCharsets.UTF_8)) + "-ProxmoxResource";
                HostVO existingHost = _hostDao.findByGuid(guid);
                if (existingHost != null) {
                    logger.info("Skipping Proxmox node {} as it is already registered as host {} (guid {})", nodeName, existingHost, guid);
                    continue;
                }

                String nodeAddress = resolveNodeAddress(client, nodeName, apiHost);

                Map<String, Object> params = new LinkedHashMap<>();
                params.put("zone", Long.toString(dcId));
                params.put("pod", Long.toString(podId));
                params.put("cluster", Long.toString(clusterId));
                params.put("guid", guid);
                params.put("url", "https://" + nodeAddress + ":" + apiPort);
                params.put("node", nodeName);
                params.put("nodeAddress", nodeAddress);
                if (tokenId != null) {
                    params.put("token.id", tokenId);
                    // host_details only auto-encrypts the "password" key; other secrets must be
                    // stored pre-encrypted and are decrypted in ProxmoxResource.configure()
                    params.put("token.secret", DBEncryptionUtil.encrypt(tokenSecret));
                } else {
                    params.put("username", apiUsername);
                    params.put("password", apiPassword);
                }
                params.put("ssh.username", sshUsername);
                if (sshPassword != null) {
                    params.put("ssh.password", DBEncryptionUtil.encrypt(sshPassword));
                }
                params.put("ssh.port", sshPort);
                for (String key : PASSTHROUGH_URL_PARAMS) {
                    if (urlParams.containsKey(key)) {
                        params.put(key, urlParams.get(key));
                    }
                }

                Map<String, String> details = new HashMap<>();
                for (Map.Entry<String, Object> entry : params.entrySet()) {
                    if (entry.getValue() != null) {
                        details.put(entry.getKey(), entry.getValue().toString());
                    }
                }

                ProxmoxResource resource = new ProxmoxResource();
                try {
                    resource.configure(nodeName, params);
                } catch (ConfigurationException e) {
                    _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_HOST, dcId, podId, "Unable to add Proxmox node " + nodeName,
                            "Error is " + e.getMessage());
                    logger.warn("Unable to configure Proxmox resource for node {}", nodeName, e);
                    throw new DiscoveryException("Unable to configure resource for Proxmox node " + nodeName + ": " + e.getMessage(), e);
                }
                resource.start();

                resources.put(resource, details);
                logger.info("Prepared Proxmox resource for node {} (address {}, guid {})", nodeName, nodeAddress, guid);
            }

            if (resources.isEmpty()) {
                logger.warn("All {} node(s) of Proxmox cluster '{}' are already registered, nothing to add", nodeNames.size(), clusterName);
            }

            if (cluster.getGuid() == null) {
                cluster.setGuid(UUID.nameUUIDFromBytes(String.valueOf(clusterId).getBytes(StandardCharsets.UTF_8)).toString());
                _clusterDao.update(clusterId, cluster);
            }

            return resources;
        } catch (DiscoveryException e) {
            throw e;
        } catch (ProxmoxApiException e) {
            String msg = "Unable to discover Proxmox cluster via " + apiHost + ":" + apiPort + " (HTTP status " + e.getStatusCode() +
                    "). Check the URL and API credentials. Error: " + e.getMessage();
            logger.warn(msg, e);
            throw new DiscoveryException(msg, e);
        } catch (RuntimeException e) {
            String msg = "Unable to discover Proxmox cluster via " + apiHost + ":" + apiPort + ". Error: " + e.getMessage();
            logger.warn(msg, e);
            throw new DiscoveryException(msg, e);
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    /**
     * Resolves the management address of a PVE node. Prefers the "ip" member of the node entry
     * in /cluster/status, then the address of the default bridge, and finally falls back to the
     * host provided in the discovery URL (single node case).
     */
    private String resolveNodeAddress(ProxmoxApiClient client, String nodeName, String fallbackAddress) {
        try {
            JsonElement status = client.get("/cluster/status");
            if (status != null && status.isJsonArray()) {
                for (JsonElement element : status.getAsJsonArray()) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject entry = element.getAsJsonObject();
                    if (entry.has("type") && "node".equals(entry.get("type").getAsString()) && entry.has("name") &&
                            nodeName.equals(entry.get("name").getAsString()) && entry.has("ip")) {
                        return entry.get("ip").getAsString();
                    }
                }
            }
        } catch (ProxmoxApiException e) {
            logger.debug("Unable to read /cluster/status while resolving the address of node {}: {}", nodeName, e.getMessage());
        }

        try {
            String address = client.getNodeNetworkAddress(nodeName, "vmbr0");
            if (StringUtils.isNotBlank(address)) {
                return address;
            }
        } catch (ProxmoxApiException e) {
            logger.debug("Unable to read the vmbr0 address of node {}: {}", nodeName, e.getMessage());
        }

        logger.debug("Falling back to the discovery URL host {} as the address of node {}", fallbackAddress, nodeName);
        return fallbackAddress;
    }

    /**
     * Parses the query string of the discovery URL into a map. Tolerates missing values and
     * URL-encoded keys/values; keys are lower-cased.
     */
    private Map<String, String> parseUrlParameters(URI uri) {
        Map<String, String> params = new HashMap<>();
        String query = uri.getRawQuery();
        if (StringUtils.isBlank(query)) {
            return params;
        }
        for (String part : query.split("&")) {
            if (part.isEmpty()) {
                continue;
            }
            int idx = part.indexOf('=');
            String key = idx < 0 ? part : part.substring(0, idx);
            String value = idx < 0 ? "" : part.substring(idx + 1);
            key = URLDecoder.decode(key, StandardCharsets.UTF_8).trim().toLowerCase();
            value = URLDecoder.decode(value, StandardCharsets.UTF_8);
            if (!key.isEmpty()) {
                params.put(key, value);
            }
        }
        return params;
    }

    protected static String normalizeUsername(String username) {
        if (username.indexOf('@') < 0) {
            return username + DEFAULT_REALM_SUFFIX;
        }
        return username;
    }

    protected static String normalizeTokenId(String tokenId) {
        int separator = tokenId.indexOf('!');
        String userPart = tokenId.substring(0, separator);
        String tokenName = tokenId.substring(separator + 1);
        return normalizeUsername(userPart) + "!" + tokenName;
    }

    @Override
    public void postDiscovery(List<HostVO> hosts, long msId) {
        // do nothing
    }

    @Override
    public boolean matchHypervisor(String hypervisor) {
        if (hypervisor == null) {
            return true;
        }
        return HypervisorType.Proxmox.toString().equalsIgnoreCase(hypervisor);
    }

    @Override
    public Hypervisor.HypervisorType getHypervisorType() {
        return HypervisorType.Proxmox;
    }

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        if (logger.isInfoEnabled()) {
            logger.info("Configure ProxmoxServerDiscoverer, discoverer name: {}", name);
        }
        super.configure(name, params);
        _resourceMgr.registerResourceStateAdapter(this.getClass().getSimpleName(), this);
        return true;
    }

    @Override
    public boolean stop() {
        _resourceMgr.unregisterResourceStateAdapter(this.getClass().getSimpleName());
        return super.stop();
    }

    @Override
    public ServerResource reloadResource(HostVO host) {
        String resourceName = host.getResource();
        ServerResource resource = getResource(resourceName);

        if (resource != null) {
            _hostDao.loadDetails(host);

            HashMap<String, Object> params = buildConfigParams(host);
            try {
                resource.configure(host.getName(), params);
            } catch (ConfigurationException e) {
                logger.warn("Unable to configure resource due to {}", e.getMessage());
                return null;
            }
            if (!resource.start()) {
                logger.warn("Unable to start the resource");
                return null;
            }
        }
        return resource;
    }

    @Override
    public HostVO createHostVOForConnectedAgent(HostVO host, StartupCommand[] cmd) {
        // Proxmox hosts are direct-connect resources; no connected agents
        return null;
    }

    @Override
    public HostVO createHostVOForDirectConnectAgent(HostVO host, StartupCommand[] startup, ServerResource resource, Map<String, String> details,
            List<String> hostTags) {
        StartupCommand firstCmd = startup[0];
        if (!(firstCmd instanceof StartupRoutingCommand)) {
            return null;
        }

        StartupRoutingCommand ssCmd = (StartupRoutingCommand)firstCmd;
        if (ssCmd.getHypervisorType() != HypervisorType.Proxmox) {
            return null;
        }

        return _resourceMgr.fillRoutingHostVO(host, ssCmd, HypervisorType.Proxmox, details, hostTags);
    }

    @Override
    public DeleteHostAnswer deleteHost(HostVO host, boolean isForced, boolean isForceDeleteStorage) throws UnableDeleteHostException {
        if (host.getType() != Host.Type.Routing || host.getHypervisorType() != HypervisorType.Proxmox) {
            return null;
        }

        _resourceMgr.deleteRoutingHost(host, isForced, isForceDeleteStorage);
        return new DeleteHostAnswer(true);
    }
}
