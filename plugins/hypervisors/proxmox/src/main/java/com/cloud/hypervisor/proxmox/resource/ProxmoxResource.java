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
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.hypervisor.proxmox.resource;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.naming.ConfigurationException;

import org.apache.cloudstack.storage.command.StorageSubSystemCommand;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.storage.to.TemplateObjectTO;
import org.apache.cloudstack.vm.UnmanagedInstanceTO;
import org.apache.commons.lang3.StringUtils;
import org.joda.time.Duration;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckHealthAnswer;
import com.cloud.agent.api.CheckHealthCommand;
import com.cloud.agent.api.CheckNetworkAnswer;
import com.cloud.agent.api.CheckNetworkCommand;
import com.cloud.agent.api.CheckOnHostAnswer;
import com.cloud.agent.api.CheckOnHostCommand;
import com.cloud.agent.api.CheckVirtualMachineAnswer;
import com.cloud.agent.api.CheckVirtualMachineCommand;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.CreateVMSnapshotAnswer;
import com.cloud.agent.api.CreateVMSnapshotCommand;
import com.cloud.agent.api.CreateStoragePoolCommand;
import com.cloud.agent.api.DeleteStoragePoolCommand;
import com.cloud.agent.api.DeleteVMSnapshotAnswer;
import com.cloud.agent.api.DeleteVMSnapshotCommand;
import com.cloud.agent.api.FenceAnswer;
import com.cloud.agent.api.FenceCommand;
import com.cloud.agent.api.GetHostStatsAnswer;
import com.cloud.agent.api.GetHostStatsCommand;
import com.cloud.agent.api.GetStorageStatsAnswer;
import com.cloud.agent.api.GetStorageStatsCommand;
import com.cloud.agent.api.GetVmDiskStatsAnswer;
import com.cloud.agent.api.GetVmDiskStatsCommand;
import com.cloud.agent.api.GetVmNetworkStatsAnswer;
import com.cloud.agent.api.GetVmNetworkStatsCommand;
import com.cloud.agent.api.GetVmStatsAnswer;
import com.cloud.agent.api.GetVmStatsCommand;
import com.cloud.agent.api.GetUnmanagedInstancesAnswer;
import com.cloud.agent.api.GetUnmanagedInstancesCommand;
import com.cloud.agent.api.GetVncPortAnswer;
import com.cloud.agent.api.GetVncPortCommand;
import com.cloud.agent.api.GetVolumeStatsAnswer;
import com.cloud.agent.api.GetVolumeStatsCommand;
import com.cloud.agent.api.HostStatsEntry;
import com.cloud.agent.api.HostVmStateReportEntry;
import com.cloud.agent.api.MaintainAnswer;
import com.cloud.agent.api.MaintainCommand;
import com.cloud.agent.api.MigrateAnswer;
import com.cloud.agent.api.MigrateCommand;
import com.cloud.agent.api.ModifySshKeysCommand;
import com.cloud.agent.api.ModifyStoragePoolAnswer;
import com.cloud.agent.api.ModifyStoragePoolCommand;
import com.cloud.agent.api.NetworkUsageAnswer;
import com.cloud.agent.api.NetworkUsageCommand;
import com.cloud.agent.api.PatchSystemVmAnswer;
import com.cloud.agent.api.PatchSystemVmCommand;
import com.cloud.agent.api.PingCommand;
import com.cloud.agent.api.PingRoutingCommand;
import com.cloud.agent.api.PingTestCommand;
import com.cloud.agent.api.PlugNicAnswer;
import com.cloud.agent.api.PlugNicCommand;
import com.cloud.agent.api.PrepareForMigrationAnswer;
import com.cloud.agent.api.PrepareForMigrationCommand;
import com.cloud.agent.api.PrepareUnmanageVMInstanceAnswer;
import com.cloud.agent.api.PrepareUnmanageVMInstanceCommand;
import com.cloud.agent.api.ReadyAnswer;
import com.cloud.agent.api.ReadyCommand;
import com.cloud.agent.api.RebootAnswer;
import com.cloud.agent.api.RebootCommand;
import com.cloud.agent.api.RebootRouterCommand;
import com.cloud.agent.api.RevertToVMSnapshotAnswer;
import com.cloud.agent.api.RevertToVMSnapshotCommand;
import com.cloud.agent.api.SetupGuestNetworkCommand;
import com.cloud.agent.api.StartAnswer;
import com.cloud.agent.api.StartCommand;
import com.cloud.agent.api.StartupCommand;
import com.cloud.agent.api.StartupRoutingCommand;
import com.cloud.agent.api.StopAnswer;
import com.cloud.agent.api.StopCommand;
import com.cloud.agent.api.UnPlugNicAnswer;
import com.cloud.agent.api.UnPlugNicCommand;
import com.cloud.agent.api.UnregisterVMCommand;
import com.cloud.agent.api.VmDiskStatsEntry;
import com.cloud.agent.api.VmNetworkStatsEntry;
import com.cloud.agent.api.VmStatsEntry;
import com.cloud.agent.api.VolumeStatsEntry;
import com.cloud.agent.api.check.CheckSshAnswer;
import com.cloud.agent.api.check.CheckSshCommand;
import com.cloud.agent.api.routing.IpAssocCommand;
import com.cloud.agent.api.routing.IpAssocVpcCommand;
import com.cloud.agent.api.routing.NetworkElementCommand;
import com.cloud.agent.api.routing.SetNetworkACLCommand;
import com.cloud.agent.api.routing.SetSourceNatCommand;
import com.cloud.agent.api.storage.ResizeVolumeAnswer;
import com.cloud.agent.api.storage.ResizeVolumeCommand;
import com.cloud.agent.api.to.DataStoreTO;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.api.to.IpAddressTO;
import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.StorageFilerTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.agent.resource.virtualnetwork.VRScripts;
import com.cloud.agent.resource.virtualnetwork.VirtualRouterDeployer;
import com.cloud.agent.resource.virtualnetwork.VirtualRoutingResource;
import com.cloud.exception.InternalErrorException;
import com.cloud.host.Host;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.Networks.TrafficType;
import com.cloud.hypervisor.proxmox.api.ProxmoxApiClient;
import com.cloud.hypervisor.proxmox.api.ProxmoxApiException;
import com.cloud.hypervisor.proxmox.storage.ProxmoxStorageProcessor;
import com.cloud.hypervisor.proxmox.storage.ProxmoxStorageSubsystemCommandHandler;
import com.cloud.resource.ServerResource;
import com.cloud.resource.ServerResourceBase;
import com.cloud.storage.Volume;
import com.cloud.storage.resource.StorageSubsystemCommandHandler;
import com.cloud.storage.template.TemplateProp;
import com.cloud.utils.ExecutionResult;
import com.cloud.utils.FileUtil;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.crypt.DBEncryptionUtil;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.cloud.utils.script.Script;
import com.cloud.utils.ssh.SshHelper;
import com.cloud.utils.validation.ChecksumUtil;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.PowerState;
import com.cloud.vm.VirtualMachineName;
import com.cloud.vm.VmDetailConstants;
import com.cloud.vm.snapshot.VMSnapshot;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Direct-connect ServerResource for a single Proxmox VE node. One instance is
 * created per PVE node by the ProxmoxServerDiscoverer and runs inside the
 * management server (VMware model). The primary control channel is the PVE
 * REST API; SSH to the node is used for the few operations the API cannot do
 * (system VM boot-args patching, qemu-img fallbacks).
 */
public class ProxmoxResource extends ServerResourceBase implements ServerResource, VirtualRouterDeployer {

    private static final int DEFAULT_DOMR_SSH_PORT = 3922;
    private static final int DEFAULT_API_PORT = 8006;
    private static final int DEFAULT_SSH_TIMEOUT_SEC = 600;
    private static final int VNC_DISPLAY_MODULO = 20000;
    private static final int VNC_BASE_PORT = 5900;
    private static final long DOM0_MIN_MEMORY_BYTES = 1024L * 1024L * 1024L;
    private static final long CONNECT_OPS_TIMEOUT_MS = 300000L;
    private static final int CONNECT_RETRIES = 24;
    private static final long NIC_HOTPLUG_WAIT_TIMEOUT_MS = 15000L;
    private static final int PATCH_RETRY_COUNT = 5;
    private static final long PATCH_RETRY_SLEEP_MS = 3000L;
    private static final long PATCH_AGENT_WAIT_MS = 180000L;
    private static final long GUEST_IP_WAIT_MS = 300000L;
    private static final int MAX_NIC_SLOTS = 32;

    private static final String RELATIVE_SYSTEMVM_KEY_PATH = "scripts/vm/systemvm/id_rsa.cloud";
    private static final String DEFAULT_SYSTEMVM_KEY_PATH = "/usr/share/cloudstack-common/scripts/vm/systemvm/id_rsa.cloud";
    // Base path on the management server where the system VM patch files (agent.zip,
    // cloud-scripts.tgz, patch-sysvms.sh) live; mirrors VmwareResource.BASEPATH.
    public static final String BASEPATH = "/usr/share/cloudstack-common/vms/";

    private static volatile File s_systemVmKeyFile = null;
    private static final Object s_systemVmKeyFileLock = new Object();

    private String _zoneId;
    private String _podId;
    private String _clusterId;
    private String _guid;
    private String _url;
    private String _nodeName;
    private String _nodeAddress;
    private int _apiPort = DEFAULT_API_PORT;
    private String _username;
    private String _password;
    private String _tokenId;
    private String _tokenSecret;
    private String _sshUsername = "root";
    private String _sshPassword;
    private int _sshPort = 22;
    private int _vmidBase = 10000;
    private String _defaultBridge = "vmbr0";
    private boolean _verifyTls = false;
    private int _taskTimeoutSec = 600;
    private boolean _migrateWithLocalDisks = false;

    private ProxmoxApiClient _apiClient;
    private final Object _apiClientLock = new Object();
    private final Map<String, String> _storageTypeCache = new HashMap<String, String>();

    private VirtualRoutingResource _vrResource;
    private StorageSubsystemCommandHandler _storageHandler;
    private ProxmoxStorageProcessor _storageProcessor;

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        this.name = name;

        _zoneId = (String) params.get("zone");
        _podId = (String) params.get("pod");
        _clusterId = (String) params.get("cluster");
        _guid = (String) params.get("guid");
        _url = (String) params.get("url");
        _nodeName = (String) params.get("node");
        _nodeAddress = (String) params.get("nodeAddress");
        _username = (String) params.get("username");
        _password = (String) params.get("password");
        _tokenId = (String) params.get("token.id");
        // "token.secret" and "ssh.password" are stored encrypted in host_details (the details
        // DAO only auto-encrypts the "password" key); the discoverer encrypts them on write
        _tokenSecret = DBEncryptionUtil.decrypt((String) params.get("token.secret"));

        if (StringUtils.isBlank(_guid)) {
            throw new ConfigurationException("Unable to find the guid in configuration parameters");
        }
        if (StringUtils.isBlank(_nodeName)) {
            throw new ConfigurationException("Unable to find the Proxmox node name in configuration parameters");
        }
        if (StringUtils.isBlank(_nodeAddress)) {
            throw new ConfigurationException("Unable to find the Proxmox node address in configuration parameters");
        }

        String value = (String) params.get("ssh.username");
        if (StringUtils.isNotBlank(value)) {
            _sshUsername = value;
        }
        value = DBEncryptionUtil.decrypt((String) params.get("ssh.password"));
        if (StringUtils.isNotBlank(value)) {
            _sshPassword = value;
        } else {
            _sshPassword = _password;
        }
        _sshPort = NumbersUtil.parseInt((String) params.get("ssh.port"), 22);
        _vmidBase = NumbersUtil.parseInt((String) params.get("vmid.base"), 10000);
        value = (String) params.get("default.bridge");
        if (StringUtils.isNotBlank(value)) {
            _defaultBridge = value;
        }
        _verifyTls = Boolean.parseBoolean((String) params.get("verify.tls"));
        _taskTimeoutSec = NumbersUtil.parseInt((String) params.get("task.timeout.sec"), 600);
        _migrateWithLocalDisks = Boolean.parseBoolean((String) params.get("migrate.with.local.disks"));

        _apiPort = parseApiPort(_url);

        _vrResource = new VirtualRoutingResource(this);
        // Our params carry ssh.port for SSH to the PVE node (22), but VirtualRoutingResource
        // reads the same key as the system VM control port for its connect() probe. Hand it a
        // copy pinned to the system VM sshd port, or every control-channel probe dials port 22.
        Map<String, Object> vrParams = new HashMap<>(params);
        vrParams.put("ssh.port", String.valueOf(DEFAULT_DOMR_SSH_PORT));
        if (!_vrResource.configure(name, vrParams)) {
            throw new ConfigurationException("Unable to configure VirtualRoutingResource");
        }

        _storageProcessor = new ProxmoxStorageProcessor(this);
        _storageHandler = new ProxmoxStorageSubsystemCommandHandler(_storageProcessor);

