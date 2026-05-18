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

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.ObjectUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.StartupCommand;
import com.cloud.agent.api.StartupRoutingCommand;
import com.cloud.agent.transport.Request;
import com.cloud.cpu.CPU;
import com.cloud.dc.ClusterDetailsDao;
import com.cloud.dc.ClusterDetailsVO;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenterIpAddressVO;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.HostPodVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.DataCenterIpAddressDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.DetailVO;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.host.dao.HostTagsDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.utils.net.Ip;
import com.cloud.utils.net.NetUtils;

@Component
public class HostRegistrationServiceImpl implements HostRegistrationService {
    private static final Logger logger = LogManager.getLogger(HostRegistrationServiceImpl.class);
    private static final int ACQUIRE_GLOBAL_LOCK_TIMEOUT_FOR_COOPERATION = 30;

    @Inject
    protected AgentManager agentManager;
    @Inject
    protected HostDao hostDao;
    @Inject
    protected HostDetailsDao hostDetailsDao;
    @Inject
    protected HostTagsDao hostTagsDao;
    @Inject
    protected HostPodDao podDao;
    @Inject
    protected DataCenterDao dcDao;
    @Inject
    protected ClusterDao clusterDao;
    @Inject
    protected ClusterDetailsDao clusterDetailsDao;
    @Inject
    protected DataCenterIpAddressDao privateIpAddressDao;
    @Inject
    protected IPAddressDao publicIpAddressDao;
    @Inject
    protected HostGpuService hostGpuService;
    @Inject
    protected HostQueryService hostQueryService;
    @Inject
    protected HostLookupService hostLookupService;
    @Inject
    protected HostRegistrationCallbacks callbacks;

    @Override
    public void checkCIDR(final HostPodVO pod, final DataCenterVO dc, final String serverPrivateIP, final String serverPrivateNetmask) throws IllegalArgumentException {
        if (serverPrivateIP == null) {
            return;
        }

        final String cidrAddress = pod.getCidrAddress();
        final long cidrSize = pod.getCidrSize();
        final String cidrSubnet = NetUtils.getCidrSubNet(cidrAddress, cidrSize);
        final String serverSubnet = NetUtils.getSubNet(serverPrivateIP, serverPrivateNetmask);
        if (!cidrSubnet.equals(serverSubnet)) {
            logger.warn("The private ip address of the server (" + serverPrivateIP + ") is not compatible with the CIDR of pod: " + pod.getName() + " and zone: " +
                    dc.getName());
            throw new IllegalArgumentException("The private ip address of the server (" + serverPrivateIP + ") is not compatible with the CIDR of pod: " + pod.getName() +
                    " and zone: " + dc.getName());
        }

        final String cidrNetmask = NetUtils.getCidrSubNet("255.255.255.255", cidrSize);
        final long cidrNetmaskNumeric = NetUtils.ip2Long(cidrNetmask);
        final long serverNetmaskNumeric = NetUtils.ip2Long(serverPrivateNetmask);
        if (serverNetmaskNumeric > cidrNetmaskNumeric) {
            throw new IllegalArgumentException("The private ip address of the server (" + serverPrivateIP + ") is not compatible with the CIDR of pod: " + pod.getName() +
                    " and zone: " + dc.getName());
        }
    }

    private boolean checkCIDR(final HostPodVO pod, final String serverPrivateIP, final String serverPrivateNetmask) {
        if (serverPrivateIP == null) {
            return true;
        }

        final String cidrAddress = pod.getCidrAddress();
        final long cidrSize = pod.getCidrSize();
        final String cidrSubnet = NetUtils.getCidrSubNet(cidrAddress, cidrSize);
        final String serverSubnet = NetUtils.getSubNet(serverPrivateIP, serverPrivateNetmask);
        if (!cidrSubnet.equals(serverSubnet)) {
            return false;
        }

        final String cidrNetmask = NetUtils.getCidrSubNet("255.255.255.255", cidrSize);
        final long cidrNetmaskNumeric = NetUtils.ip2Long(cidrNetmask);
        final long serverNetmaskNumeric = NetUtils.ip2Long(serverPrivateNetmask);
        return serverNetmaskNumeric <= cidrNetmaskNumeric;
    }

