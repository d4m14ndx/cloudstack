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
package com.cloud.resource;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.command.admin.cluster.AddClusterCmd;
import org.apache.cloudstack.api.command.admin.cluster.DeleteClusterCmd;
import org.apache.cloudstack.api.command.admin.cluster.UpdateClusterCmd;
import org.apache.cloudstack.extension.Extension;
import org.apache.cloudstack.extension.ExtensionResourceMap;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.extensions.dao.ExtensionDao;
import org.apache.cloudstack.framework.extensions.dao.ExtensionResourceMapDao;
import org.apache.cloudstack.framework.extensions.manager.ExtensionsManager;
import org.apache.cloudstack.framework.extensions.vo.ExtensionResourceMapVO;
import org.apache.cloudstack.framework.extensions.vo.ExtensionVO;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.utils.identity.ManagementServerNode;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.capacity.CapacityManager;
import com.cloud.capacity.dao.CapacityDao;
import com.cloud.configuration.Config;
import org.apache.cloudstack.context.CallContext;
import com.cloud.cpu.CPU;
import com.cloud.dc.ClusterDetailsDao;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.DedicatedResourceVO;
import com.cloud.dc.HostPodVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.ClusterVSMMapDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.DedicatedResourceDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.exception.DiscoveryException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.org.Cluster;
import com.cloud.org.Grouping;
import com.cloud.org.Managed;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.Pair;
import com.cloud.utils.StringUtils;
import com.cloud.utils.UriUtils;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VmDetailConstants;

/**
 * Cluster CRUD implementation extracted from {@link ResourceManagerImpl} as
 * part of Phase 4 decomposition (slice 6).
 *
 * <p>Handles {@code discoverCluster}, {@code deleteCluster}, and
 * {@code updateCluster}. Calls back into {@link ResourceManagerImpl} via
 * {@link ClusterLifecycleCallbacks} for operations that cannot be moved
 * ({@code createHostAndAgent}, {@code getCluster}, {@code umanageHost}).
 */
@Component
public class ClusterLifecycleServiceImpl implements ClusterLifecycleService {

    protected Logger LOG = LogManager.getLogger(ClusterLifecycleServiceImpl.class);

    private static final long NODE_ID = ManagementServerNode.getManagementServerId();

    @Inject
    protected DataCenterDao dataCenterDao;
    @Inject
    protected HostPodDao podDao;
    @Inject
    protected ClusterDao clusterDao;
    @Inject
    protected ClusterDetailsDao clusterDetailsDao;
    @Inject
    protected HostDao hostDao;
    @Inject
    protected PrimaryDataStoreDao storagePoolDao;
    @Inject
    protected CapacityDao capacityDao;
    @Inject
    protected ConfigurationDao configDao;
    @Inject
    protected ClusterVSMMapDao clusterVSMMapDao;
    @Inject
    protected DedicatedResourceDao dedicatedDao;
    @Inject
    protected AnnotationDao annotationDao;
    @Inject
    protected ExtensionResourceMapDao extensionResourceMapDao;
    @Inject
    protected ExtensionDao extensionDao;
    @Inject
    protected ExtensionsManager extensionsManager;
    @Inject
    protected AccountManager accountManager;
    @Inject
    protected HostLookupService hostLookupService;
    @Inject
    protected List<? extends Discoverer> discoverers;
    @Inject
    protected ClusterLifecycleCallbacks callbacks;

    private Discoverer findDiscoverer(final HypervisorType hypervisorType) {
        for (final Discoverer d : discoverers) {
            if (d.getHypervisorType() == hypervisorType) {
                return d;
            }
        }
        return null;
    }