        logger.info("Configured ProxmoxResource for node " + _nodeName + " (" + _nodeAddress + "), guid " + _guid);
        return true;
    }

    private static int parseApiPort(String url) {
        if (StringUtils.isNotBlank(url)) {
            try {
                int port = new URI(url).getPort();
                if (port > 0) {
                    return port;
                }
            } catch (Exception e) {
                // fall through to default
            }
        }
        return DEFAULT_API_PORT;
    }

    /**
     * Lazily constructs the PVE API client so that configure() succeeds even
     * when the node is briefly unreachable at host-add time.
     */
    public ProxmoxApiClient getApiClient() {
        synchronized (_apiClientLock) {
            if (_apiClient == null) {
                _apiClient = new ProxmoxApiClient(_nodeAddress, _apiPort, _username, _password, _tokenId, _tokenSecret,
                        _verifyTls, 30, 120);
            }
            return _apiClient;
        }
    }

    public String getNodeName() {
        return _nodeName;
    }

    public String getNodeAddress() {
        return _nodeAddress;
    }

    public int getVmidBase() {
        return _vmidBase;
    }

    public long getTaskTimeoutMs() {
        return _taskTimeoutSec * 1000L;
    }

    public String getDefaultBridge() {
        return _defaultBridge;
    }

    /** Traffic label of the NIC (its network name) when set, else the default bridge. */
    public String resolveBridge(NicTO nic) {
        if (nic != null && StringUtils.isNotBlank(nic.getName())) {
            return nic.getName();
        }
        return _defaultBridge;
    }

    public int vmidOf(VirtualMachineTO vm) {
        return _vmidBase + (int) vm.getId();
    }

    /**
     * Derives the PVE vmid from a CloudStack internal instance name
     * (i-&lt;account&gt;-&lt;id&gt;-VM, r-&lt;id&gt;-VM, s-&lt;id&gt;-VM, v-&lt;id&gt;-VM):
     * the last numeric token is the CloudStack VM id; vmid = base + id.
     */
    public int vmidOfInstanceName(String internalName) {
        Integer vmid = tryParseVmid(internalName);
        if (vmid == null) {
            throw new CloudRuntimeException("Unable to derive a Proxmox vmid from instance name " + internalName);
        }
        return vmid;
    }

    private Integer tryParseVmid(String internalName) {
        if (internalName == null) {
            return null;
        }
        String[] tokens = internalName.split("-");
        for (int i = tokens.length - 1; i >= 0; i--) {
            if (tokens[i].matches("\\d{1,9}")) {
                return _vmidBase + Integer.parseInt(tokens[i]);
            }
        }
        return null;
    }

    /**
     * Resolves the vmid for an instance name against the cluster inventory. The PVE name
     * attribute is authoritative: every VM this plugin creates carries its instance name
     * there, and imported VMs keep their original PVE name as their instance name. The
     * vmid-from-instance-name convention only confirms a match, never overrides a name —
     * an imported name that happens to contain digits (say web-01) must not be misread
     * as the vmid of an unrelated CloudStack VM. Returns null when the VM is nowhere to
     * be found.
     */
    public Integer findVmid(String name) {
        if (name == null) {
            return null;
        }
        JsonArray vms = getApiClient().getClusterResources("vm");
        Integer parsed = tryParseVmid(name);
        Integer byName = null;
        boolean parsedMatchesName = false;
        for (JsonElement element : vms) {
            JsonObject vm = element.getAsJsonObject();
            if (!vm.has("vmid") || !"qemu".equals(jsonString(vm, "type"))) {
                continue;
            }
            int vmid = vm.get("vmid").getAsInt();
            String pveName = jsonString(vm, "name");
            if (name.equals(pveName)) {
                if (byName != null && byName != vmid) {
                    throw new CloudRuntimeException("More than one Proxmox VM (vmids " + byName + ", " + vmid
                            + ") carries the name " + name + "; refusing an ambiguous match");
                }
                byName = vmid;
            }
            if (parsed != null && vmid == parsed && (pveName == null || pveName.equals(name))) {
                parsedMatchesName = true;
            }
        }
        if (byName != null) {
            return byName;
        }
        if (parsed != null && parsedMatchesName) {
            return parsed;
        }
        return null;
    }

    public Pair<Boolean, String> executeOnNode(String command) {
        return executeOnNode(command, DEFAULT_SSH_TIMEOUT_SEC);
    }

    public Pair<Boolean, String> executeOnNode(String command, int timeoutSec) {
        try {
            return SshHelper.sshExecute(_nodeAddress, _sshPort, _sshUsername, null, _sshPassword, command,
                    timeoutSec * 1000);
        } catch (Exception e) {
            logger.warn("SSH command failed on node " + _nodeName + ": " + command, e);
            return new Pair<Boolean, String>(false, e.getMessage());
        }
    }

    /** Maps a CloudStack primary storage pool (PreSetup) to the PVE storage id: the last path segment. */
    public String getPveStorageId(DataStoreTO store) {
        if (store instanceof PrimaryDataStoreTO) {
            return lastPathSegment(((PrimaryDataStoreTO) store).getPath());
        }
        throw new CloudRuntimeException("Unable to map data store " + store + " to a Proxmox storage id");
    }

    private static String lastPathSegment(String path) {
        if (path == null) {
            return null;
        }
        String trimmed = path.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        int idx = trimmed.lastIndexOf('/');
        return idx >= 0 ? trimmed.substring(idx + 1) : trimmed;
    }

    /**
     * Returns the PVE storage type (dir, nfs, rbd, cephfs, lvmthin, zfspool, ...) of the given
     * storage id on this node. Types are cached for the lifetime of the resource: a PVE storage
     * cannot change type in place.
     */
    public String getStorageType(String storageId) {
        if (StringUtils.isBlank(storageId)) {
            throw new CloudRuntimeException("Cannot determine the type of a blank PVE storage id");
        }
        synchronized (_storageTypeCache) {
            String cached = _storageTypeCache.get(storageId);
            if (cached != null) {
                return cached;
            }
        }
        JsonArray storages = getApiClient().listStorage(_nodeName);
        String result = null;
        synchronized (_storageTypeCache) {
            for (JsonElement element : storages) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject storage = element.getAsJsonObject();
                String id = jsonString(storage, "storage");
                String type = jsonString(storage, "type");
                if (id == null || type == null) {
                    continue;
                }
                _storageTypeCache.put(id, type);
                if (id.equals(storageId)) {
                    result = type;
                }
            }
        }
        if (result == null) {
            throw new CloudRuntimeException(String.format("Unable to determine the type of PVE storage '%s' on node %s", storageId, _nodeName));
        }
        return result;
    }

    @Override
    public Host.Type getType() {
        return Host.Type.Routing;
    }

    @Override
    protected String getDefaultScriptsDir() {
        return null;
    }

    @Override
    public boolean start() {
        return true;
    }

    @Override
    public boolean stop() {
        return true;
    }

    @Override
    public void setName(String name) {
        this.name = name;
    }

    @Override
    public void setConfigParams(Map<String, Object> params) {
    }

    @Override
    public Map<String, Object> getConfigParams() {
        return null;
    }

    @Override
    public int getRunLevel() {
        return 0;
    }

    @Override
    public void setRunLevel(int level) {
    }

    @Override
    public StartupCommand[] initialize() {
        try {
            ProxmoxApiClient api = getApiClient();
            JsonObject status = api.getNodeStatus(_nodeName);
            JsonObject cpuInfo = status.getAsJsonObject("cpuinfo");
            JsonObject memory = status.getAsJsonObject("memory");

            StartupRoutingCommand cmd = new StartupRoutingCommand();
            cmd.setCpus((int) jsonLong(cpuInfo, "cpus", 1));
            cmd.setSpeed((long) jsonDouble(cpuInfo, "mhz", 1000d));
            long sockets = jsonLong(cpuInfo, "sockets", 0);
            if (sockets > 0) {
                cmd.setCpuSockets((int) sockets);
            }
            cmd.setMemory(jsonLong(memory, "total", 0));
            cmd.setDom0MinMemory(DOM0_MIN_MEMORY_BYTES);
            cmd.setCaps("hvm");
            cmd.setHypervisorType(HypervisorType.Proxmox);
            try {
                cmd.setHypervisorVersion(api.getVersion());
            } catch (Exception e) {
                logger.warn("Unable to fetch Proxmox version from node " + _nodeName, e);
            }
            cmd.setCluster(_clusterId);
            try {
                cmd.setPool(api.getClusterName());
            } catch (Exception e) {
                logger.debug("Unable to fetch Proxmox cluster name from node " + _nodeName, e);
            }
            cmd.setName(_nodeName);
            cmd.setGuid(_guid);
            cmd.setDataCenter(_zoneId);
            cmd.setPod(_podId);
            cmd.setPrivateIpAddress(_nodeAddress);
            cmd.setPrivateNetmask(discoverPrivateNetmask());
            cmd.setStorageIpAddress(_nodeAddress);
            cmd.setVersion(ProxmoxResource.class.getPackage().getImplementationVersion());

            return new StartupCommand[] {cmd};
        } catch (Exception e) {
            logger.error("ProxmoxResource initialize() failed for node " + _nodeName, e);
            return null;
        }
    }

    private String discoverPrivateNetmask() {
        try {
            JsonElement data = getApiClient().get("/nodes/" + _nodeName + "/network");
            if (data != null && data.isJsonArray()) {
                for (JsonElement e : data.getAsJsonArray()) {
                    JsonObject iface = e.getAsJsonObject();
                    if (_nodeAddress.equals(jsonString(iface, "address"))) {
                        String netmask = jsonString(iface, "netmask");
                        if (StringUtils.isNotBlank(netmask)) {
                            return netmask;
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Unable to discover netmask of node " + _nodeName + ", using default", e);
        }
        return "255.255.255.0";
    }

    @Override
    public PingCommand getCurrentStatus(long id) {
        try {
            return new PingRoutingCommand(getType(), id, getHostVmStateReport());
        } catch (Exception e) {
            logger.warn("Unable to fetch VM state report from node " + _nodeName + ", reporting disconnect", e);
            return null;
        }
    }

    private HashMap<String, HostVmStateReportEntry> getHostVmStateReport() {
        HashMap<String, HostVmStateReportEntry> report = new HashMap<String, HostVmStateReportEntry>();
        // Deliberately per-node live status, not /cluster/resources: the cluster view is
        // pvestatd-cached and can lag ~10s. A stale PowerOff report for a VM that just
        // started makes the power sync "clean up" the VM with an out-of-band StopCommand.
        JsonArray vms = getApiClient().listNodeVms(_nodeName);
        if (vms == null) {
            return report;
        }
        for (JsonElement e : vms) {
            JsonObject vm = e.getAsJsonObject();
            String vmName = jsonString(vm, "name");
            if (StringUtils.isBlank(vmName)) {
                continue;
            }
            report.put(vmName, new HostVmStateReportEntry(toPowerState(jsonString(vm, "status")), _nodeName));
        }
        return report;
    }

    private static PowerState toPowerState(String pveStatus) {
        if ("running".equalsIgnoreCase(pveStatus)) {
            return PowerState.PowerOn;
        }
        if ("stopped".equalsIgnoreCase(pveStatus)) {
            return PowerState.PowerOff;
        }
        return PowerState.PowerUnknown;
    }

    @Override
    public Answer executeRequest(Command cmd) {
        if (logger.isDebugEnabled()) {
            logger.debug("Executing " + cmd.getClass().getSimpleName() + " on node " + _nodeName);
        }
        try {
            Class<? extends Command> clz = cmd.getClass();
            if (cmd instanceof NetworkElementCommand) {
                return _vrResource.executeRequest((NetworkElementCommand) cmd);
            } else if (cmd instanceof StorageSubSystemCommand) {
                return _storageHandler.handleStorageCommands((StorageSubSystemCommand) cmd);
            } else if (clz == ReadyCommand.class) {
                return execute((ReadyCommand) cmd);
            } else if (clz == CheckHealthCommand.class) {
                return execute((CheckHealthCommand) cmd);
            } else if (clz == PingTestCommand.class) {
                return execute((PingTestCommand) cmd);
            } else if (clz == GetHostStatsCommand.class) {
                return execute((GetHostStatsCommand) cmd);
            } else if (clz == GetVmStatsCommand.class) {
                return execute((GetVmStatsCommand) cmd);
            } else if (clz == GetVmDiskStatsCommand.class) {
                return execute((GetVmDiskStatsCommand) cmd);
            } else if (clz == GetVmNetworkStatsCommand.class) {
                return execute((GetVmNetworkStatsCommand) cmd);
            } else if (clz == StartCommand.class) {
                return execute((StartCommand) cmd);
            } else if (clz == StopCommand.class) {
                return execute((StopCommand) cmd);
            } else if (clz == RebootRouterCommand.class) {
                return execute((RebootRouterCommand) cmd);
            } else if (clz == RebootCommand.class) {
                return execute((RebootCommand) cmd);
            } else if (clz == CheckVirtualMachineCommand.class) {
                return execute((CheckVirtualMachineCommand) cmd);
            } else if (clz == PrepareForMigrationCommand.class) {
                return execute((PrepareForMigrationCommand) cmd);
            } else if (clz == MigrateCommand.class) {
                return execute((MigrateCommand) cmd);
            } else if (clz == CheckOnHostCommand.class) {
                return execute((CheckOnHostCommand) cmd);
            } else if (clz == FenceCommand.class) {
                return execute((FenceCommand) cmd);
            } else if (clz == MaintainCommand.class) {
                return execute((MaintainCommand) cmd);
            } else if (clz == GetVncPortCommand.class) {
                return execute((GetVncPortCommand) cmd);
            } else if (clz == CheckNetworkCommand.class) {
                return execute((CheckNetworkCommand) cmd);
            } else if (clz == CreateStoragePoolCommand.class) {
                return new Answer(cmd, true, "success");
            } else if (clz == ModifyStoragePoolCommand.class) {
                return execute((ModifyStoragePoolCommand) cmd);
            } else if (clz == DeleteStoragePoolCommand.class) {
                return execute((DeleteStoragePoolCommand) cmd);
            } else if (clz == GetStorageStatsCommand.class) {
                return execute((GetStorageStatsCommand) cmd);
            } else if (cmd instanceof GetVolumeStatsCommand) {
                return execute((GetVolumeStatsCommand) cmd);
            } else if (clz == ResizeVolumeCommand.class) {
                return execute((ResizeVolumeCommand) cmd);
            } else if (clz == ModifySshKeysCommand.class) {
                return execute((ModifySshKeysCommand) cmd);
            } else if (clz == CheckSshCommand.class) {
                return execute((CheckSshCommand) cmd);
            } else if (clz == PlugNicCommand.class) {
                return execute((PlugNicCommand) cmd);
            } else if (clz == UnPlugNicCommand.class) {
                return execute((UnPlugNicCommand) cmd);
            } else if (cmd instanceof CreateVMSnapshotCommand) {
                return execute((CreateVMSnapshotCommand) cmd);
            } else if (cmd instanceof DeleteVMSnapshotCommand) {
                return execute((DeleteVMSnapshotCommand) cmd);
            } else if (cmd instanceof RevertToVMSnapshotCommand) {
                return execute((RevertToVMSnapshotCommand) cmd);
            } else if (clz == NetworkUsageCommand.class) {
                return execute((NetworkUsageCommand) cmd);
            } else if (clz == UnregisterVMCommand.class) {
                return execute((UnregisterVMCommand) cmd);
            } else if (clz == GetUnmanagedInstancesCommand.class) {
                return execute((GetUnmanagedInstancesCommand) cmd);
            } else if (clz == PrepareUnmanageVMInstanceCommand.class) {
                return execute((PrepareUnmanageVMInstanceCommand) cmd);
            } else if (clz == PatchSystemVmCommand.class) {
                return execute((PatchSystemVmCommand) cmd);
            } else {
                return Answer.createUnsupportedCommandAnswer(cmd);
            }
        } catch (Exception e) {
            logger.error("Unexpected exception executing " + cmd.getClass().getSimpleName() + " on node " + _nodeName, e);
            return new Answer(cmd, e);
        }
    }

    protected Answer execute(ReadyCommand cmd) {
        return new ReadyAnswer(cmd);
    }

    protected Answer execute(CheckHealthCommand cmd) {
        try {
            getApiClient().getVersion();
            return new CheckHealthAnswer(cmd, true);
        } catch (Exception e) {
            logger.warn("Health check of node " + _nodeName + " failed", e);
            return new CheckHealthAnswer(cmd, false);
        }
    }

    protected Answer execute(PingTestCommand cmd) {
        String ip = cmd.getComputingHostIp() != null ? cmd.getComputingHostIp() : cmd.getPrivateIp();
        if (ip == null) {
            return new Answer(cmd, false, "No IP address to ping");
        }
        Pair<Boolean, String> result = executeOnNode("ping -c 2 -W 2 " + ip, 30);
        if (result.first()) {
            return new Answer(cmd);
        }
        return new Answer(cmd, false, "Ping of " + ip + " failed: " + result.second());
    }

    protected Answer execute(GetHostStatsCommand cmd) {
        try {
            JsonObject status = getApiClient().getNodeStatus(_nodeName);
            JsonObject memory = status.getAsJsonObject("memory");
            double cpuUtilization = jsonDouble(status, "cpu", 0d) * 100.0;
            double totalMemoryKBs = jsonLong(memory, "total", 0) / 1024.0;
            double freeMemoryKBs = jsonLong(memory, "free", 0) / 1024.0;
            double loadAverage = 0d;
            try {
                JsonElement loadavg = status.get("loadavg");
                if (loadavg != null && loadavg.isJsonArray() && loadavg.getAsJsonArray().size() > 0) {
                    loadAverage = Double.parseDouble(loadavg.getAsJsonArray().get(0).getAsString());
                }
            } catch (Exception e) {
                logger.trace("Unable to parse load average of node " + _nodeName);
            }
            HostStatsEntry entry = new HostStatsEntry(cmd.getHostId(), cpuUtilization, 0d, 0d, "host",
                    totalMemoryKBs, freeMemoryKBs, 0d, loadAverage);
            return new GetHostStatsAnswer(cmd, entry);
        } catch (Exception e) {
            logger.error("Unable to fetch host stats of node " + _nodeName, e);
            return new Answer(cmd, false, e.getMessage());
        }
    }

    protected Answer execute(GetVmStatsCommand cmd) {
        HashMap<String, VmStatsEntry> vmStatsMap = new HashMap<String, VmStatsEntry>();
        ProxmoxApiClient api = getApiClient();
        for (String vmName : cmd.getVmNames()) {
            try {
                Integer vmid = findVmid(vmName);
                if (vmid == null) {
                    continue;
                }
                String node = api.findNodeOfVm(vmid);
                if (node == null) {
                    continue;
                }
                JsonObject status = api.getVmStatus(node, vmid);
                VmStatsEntry entry = new VmStatsEntry();
                entry.setEntityType("vm");
                entry.setVmId(vmid - _vmidBase);
                entry.setCPUUtilization(jsonDouble(status, "cpu", 0d) * 100.0);
                entry.setNumCPUs((int) jsonDouble(status, "cpus", 1d));
                long maxMem = jsonLong(status, "maxmem", 0);
                long mem = jsonLong(status, "mem", 0);
                entry.setMemoryKBs(maxMem / 1024.0);
                entry.setIntFreeMemoryKBs(Math.max(0, maxMem - mem) / 1024.0);
                entry.setTargetMemoryKBs(maxMem / 1024.0);
                entry.setNetworkReadKBs(jsonLong(status, "netin", 0) / 1024.0);
                entry.setNetworkWriteKBs(jsonLong(status, "netout", 0) / 1024.0);
                entry.setDiskReadKBs(jsonLong(status, "diskread", 0) / 1024.0);
                entry.setDiskWriteKBs(jsonLong(status, "diskwrite", 0) / 1024.0);
                vmStatsMap.put(vmName, entry);
            } catch (Exception e) {
                logger.debug("Unable to fetch stats of VM " + vmName + " on node " + _nodeName, e);
            }
        }
        return new GetVmStatsAnswer(cmd, vmStatsMap);
    }

    protected Answer execute(GetVmDiskStatsCommand cmd) {
        // PVE exposes only cumulative disk I/O per VM, not per-volume stats; return an empty report.
        HashMap<String, List<VmDiskStatsEntry>> stats = new HashMap<String, List<VmDiskStatsEntry>>();
        return new GetVmDiskStatsAnswer(cmd, null, cmd.getHostName(), stats);
    }

    protected Answer execute(GetVmNetworkStatsCommand cmd) {
        // Per-NIC counters are not exposed by the PVE status API; return an empty report.
        HashMap<String, List<VmNetworkStatsEntry>> stats = new HashMap<String, List<VmNetworkStatsEntry>>();
        return new GetVmNetworkStatsAnswer(cmd, null, cmd.getHostName(), stats);
    }

    protected Answer execute(StartCommand cmd) {
        VirtualMachineTO spec = cmd.getVirtualMachine();
        Integer adoptedVmid = adoptedVmidOf(spec);
        int vmid = adoptedVmid != null ? adoptedVmid : vmidOf(spec);
        ProxmoxApiClient api = getApiClient();
        try {
            String node = api.findNodeOfVm(vmid);
            if (adoptedVmid != null) {
                return startAdoptedVm(cmd, spec, node, vmid);
            }
            Map<String, Object> config = ProxmoxVmConfigBuilder.build(spec, vmid, this);
            boolean createVm = node == null;
            if (createVm) {
                node = _nodeName;
            } else {
                node = bringVmConfigToThisNode(node, vmid);
            }
            // An ISO attached while the VM was stopped only exists in the CloudStack DB; it
            // arrives here as an ISO DiskTO and must be staged and inserted before boot.
            insertAttachedIso(spec, node, config);
            if (createVm) {
                api.createVm(node, vmid, config, getTaskTimeoutMs());
            } else {
                api.setVmConfig(node, vmid, config);
            }

            api.startVm(node, vmid, getTaskTimeoutMs());

            try {
                if (spec.getType() != VirtualMachine.Type.User) {
                    patchSystemVm(spec, node, vmid);
                    deliverSystemVmPatchFiles(spec, node, vmid);
                }
                if (StringUtils.isNotBlank(spec.getVncPassword())) {
                    setVncPassword(node, vmid, spec.getVncPassword());
                }
            } catch (Exception e) {
                logger.error("Post-start setup of VM " + spec.getName() + " failed, stopping it", e);
                stopVmQuietly(node, vmid);
                return new StartAnswer(cmd, e.getMessage());
            }

            return new StartAnswer(cmd);
        } catch (Exception e) {
            logger.error("StartCommand failed for VM " + spec.getName() + " (vmid " + vmid + ")", e);
            return new StartAnswer(cmd, e.getMessage());
        }
    }

    /**
     * The PVE vmid recorded against an imported VM, or null for a VM this plugin created
     * itself (whose vmid follows the vmid-from-instance-name convention instead).
     */
    private Integer adoptedVmidOf(VirtualMachineTO spec) {
        Map<String, String> details = spec.getDetails();
        String raw = details != null ? details.get(VmDetailConstants.PROXMOX_VM_ID) : null;
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            logger.warn("Ignoring malformed {} detail '{}' on VM {}", VmDetailConstants.PROXMOX_VM_ID, raw, spec.getName());
            return null;
        }
    }

    /**
     * CloudStack placed the VM on this host, but the vmid config is pinned to another node
     * (left there by a migration or a node death). Starting it where the config happens to
     * live would silently diverge from CloudStack's host_id record — the VM then dies
     * invisibly with the wrong node. Bring the config here instead: an offline migration
     * when the owner is reachable (a config move on shared storage), the PVE-HA-style
     * config steal when it is dead.
     */
    private String bringVmConfigToThisNode(String node, int vmid) {
        if (!node.equals(_nodeName)) {
            if (isNodeOnline(node)) {
                getApiClient().migrateVm(node, vmid, _nodeName, false, _migrateWithLocalDisks, getTaskTimeoutMs());
            } else {
                stealVmConfig(node, vmid);
            }
        }
        return _nodeName;
    }

    /**
     * Starts a VM that was imported into CloudStack rather than created by this plugin.
     * Its PVE config is the imported one and stays untouched (rebuilding it to this
     * plugin's conventions would rewire the guest's device topology), so only runtime
     * essentials are applied: a CloudStack-attached ISO and the console VNC password.
     */
    private Answer startAdoptedVm(StartCommand cmd, VirtualMachineTO spec, String node, int vmid) {
        ProxmoxApiClient api = getApiClient();
        if (node == null) {
            return new StartAnswer(cmd, "Imported VM " + spec.getName() + " (vmid " + vmid + ") no longer exists in the Proxmox cluster");
        }
        node = bringVmConfigToThisNode(node, vmid);
        Map<String, Object> isoPatch = new HashMap<>();
        insertAttachedIso(spec, node, isoPatch);
        if (!isoPatch.isEmpty()) {
            String ide2 = jsonString(api.getVmConfig(node, vmid), "ide2");
            if (ide2 != null && !ide2.startsWith("none") && !ide2.contains("media=cdrom")) {
                logger.warn("Not inserting the attached ISO into imported VM {} (vmid {}): its ide2 slot holds {} which is not a cdrom", spec.getName(), vmid, ide2);
            } else {
                api.setVmConfig(node, vmid, isoPatch);
            }
        }
        api.startVm(node, vmid, getTaskTimeoutMs());
        if (StringUtils.isNotBlank(spec.getVncPassword())) {
            try {
                setVncPassword(node, vmid, spec.getVncPassword());
            } catch (Exception e) {
                logger.warn("Unable to set the VNC password of imported VM {} (vmid {}) after start", spec.getName(), vmid, e);
            }
        }
        return new StartAnswer(cmd);
    }

    /**
     * Stages the ISO recorded against the VM (if any) onto a PVE iso-content storage and
     * points ide2 at it, so a VM started with an ISO attached in the CloudStack DB boots
     * with the medium inserted, matching attachIso behavior on running VMs.
     */
    private void insertAttachedIso(VirtualMachineTO spec, String node, Map<String, Object> config) {
        if (spec.getDisks() == null) {
            return;
        }
        for (DiskTO disk : spec.getDisks()) {
            if (disk.getType() != Volume.Type.ISO || !(disk.getData() instanceof TemplateObjectTO)) {
                continue;
            }
            TemplateObjectTO iso = (TemplateObjectTO) disk.getData();
            if (StringUtils.isBlank(iso.getPath()) || iso.getDataStore() == null) {
                continue; // empty cdrom placeholder
            }
            config.put("ide2", _storageProcessor.stageIso(iso, node) + ",media=cdrom");
        }
    }

    /**
     * After the boot-args are in place, deliver the CloudStack agent code (agent.zip,
     * cloud-scripts.tgz, patch-sysvms.sh) to the freshly booted system VM over SSH and wait
     * for the guest's postinit to unpack it. Mirrors LibvirtStartCommandWrapper and the
     * VMware StartCommand flow: the system VM template ships without the agent code, so
     * without this step /usr/local/cloud/systemvm never exists and cloud.service cannot start.
     */
    private void deliverSystemVmPatchFiles(VirtualMachineTO spec, String node, int vmid) throws Exception {
        String controlIp = getControlIp(spec.getNics());
        if (controlIp == null) {
            throw new CloudRuntimeException("No control/management IP on system VM " + spec.getName() + " to deliver the patch files to");
        }
        // The firewall fix must be retried together with the connect attempts: right after the
        // boot-args are written the guest is still inside cloud-early-config, so the control IP
        // is not assigned yet (the interface lookup finds nothing) and the template's
        // iptables-restore would flush a too-early rule insert anyway.
        for (int count = 0; count < 60; count++) {
            openSshdFirewallForControlIp(spec.getName(), node, vmid, controlIp);
            if (_vrResource.connect(controlIp, 1, 5000)) {
                break;
            }
        }
        FileUtil.scpPatchFiles(controlIp, VRScripts.CONFIG_CACHE_LOCATION, DEFAULT_DOMR_SSH_PORT, getSystemVmKeyFile(), systemVmPatchFiles, BASEPATH);
        if (!_vrResource.isSystemVMSetup(spec.getName(), controlIp)) {
            throw new CloudRuntimeException("System VM " + spec.getName() + " did not finish setup after the patch files were delivered");
        }
    }

    /**
     * The system VM template's init.sh auto-detects the hypervisor with virt-what, which
     * reports "kvm" on Proxmox, so it firewalls sshd port 3922 to the link-local control
     * interface (eth0) — an interface that carries no IP here because the management server,
     * not a host agent, drives the VM. Insert an ACCEPT for 3922 on the interface that holds
     * the control IP through the qemu guest agent before trying to SSH in. Idempotent, and a
     * no-op for virtual routers whose control NIC is already the firewalled one.
     */
    private void openSshdFirewallForControlIp(String vmName, String node, int vmid, String controlIp) {
        String rule = "-p tcp -m state --state NEW --dport 3922 -j ACCEPT";
        // On flat networks the management and public NICs can share a subnet; Linux ARP flux
        // then lets the public NIC answer ARP for the control IP, packets arrive on the wrong
        // interface and the per-interface 3922 rule never matches. Pin ARP to the owning NIC.
        String guestScript = "sysctl -w net.ipv4.conf.all.arp_ignore=1 >/dev/null 2>&1; "
                + "sysctl -w net.ipv4.conf.all.arp_announce=2 >/dev/null 2>&1; "
                + "dev=$(ip -o -4 addr show to " + controlIp + "/32 | awk \"{print \\$2; exit}\"); "
                + "[ -n \"$dev\" ] && { iptables -C INPUT -i \"$dev\" " + rule + " 2>/dev/null"
                + " || iptables -I INPUT -i \"$dev\" " + rule + "; "
                // also fix the persisted rule (like setup_sshd does for vmware/hyperv) so a
                // later iptables-restore does not drop 3922 back to the IP-less eth0
                + "[ -f /etc/iptables/rules.v4 ] && sed -i \"/3922/s/-i eth[0-9]*/-i $dev/\" /etc/iptables/rules.v4; }";
        String nodeCommand = "pvesh create /nodes/" + node + "/qemu/" + vmid
                + "/agent/exec --command /bin/sh --command -c --command '" + guestScript + "'";
        Pair<Boolean, String> result = executeOnNode(nodeCommand);
        if (!result.first()) {
            logger.warn("Could not open the system VM sshd firewall for " + vmName + " (vmid " + vmid
                    + ") via the guest agent, patch file delivery may time out: " + result.second());
        }
    }

    /**
     * The control NIC of Proxmox SSVM/CPVM instances intentionally carries no IP (see
     * ControlNetworkGuru), so fall through to the management NIC; virtual routers get a
     * management-range IP on their control NIC. Mirrors VmwareResource.getControlIp.
     */
    private String getControlIp(NicTO[] nics) {
        if (nics == null) {
            return null;
        }
        for (NicTO nic : nics) {
            if ((TrafficType.Management == nic.getType() || TrafficType.Control == nic.getType()) && nic.getIp() != null) {
                return nic.getIp();
            }
        }
        return null;
    }

    private void setVncPassword(String node, int vmid, String password) {
        try {
            // PVE runs its own VNC server on a unix socket, which qemu names "default"; the
            // TCP display we add through args: is always the second one, auto-named "vnc2".
            // Without -d the password lands on PVE's display and console auth on ours fails.
            String response = getApiClient().monitorCommand(node, vmid,
                    "set_password vnc " + password + " -d vnc2");
            if (StringUtils.isNotBlank(response)) {
                // the monitor endpoint reports HMP errors as body text with HTTP 200
                logger.warn("set_password on vmid " + vmid + " display vnc2 returned: " + response);
            }
        } catch (Exception e) {
            logger.warn("Unable to set VNC password of vmid " + vmid + " on node " + node, e);
        }
    }

    private void stopVmQuietly(String node, int vmid) {
        try {
            getApiClient().stopVm(node, vmid, 60000L);
        } catch (Exception e) {
            logger.warn("Cleanup stop of vmid " + vmid + " on node " + node + " failed", e);
        }
    }

    /**
     * Patches system VM boot arguments through the qemu guest agent, exactly
     * like the 4.21 KVM agent's scripts/vm/hypervisor/kvm/patch.sh: wait until
     * the guest agent answers a ping, then write the raw boot args to
     * /var/cache/cloud/cmdline, which cloud-early-config in the system VM
     * template waits for. The VM is created with agent=1, and Proxmox exposes
     * the guest agent natively via its REST API, so no node-side access is
     * needed.
     */
    private void patchSystemVm(VirtualMachineTO spec, String node, int vmid) {
        ProxmoxApiClient api = getApiClient();
        waitForGuestAgent(spec.getName(), node, vmid);
        writeSystemVmBootArgs(spec, node, vmid);

        // A reused root disk (any stop/start cycle — system VM IPs are re-allocated on every
        // start) still carries the previous boot's cmdline, and the guest's early-config
        // consumes it before the write above can land. The corrected cmdline is on disk now,
        // so one reboot makes the guest configure the IPs CloudStack actually allocated.
        String controlIp = getControlIp(spec.getNics());
        if (controlIp == null || waitForGuestControlIp(spec.getName(), node, vmid, controlIp)) {
            return;
        }
        logger.info("System VM {} (vmid {}) came up with stale boot args (control IP {} not applied);"
                + " rebooting it once to pick up the rewritten cmdline", spec.getName(), vmid, controlIp);
        api.rebootVm(node, vmid, getTaskTimeoutMs());
        waitForGuestAgent(spec.getName(), node, vmid);
        writeSystemVmBootArgs(spec, node, vmid);
        if (!waitForGuestControlIp(spec.getName(), node, vmid, controlIp)) {
            throw new CloudRuntimeException("System VM " + spec.getName() + " (vmid " + vmid
                    + ") did not apply its control IP " + controlIp + " even after a reboot with corrected boot args");
        }
    }

    private void waitForGuestAgent(String vmName, String node, int vmid) {
        ProxmoxApiClient api = getApiClient();
        long deadline = System.currentTimeMillis() + PATCH_AGENT_WAIT_MS;
        Exception lastPingError = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                api.post("/nodes/" + node + "/qemu/" + vmid + "/agent/ping", new HashMap<>());
                return;
            } catch (Exception e) {
                lastPingError = e;
                try {
                    Thread.sleep(PATCH_RETRY_SLEEP_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new CloudRuntimeException("Interrupted while waiting for the guest agent of system VM "
                            + vmName);
                }
            }
        }
        throw new CloudRuntimeException("Guest agent of system VM " + vmName + " (vmid " + vmid
                + ") did not come up within " + (PATCH_AGENT_WAIT_MS / 1000) + "s: "
                + (lastPingError != null ? lastPingError.getMessage() : "unknown"));
    }

    private void writeSystemVmBootArgs(VirtualMachineTO spec, String node, int vmid) {
        String cmdline = spec.getBootArgs() == null ? "" : spec.getBootArgs();
        ProxmoxApiClient api = getApiClient();
        CloudRuntimeException lastWriteError = null;
        for (int attempt = 1; attempt <= PATCH_RETRY_COUNT; attempt++) {
            try {
                Map<String, Object> params = new HashMap<>();
                params.put("file", "/var/cache/cloud/cmdline");
                params.put("content", cmdline);
                api.post("/nodes/" + node + "/qemu/" + vmid + "/agent/file-write", params);
                logger.info("Patched boot args of system VM " + spec.getName() + " via the qemu guest agent");
                return;
            } catch (Exception e) {
                lastWriteError = new CloudRuntimeException("guest-file-write of system VM " + spec.getName()
                        + " failed: " + e.getMessage(), e);
                logger.warn("Attempt " + attempt + " to patch system VM " + spec.getName()
                        + " via the guest agent failed", e);
                try {
                    Thread.sleep(PATCH_RETRY_SLEEP_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw lastWriteError != null ? lastWriteError
                : new CloudRuntimeException("Unable to patch boot args of system VM " + spec.getName());
    }

    /**
     * Waits until the guest reports the expected control IP on some interface. A fresh
     * template boot assigns it only after early-config finishes waiting for the (not yet
     * delivered) patch files, so the budget is generous. Returns false when the guest instead
     * settles on other addresses — the fingerprint of a stale cmdline from a reused disk.
     */
    private boolean waitForGuestControlIp(String vmName, String node, int vmid, String controlIp) {
        long deadline = System.currentTimeMillis() + GUEST_IP_WAIT_MS;
        int wrongConfigPolls = 0;
        while (System.currentTimeMillis() < deadline) {
            Set<String> ips = getGuestIpv4Addresses(node, vmid);
            if (ips.contains(controlIp)) {
                return true;
            }
            if (!ips.isEmpty()) {
                // the guest has configured global addresses that do not include the expected
                // control IP; require a few stable polls so a half-configured fresh boot
                // (interfaces coming up one by one) is not mistaken for stale config
                if (++wrongConfigPolls >= 3) {
                    return false;
                }
            }
            try {
                Thread.sleep(PATCH_RETRY_SLEEP_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new CloudRuntimeException("Interrupted while waiting for the control IP of system VM " + vmName);
            }
        }
        return false;
    }

    /** Global (non-loopback, non-link-local) IPv4 addresses currently applied inside the guest. */
    private Set<String> getGuestIpv4Addresses(String node, int vmid) {
        Set<String> ips = new HashSet<>();
        try {
            JsonElement data = getApiClient().get("/nodes/" + node + "/qemu/" + vmid + "/agent/network-get-interfaces");
            JsonObject result = data != null && data.isJsonObject() ? data.getAsJsonObject() : null;
            JsonElement ifaces = result != null ? result.get("result") : null;
            if (ifaces != null && ifaces.isJsonArray()) {
                for (JsonElement ifaceEl : ifaces.getAsJsonArray()) {
                    JsonElement addrs = ifaceEl.getAsJsonObject().get("ip-addresses");
                    if (addrs == null || !addrs.isJsonArray()) {
                        continue;
                    }
                    for (JsonElement addrEl : addrs.getAsJsonArray()) {
                        JsonObject addr = addrEl.getAsJsonObject();
                        if (!"ipv4".equals(jsonString(addr, "ip-address-type"))) {
                            continue;
                        }
                        String ip = jsonString(addr, "ip-address");
                        if (ip != null && !ip.startsWith("127.") && !ip.startsWith("169.254.")) {
                            ips.add(ip);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Could not read guest addresses of vmid {} via the agent: {}", vmid, e.getMessage());
        }
        return ips;
    }

    protected Answer execute(StopCommand cmd) {
        String vmName = cmd.getVmName();
        ProxmoxApiClient api = getApiClient();
        try {
            Integer vmid = findVmid(vmName);
            if (vmid == null) {
                return new StopAnswer(cmd, "VM " + vmName + " no longer exists", true);
            }
            String node = api.findNodeOfVm(vmid);
            if (node == null) {
                return new StopAnswer(cmd, "VM " + vmName + " not found in cluster", true);
            }
            JsonObject status = api.getVmStatus(node, vmid);
            if ("stopped".equalsIgnoreCase(jsonString(status, "status"))) {
                return new StopAnswer(cmd, "VM " + vmName + " is already stopped", true);
            }
            long timeoutMs = cmd.getWait() > 0 ? cmd.getWait() * 1000L : getTaskTimeoutMs();
            boolean stopped = false;
            if (!cmd.isForceStop()) {
                stopped = api.shutdownVm(node, vmid, timeoutMs);
                if (!stopped) {
                    logger.warn("Graceful shutdown of VM " + vmName + " timed out or failed, forcing stop");
                }
            }
            if (!stopped) {
                api.stopVm(node, vmid, getTaskTimeoutMs());
            }
            return new StopAnswer(cmd, null, true);
        } catch (Exception e) {
            logger.error("StopCommand failed for VM " + vmName, e);
            return new StopAnswer(cmd, e.getMessage(), false);
        }
    }

    protected Answer execute(RebootCommand cmd) {
        String vmName = cmd.getVmName();
        ProxmoxApiClient api = getApiClient();
        try {
            Integer vmid = findVmid(vmName);
            if (vmid == null) {
                return new RebootAnswer(cmd, "VM " + vmName + " no longer exists", false);
            }
            String node = api.findNodeOfVm(vmid);
            if (node == null) {
                return new RebootAnswer(cmd, "VM " + vmName + " not found in cluster", false);
            }
            api.rebootVm(node, vmid, getTaskTimeoutMs());
            if (VirtualMachineName.isValidConsoleProxyName(vmName) || VirtualMachineName.isValidSecStorageVmName(vmName, null)) {
                reopenControlSshAfterReboot(vmName, node, vmid);
            }
            return new RebootAnswer(cmd, "reboot succeeded", true);
        } catch (Exception e) {
            logger.error("RebootCommand failed for VM " + vmName, e);
            return new RebootAnswer(cmd, e.getMessage(), false);
        }
    }

    /**
     * A rebooted CPVM/SSVM re-runs the template's early-config, which regenerates the guest
     * firewall with sshd port 3922 pinned back to the IP-less link-local interface (the KVM
     * layout — virt-what reports "kvm" on Proxmox), wiping the control-interface rule that
     * StartCommand inserted. The agent still connects (outbound), so the VM looks healthy,
     * but the management server can no longer SSH in. Re-open the firewall after every
     * reboot; RebootCommand does not carry the NIC layout, so the control IP is recovered
     * from the guest's own on-disk boot args (which a plain reboot preserves).
     */
    private void reopenControlSshAfterReboot(String vmName, String node, int vmid) {
        try {
            waitForGuestAgent(vmName, node, vmid);
            String controlIp = readGuestControlIp(node, vmid);
            if (controlIp == null) {
                logger.warn("Could not determine the control IP of rebooted system VM {} (vmid {}) from its boot"
                        + " args; the management server may be unable to SSH into it until it is stopped and started",
                        vmName, vmid);
                return;
            }
            // early-config wipes the firewall late in the boot, so an insert can land too soon
            // and be flushed again; retry until sshd on the control IP actually accepts
            for (int count = 0; count < 24; count++) {
                openSshdFirewallForControlIp(vmName, node, vmid, controlIp);
                if (_vrResource.connect(controlIp, 1, 5000)) {
                    logger.info("Reopened the control SSH firewall of system VM {} (vmid {}) on {} after reboot",
                            vmName, vmid, controlIp);
                    return;
                }
            }
            logger.warn("SSH to rebooted system VM {} (vmid {}) on control IP {} still fails after reopening its"
                    + " firewall", vmName, vmid, controlIp);
        } catch (Exception e) {
            logger.warn("Could not reopen the control SSH firewall of system VM " + vmName + " (vmid " + vmid
                    + ") after reboot", e);
        }
    }

    /**
     * Reads the control/management IP a system VM is actually configured with from its
     * on-disk /var/cache/cloud/cmdline: the ethNip= argument that falls inside mgmtcidr=.
     * The control NIC itself is written as eth0ip=0.0.0.0 on Proxmox (see ControlNetworkGuru),
     * so the management-network address is the one the management server can SSH to.
     */
    private String readGuestControlIp(String node, int vmid) {
        try {
            JsonElement data = getApiClient().get("/nodes/" + node + "/qemu/" + vmid + "/agent/file-read?file="
                    + URLEncoder.encode("/var/cache/cloud/cmdline", StandardCharsets.UTF_8));
            String content = data != null && data.isJsonObject() ? jsonString(data.getAsJsonObject(), "content") : null;
            if (content == null) {
                return null;
            }
            String mgmtCidr = null;
            String eth1Ip = null;
            List<String> nicIps = new ArrayList<>();
            for (String arg : content.trim().split("\\s+")) {
                int eq = arg.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = arg.substring(0, eq);
                String value = arg.substring(eq + 1);
                if ("mgmtcidr".equals(key)) {
                    mgmtCidr = value;
                } else if (key.matches("eth\\d+ip") && !"0.0.0.0".equals(value)) {
                    nicIps.add(value);
                    if ("eth1ip".equals(key)) {
                        eth1Ip = value;
                    }
                }
            }
            // The management NIC of a CPVM/SSVM is deviceId 1 by construction (control eth0,
            // management eth1, public eth2), so eth1ip is authoritative. Only fall back to
            // matching against mgmtcidr when it is absent — on flat networks the public IP
            // lives in the same subnet as the management range, making the cidr test ambiguous.
            if (eth1Ip != null) {
                return eth1Ip;
            }
            if (mgmtCidr != null) {
                for (String ip : nicIps) {
                    if (NetUtils.isIpWithInCidrRange(ip, mgmtCidr)) {
                        return ip;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            logger.debug("Could not read the boot args of vmid {} via the guest agent: {}", vmid, e.getMessage());
            return null;
        }
    }

    protected Answer execute(RebootRouterCommand cmd) {
        RebootAnswer answer = (RebootAnswer) execute((RebootCommand) cmd);
        if (answer.getResult()) {
            String connectResult = connect(cmd.getVmName(), cmd.getPrivateIpAddress(), DEFAULT_DOMR_SSH_PORT);
            networkUsage(cmd.getPrivateIpAddress(), "create", null);
            if (connectResult == null) {
                return answer;
            }
            return new Answer(cmd, false, connectResult);
        }
        return answer;
    }

    protected Answer execute(CheckVirtualMachineCommand cmd) {
        ProxmoxApiClient api = getApiClient();
        try {
            Integer vmid = findVmid(cmd.getVmName());
            if (vmid == null) {
                return new CheckVirtualMachineAnswer(cmd, PowerState.PowerUnknown, null);
            }
            String node = api.findNodeOfVm(vmid);
            if (node == null) {
                return new CheckVirtualMachineAnswer(cmd, PowerState.PowerUnknown, null);
            }
            JsonObject status = api.getVmStatus(node, vmid);
            PowerState state = toPowerState(jsonString(status, "status"));
            Integer vncPort = state == PowerState.PowerOn ? vncPortOf(vmid) : null;
            return new CheckVirtualMachineAnswer(cmd, state, vncPort);
        } catch (Exception e) {
            logger.error("CheckVirtualMachineCommand failed for VM " + cmd.getVmName(), e);
            return new CheckVirtualMachineAnswer(cmd, e.getMessage());
        }
    }

    private static int vncPortOf(int vmid) {
        return VNC_BASE_PORT + (vmid % VNC_DISPLAY_MODULO);
    }

    protected Answer execute(PrepareForMigrationCommand cmd) {
        // Shared storage; nothing to prepare on the target node.
        return new PrepareForMigrationAnswer(cmd);
    }

    protected Answer execute(MigrateCommand cmd) {
        String vmName = cmd.getVmName();
        ProxmoxApiClient api = getApiClient();
        try {
            Integer vmid = findVmid(vmName);
            if (vmid == null) {
                return new MigrateAnswer(cmd, false, "VM " + vmName + " no longer exists", null);
            }
            String sourceNode = api.findNodeOfVm(vmid);
            if (sourceNode == null) {
                return new MigrateAnswer(cmd, false, "VM " + vmName + " not found in cluster", null);
            }
            String targetNode = findNodeByAddress(cmd.getDestinationIp());
            if (targetNode == null) {
                return new MigrateAnswer(cmd, false, "Unable to resolve a Proxmox node with address "
                        + cmd.getDestinationIp(), null);
            }
            if (targetNode.equals(sourceNode)) {
                return new MigrateAnswer(cmd, true, "VM already on target node", null);
            }
            api.migrateVm(sourceNode, vmid, targetNode, true, _migrateWithLocalDisks, getTaskTimeoutMs());
            // The VNC password is runtime state of the source QEMU process and does not
            // survive live migration; the destination starts with password=on but unset.
            if (cmd.getVirtualMachine() != null && cmd.getVirtualMachine().getVncPassword() != null) {
                setVncPassword(targetNode, vmid, cmd.getVirtualMachine().getVncPassword());
            }
            return new MigrateAnswer(cmd, true, "migration succeeded", null);
        } catch (Exception e) {
            logger.error("MigrateCommand failed for VM " + vmName, e);
            return new MigrateAnswer(cmd, false, e.getMessage(), null);
        }
    }

    /** Resolves a cluster node name from its management IP via /cluster/status. */
    private String findNodeByAddress(String address) {
        if (StringUtils.isBlank(address)) {
            return null;
        }
        JsonElement data = getApiClient().get("/cluster/status");
        if (data == null || !data.isJsonArray()) {
            return null;
        }
        for (JsonElement e : data.getAsJsonArray()) {
            JsonObject entry = e.getAsJsonObject();
            if ("node".equals(jsonString(entry, "type")) && address.equals(jsonString(entry, "ip"))) {
                return jsonString(entry, "name");
            }
        }
        return null;
    }

    /**
     * Whether a PVE cluster member is online according to the corosync view served by this
     * resource's (healthy) node. A node absent from the listing is not a cluster member and
     * counts as offline.
     */
    private boolean isNodeOnline(String node) {
        JsonElement data = getApiClient().get("/cluster/status");
        if (data != null && data.isJsonArray()) {
            for (JsonElement e : data.getAsJsonArray()) {
                JsonObject entry = e.getAsJsonObject();
                if ("node".equals(jsonString(entry, "type")) && node.equals(jsonString(entry, "name"))) {
                    return jsonLong(entry, "online", 0) == 1;
                }
            }
        }
        return false;
    }

    /**
     * Moves a vmid's config file from a dead node's pmxcfs directory to this node's, the same
     * config-steal PVE HA recovery performs. Only safe while the cluster is quorate and the
     * owning node is offline; the caller checks both.
     */
    private void stealVmConfig(String deadNode, int vmid) {
        String conf = vmid + ".conf";
        Pair<Boolean, String> result = executeOnNode("mv /etc/pve/nodes/" + deadNode + "/qemu-server/" + conf
                + " /etc/pve/nodes/" + _nodeName + "/qemu-server/" + conf);
        if (!result.first()) {
            throw new CloudRuntimeException("Unable to take over vmid " + vmid + " from offline node " + deadNode
                    + ": " + result.second());
        }
        logger.info("Took over vmid " + vmid + " from offline node " + deadNode + " onto " + _nodeName);
    }

    protected Answer execute(CheckOnHostCommand cmd) {
        try {
            ProxmoxApiClient api = getApiClient();
            if (!api.isQuorate()) {
                return new CheckOnHostAnswer(cmd, null, "Cluster is not quorate; host state unknown");
            }
            String targetIp = null;
            if (cmd.getHost() != null && cmd.getHost().getPrivateNetwork() != null) {
                targetIp = cmd.getHost().getPrivateNetwork().getIp();
            }
            if (targetIp == null) {
                return new CheckOnHostAnswer(cmd, null, "No private IP for host to check");
            }
            Boolean alive = null;
            JsonElement data = api.get("/cluster/status");
            if (data != null && data.isJsonArray()) {
                for (JsonElement e : data.getAsJsonArray()) {
                    JsonObject entry = e.getAsJsonObject();
                    if ("node".equals(jsonString(entry, "type")) && targetIp.equals(jsonString(entry, "ip"))) {
                        alive = jsonLong(entry, "online", 0) == 1;
                        break;
                    }
                }
            }
            return new CheckOnHostAnswer(cmd, alive, "checked via PVE API");
        } catch (Exception e) {
            logger.error("CheckOnHostCommand failed", e);
            return new CheckOnHostAnswer(cmd, e.getMessage());
        }
    }

    protected Answer execute(FenceCommand cmd) {
        String vmName = cmd.getVmName();
        ProxmoxApiClient api = getApiClient();
        try {
            if (!api.isQuorate()) {
                return new FenceAnswer(cmd, false, "Proxmox cluster is not quorate, cannot safely fence VM " + vmName);
            }
            Integer vmid = findVmid(vmName);
            if (vmid == null) {
                return new FenceAnswer(cmd);
            }
            String node = api.findNodeOfVm(vmid);
            if (node == null) {
                return new FenceAnswer(cmd);
            }
            if (!isNodeOnline(node)) {
                // Node-scoped API calls are proxied to the owning node, so a stop request
                // for a VM on a dead node fails forever and HA never gets past fencing.
                // pmxcfs pins a vmid to its node — the VM cannot have moved — and losing
                // quorum cut the node off from pmxcfs; storage safety on restart comes from
                // RBD exclusive-lock stealing. Declare the fence done.
                logger.info("Node " + node + " owning VM " + vmName + " (vmid " + vmid
                        + ") is offline in the quorate cluster view, considering the VM fenced");
                return new FenceAnswer(cmd);
            }
            try {
                JsonObject status = api.getVmStatus(node, vmid);
                if ("stopped".equalsIgnoreCase(jsonString(status, "status"))) {
                    return new FenceAnswer(cmd);
                }
            } catch (Exception e) {
                logger.debug("Unable to query status of VM " + vmName + " on node " + node + ", attempting stop", e);
            }
            api.stopVm(node, vmid, getTaskTimeoutMs());
            return new FenceAnswer(cmd);
        } catch (Exception e) {
            logger.error("FenceCommand failed for VM " + vmName, e);
            return new FenceAnswer(cmd, false, e.getMessage());
        }
    }

    protected Answer execute(MaintainCommand cmd) {
        return new MaintainAnswer(cmd);
    }

    protected Answer execute(GetVncPortCommand cmd) {
        try {
            Integer vmid = findVmid(cmd.getName());
            if (vmid == null) {
                return new GetVncPortAnswer(cmd, "Unable to find VM " + cmd.getName());
            }
            return new GetVncPortAnswer(cmd, _nodeAddress, vncPortOf(vmid));
        } catch (Exception e) {
            logger.error("GetVncPortCommand failed for VM " + cmd.getName(), e);
            return new GetVncPortAnswer(cmd, e.getMessage());
        }
    }

    protected Answer execute(CheckNetworkCommand cmd) {
        // PVE bridges are provisioned by the administrator on the node.
        return new CheckNetworkAnswer(cmd, true, null);
    }

    protected Answer execute(ModifyStoragePoolCommand cmd) {
        try {
            StorageFilerTO pool = cmd.getPool();
            String storageId = lastPathSegment(pool.getPath());
            JsonObject status = getApiClient().getStorageStatus(_nodeName, storageId);
            boolean usable = jsonLong(status, "enabled", 1) == 1 && jsonLong(status, "active", 1) == 1;
            if (!usable) {
                return new ModifyStoragePoolAnswer(cmd, false,
                        "Proxmox storage " + storageId + " is not enabled/active on node " + _nodeName);
            }
            long capacity = jsonLong(status, "total", 0);
            long available = jsonLong(status, "avail", 0);
            return new ModifyStoragePoolAnswer(cmd, capacity, available, new HashMap<String, TemplateProp>());
        } catch (Exception e) {
            logger.error("ModifyStoragePoolCommand failed for pool " + cmd.getPool().getUuid(), e);
            return new ModifyStoragePoolAnswer(cmd, false, e.getMessage());
        }
    }

    protected Answer execute(DeleteStoragePoolCommand cmd) {
        // PVE storage is administrator-managed; detaching a pool is a no-op.
        return new Answer(cmd, true, "success");
    }

    protected Answer execute(GetStorageStatsCommand cmd) {
        try {
            String storageId = null;
            DataStoreTO store = cmd.getStore();
            if (store instanceof org.apache.cloudstack.storage.to.PrimaryDataStoreTO) {
                storageId = getPveStorageId(store);
            }
            if (storageId == null && StringUtils.isNotBlank(cmd.getLocalPath())) {
                storageId = lastPathSegment(cmd.getLocalPath());
            }
            if (StringUtils.isBlank(storageId)) {
                return new GetStorageStatsAnswer(cmd, "Unable to resolve Proxmox storage id for stats request");
            }
            JsonObject status = getApiClient().getStorageStatus(_nodeName, storageId);
            long capacity = jsonLong(status, "total", 0);
            long used = jsonLong(status, "used", 0);
            return new GetStorageStatsAnswer(cmd, capacity, used);
        } catch (Exception e) {
            logger.error("GetStorageStatsCommand failed", e);
            return new GetStorageStatsAnswer(cmd, e.getMessage());
        }
    }

    protected Answer execute(GetVolumeStatsCommand cmd) {
        HashMap<String, VolumeStatsEntry> stats = new HashMap<String, VolumeStatsEntry>();
        Map<String, JsonArray> contentCache = new HashMap<String, JsonArray>();
        ProxmoxApiClient api = getApiClient();
        for (String volid : cmd.getVolumeUuids()) {
            try {
                int idx = volid.indexOf(':');
                if (idx <= 0) {
                    continue;
                }
                String storage = volid.substring(0, idx);
                JsonArray content = contentCache.get(storage);
                if (content == null) {
                    content = api.listStorageContent(_nodeName, storage, null);
                    contentCache.put(storage, content);
                }
                if (content == null) {
                    continue;
                }
                for (JsonElement e : content) {
                    JsonObject item = e.getAsJsonObject();
                    if (volid.equals(jsonString(item, "volid"))) {
                        long size = jsonLong(item, "size", 0);
                        long used = jsonLong(item, "used", size);
                        stats.put(volid, new VolumeStatsEntry(volid, used, size));
                        break;
                    }
                }
            } catch (Exception e) {
                logger.debug("Unable to fetch stats of volume " + volid, e);
            }
        }
        return new GetVolumeStatsAnswer(cmd, "", stats);
    }

    protected Answer execute(ResizeVolumeCommand cmd) {
        String volid = cmd.getPath();
        long newSize = cmd.getNewSize();
        long currentSize = cmd.getCurrentSize();
        if (newSize < currentSize) {
            return new ResizeVolumeAnswer(cmd, false, "Proxmox does not support shrinking volumes");
        }
        if (newSize == currentSize) {
            return new ResizeVolumeAnswer(cmd, true, "", newSize);
        }
        ProxmoxApiClient api = getApiClient();
        try {
            if (StringUtils.isNotBlank(cmd.getInstanceName())) {
                Integer vmid = findVmid(cmd.getInstanceName());
                if (vmid != null) {
                    String node = api.findNodeOfVm(vmid);
                    if (node != null) {
                        String diskKey = findDiskKey(api.getVmConfig(node, vmid), volid);
                        if (diskKey != null) {
                            api.resizeDisk(node, vmid, diskKey, newSize, true);
                            return new ResizeVolumeAnswer(cmd, true, "", newSize);
                        }
                    }
                }
            }
            // Volume not attached (or VM gone): resize the image directly on the node,
            // with the resize tool picked by the storage type backing the volume.
            String path = api.getVolumePath(_nodeName, volid);
            if (StringUtils.isBlank(path)) {
                return new ResizeVolumeAnswer(cmd, false, "Unable to resolve path of volume " + volid);
            }
            if (path.contains("'")) {
                return new ResizeVolumeAnswer(cmd, false, "Refusing to resize volume with unsafe path " + path);
            }
            int colon = volid.indexOf(':');
            String storageType = colon > 0 ? getStorageType(volid.substring(0, colon)) : null;
            Pair<Boolean, String> result;
            if ("rbd".equals(storageType)) {
                result = executeOnNode(ProxmoxStorageProcessor.buildRbdResizeCommand(path, newSize), 300);
                if (!result.first()) {
                    return new ResizeVolumeAnswer(cmd, false, "rbd resize failed: " + result.second());
                }
            } else if (ProxmoxStorageProcessor.isRawBlockStorageType(storageType)) {
                return new ResizeVolumeAnswer(cmd, false, String.format(
                        "Resizing detached volumes on PVE storage type '%s' is not supported by the Proxmox plugin yet; attach the volume to an instance and retry", storageType));
            } else {
                result = executeOnNode("qemu-img resize '" + path + "' " + newSize, 300);
                if (!result.first()) {
                    return new ResizeVolumeAnswer(cmd, false, "qemu-img resize failed: " + result.second());
                }
            }
            return new ResizeVolumeAnswer(cmd, true, "", newSize);
        } catch (Exception e) {
            logger.error("ResizeVolumeCommand failed for volume " + volid, e);
            return new ResizeVolumeAnswer(cmd, false, e.getMessage());
        }
    }

    /** Finds the config key (scsiN/virtioN/ideN/sataN) referencing the given volid, or null. */
    private String findDiskKey(JsonObject config, String volid) {
        if (config == null || volid == null) {
            return null;
        }
        for (Map.Entry<String, JsonElement> entry : config.entrySet()) {
            if (!entry.getKey().matches("(scsi|virtio|ide|sata)\\d+")) {
                continue;
            }
            try {
                String value = entry.getValue().getAsString();
                if (value.equals(volid) || value.startsWith(volid + ",")) {
                    return entry.getKey();
                }
            } catch (Exception e) {
                logger.trace("Skipping non-string config entry " + entry.getKey());
            }
        }
        return null;
    }

    protected Answer execute(ModifySshKeysCommand cmd) {
        // System VM keys live on the management server; nothing to do on the node.
        return new Answer(cmd, true, null);
    }

    protected Answer execute(CheckSshCommand cmd) {
        String vmName = cmd.getName();
        if (logger.isDebugEnabled()) {
            logger.debug("Ping command port, " + cmd.getIp() + ":" + cmd.getPort());
        }
        String result = connect(vmName, cmd.getIp(), cmd.getPort());
        if (result != null) {
            String message = "Can not ping System VM " + vmName + ", due to: " + result;
            logger.error(message);
            return new CheckSshAnswer(cmd, message);
        }
        if (logger.isDebugEnabled()) {
            logger.debug("Ping command port succeeded for vm " + vmName);
        }
        return new CheckSshAnswer(cmd);
    }

    /**
     * Waits for a TCP port of a (system) VM to become reachable. Returns null
     * on success, an error message on timeout. Mirrors VmwareResource.connect().
     */
    protected String connect(String vmName, String ipAddress, int port) {
        long startTick = System.currentTimeMillis();
        int retry = CONNECT_RETRIES;
        while (System.currentTimeMillis() - startTick <= CONNECT_OPS_TIMEOUT_MS || --retry > 0) {
            logger.info("Trying to connect to " + ipAddress + ":" + port);
            try (SocketChannel sch = SocketChannel.open()) {
                sch.configureBlocking(true);
                sch.socket().setSoTimeout(5000);
                sch.connect(new InetSocketAddress(ipAddress, port));
                return null;
            } catch (IOException e) {
                logger.info("Could not connect to " + ipAddress + ":" + port + " due to " + e);
                if (e instanceof ConnectException) {
                    // Connection refused while the VM boots: burn less retry quota.
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return "Interrupted while connecting to " + ipAddress;
                    }
                }
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return "Interrupted while connecting to " + ipAddress;
            }
        }
        logger.info("Unable to connect to " + ipAddress + ":" + port);
        return "Unable to connect";
    }

    protected Answer execute(PlugNicCommand cmd) {
        try {
            Integer vmid = findVmid(cmd.getVmName());
            if (vmid == null) {
                return new PlugNicAnswer(cmd, false, "VM " + cmd.getVmName() + " no longer exists");
            }
            String node = getApiClient().findNodeOfVm(vmid);
            if (node == null) {
                return new PlugNicAnswer(cmd, false, "VM " + cmd.getVmName() + " not found in cluster");
            }
            plugNicInternal(node, vmid, cmd.getNic());
            return new PlugNicAnswer(cmd, true, "success");
        } catch (Exception e) {
            logger.error("PlugNicCommand failed for VM " + cmd.getVmName(), e);
            return new PlugNicAnswer(cmd, false, "Unable to execute PlugNicCommand due to " + e.getMessage());
        }
    }

    private void plugNicInternal(String node, int vmid, NicTO nic) {
        ProxmoxApiClient api = getApiClient();
        JsonObject config = api.getVmConfig(node, vmid);
        String key = findNicKeyByMac(config, nic.getMac());
        if (key == null) {
            key = findFreeNicKey(config);
        }
        Map<String, Object> update = new HashMap<String, Object>();
        update.put(key, ProxmoxVmConfigBuilder.buildNicSpec(nic, resolveBridge(nic)));
        api.setVmConfig(node, vmid, update);
    }

    protected Answer execute(UnPlugNicCommand cmd) {
        try {
            Integer vmid = findVmid(cmd.getVmName());
            if (vmid == null) {
                return new UnPlugNicAnswer(cmd, true, "VM " + cmd.getVmName() + " no longer exists");
            }
            ProxmoxApiClient api = getApiClient();
            String node = api.findNodeOfVm(vmid);
            if (node == null) {
                return new UnPlugNicAnswer(cmd, true, "VM " + cmd.getVmName() + " not found in cluster");
            }
            JsonObject config = api.getVmConfig(node, vmid);
            String key = findNicKeyByMac(config, cmd.getNic().getMac());
            if (key == null) {
                return new UnPlugNicAnswer(cmd, true, "NIC already unplugged");
            }
            Map<String, Object> update = new HashMap<String, Object>();
            update.put("delete", key);
            api.setVmConfig(node, vmid, update);
            return new UnPlugNicAnswer(cmd, true, "success");
        } catch (Exception e) {
            logger.error("UnPlugNicCommand failed for VM " + cmd.getVmName(), e);
            return new UnPlugNicAnswer(cmd, false, "Unable to execute UnPlugNicCommand due to " + e.getMessage());
        }
    }

    private String findNicKeyByMac(JsonObject config, String mac) {
        if (config == null || StringUtils.isBlank(mac)) {
            return null;
        }
        String needle = mac.toLowerCase();
        for (Map.Entry<String, JsonElement> entry : config.entrySet()) {
            if (!entry.getKey().matches("net\\d+")) {
                continue;
            }
            try {
                if (entry.getValue().getAsString().toLowerCase().contains(needle)) {
                    return entry.getKey();
                }
            } catch (Exception e) {
                logger.trace("Skipping non-string config entry " + entry.getKey());
            }
        }
        return null;
    }

    private String findFreeNicKey(JsonObject config) {
        for (int i = 0; i < MAX_NIC_SLOTS; i++) {
            if (config == null || !config.has("net" + i)) {
                return "net" + i;
            }
        }
        throw new CloudRuntimeException("No free network device slot on VM");
    }

    protected Answer execute(CreateVMSnapshotCommand cmd) {
        try {
            Integer vmid = findVmid(cmd.getVmName());
            if (vmid == null) {
                return new CreateVMSnapshotAnswer(cmd, false, "VM " + cmd.getVmName() + " no longer exists");
            }
            ProxmoxApiClient api = getApiClient();
            String node = api.findNodeOfVm(vmid);
            if (node == null) {
                return new CreateVMSnapshotAnswer(cmd, false, "VM " + cmd.getVmName() + " not found in cluster");
            }
            boolean withMemory = cmd.getTarget().getType() == VMSnapshot.Type.DiskAndMemory;
            api.createSnapshot(node, vmid, cmd.getTarget().getSnapshotName(), cmd.getTarget().getDescription(),
                    withMemory, getTaskTimeoutMs());
            return new CreateVMSnapshotAnswer(cmd, cmd.getTarget(), cmd.getVolumeTOs());
        } catch (Exception e) {
            logger.error("CreateVMSnapshotCommand failed for VM " + cmd.getVmName(), e);
            return new CreateVMSnapshotAnswer(cmd, false, e.getMessage());
        }
    }

    protected Answer execute(DeleteVMSnapshotCommand cmd) {
        try {
            Integer vmid = findVmid(cmd.getVmName());
            if (vmid == null) {
                return new DeleteVMSnapshotAnswer(cmd, cmd.getVolumeTOs());
            }
            ProxmoxApiClient api = getApiClient();
            String node = api.findNodeOfVm(vmid);
            if (node == null) {
                return new DeleteVMSnapshotAnswer(cmd, cmd.getVolumeTOs());
            }
            api.deleteSnapshot(node, vmid, cmd.getTarget().getSnapshotName(), getTaskTimeoutMs());
            return new DeleteVMSnapshotAnswer(cmd, cmd.getVolumeTOs());
        } catch (Exception e) {
            logger.error("DeleteVMSnapshotCommand failed for VM " + cmd.getVmName(), e);
            return new DeleteVMSnapshotAnswer(cmd, false, e.getMessage());
        }
    }

    protected Answer execute(RevertToVMSnapshotCommand cmd) {
        try {
            Integer vmid = findVmid(cmd.getVmName());
            if (vmid == null) {
                return new RevertToVMSnapshotAnswer(cmd, false, "VM " + cmd.getVmName() + " no longer exists");
            }
            ProxmoxApiClient api = getApiClient();
            String node = api.findNodeOfVm(vmid);
            if (node == null) {
                return new RevertToVMSnapshotAnswer(cmd, false, "VM " + cmd.getVmName() + " not found in cluster");
            }
            api.rollbackSnapshot(node, vmid, cmd.getTarget().getSnapshotName(), getTaskTimeoutMs());
            PowerState state = PowerState.PowerOff;
            try {
                JsonObject status = api.getVmStatus(node, vmid);
                state = toPowerState(jsonString(status, "status"));
            } catch (Exception e) {
                logger.debug("Unable to query power state of VM " + cmd.getVmName() + " after snapshot revert", e);
            }
            return new RevertToVMSnapshotAnswer(cmd, cmd.getVolumeTOs(), state);
        } catch (Exception e) {
            logger.error("RevertToVMSnapshotCommand failed for VM " + cmd.getVmName(), e);
            return new RevertToVMSnapshotAnswer(cmd, false, e.getMessage());
        }
    }

    /**
     * Sent by ProxmoxGuru.finalizeExpunge when a CloudStack instance is expunged: removes the
     * leftover PVE VM definition (PVE VM configs are persistent, VMware model). By this point
     * CloudStack has detached the data disks (handing them to the template-holder vmid) and
     * freed the root disk, so the config should reference no owned volumes any more. That is
     * re-checked before destroying, because PVE's destroy also frees every owned volume still
     * referenced in the config. On any doubt the VM shell is left behind and success is
     * returned: a leaked, stopped VM definition is harmless, a destroyed volume is not.
     */
    protected Answer execute(UnregisterVMCommand cmd) {
        String vmName = cmd.getVmName();
        ProxmoxApiClient api = getApiClient();
        try {
            Integer vmid = findVmid(vmName);
            String node = vmid != null ? api.findNodeOfVm(vmid) : null;
            if (vmid == null || node == null) {
                return new Answer(cmd, true, "VM " + vmName + " already gone from PVE");
            }
            JsonObject status = api.getVmStatus(node, vmid);
            if ("running".equalsIgnoreCase(jsonString(status, "status"))) {
                logger.warn("Not destroying PVE VM {} ({}): it is unexpectedly still running; leaving the definition behind", vmid, vmName);
                return new Answer(cmd, true, "left running PVE VM " + vmid + " in place");
            }
            String ownedVolid = findReferencedOwnedVolume(api, node, vmid);
            if (ownedVolid != null) {
                logger.warn("Not destroying PVE VM {} ({}): its config still references owned volume {} which PVE would destroy with it; leaving the definition behind",
                        vmid, vmName, ownedVolid);
                return new Answer(cmd, true, "left PVE VM " + vmid + " in place to protect volume " + ownedVolid);
            }
            api.destroyVm(node, vmid, getTaskTimeoutMs());
            logger.debug("Destroyed PVE VM {} of expunged instance {}", vmid, vmName);
            return new Answer(cmd, true, "destroyed PVE VM " + vmid);
        } catch (Exception e) {
            logger.warn("Unable to destroy the PVE VM of expunged instance " + vmName + "; leaving it behind", e);
            return new Answer(cmd, true, "left PVE VM behind: " + e.getMessage());
        }
    }

    /**
     * Lists the qemu VMs on this node that CloudStack does not manage, for the
     * Import-Export Instances feature. Templates are excluded, and so are VMs without a
     * PVE name attribute: the name is the key every other part of this plugin resolves a
     * VM by (power reports, command dispatch), so a nameless VM cannot be safely adopted —
     * give it a name in PVE first.
     */
    protected Answer execute(GetUnmanagedInstancesCommand cmd) {
        ProxmoxApiClient api = getApiClient();
        HashMap<String, UnmanagedInstanceTO> unmanagedInstances = new HashMap<>();
        try {
            JsonArray vms = api.listNodeVms(_nodeName);
            for (JsonElement element : vms) {
                JsonObject vm = element.getAsJsonObject();
                if (!vm.has("vmid") || jsonLong(vm, "template", 0) == 1) {
                    continue;
                }
                int vmid = vm.get("vmid").getAsInt();
                String name = jsonString(vm, "name");
                if (StringUtils.isBlank(name)) {
                    logger.debug("Skipping unmanaged PVE VM {} on {}: it has no name attribute", vmid, _nodeName);
                    continue;
                }
                if (StringUtils.isNotBlank(cmd.getInstanceName()) && !cmd.getInstanceName().equals(name)) {
                    continue;
                }
                if (cmd.hasManagedInstance(name)) {
                    continue;
                }
                UnmanagedInstanceTO instance = getUnmanagedInstance(api, vmid, name, jsonString(vm, "status"));
                if (instance != null) {
                    unmanagedInstances.put(name, instance);
                }
            }
            return new GetUnmanagedInstancesAnswer(cmd, "OK", unmanagedInstances);
        } catch (Exception e) {
            logger.error("GetUnmanagedInstancesCommand failed on node " + _nodeName, e);
            return new GetUnmanagedInstancesAnswer(cmd, e.getMessage());
        }
    }

    protected Answer execute(PrepareUnmanageVMInstanceCommand cmd) {
        String instanceName = cmd.getInstanceName();
        logger.debug("Verifying VM {} exists in the Proxmox cluster before unmanaging it", instanceName);
        try {
            if (findVmid(instanceName) == null) {
                return new PrepareUnmanageVMInstanceAnswer(cmd, false, "VM " + instanceName + " not found in the Proxmox cluster");
            }
            return new PrepareUnmanageVMInstanceAnswer(cmd, true, "OK");
        } catch (Exception e) {
            logger.error("PrepareUnmanageVMInstanceCommand failed for VM " + instanceName, e);
            return new PrepareUnmanageVMInstanceAnswer(cmd, false, e.getMessage());
        }
    }

    /**
     * Builds the transfer object describing one unmanaged PVE VM from its config. The
     * vmid travels in the path field — the import flow persists it as the {@code
     * proxmox.vmid} VM detail, because an imported vmid does not follow the plugin's
     * vmid-from-instance-name convention. Datastore host/path mirror how PreSetup
     * primary storage pools are registered (localhost + /&lt;pve-storage-id&gt;), so the
     * management server can match each disk to its pool exactly.
     */
    private UnmanagedInstanceTO getUnmanagedInstance(ProxmoxApiClient api, int vmid, String name, String status) {
        try {
            JsonObject config = api.getVmConfig(_nodeName, vmid);
            UnmanagedInstanceTO instance = new UnmanagedInstanceTO();
            instance.setName(name);
            instance.setInternalCSName(name);
            instance.setPath(String.valueOf(vmid));
            instance.setPowerState("running".equalsIgnoreCase(status)
                    ? UnmanagedInstanceTO.PowerState.PowerOn : UnmanagedInstanceTO.PowerState.PowerOff);
            int cores = (int) jsonLong(config, "cores", 1);
            int sockets = (int) jsonLong(config, "sockets", 1);
            instance.setCpuCores(cores * sockets);
            instance.setCpuCoresPerSocket(cores);
            instance.setMemory((int) jsonLong(config, "memory", 512));
            instance.setOperatingSystem(pveOsTypeToDisplayName(jsonString(config, "ostype")));
            instance.setOperatingSystemId(jsonString(config, "ostype"));
            instance.setHypervisorType(HypervisorType.Proxmox.name());
            instance.setClusterName(api.getClusterName());
            instance.setHostName(_nodeName);
            if ("ovmf".equalsIgnoreCase(jsonString(config, "bios"))) {
                instance.setBootType("UEFI");
                String efidisk = jsonString(config, "efidisk0");
                instance.setBootMode(efidisk != null && efidisk.contains("pre-enrolled-keys=1") ? "SECURE" : "LEGACY");
            } else {
                instance.setBootType("BIOS");
                instance.setBootMode("LEGACY");
            }
            instance.setDisks(getUnmanagedInstanceDisks(config, vmid, name));
            instance.setNics(getUnmanagedInstanceNics(config));
            return instance;
        } catch (Exception e) {
            logger.warn("Unable to describe unmanaged PVE VM {} ({}) on {}", vmid, name, _nodeName, e);
            return null;
        }
    }

    private List<UnmanagedInstanceTO.Disk> getUnmanagedInstanceDisks(JsonObject config, int vmid, String vmName) {
        List<UnmanagedInstanceTO.Disk> disks = new ArrayList<>();
        for (String key : sortedConfigKeys(config, "(scsi|virtio|sata|ide)\\d+")) {
            String value = config.get(key).getAsString();
            if (value.contains("media=cdrom")) {
                continue;
            }
            String volid = value.split(",")[0].trim();
            int colon = volid.indexOf(':');
            if (colon <= 0) {
                logger.warn("Skipping disk {} of unmanaged PVE VM {} ({}): '{}' is not a storage-backed volume", key, vmid, vmName, volid);
                continue;
            }
            UnmanagedInstanceTO.Disk disk = new UnmanagedInstanceTO.Disk();
            disk.setDiskId(key);
            disk.setLabel(key);
            disk.setController(key.replaceAll("\\d+$", ""));
            disk.setControllerUnit(0);
            disk.setPosition(Integer.parseInt(key.replaceAll("^\\D+", "")));
            disk.setCapacity(parsePveSize(configOption(value, "size")));
            disk.setImagePath(volid);
            disk.setFileBaseName(volid);
            String storage = volid.substring(0, colon);
            disk.setDatastoreName(storage);
            disk.setDatastoreHost("localhost");
            disk.setDatastorePath("/" + storage);
            disk.setDatastoreType("PreSetup");
            disk.setDatastorePort(0);
            disks.add(disk);
        }
        return disks;
    }

    private List<UnmanagedInstanceTO.Nic> getUnmanagedInstanceNics(JsonObject config) {
        List<UnmanagedInstanceTO.Nic> nics = new ArrayList<>();
        for (String key : sortedConfigKeys(config, "net\\d+")) {
            String value = config.get(key).getAsString();
            String first = value.split(",")[0].trim();
            int eq = first.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            UnmanagedInstanceTO.Nic nic = new UnmanagedInstanceTO.Nic();
            nic.setNicId(key);
            nic.setAdapterType(first.substring(0, eq));
            nic.setMacAddress(first.substring(eq + 1));
            nic.setNetwork(configOption(value, "bridge"));
            String tag = configOption(value, "tag");
            if (tag != null) {
                nic.setVlan(Integer.valueOf(tag));
            }
            nics.add(nic);
        }
        return nics;
    }

    /** Config keys matching the pattern, ordered by name then index (scsi0, scsi1, virtio0...). */
    private static List<String> sortedConfigKeys(JsonObject config, String pattern) {
        return config.entrySet().stream()
                .map(Map.Entry::getKey)
                .filter(k -> k.matches(pattern))
                .sorted(Comparator.comparing((String k) -> k.replaceAll("\\d+$", ""))
                        .thenComparingInt(k -> Integer.parseInt(k.replaceAll("^\\D+", ""))))
                .collect(Collectors.toList());
    }

    /** The value of a {@code key=value} option in a PVE config value string, or null. */
    private static String configOption(String value, String key) {
        for (String part : value.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) {
                return kv[1];
            }
        }
        return null;
    }

    /** Parses a PVE size string (32G, 1536M, plain bytes) into bytes; null when absent. */
    private static Long parsePveSize(String size) {
        if (StringUtils.isBlank(size)) {
            return null;
        }
        char unit = size.charAt(size.length() - 1);
        if (Character.isDigit(unit)) {
            return Long.parseLong(size);
        }
        long base = Long.parseLong(size.substring(0, size.length() - 1));
        switch (Character.toUpperCase(unit)) {
        case 'K':
            return base * 1024L;
        case 'M':
            return base * 1024L * 1024L;
        case 'G':
            return base * 1024L * 1024L * 1024L;
        case 'T':
            return base * 1024L * 1024L * 1024L * 1024L;
        default:
            throw new CloudRuntimeException("Unparseable PVE size string: " + size);
        }
    }

    /** Maps a PVE ostype config value to a human-readable OS name for the import listing. */
    private static String pveOsTypeToDisplayName(String ostype) {
        if (ostype == null) {
            return "Other";
        }
        switch (ostype) {
        case "l24":
            return "Linux 2.4 Kernel (64-bit)";
        case "l26":
            return "Other Linux (64-bit)";
        case "win11":
            return "Windows 11 (64-bit)";
        case "win10":
            return "Windows 10 (64-bit)";
        case "win8":
            return "Windows 8 (64-bit)";
        case "win7":
            return "Windows 7 (64-bit)";
        case "wvista":
            return "Windows Vista (64-bit)";
        case "wxp":
            return "Windows XP (32-bit)";
        case "w2k":
            return "Windows 2000 Server";
        case "w2k3":
            return "Windows Server 2003 (64-bit)";
        case "w2k8":
            return "Windows Server 2008 (64-bit)";
        case "solaris":
            return "Sun Solaris 11 (64-bit)";
        default:
            return "Other (64-bit)";
        }
    }

    /**
     * Returns the volid of a disk in the VM config that is owned by this vmid (name carries
     * {@code vm-<vmid>-}/{@code base-<vmid>-}) and still exists on storage, or null. Dangling
     * entries whose volume is gone are ignored; volumes whose existence cannot be determined
     * count as existing (conservative: the caller then refuses to destroy the VM).
     */
    private String findReferencedOwnedVolume(ProxmoxApiClient api, String node, int vmid) {
        JsonObject config = api.getVmConfig(node, vmid);
        Pattern ownerPattern = Pattern.compile("(?:vm|base)-(\\d+)-");
        for (Map.Entry<String, JsonElement> entry : config.entrySet()) {
            if (!entry.getKey().matches("(scsi|virtio|ide|sata|unused)\\d+") || !entry.getValue().isJsonPrimitive()) {
                continue;
            }
            String volid = entry.getValue().getAsString().split(",")[0].trim();
            if (volid.indexOf(':') <= 0) {
                continue; // e.g. "none" cdrom entries
            }
            Matcher matcher = ownerPattern.matcher(volid.substring(volid.indexOf(':') + 1));
            if (!matcher.find() || Integer.parseInt(matcher.group(1)) != vmid) {
                continue;
            }
            try {
                api.getVolumePath(node, volid);
                return volid;
            } catch (ProxmoxApiException e) {
                String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                boolean gone = e.getStatusCode() == 404 || message.contains("does not exist") || message.contains("no such") || message.contains("not found");
                if (!gone) {
                    return volid;
                }
            }
        }
        return null;
    }

    protected Answer execute(NetworkUsageCommand cmd) {
        if (cmd.isForVpc()) {
            return vpcNetworkUsage(cmd);
        }
        if (cmd.getOption() != null && cmd.getOption().equals("create")) {
            String result = networkUsage(cmd.getPrivateIP(), "create", null);
            return new NetworkUsageAnswer(cmd, result, 0L, 0L);
        }
        long[] stats = getNetworkStats(cmd.getPrivateIP(), null);
        return new NetworkUsageAnswer(cmd, "", stats[0], stats[1]);
    }

    protected NetworkUsageAnswer vpcNetworkUsage(NetworkUsageCommand cmd) {
        long[] stats = getVPCNetworkStats(cmd.getPrivateIP(), cmd.getGatewayIP(), cmd.getOption(), cmd.getVpcCIDR());
        return new NetworkUsageAnswer(cmd, "", stats[0], stats[1]);
    }

    protected long[] getVPCNetworkStats(String privateIp, String publicIp, String option, String vpcCIDR) {
        String args = "-l " + publicIp + " ";
        if (option.equals("get")) {
            args += "-g";
        } else if (option.equals("create")) {
            args += "-c";
            args += " -v " + vpcCIDR;
        } else if (option.equals("reset")) {
            args += "-r";
        } else if (option.equals("vpn")) {
            args += "-n";
        } else if (option.equals("remove")) {
            args += "-d";
        } else {
            return new long[2];
        }

        ExecutionResult callResult = executeInVR(privateIp, "vpc_netusage.sh", args);
        if (!callResult.isSuccess()) {
            logger.error("Unable to execute NetworkUsage command on DomR (" + privateIp
                    + "), domR may not be ready yet. failure due to " + callResult.getDetails());
        }

        if (option.equals("get") || option.equals("vpn")) {
            String result = callResult.getDetails();
            if (result == null || result.isEmpty()) {
                logger.error(" vpc network usage get returns empty ");
            }
            long[] stats = new long[2];
            if (result != null) {
                String[] splitResult = result.split(":");
                int i = 0;
                while (i < splitResult.length - 1) {
                    stats[0] += Long.parseLong(splitResult[i++]);
                    stats[1] += Long.parseLong(splitResult[i++]);
                }
                return stats;
            }
        }
        return new long[2];
    }

    protected String networkUsage(String privateIpAddress, String option, String ethName) {
        return networkUsage(privateIpAddress, option, ethName, null);
    }

    protected String networkUsage(String privateIpAddress, String option, String ethName, String publicIp) {
        String args = "";
        if (option.equals("get")) {
            args = "-g";
            if (StringUtils.isNotEmpty(publicIp)) {
                args += " -l " + publicIp;
            }
        } else if (option.equals("create")) {
            args = "-c";
        } else if (option.equals("reset")) {
            args = "-r";
        } else if (option.equals("addVif")) {
            args = "-a";
            args += ethName;
        } else if (option.equals("deleteVif")) {
            args = "-d";
            args += ethName;
        }

        ExecutionResult result = executeInVR(privateIpAddress, "netusage.sh", args);
        if (!result.isSuccess()) {
            return null;
        }
        return result.getDetails();
    }

    protected long[] getNetworkStats(String privateIP, String publicIp) {
        String result = networkUsage(privateIP, "get", null, publicIp);
        long[] stats = new long[2];
        if (result != null) {
            try {
                String[] splitResult = result.split(":");
                int i = 0;
                while (i < splitResult.length - 1) {
                    stats[0] += Long.parseLong(splitResult[i++]);
                    stats[1] += Long.parseLong(splitResult[i++]);
                }
            } catch (Exception e) {
                logger.warn("Unable to parse return from script return of network usage command: " + e, e);
            }
        }
        return stats;
    }

    //
    // VirtualRouterDeployer implementation
    //

    @Override
    public ExecutionResult executeInVR(String routerIP, String script, String args) {
        return executeInVR(routerIP, script, args, VRScripts.VR_SCRIPT_EXEC_TIMEOUT);
    }

    @Override
    public ExecutionResult executeInVR(String routerIP, String script, String args, Duration timeout) {
        Pair<Boolean, String> result;
        if (logger.isDebugEnabled()) {
            logger.debug("Run command on VR: " + routerIP + ", script: " + script + " with args: " + args);
        }
        try {
            result = SshHelper.sshExecute(routerIP, DEFAULT_DOMR_SSH_PORT, "root", getSystemVmKeyFile(), null,
                    "/opt/cloud/bin/" + script + " " + args, VRScripts.CONNECTION_TIMEOUT, VRScripts.CONNECTION_TIMEOUT,
                    timeout);
        } catch (Exception e) {
            String msg = "Command failed due to " + e.getMessage();
            logger.error(msg);
            result = new Pair<Boolean, String>(false, msg);
        }
        if (logger.isDebugEnabled()) {
            logger.debug(script + " execution result: " + result.first().toString());
        }
        return new ExecutionResult(result.first(), result.second());
    }

    /**
     * Fetch the running system VM template version and the checksum of its cloud-scripts
     * over SSH (get_template_version.sh). Mirrors VmwareResource.getSystemVmVersionAndChecksum.
     * The details are returned as "version&checksum".
     */
    private ExecutionResult getSystemVmVersionAndChecksum(String controlIp) {
        ExecutionResult result;
        try {
            result = executeInVR(controlIp, VRScripts.VERSION, null);
            if (!result.isSuccess()) {
                String errMsg = String.format("GetSystemVMVersionCmd on %s failed, message %s", controlIp, result.getDetails());
                logger.error(errMsg);
                throw new CloudRuntimeException(errMsg);
            }
        } catch (final Exception e) {
            final String msg = "GetSystemVMVersionCmd failed due to " + e;
            logger.error(msg, e);
            throw new CloudRuntimeException(msg, e);
        }
        return result;
    }

    /**
     * Deliver the CloudStack agent code (agent.zip + cloud-scripts.tgz + patch-sysvms.sh) to a
     * freshly booted system VM (SSVM/CPVM) over SSH and run the patch script so cloud.service can
     * start and the agent connects back. Mirrors VmwareResource.execute(PatchSystemVmCommand),
     * but reaches the system VM on its management-network control IP (ROUTER_IP) using the
     * systemvm SSH key resolved by getSystemVmKeyFile() rather than the invoking user's key.
     */
    private Answer execute(PatchSystemVmCommand cmd) {
        String controlIp = cmd.getAccessDetail(NetworkElementCommand.ROUTER_IP);
        String sysVMName = cmd.getAccessDetail(NetworkElementCommand.ROUTER_NAME);
        File pemFile = getSystemVmKeyFile();
        ExecutionResult result;
        try {
            result = getSystemVmVersionAndChecksum(controlIp);
            FileUtil.scpPatchFiles(controlIp, VRScripts.CONFIG_CACHE_LOCATION, DEFAULT_DOMR_SSH_PORT, pemFile, systemVmPatchFiles, BASEPATH);
        } catch (CloudRuntimeException e) {
            return new PatchSystemVmAnswer(cmd, e.getMessage());
        }

        final String[] lines = result.getDetails().split("&");
        // TODO: do we fail, or patch anyway??
        if (lines.length != 2) {
            return new PatchSystemVmAnswer(cmd, result.getDetails());
        }

        String scriptChecksum = lines[1].trim();
        String checksum = ChecksumUtil.calculateCurrentChecksum(sysVMName, "vms/cloud-scripts.tgz").trim();

        if (!StringUtils.isEmpty(checksum) && checksum.equals(scriptChecksum) && !cmd.isForced()) {
            String msg = String.format("No change in the scripts checksum, not patching systemVM %s", sysVMName);
            logger.info(msg);
            return new PatchSystemVmAnswer(cmd, msg, lines[0], lines[1]);
        }

        Pair<Boolean, String> patchResult;
        try {
            patchResult = SshHelper.sshExecute(controlIp, DEFAULT_DOMR_SSH_PORT, "root",
                    pemFile, null, "/var/cache/cloud/patch-sysvms.sh", 10000, 10000, 600000);
        } catch (Exception e) {
            return new PatchSystemVmAnswer(cmd, e.getMessage());
        }

        String scriptVersion = lines[1];
        if (StringUtils.isNotEmpty(patchResult.second())) {
            String res = patchResult.second().replace("\n", " ");
            String[] output = res.split(":");
            if (output.length != 2) {
                logger.warn("Failed to get the latest script version");
            } else {
                scriptVersion = output[1].split(" ")[0];
            }
        }
        if (patchResult.first()) {
            return new PatchSystemVmAnswer(cmd, String.format("Successfully patched systemVM %s ", sysVMName), lines[0], scriptVersion);
        }
        return new PatchSystemVmAnswer(cmd, patchResult.second());
    }

    @Override
    public ExecutionResult createFileInVR(String routerIp, String filePath, String fileName, String content) {
        File keyFile = getSystemVmKeyFile();
        try {
            SshHelper.scpTo(routerIp, DEFAULT_DOMR_SSH_PORT, "root", keyFile, null, filePath,
                    content.getBytes(StandardCharsets.UTF_8), fileName, null);
        } catch (Exception e) {
            logger.warn("Fail to create file " + filePath + fileName + " in VR " + routerIp, e);
            return new ExecutionResult(false, e.getMessage());
        }
        return new ExecutionResult(true, null);
    }

    @Override
    public ExecutionResult prepareCommand(NetworkElementCommand cmd) {
        // Update IP used to access the router
        cmd.setRouterAccessIp(getRouterSshControlIp(cmd));

        if (cmd instanceof IpAssocVpcCommand) {
            return prepareNetworkElementCommand((IpAssocVpcCommand) cmd);
        } else if (cmd instanceof IpAssocCommand) {
            return prepareNetworkElementCommand((IpAssocCommand) cmd);
        } else if (cmd instanceof SetSourceNatCommand) {
            return prepareNetworkElementCommand((SetSourceNatCommand) cmd);
        } else if (cmd instanceof SetupGuestNetworkCommand) {
            return prepareNetworkElementCommand((SetupGuestNetworkCommand) cmd);
        } else if (cmd instanceof SetNetworkACLCommand) {
            return prepareNetworkElementCommand((SetNetworkACLCommand) cmd);
        }
        return new ExecutionResult(true, null);
    }

    @Override
    public ExecutionResult cleanupCommand(NetworkElementCommand cmd) {
        if (cmd instanceof IpAssocCommand && !(cmd instanceof IpAssocVpcCommand)) {
            return cleanupNetworkElementCommand((IpAssocCommand) cmd);
        }
        return new ExecutionResult(true, null);
    }

    private String getRouterSshControlIp(NetworkElementCommand cmd) {
        String routerIp = cmd.getAccessDetail(NetworkElementCommand.ROUTER_IP);
        if (logger.isDebugEnabled()) {
            logger.debug("Use router's private IP for SSH control. IP : " + routerIp);
        }
        return routerIp;
    }

    private ExecutionResult prepareNetworkElementCommand(IpAssocVpcCommand cmd) {
        String routerName = cmd.getAccessDetail(NetworkElementCommand.ROUTER_NAME);
        String routerIp = getRouterSshControlIp(cmd);
        try {
            IpAddressTO[] ips = cmd.getIpAddresses();
            for (IpAddressTO ip : ips) {
                int ethDeviceNum = findRouterEthDeviceIndex(routerName, routerIp, ip.getVifMacAddress());
                if (ethDeviceNum < 0) {
                    if (ip.isAdd()) {
                        throw new InternalErrorException("Failed to find DomR VIF to associate/disassociate IP with.");
                    }
                    logger.debug("VIF to deassociate IP with does not exist, return success");
                    continue;
                }
                ip.setNicDevId(ethDeviceNum);
            }
        } catch (Exception e) {
            logger.error("Prepare Ip Assoc failure on applying one ip due to exception:  ", e);
            return new ExecutionResult(false, e.toString());
        }
        return new ExecutionResult(true, null);
    }

    private ExecutionResult prepareNetworkElementCommand(IpAssocCommand cmd) {
        try {
            String routerName = cmd.getAccessDetail(NetworkElementCommand.ROUTER_NAME);
            String controlIp = getRouterSshControlIp(cmd);
            Integer vmid = findVmid(routerName);
            if (vmid == null) {
                throw new InternalErrorException("Router " + routerName + " no longer exists to execute IPAssoc command");
            }
            ProxmoxApiClient api = getApiClient();
            String node = api.findNodeOfVm(vmid);
            if (node == null) {
                throw new InternalErrorException("Router " + routerName + " not found in cluster");
            }

            for (IpAddressTO ip : cmd.getIpAddresses()) {
                JsonObject config = api.getVmConfig(node, vmid);
                String nicKey = findNicKeyByMac(config, ip.getVifMacAddress());
                boolean addVif = ip.isAdd() && nicKey == null;
                if (addVif) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Plug new NIC to associate " + controlIp + " to " + ip.getPublicIp());
                    }
                    plugNicInternal(node, vmid, ip.getNicTO());
                }

                int ethDeviceNum = findRouterEthDeviceIndex(routerName, controlIp, ip.getVifMacAddress());
                if (ethDeviceNum < 0) {
                    if (ip.isAdd()) {
                        throw new InternalErrorException("Failed to find DomR VIF to associate/disassociate IP with.");
                    }
                    logger.debug("VIF to deassociate IP with does not exist, return success");
                    continue;
                }
                if (addVif) {
                    networkUsage(controlIp, "addVif", "eth" + ethDeviceNum);
                }
                ip.setNicDevId(ethDeviceNum);
                ip.setNewNic(addVif);
            }
        } catch (Exception e) {
            logger.error("Unexpected exception: " + e + " will shortcut rest of IPAssoc commands", e);
            return new ExecutionResult(false, e.toString());
        }
        return new ExecutionResult(true, null);
    }

    private ExecutionResult cleanupNetworkElementCommand(IpAssocCommand cmd) {
        try {
            String routerName = cmd.getAccessDetail(NetworkElementCommand.ROUTER_NAME);
            String controlIp = getRouterSshControlIp(cmd);
            String lastIp = cmd.getAccessDetail(NetworkElementCommand.NETWORK_PUB_LAST_IP);
            Integer vmid = findVmid(routerName);
            if (vmid == null) {
                return new ExecutionResult(true, "Router " + routerName + " no longer exists");
            }
            ProxmoxApiClient api = getApiClient();
            String node = api.findNodeOfVm(vmid);
            if (node == null) {
                return new ExecutionResult(true, "Router " + routerName + " not found in cluster");
            }

            for (IpAddressTO ip : cmd.getIpAddresses()) {
                if (ip.isAdd() || (lastIp != null && lastIp.equalsIgnoreCase("false"))) {
                    continue;
                }
                int ethDeviceNum = findRouterEthDeviceIndex(routerName, controlIp, ip.getVifMacAddress());
                if (ethDeviceNum == 2) {
                    return new ExecutionResult(true, "Not removing eth2 in network VR because it is the public NIC of source NAT");
                }
                JsonObject config = api.getVmConfig(node, vmid);
                String nicKey = findNicKeyByMac(config, ip.getVifMacAddress());
                if (nicKey == null) {
                    return new ExecutionResult(false, "Couldn't find NIC");
                }
                Map<String, Object> update = new HashMap<String, Object>();
                update.put("delete", nicKey);
                api.setVmConfig(node, vmid, update);
            }
        } catch (Exception e) {
            logger.error("Unexpected exception: " + e + " will shortcut rest of IPAssoc commands", e);
            return new ExecutionResult(false, e.toString());
        }
        return new ExecutionResult(true, null);
    }

    protected ExecutionResult prepareNetworkElementCommand(SetSourceNatCommand cmd) {
        String routerName = cmd.getAccessDetail(NetworkElementCommand.ROUTER_NAME);
        String routerIp = getRouterSshControlIp(cmd);
        IpAddressTO pubIp = cmd.getIpAddress();
        try {
            int ethDeviceNum = findRouterEthDeviceIndex(routerName, routerIp, pubIp.getVifMacAddress());
            pubIp.setNicDevId(ethDeviceNum);
        } catch (Exception e) {
            String msg = "Prepare Ip SNAT failure due to " + e;
            logger.error(msg, e);
            return new ExecutionResult(false, e.toString());
        }
        return new ExecutionResult(true, null);
    }

    protected ExecutionResult prepareNetworkElementCommand(SetupGuestNetworkCommand cmd) {
        NicTO nic = cmd.getNic();
        String routerIp = getRouterSshControlIp(cmd);
        String routerName = cmd.getAccessDetail(NetworkElementCommand.ROUTER_NAME);
        try {
            int ethDeviceNum = findRouterEthDeviceIndex(routerName, routerIp, nic.getMac());
            nic.setDeviceId(ethDeviceNum);
        } catch (Exception e) {
            String msg = "Prepare SetupGuestNetwork failed due to " + e;
            logger.warn(msg, e);
            return new ExecutionResult(false, msg);
        }
        return new ExecutionResult(true, null);
    }

    private ExecutionResult prepareNetworkElementCommand(SetNetworkACLCommand cmd) {
        NicTO nic = cmd.getNic();
        String routerName = cmd.getAccessDetail(NetworkElementCommand.ROUTER_NAME);
        String routerIp = getRouterSshControlIp(cmd);
        try {
            int ethDeviceNum = findRouterEthDeviceIndex(routerName, routerIp, nic.getMac());
            nic.setDeviceId(ethDeviceNum);
        } catch (Exception e) {
            String msg = "Prepare SetNetworkACL failed due to " + e;
            logger.error(msg, e);
            return new ExecutionResult(false, msg);
        }
        return new ExecutionResult(true, null);
    }

    /**
     * Finds the ethN device index inside the router that carries the given MAC
     * by probing the guest over SSH. Waits up to the hot-plug timeout for the
     * device to show up. Mirrors VmwareResource.findRouterEthDeviceIndex().
     */
    private int findRouterEthDeviceIndex(String domrName, String routerIp, String mac) throws Exception {
        File keyFile = getSystemVmKeyFile();
        logger.info("findRouterEthDeviceIndex. mac: " + mac);
        ArrayList<String> skipInterfaces = new ArrayList<String>(Arrays.asList("all", "default", "lo"));

        // When a NIC is hot-plugged it may take a while to show up in the guest,
        // so wait in a loop.
        long startTick = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTick < NIC_HOTPLUG_WAIT_TIMEOUT_MS) {
            Pair<Boolean, String> result = SshHelper.sshExecute(routerIp, DEFAULT_DOMR_SSH_PORT, "root", keyFile, null,
                    "ls /proc/sys/net/ipv4/conf");
            if (result.first()) {
                String[] tokens = result.second().split("\\s+");
                for (String token : tokens) {
                    if (!skipInterfaces.contains(token)) {
                        String cmd = String.format(
                                "ip address show %s | grep link/ether | sed -e 's/^[ \t]*//' | cut -d' ' -f2", token);
                        if (logger.isDebugEnabled()) {
                            logger.debug("Run domr script " + cmd);
                        }
                        Pair<Boolean, String> result2 = SshHelper.sshExecute(routerIp, DEFAULT_DOMR_SSH_PORT, "root",
                                keyFile, null, cmd);
                        if (logger.isDebugEnabled()) {
                            logger.debug("result: " + result2.first() + ", output: " + result2.second());
                        }
                        if (result2.first() && result2.second().trim().equalsIgnoreCase(mac.trim())) {
                            return Integer.parseInt(token.substring(3));
                        }
                        skipInterfaces.add(token);
                    }
                }
            }

            logger.warn("can not find interface associated with mac: " + mac
                    + ", guest OS may still be at loading state, retry...");
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return -1;
    }

    /**
     * Resolves the systemvm SSH private key the way VmwareResource does: first
     * from the classpath, then from the cloudstack-common install location,
     * finally from the management server's .ssh directory.
     */
    protected File getSystemVmKeyFile() {
        if (s_systemVmKeyFile == null) {
            synchronized (s_systemVmKeyFileLock) {
                if (s_systemVmKeyFile == null) {
                    s_systemVmKeyFile = fetchSystemVmKeyFile();
                }
            }
        }
        return s_systemVmKeyFile;
    }

    private File fetchSystemVmKeyFile() {
        logger.debug("Looking for file [" + RELATIVE_SYSTEMVM_KEY_PATH + "] in the classpath.");
        URL url = Script.class.getClassLoader().getResource(RELATIVE_SYSTEMVM_KEY_PATH);
        File keyFile = null;
        if (url != null) {
            keyFile = new File(url.getPath());
        }
        if (keyFile == null || !keyFile.exists()) {
            keyFile = new File(DEFAULT_SYSTEMVM_KEY_PATH);
        }
        if (!keyFile.exists()) {
            keyFile = new File("/var/cloudstack/management/.ssh/id_rsa");
        }
        if (!keyFile.exists()) {
            logger.error("Unable to locate the systemvm SSH private key; last tried " + keyFile.getAbsolutePath());
        }
        return keyFile;
    }

    //
    // JSON helpers
    //

    private static String jsonString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        return obj.get(key).getAsString();
    }

    private static long jsonLong(JsonObject obj, String key, long defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        try {
            return obj.get(key).getAsLong();
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static double jsonDouble(JsonObject obj, String key, double defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        try {
            return obj.get(key).getAsDouble();
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