    private HostVO getNewHost(final StartupCommand[] startupCommands) {
        final StartupCommand startupCommand = startupCommands[0];

        final String fullGuid = startupCommand.getGuid();
        logger.debug(String.format("Trying to find Host by guid %s", fullGuid));
        HostVO host = hostQueryService.findHostByGuid(fullGuid);

        if (host != null) {
            logger.debug(String.format("Found Host by guid %s: %s", fullGuid, host));
            return host;
        }

        final String guidPrefix = startupCommand.getGuidWithoutResource();
        logger.debug(String.format("Trying to find Host by guid prefix %s", guidPrefix));
        host = hostQueryService.findHostByGuidPrefix(guidPrefix);

        if (host != null) {
            logger.debug(String.format("Found Host by guid prefix %s: %s", guidPrefix, host));
            return host;
        }

        logger.debug(String.format("Could not find Host by guid %s", fullGuid));
        return null;
    }

    protected HostVO createHostVO(final StartupCommand[] cmds, final ServerResource resource, final Map<String, String> details, final List<String> hostTags,
            final List<String> storageAccessGroups, final ResourceStateAdapter.Event stateEvent) {
        boolean newHost = false;
        final StartupCommand startup = cmds[0];

        HostVO host = getNewHost(cmds);

        if (host == null) {
            host = new HostVO(startup.getGuid());
            newHost = true;
        }

        String dataCenter = startup.getDataCenter();
        String pod = startup.getPod();
        final String cluster = startup.getCluster();

        if (pod != null && dataCenter != null && pod.equalsIgnoreCase("default") && dataCenter.equalsIgnoreCase("default")) {
            final List<HostPodVO> pods = podDao.listAllIncludingRemoved();
            for (final HostPodVO hpv : pods) {
                if (checkCIDR(hpv, startup.getPrivateIpAddress(), startup.getPrivateNetmask())) {
                    pod = hpv.getName();
                    dataCenter = dcDao.findById(hpv.getDataCenterId()).getName();
                    break;
                }
            }
        }

        long dcId;
        DataCenterVO dc = dcDao.findByName(dataCenter);
        if (dc == null) {
            try {
                dcId = Long.parseLong(dataCenter != null ? dataCenter : "-1");
                dc = dcDao.findById(dcId);
            } catch (final NumberFormatException e) {
                logger.debug("Cannot parse " + dataCenter + " into Long.");
            }
        }
        if (dc == null) {
            throw new IllegalArgumentException("Host " + startup.getPrivateIpAddress() + " sent incorrect data center: " + dataCenter);
        }
        dcId = dc.getId();

        HostPodVO p = podDao.findByName(pod, dcId);
        if (p == null) {
            try {
                final long podId = Long.parseLong(pod != null ? pod : "-1");
                p = podDao.findById(podId);
            } catch (final NumberFormatException e) {
                logger.debug("Cannot parse " + pod + " into Long.");
            }
        }
        final Long podId = p == null ? null : p.getId();

        Long clusterId = null;
        if (cluster != null) {
            try {
                clusterId = Long.valueOf(cluster);
            } catch (final NumberFormatException e) {
                if (podId != null) {
                    ClusterVO c = clusterDao.findBy(cluster, podId);
                    if (c == null) {
                        c = new ClusterVO(dcId, podId, cluster);
                        c = clusterDao.persist(c);
                    }
                    clusterId = c.getId();
                }
            }
        }

        host.setDataCenterId(dc.getId());
        host.setPodId(podId);
        host.setClusterId(clusterId);
        host.setPrivateIpAddress(startup.getPrivateIpAddress());
        host.setPrivateNetmask(startup.getPrivateNetmask());
        host.setPrivateMacAddress(startup.getPrivateMacAddress());
        host.setPublicIpAddress(startup.getPublicIpAddress());
        host.setPublicMacAddress(startup.getPublicMacAddress());
        host.setPublicNetmask(startup.getPublicNetmask());
        host.setStorageIpAddress(startup.getStorageIpAddress());
        host.setStorageMacAddress(startup.getStorageMacAddress());
        host.setStorageNetmask(startup.getStorageNetmask());
        host.setVersion(startup.getVersion());
        host.setName(startup.getName());
        host.setManagementServerId(callbacks.getManagementServerNodeId());
        host.setStorageUrl(startup.getIqn());
        host.setLastPinged(System.currentTimeMillis() >> 10);
        host.setHostTags(hostTags, false);
        if (CollectionUtils.isNotEmpty(storageAccessGroups)) {
            host.setStorageAccessGroups(String.join(",", storageAccessGroups));
        }
        host.setDetails(details);
        host.setArch(CPU.CPUArch.fromType(startup.getArch()));
        if (startup.getStorageIpAddressDeux() != null) {
            host.setStorageIpAddressDeux(startup.getStorageIpAddressDeux());
            host.setStorageMacAddressDeux(startup.getStorageMacAddressDeux());
            host.setStorageNetmaskDeux(startup.getStorageNetmaskDeux());
        }
        if (resource != null) {
            host.setResource(resource.getClass().getName());
        }

        host = callbacks.dispatchCreateHostVo(stateEvent, host, cmds, resource, details, hostTags);
        if (host == null) {
            throw new CloudRuntimeException("No resource state adapter response");
        }

        if (newHost) {
            host = persistNewHost(host, startup);
        } else {
            hostDao.update(host.getId(), host);
        }

        if (startup instanceof StartupRoutingCommand) {
            final StartupRoutingCommand ssCmd = (StartupRoutingCommand) startup;
            hostTagsDao.updateImplicitTags(host.getId(), ssCmd.getHostTags());
            updateSupportsClonedVolumes(host, ssCmd.getSupportsClonedVolumes());
        }

        try {
            callbacks.resourceStateTransitTo(host, ResourceState.Event.InternalCreated);
            agentManager.agentStatusTransitTo(host, Status.Event.AgentConnected, callbacks.getManagementServerNodeId());
        } catch (final Exception e) {
            logger.debug(String.format("Cannot transit %s to Creating state", host), e);
            agentManager.agentStatusTransitTo(host, Status.Event.Error, callbacks.getManagementServerNodeId());
            try {
                callbacks.resourceStateTransitTo(host, ResourceState.Event.Error);
            } catch (final NoTransitionException e1) {
                logger.debug(String.format("Cannot transit %s to Error state", host), e);
            }
        }

        return host;
    }