    @DB
    @Override
    public List<? extends Cluster> discoverCluster(final AddClusterCmd cmd) throws IllegalArgumentException, DiscoveryException {
        final long dcId = cmd.getZoneId();
        final long podId = cmd.getPodId();
        final String clusterName = cmd.getClusterName();
        String url = cmd.getUrl();
        final String username = cmd.getUsername();
        final String password = cmd.getPassword();
        CPU.CPUArch arch = cmd.getArch();
        final Long extensionId = cmd.getExtensionId();
        final Map<String, String> externalDetails = cmd.getExternalDetails();

        if (url != null) {
            url = URLDecoder.decode(url, com.cloud.utils.StringUtils.getPreferredCharset());
        }

        URI uri;

        // Check if the zone exists in the system
        final DataCenterVO zone = dataCenterDao.findById(dcId);
        if (zone == null) {
            final InvalidParameterValueException ex = new InvalidParameterValueException("Can't find zone by the id specified");
            ex.addProxyObject(String.valueOf(dcId), "dcId");
            throw ex;
        }

        final Account account = CallContext.current().getCallingAccount();
        if (Grouping.AllocationState.Disabled == zone.getAllocationState() && !accountManager.isRootAdmin(account.getId())) {
            final PermissionDeniedException ex = new PermissionDeniedException("Cannot perform this operation, Zone with specified id is currently disabled");
            ex.addProxyObject(zone.getUuid(), "dcId");
            throw ex;
        }

        final HostPodVO pod = podDao.findById(podId);

        // Check if the pod exists in the system
        if (podDao.findById(podId) == null) {
            throw new InvalidParameterValueException("Can't find pod by id " + podId);
        }
        // check if pod belongs to the zone
        if (!Long.valueOf(pod.getDataCenterId()).equals(dcId)) {
            final InvalidParameterValueException ex = new InvalidParameterValueException(String.format("Pod with specified id doesn't belong to the zone %s", zone));
            ex.addProxyObject(pod.getUuid(), "podId");
            ex.addProxyObject(zone.getUuid(), "dcId");
            throw ex;
        }

        // Verify cluster information and create a new cluster if needed
        if (clusterName == null || clusterName.isEmpty()) {
            throw new InvalidParameterValueException("Please specify cluster name");
        }

        if (cmd.getHypervisor() == null || cmd.getHypervisor().isEmpty()) {
            throw new InvalidParameterValueException("Please specify a hypervisor");
        }

        final Hypervisor.HypervisorType hypervisorType = Hypervisor.HypervisorType.getType(cmd.getHypervisor());
        if (hypervisorType == null) {
            LOG.error("Unable to resolve " + cmd.getHypervisor() + " to a valid supported hypervisor type");
            throw new InvalidParameterValueException("Unable to resolve " + cmd.getHypervisor() + " to a supported ");
        }

        if (zone.isSecurityGroupEnabled() && zone.getNetworkType().equals(NetworkType.Advanced)) {
            if (hypervisorType != HypervisorType.KVM && hypervisorType != HypervisorType.XenServer
                    && hypervisorType != HypervisorType.LXC && hypervisorType != HypervisorType.Simulator) {
                throw new InvalidParameterValueException("Don't support hypervisor type " + hypervisorType + " in advanced security enabled zone");
            }
        }

        if (!HypervisorType.External.equals(hypervisorType) && extensionId != null) {
            throw new InvalidParameterValueException("Extension can be specified only for External hypervisor type");
        }

        ExtensionVO extension = null;
        if (extensionId != null) {
            extension = extensionDao.findById(extensionId);
            if (extension == null || !Extension.Type.Orchestrator.equals(extension.getType())) {
                throw new InvalidParameterValueException("Invalid extension specified");
            }
        }

        if (MapUtils.isNotEmpty(externalDetails) && extension == null) {
            throw new InvalidParameterValueException("External details can be specified only with extension");
        }

        Cluster.ClusterType clusterType = null;
        if (cmd.getClusterType() != null && !cmd.getClusterType().isEmpty()) {
            clusterType = Cluster.ClusterType.valueOf(cmd.getClusterType());
        }
        if (clusterType == null) {
            clusterType = Cluster.ClusterType.CloudManaged;
        }

        Grouping.AllocationState allocationState = null;
        if (cmd.getAllocationState() != null && !cmd.getAllocationState().isEmpty()) {
            try {
                allocationState = Grouping.AllocationState.valueOf(cmd.getAllocationState());
            } catch (final IllegalArgumentException ex) {
                throw new InvalidParameterValueException("Unable to resolve Allocation State '" + cmd.getAllocationState() + "' to a supported state");
            }
        }
        if (allocationState == null) {
            allocationState = Grouping.AllocationState.Enabled;
        }

        final Discoverer discoverer = findDiscoverer(hypervisorType);
        if (discoverer == null) {

            throw new InvalidParameterValueException("Could not find corresponding resource manager for " + cmd.getHypervisor());
        }

        if (hypervisorType == HypervisorType.VMware) {
            final Map<String, String> allParams = cmd.getFullUrlParams();
            discoverer.putParam(allParams);
        }

        final List<ClusterVO> result = new ArrayList<>();

        ClusterVO cluster = new ClusterVO(dcId, podId, clusterName);
        cluster.setHypervisorType(hypervisorType.toString());

        cluster.setClusterType(clusterType);
        cluster.setAllocationState(allocationState);
        cluster.setArch(arch.getType());
        List<String> storageAccessGroups = cmd.getStorageAccessGroups();
        if (CollectionUtils.isNotEmpty(storageAccessGroups)) {
            cluster.setStorageAccessGroups(String.join(",", storageAccessGroups));
        }

        try {
            cluster = clusterDao.persist(cluster);
        } catch (final Exception e) {
            // no longer tolerate exception during the cluster creation phase
            final CloudRuntimeException ex = new CloudRuntimeException("Unable to create cluster " + clusterName + " in pod and data center with specified ids", e);
            // Get the pod VO object's table name.
            ex.addProxyObject(pod.getUuid(), "podId");
            ex.addProxyObject(zone.getUuid(), "dcId");
            throw ex;
        }
        result.add(cluster);

        if (clusterType == Cluster.ClusterType.CloudManaged) {
            final Map<String, String> details = new HashMap<>();
            details.put(VmDetailConstants.CPU_OVER_COMMIT_RATIO, CapacityManager.CpuOverprovisioningFactor.value().toString());
            details.put(VmDetailConstants.MEMORY_OVER_COMMIT_RATIO, CapacityManager.MemOverprovisioningFactor.value().toString());
            clusterDetailsDao.persist(cluster.getId(), details);
            if (HypervisorType.External.equals(cluster.getHypervisorType()) && extension != null) {
                extensionsManager.registerExtensionWithCluster(cluster, extension, externalDetails);
            }
            return result;
        }

        // save cluster details for later cluster/host cross-checking
        final Map<String, String> details = new HashMap<>();
        details.put("url", url);
        details.put("username", StringUtils.defaultString(username));
        details.put("password", StringUtils.defaultString(password));
        details.put(VmDetailConstants.CPU_OVER_COMMIT_RATIO, CapacityManager.CpuOverprovisioningFactor.value().toString());
        details.put(VmDetailConstants.MEMORY_OVER_COMMIT_RATIO, CapacityManager.MemOverprovisioningFactor.value().toString());
        clusterDetailsDao.persist(cluster.getId(), details);

        boolean success = false;
        try {
            try {
                uri = new URI(UriUtils.encodeURIComponent(url));
                if (uri.getScheme() == null) {
                    throw new InvalidParameterValueException("uri.scheme is null " + url + ", add http:// as a prefix");
                } else if (uri.getScheme().equalsIgnoreCase("http")) {
                    if (uri.getHost() == null || uri.getHost().equalsIgnoreCase("") || uri.getPath() == null || uri.getPath().equalsIgnoreCase("")) {
                        throw new InvalidParameterValueException("Your host and/or path is wrong.  Make sure it's of the format http://hostname/path");
                    }
                }
            } catch (final URISyntaxException e) {
                throw new InvalidParameterValueException(url + " is not a valid uri");
            }

            final List<HostVO> hosts = new ArrayList<>();
            Map<? extends ServerResource, Map<String, String>> resources;
            resources = discoverer.find(dcId, podId, cluster.getId(), uri, username, password, null);

            if (resources != null) {
                for (final Map.Entry<? extends ServerResource, Map<String, String>> entry : resources.entrySet()) {
                    final ServerResource resource = entry.getKey();

                    final HostVO host = (HostVO) callbacks.createHostAndAgentForDiscovery(resource, entry.getValue(), null, null);
                    if (host != null) {
                        hosts.add(host);
                    }
                    discoverer.postDiscovery(hosts, NODE_ID);
                }
                LOG.info("External cluster has been successfully discovered by " + discoverer.getName());
                success = true;
                CallContext.current().putContextParameter(Cluster.class, cluster.getUuid());
                return result;
            }

            LOG.warn("Unable to find the server resources at " + url);
            throw new DiscoveryException("Unable to add the external cluster");
        } finally {
            if (!success) {
                clusterDetailsDao.deleteDetails(cluster.getId());
                clusterDao.remove(cluster.getId());
            }
        }
    }

