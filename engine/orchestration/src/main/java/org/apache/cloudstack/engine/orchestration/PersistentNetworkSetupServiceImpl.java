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
package org.apache.cloudstack.engine.orchestration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.SetupPersistentNetworkAnswer;
import com.cloud.agent.api.SetupPersistentNetworkCommand;
import com.cloud.agent.api.to.NicTO;
import com.cloud.configuration.ConfigurationManager;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.NetworkVO;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.resource.ResourceManager;
import com.cloud.utils.Pair;

@Component
public class PersistentNetworkSetupServiceImpl implements PersistentNetworkSetupService {
    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    protected ClusterDao clusterDao;
    @Inject
    protected ResourceManager resourceManager;
    @Inject
    protected NetworkModel networkModel;
    @Inject
    protected ConfigurationManager configurationManager;
    @Inject
    protected AgentManager agentManager;

    @Override
    public void setupPersistentNetwork(final NetworkVO network, final NetworkOfferingVO offering, final Long dcId) throws AgentUnavailableException, OperationTimedoutException {
        final List<ClusterVO> clusterVOs = clusterDao.listClustersByDcId(dcId);
        final List<HostVO> hosts = resourceManager.listAllUpAndEnabledHostsInOneZoneByType(Host.Type.Routing, dcId);
        final Map<Long, List<Long>> clusterToHostsMap = new HashMap<>();

        for (final HostVO host : hosts) {
            try {
                final Pair<Boolean, NicTO> networkCfgStateAndDetails = isNtwConfiguredInCluster(host, clusterToHostsMap, network, offering);
                if (networkCfgStateAndDetails.first()) {
                    continue;
                }
                final NicTO to = networkCfgStateAndDetails.second();
                final SetupPersistentNetworkCommand cmd = new SetupPersistentNetworkCommand(to);
                final SetupPersistentNetworkAnswer answer = (SetupPersistentNetworkAnswer) agentManager.send(host.getId(), cmd);

                if (answer == null) {
                    logger.warn("Unable to get an answer to the SetupPersistentNetworkCommand from agent: {}", host);
                    clusterToHostsMap.get(host.getClusterId()).remove(host.getId());
                    continue;
                }

                if (!answer.getResult()) {
                    logger.warn("Unable to setup agent {} due to {}", host, answer.getDetails());
                    clusterToHostsMap.get(host.getClusterId()).remove(host.getId());
                }
            } catch (final Exception e) {
                logger.warn("Failed to connect to host: {}", host);
            }
        }
        if (clusterToHostsMap.keySet().size() != clusterVOs.size()) {
            logger.warn("Hosts on all clusters may not have been configured with network devices.");
        }
    }

    private Pair<Boolean, NicTO> isNtwConfiguredInCluster(final HostVO host, final Map<Long, List<Long>> clusterToHostsMap, final NetworkVO network,
            final NetworkOfferingVO offering) {
        final Long clusterId = host.getClusterId();
        List<Long> hosts = clusterToHostsMap.get(clusterId);
        if (hosts == null) {
            hosts = new ArrayList<>();
        }
        if (host.getHypervisorType() == HypervisorType.KVM || host.getHypervisorType() == HypervisorType.XenServer) {
            hosts.add(host.getId());
            clusterToHostsMap.put(clusterId, hosts);
            return new Pair<>(false, createNicTOFromNetworkAndOffering(network, offering, host));
        }
        if (hosts != null && !hosts.isEmpty()) {
            return new Pair<>(true, createNicTOFromNetworkAndOffering(network, offering, host));
        }
        hosts.add(host.getId());
        clusterToHostsMap.put(clusterId, hosts);
        return new Pair<>(false, createNicTOFromNetworkAndOffering(network, offering, host));
    }

    private NicTO createNicTOFromNetworkAndOffering(final NetworkVO network, final NetworkOfferingVO offering, final HostVO host) {
        final NicTO to = new NicTO();
        to.setName(networkModel.getNetworkTag(host.getHypervisorType(), network));
        to.setBroadcastType(network.getBroadcastDomainType());
        to.setType(network.getTrafficType());
        to.setBroadcastUri(network.getBroadcastUri());
        to.setIsolationuri(network.getBroadcastUri());
        to.setNetworkRateMbps(configurationManager.getNetworkOfferingNetworkRate(offering.getId(), network.getDataCenterId()));
        to.setSecurityGroupEnabled(networkModel.isSecurityGroupSupportedInNetwork(network));
        return to;
    }
}