    private HostVO persistNewHost(final HostVO host, final StartupCommand startup) {
        final HostVO hostVo = hostDao.persist(host);
        if (startup instanceof StartupRoutingCommand &&
                CollectionUtils.isNotEmpty(((StartupRoutingCommand) startup).getGpuDevices())) {
            final StartupRoutingCommand ssCmd = (StartupRoutingCommand) startup;
            host.setGpuGroups(hostGpuService.getGroupDetails(host, ssCmd.getGpuDevices(), ssCmd.getGpuGroupDetails()));
            hostDao.update(hostVo.getId(), host);
        }
        return hostVo;
    }

    private void updateSupportsClonedVolumes(final HostVO host, final boolean supportsClonedVolumes) {
        final String name = "supportsResign";

        DetailVO hostDetail = hostDetailsDao.findDetail(host.getId(), name);

        if (hostDetail != null) {
            if (supportsClonedVolumes) {
                hostDetail.setValue(Boolean.TRUE.toString());
                hostDetailsDao.update(hostDetail.getId(), hostDetail);
            } else {
                hostDetailsDao.remove(hostDetail.getId());
            }
        } else if (supportsClonedVolumes) {
            hostDetail = new DetailVO(host.getId(), name, Boolean.TRUE.toString());
            hostDetailsDao.persist(hostDetail);
        }

        boolean clusterSupportsResigning = true;
        final List<Long> hostIds = hostDao.listIdsByClusterId(host.getClusterId());

        for (final Long hostId : hostIds) {
            final DetailVO hostDetailVO = hostDetailsDao.findDetail(hostId, name);
            if (hostDetailVO == null || !Boolean.parseBoolean(hostDetailVO.getValue())) {
                clusterSupportsResigning = false;
                break;
            }
        }

        ClusterDetailsVO clusterDetailsVO = clusterDetailsDao.findDetail(host.getClusterId(), name);

        if (clusterDetailsVO != null) {
            if (clusterSupportsResigning) {
                clusterDetailsVO.setValue(Boolean.TRUE.toString());
                clusterDetailsDao.update(clusterDetailsVO.getId(), clusterDetailsVO);
            } else {
                clusterDetailsDao.remove(clusterDetailsVO.getId());
            }
        } else if (clusterSupportsResigning) {
            clusterDetailsVO = new ClusterDetailsVO(host.getClusterId(), name, Boolean.TRUE.toString());
            clusterDetailsDao.persist(clusterDetailsVO);
        }
    }