    @Override
    @DB
    public boolean deleteCluster(final DeleteClusterCmd cmd) {
        try {
            Transaction.execute(new TransactionCallbackNoReturn() {
                @Override
                public void doInTransactionWithoutResult(final TransactionStatus status) {
                    final ClusterVO cluster = clusterDao.lockRow(cmd.getId(), true);
                    if (cluster == null) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Cluster: " + cmd.getId() + " does not even exist.  Delete call is ignored.");
                        }
                        throw new CloudRuntimeException("Cluster: " + cmd.getId() + " does not exist");
                    }

                    final Hypervisor.HypervisorType hypervisorType = cluster.getHypervisorType();

                    final List<Long> hostIds = hostDao.listIdsByClusterId(cmd.getId());
                    if (!hostIds.isEmpty()) {
                        LOG.debug("{} still has hosts, can't remove", cluster);
                        throw new CloudRuntimeException("Cluster: " + cmd.getId() + " cannot be removed. Cluster still has hosts");
                    }

                    // don't allow to remove the cluster if it has non-removed storage
                    // pools
                    final List<StoragePoolVO> storagePools = storagePoolDao.listPoolsByCluster(cmd.getId());
                    if (!storagePools.isEmpty()) {
                        LOG.debug("{} still has storage pools, can't remove", cluster);
                        throw new CloudRuntimeException(String.format("Cluster: %s cannot be removed. Cluster still has storage pools", cluster));
                    }

                    if (HypervisorType.External.toString().equalsIgnoreCase(cluster.getHypervisorType().toString())) {
                        ExtensionResourceMapVO registeredExtension =
                                extensionResourceMapDao.findByResourceIdAndType(cluster.getId(),
                                        ExtensionResourceMap.ResourceType.Cluster);
                        if (registeredExtension != null) {
                            extensionsManager.unregisterExtensionWithCluster(cluster, registeredExtension.getExtensionId());
                        }
                    }

                    if (clusterDao.remove(cmd.getId())) {
                        capacityDao.removeBy(null, null, null, cluster.getId(), null);
                        // If this cluster is of type vmware, and if the nexus vswitch
                        // global parameter setting is turned
                        // on, remove the row in cluster_vsm_map for this cluster id.
                        if (hypervisorType == HypervisorType.VMware && Boolean.parseBoolean(configDao.getValue(Config.VmwareUseNexusVSwitch.toString()))) {
                            clusterVSMMapDao.removeByClusterId(cmd.getId());
                        }
                        // remove from dedicated resources
                        final DedicatedResourceVO dr = dedicatedDao.findByClusterId(cluster.getId());
                        if (dr != null) {
                            dedicatedDao.remove(dr.getId());
                        }
                        // Remove comments (if any)
                        annotationDao.removeByEntityType(AnnotationService.EntityType.CLUSTER.name(), cluster.getUuid());
                    }

                }
            });
            return true;
        } catch (final CloudRuntimeException e) {
            throw e;
        } catch (final Throwable t) {
            LOG.error("Unable to delete cluster: {}", clusterDao.findById(cmd.getId()), t);
            return false;
        }
    }

    @Override
    @DB
    public Cluster updateCluster(final UpdateClusterCmd cmd) {
        ClusterVO cluster = (ClusterVO) callbacks.getCluster(cmd.getId());
        String clusterType = cmd.getClusterType();
        String hypervisor = cmd.getHypervisor();
        String allocationState = cmd.getAllocationState();
        String managedstate = cmd.getManagedstate();
        String name = cmd.getClusterName();
        CPU.CPUArch arch = cmd.getArch();
        final Map<String, String> externalDetails = cmd.getExternalDetails();

        // Verify cluster information and update the cluster if needed
        boolean doUpdate = false;
        Pair<Boolean, ExtensionResourceMap> needDetailsUpdateMapPair =
                extensionsManager.extensionResourceMapDetailsNeedUpdate(cluster.getId(),
                ExtensionResourceMap.ResourceType.Cluster, externalDetails);
        if (Boolean.TRUE.equals(needDetailsUpdateMapPair.first()) && needDetailsUpdateMapPair.second() == null) {
            throw new InvalidParameterValueException(
                    String.format("Cluster: %s is not registered with any extension, details cannot be updated",
                            cluster.getName()));
        }

        if (StringUtils.isNotBlank(name)) {
            if (cluster.getHypervisorType() == HypervisorType.VMware) {
                throw new InvalidParameterValueException("Renaming VMware cluster is not supported as it could cause problems if the updated  cluster name is not mapped on VCenter.");
            }
            LOG.debug("Updating Cluster name to: " + name);
            cluster.setName(name);
            doUpdate = true;
        }

        if (hypervisor != null && !hypervisor.isEmpty()) {
            final Hypervisor.HypervisorType hypervisorType = Hypervisor.HypervisorType.getType(hypervisor);
            if (hypervisorType == null) {
                LOG.error("Unable to resolve " + hypervisor + " to a valid supported hypervisor type");
                throw new InvalidParameterValueException("Unable to resolve " + hypervisor + " to a supported type");
            } else {
                cluster.setHypervisorType(hypervisor);
                doUpdate = true;
            }
        }

        Cluster.ClusterType newClusterType;
        if (clusterType != null && !clusterType.isEmpty()) {
            try {
                newClusterType = Cluster.ClusterType.valueOf(clusterType);
            } catch (final IllegalArgumentException ex) {
                throw new InvalidParameterValueException("Unable to resolve " + clusterType + " to a supported type");
            }
            if (newClusterType == null) {
                LOG.error("Unable to resolve " + clusterType + " to a valid supported cluster type");
                throw new InvalidParameterValueException("Unable to resolve " + clusterType + " to a supported type");
            } else {
                cluster.setClusterType(newClusterType);
                doUpdate = true;
            }
        }

        Grouping.AllocationState newAllocationState;
        if (allocationState != null && !allocationState.isEmpty()) {
            try {
                newAllocationState = Grouping.AllocationState.valueOf(allocationState);
            } catch (final IllegalArgumentException ex) {
                throw new InvalidParameterValueException("Unable to resolve Allocation State '" + allocationState + "' to a supported state");
            }
            if (newAllocationState == null) {
                LOG.error("Unable to resolve " + allocationState + " to a valid supported allocation State");
                throw new InvalidParameterValueException("Unable to resolve " + allocationState + " to a supported state");
            } else {
                cluster.setAllocationState(newAllocationState);
                doUpdate = true;
            }
        }

        Managed.ManagedState newManagedState = null;
        final Managed.ManagedState oldManagedState = cluster.getManagedState();
        if (managedstate != null && !managedstate.isEmpty()) {
            try {
                newManagedState = Managed.ManagedState.valueOf(managedstate);
            } catch (final IllegalArgumentException ex) {
                throw new InvalidParameterValueException("Unable to resolve Managed State '" + managedstate + "' to a supported state");
            }
            if (newManagedState == null) {
                LOG.error("Unable to resolve Managed State '" + managedstate + "' to a supported state");
                throw new InvalidParameterValueException("Unable to resolve Managed State '" + managedstate + "' to a supported state");
            } else {
                doUpdate = true;
            }
        }

        if (arch != null) {
            List<CPU.CPUArch> architectureTypes = hostDao.listDistinctArchTypes(cluster.getId());
            if (architectureTypes.stream().anyMatch(a -> !a.equals(arch))) {
                throw new InvalidParameterValueException(String.format(
                        "Cluster has host(s) present with arch type(s): %s",
                        StringUtils.join(architectureTypes.stream().map(CPU.CPUArch::getType).toArray())));
            }
            cluster.setArch(arch.getType());
            doUpdate = true;
        }

        if (doUpdate) {
            clusterDao.update(cluster.getId(), cluster);
        }

        if (Boolean.TRUE.equals(needDetailsUpdateMapPair.first())) {
            ExtensionResourceMap extensionResourceMap = needDetailsUpdateMapPair.second();
            extensionsManager.updateExtensionResourceMapDetails(extensionResourceMap.getId(), externalDetails);
        }

        if (newManagedState != null && !newManagedState.equals(oldManagedState)) {
            if (newManagedState.equals(Managed.ManagedState.Unmanaged)) {
                boolean success = false;
                try {
                    cluster.setManagedState(Managed.ManagedState.PrepareUnmanaged);
                    clusterDao.update(cluster.getId(), cluster);
                    List<HostVO> hosts = hostLookupService.listAllHosts(Host.Type.Routing, cluster.getId(), cluster.getPodId(), cluster.getDataCenterId());
                    for (final HostVO host : hosts) {
                        if (host.getType().equals(Host.Type.Routing) && !host.getStatus().equals(Status.Down) && !host.getStatus().equals(Status.Disconnected) &&
                                !host.getStatus().equals(Status.Up) && !host.getStatus().equals(Status.Alert)) {
                            final String msg = "host " + host.getPrivateIpAddress() + " should not be in " + host.getStatus().toString() + " status";
                            throw new CloudRuntimeException("PrepareUnmanaged Failed due to " + msg);
                        }
                    }

                    for (final HostVO host : hosts) {
                        if (host.getStatus().equals(Status.Up)) {
                            callbacks.umanageHost(host.getId());
                        }
                    }
                    final int retry = 40;
                    boolean lsuccess;
                    for (int i = 0; i < retry; i++) {
                        lsuccess = true;
                        try {
                            Thread.sleep(5 * 1000);
                        } catch (final InterruptedException e) {
                            LOG.debug("thread unexpectedly interrupted during wait, while updating cluster");
                        }
                        hosts = hostLookupService.listAllUpAndEnabledHosts(Host.Type.Routing, cluster.getId(), cluster.getPodId(), cluster.getDataCenterId());
                        for (final HostVO host : hosts) {
                            if (!host.getStatus().equals(Status.Down) && !host.getStatus().equals(Status.Disconnected) && !host.getStatus().equals(Status.Alert)) {
                                lsuccess = false;
                                break;
                            }
                        }
                        if (lsuccess) {
                            success = true;
                            break;
                        }
                    }
                    if (!success) {
                        throw new CloudRuntimeException("PrepareUnmanaged Failed due to some hosts are still in UP status after 5 Minutes, please try later ");
                    }
                } finally {
                    cluster.setManagedState(success ? Managed.ManagedState.Unmanaged : Managed.ManagedState.PrepareUnmanagedError);
                    clusterDao.update(cluster.getId(), cluster);
                }
            } else if (newManagedState.equals(Managed.ManagedState.Managed)) {
                cluster.setManagedState(Managed.ManagedState.Managed);
                clusterDao.update(cluster.getId(), cluster);
            }

        }

        return clusterDao.findById(cluster.getId());
    }
}