    private boolean isFirstHostInCluster(final HostVO host) {
        boolean isFirstHost = true;
        if (host.getClusterId() != null) {
            final SearchBuilder<HostVO> sb = hostDao.createSearchBuilder();
            sb.and("removed", sb.entity().getRemoved(), SearchCriteria.Op.NULL);
            sb.and("cluster", sb.entity().getClusterId(), SearchCriteria.Op.EQ);
            sb.done();
            final SearchCriteria<HostVO> sc = sb.create();
            sc.setParameters("cluster", host.getClusterId());

            final List<HostVO> hosts = hostDao.search(sc, null);
            if (hosts != null && hosts.size() > 1) {
                isFirstHost = false;
            }
        }
        return isFirstHost;
    }

    private void markHostAsDisconnected(HostVO host, final StartupCommand[] cmds) {
        if (host == null && cmds != null) {
            final StartupCommand firstCmd = cmds[0];
            host = hostQueryService.findHostByGuid(firstCmd.getGuid());
            if (host == null) {
                host = hostQueryService.findHostByGuidPrefix(firstCmd.getGuidWithoutResource());
            }
        }

        if (host != null) {
            agentManager.agentStatusTransitTo(host, Status.Event.AgentDisconnected, callbacks.getManagementServerNodeId());
        }
    }

    private Host createHostAndAgentInternal(final ServerResource resource, final Map<String, String> details, final boolean old, final List<String> hostTags,
            final List<String> storageAccessGroups, final boolean forRebalance) {
        return createHostAndAgentInternal(resource, details, old, hostTags, storageAccessGroups, forRebalance, false);
    }

    private Host createHostAndAgentInternal(final ServerResource resource, final Map<String, String> details, final boolean old, final List<String> hostTags,
            final List<String> storageAccessGroups, final boolean forRebalance, final boolean isTransferredConnection) {
        HostVO host = null;
        StartupCommand[] cmds = null;
        boolean hostExists = false;
        boolean created = false;

        try {
            cmds = resource.initialize(isTransferredConnection);
            if (cmds == null) {
                logger.info("Unable to fully initialize the agent because no StartupCommands are returned");
                return null;
            }

            if (this.getClass().getPackage().getImplementationVersion() == null) {
                for (final StartupCommand cmd : cmds) {
                    if (cmd.getVersion() == null) {
                        cmd.setVersion(Long.toString(System.currentTimeMillis()));
                    }
                }
            }

            if (logger.isDebugEnabled()) {
                new Request(-1L, -1L, cmds, true, false).logD("Startup request from directly connected host: ", true);
            }

            if (old) {
                final StartupCommand firstCmd = cmds[0];
                host = hostQueryService.findHostByGuid(firstCmd.getGuid());
                if (host == null) {
                    host = hostQueryService.findHostByGuidPrefix(firstCmd.getGuidWithoutResource());
                }
                if (host != null && host.getRemoved() == null) {
                    logger.debug(String.format("Found %s by guid: %s, old host reconnected as new", host, firstCmd.getGuid()));
                    hostExists = true;
                    return null;
                }
            }

            final boolean newHost = getNewHost(cmds) == null;
            host = createHostVO(cmds, resource, details, hostTags, storageAccessGroups, ResourceStateAdapter.Event.CREATE_HOST_VO_FOR_DIRECT_CONNECT);

            if (host != null) {
                created = agentManager.handleDirectConnectAgent(host, cmds, resource, forRebalance, newHost);
                host = hostDao.findById(host.getId());
            }
        } catch (final Exception e) {
            logger.warn("Unable to connect due to ", e);
        } finally {
            if (hostExists) {
                if (cmds != null) {
                    resource.disconnected();
                }
            } else if (!created) {
                if (cmds != null) {
                    resource.disconnected();
                }
                markHostAsDisconnected(host, cmds);
            }
        }

        return host;
    }

    private Host createHostAndAgentDeferred(final ServerResource resource, final Map<String, String> details, final boolean old, final List<String> hostTags,
            final List<String> storageAccessGroups, final boolean forRebalance) {
        HostVO host = null;
        StartupCommand[] cmds = null;
        boolean hostExists = false;
        boolean deferAgentCreation = true;
        boolean created = false;

        try {
            cmds = resource.initialize();
            if (cmds == null) {
                logger.info("Unable to fully initialize the agent because no StartupCommands are returned");
                return null;
            }

            if (this.getClass().getPackage().getImplementationVersion() == null) {
                for (final StartupCommand cmd : cmds) {
                    if (cmd.getVersion() == null) {
                        cmd.setVersion(Long.toString(System.currentTimeMillis()));
                    }
                }
            }

            if (logger.isDebugEnabled()) {
                new Request(-1L, -1L, cmds, true, false).logD("Startup request from directly connected host: ", true);
            }

            if (old) {
                final StartupCommand firstCmd = cmds[0];
                host = hostQueryService.findHostByGuid(firstCmd.getGuid());
                if (host == null) {
                    host = hostQueryService.findHostByGuidPrefix(firstCmd.getGuidWithoutResource());
                }
                if (host != null && host.getRemoved() == null) {
                    logger.debug(String.format("Found %s by guid %s, old host reconnected as new.", host, firstCmd.getGuid()));
                    hostExists = true;
                    return null;
                }
            }

            host = null;
            boolean newHost = false;
            final GlobalLock addHostLock = GlobalLock.getInternLock("AddHostLock");

            try {
                if (addHostLock.lock(ACQUIRE_GLOBAL_LOCK_TIMEOUT_FOR_COOPERATION)) {
                    try {
                        newHost = getNewHost(cmds) == null;
                        host = createHostVO(cmds, resource, details, hostTags, storageAccessGroups, ResourceStateAdapter.Event.CREATE_HOST_VO_FOR_DIRECT_CONNECT);

                        if (host != null) {
                            deferAgentCreation = !isFirstHostInCluster(host);
                        }
                    } finally {
                        addHostLock.unlock();
                    }
                }
            } finally {
                addHostLock.releaseRef();
            }

            if (host != null) {
                if (!deferAgentCreation) {
                    created = agentManager.handleDirectConnectAgent(host, cmds, resource, forRebalance, newHost);
                    host = hostDao.findById(host.getId());
                } else {
                    host = hostDao.findById(host.getId());
                    agentManager.agentStatusTransitTo(host, Status.Event.AgentDisconnected, callbacks.getManagementServerNodeId());

                    host = hostDao.findById(host.getId());
                    host.setLastPinged(0);
                    hostDao.update(host.getId(), host);
                }
            }
        } catch (final Exception e) {
            logger.warn("Unable to connect due to ", e);
        } finally {
            if (hostExists) {
                if (cmds != null) {
                    resource.disconnected();
                }
            } else if (!deferAgentCreation && !created) {
                if (cmds != null) {
                    resource.disconnected();
                }
                markHostAsDisconnected(host, cmds);
            }
        }

        return host;
    }

    @Override
    public Host createHostAndAgent(final Long hostId, final ServerResource resource, final Map<String, String> details, final boolean old, final List<String> hostTags,
            final boolean forRebalance) {
        return createHostAndAgent(hostId, resource, details, old, hostTags, forRebalance, false);
    }

    @Override
    public Host createHostAndAgent(final ServerResource resource, final Map<String, String> details, final boolean old, final List<String> hostTags,
            final List<String> storageAccessGroups, final boolean forRebalance) {
        return createHostAndAgentInternal(resource, details, old, hostTags, storageAccessGroups, forRebalance, false);
    }

    @Override
    public Host createHostAndAgent(final Long hostId, final ServerResource resource, final Map<String, String> details, final boolean old, final List<String> hostTags,
            final boolean forRebalance, final boolean isTransferredConnection) {
        return createHostAndAgentInternal(resource, details, old, hostTags, null, forRebalance, isTransferredConnection);
    }

    @Override
    public Host addHost(final long zoneId, final ServerResource resource, final Host.Type hostType, final Map<String, String> hostDetails) {
        if (dcDao.findById(zoneId) == null) {
            throw new InvalidParameterValueException("Can't find zone with id " + zoneId);
        }

        final String guid = hostDetails.get("guid");
        final List<HostVO> currentHosts = hostLookupService.listAllUpAndEnabledHostsInOneZoneByType(hostType, zoneId);
        for (final HostVO currentHost : currentHosts) {
            if (currentHost.getGuid().equals(guid)) {
                return currentHost;
            }
        }

        return createHostAndAgent(resource, hostDetails, true, null, null, false);
    }

    @Override
    public HostVO createHostVOForConnectedAgent(final StartupCommand[] cmds) {
        return createHostVO(cmds, null, null, null, null, ResourceStateAdapter.Event.CREATE_HOST_VO_FOR_CONNECTED);
    }

    private void checkIPConflicts(final HostPodVO pod, final DataCenterVO dc, final String serverPrivateIP, final String serverPublicIP) {
        if (!ObjectUtils.equals(serverPrivateIP, serverPublicIP)) {
            if (!privateIpAddressDao.mark(dc.getId(), pod.getId(), serverPrivateIP)) {
                final List<DataCenterIpAddressVO> existingPrivateIPs = privateIpAddressDao.listByPodIdDcIdIpAddress(pod.getId(), dc.getId(), serverPrivateIP);

                assert existingPrivateIPs.size() <= 1 : " How can we get more than one ip address with " + serverPrivateIP;
                if (existingPrivateIPs.size() > 1) {
                    throw new IllegalArgumentException("The private ip address of the server (" + serverPrivateIP + ") is already in use in pod: " + pod.getName() +
                            " and zone: " + dc.getName());
                }
                if (existingPrivateIPs.size() == 1) {
                    final DataCenterIpAddressVO vo = existingPrivateIPs.get(0);
                    if (vo.getNicId() != null) {
                        throw new IllegalArgumentException("The private ip address of the server (" + serverPrivateIP + ") is already in use in pod: " + pod.getName() +
                                " and zone: " + dc.getName());
                    }
                }
            }
        }

        if (serverPublicIP != null && !publicIpAddressDao.mark(dc.getId(), new Ip(serverPublicIP))) {
            final List<IPAddressVO> existingPublicIPs = publicIpAddressDao.listByDcIdIpAddress(dc.getId(), serverPublicIP);
            if (!existingPublicIPs.isEmpty()) {
                throw new IllegalArgumentException("The public ip address of the server (" + serverPublicIP + ") is already in use in zone: " + dc.getName());
            }
        }
    }

    @Override
    public HostVO fillRoutingHostVO(final HostVO host, final StartupRoutingCommand ssCmd, final HypervisorType hyType, Map<String, String> details,
            final List<String> hostTags) {
        if (host.getPodId() == null) {
            logger.error("Host " + ssCmd.getPrivateIpAddress() + " sent incorrect pod, pod id is null");
            throw new IllegalArgumentException("Host " + ssCmd.getPrivateIpAddress() + " sent incorrect pod, pod id is null");
        }

        final ClusterVO clusterVO = clusterDao.findById(host.getClusterId());
        if (clusterVO.getHypervisorType() != hyType) {
            throw new IllegalArgumentException(String.format("Can't add host whose hypervisor type is: %s into cluster: %s whose hypervisor type is: %s",
                    hyType, clusterVO, clusterVO.getHypervisorType()));
        }
        final CPU.CPUArch hostCpuArch = CPU.CPUArch.fromType(ssCmd.getCpuArch());
        if (hostCpuArch != null && clusterVO.getArch() != null && hostCpuArch != clusterVO.getArch()) {
            final String msg = String.format("Can't add a host whose arch is: %s into cluster of arch type: %s",
                    hostCpuArch.getType(), clusterVO.getArch().getType());
            logger.error(msg);
            throw new IllegalArgumentException(msg);
        }

        final Map<String, String> hostDetails = ssCmd.getHostDetails();
        if (hostDetails != null) {
            if (details != null) {
                details.putAll(hostDetails);
            } else {
                details = hostDetails;
            }
        }

        final HostPodVO pod = podDao.findById(host.getPodId());
        final DataCenterVO dc = dcDao.findById(host.getDataCenterId());
        checkIPConflicts(pod, dc, ssCmd.getPrivateIpAddress(), ssCmd.getPublicIpAddress());
        host.setType(Host.Type.Routing);
        host.setDetails(details);
        host.setCaps(ssCmd.getCapabilities());
        host.setCpuSockets(ssCmd.getCpuSockets());
        host.setCpus(ssCmd.getCpus());
        host.setArch(hostCpuArch);
        host.setTotalMemory(ssCmd.getMemory());
        host.setSpeed(ssCmd.getSpeed());
        host.setHypervisorType(hyType);
        host.setHypervisorVersion(ssCmd.getHypervisorVersion());
        host.setGpuGroups(hostGpuService.getGroupDetails(host, ssCmd.getGpuDevices(), ssCmd.getGpuGroupDetails()));
        return host;
    }

    @Override
    public Host createHostAndAgentForDiscovery(final ServerResource resource, final Map<String, String> details, final List<String> hostTags,
            final List<String> storageAccessGroups) {
        return createHostAndAgentDeferred(resource, details, true, hostTags, storageAccessGroups, false);
    }
}
