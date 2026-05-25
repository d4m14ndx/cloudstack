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
package com.cloud.kubernetes.cluster;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.network.RoutedIpv4Manager;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.kubernetes.cluster.actionworkers.KubernetesClusterActionWorker;
import com.cloud.network.IpAddress;
import com.cloud.network.Network;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.FirewallRuleVO;
import com.cloud.network.vpc.NetworkACL;
import com.cloud.offering.NetworkOffering;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.utils.net.NetUtils;

@Component
public class KubernetesClusterValidationServiceImpl implements KubernetesClusterValidationService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    protected FirewallRulesDao firewallRulesDao;
    @Inject
    protected NetworkModel networkModel;
    @Inject
    protected NetworkOfferingDao networkOfferingDao;
    @Inject
    protected RoutedIpv4Manager routedIpv4Manager;

    @Override
    public IpAddress getSourceNatIp(Network network) {
        List<? extends IpAddress> addresses = networkModel.listPublicIpsAssignedToGuestNtwk(network.getId(), true);
        if (CollectionUtils.isEmpty(addresses)) {
            return null;
        }
        for (IpAddress address : addresses) {
            if (address.isSourceNat()) {
                return address;
            }
        }
        return null;
    }

    @Override
    public void validateIsolatedNetworkIpRules(long ipId, FirewallRule.Purpose purpose, Network network, int clusterTotalNodeCount) {
        List<FirewallRuleVO> rules = firewallRulesDao.listByIpPurposeProtocolAndNotRevoked(ipId, purpose, NetUtils.TCP_PROTO);
        for (FirewallRuleVO rule : rules) {
            int startPort = ObjectUtils.defaultIfNull(rule.getSourcePortStart(), 1);
            int endPort = ObjectUtils.defaultIfNull(rule.getSourcePortEnd(), NetUtils.PORT_RANGE_MAX);
            if (logger.isDebugEnabled()) {
                logger.debug("Validating rule with purpose: {} for network: {} with ports: {}-{}", purpose.toString(), network, startPort, endPort);
            }
            if (startPort <= KubernetesClusterActionWorker.CLUSTER_API_PORT && KubernetesClusterActionWorker.CLUSTER_API_PORT <= endPort) {
                throw new InvalidParameterValueException(String.format("Network: %s has conflicting %s rules to provision Kubernetes cluster for API access", network, purpose.toString().toLowerCase()));
            }
            int expectedSshStart = KubernetesClusterActionWorker.CLUSTER_NODES_DEFAULT_START_SSH_PORT;
            int expectedSshEnd = expectedSshStart + clusterTotalNodeCount - 1;
            if (Math.max(expectedSshStart, startPort) <= Math.min(expectedSshEnd, endPort)) {
                throw new InvalidParameterValueException(String.format("Network: %s has conflicting %s rules to provision Kubernetes cluster for node VM SSH access", network, purpose.toString().toLowerCase()));
            }
        }
    }

    @Override
    public void validateIsolatedNetwork(Network network, int clusterTotalNodeCount) {
        if (!Network.GuestType.Isolated.equals(network.getGuestType())) {
            return;
        }
        if (Network.State.Allocated.equals(network.getState())) {
            return;
        }
        IpAddress sourceNatIp = getSourceNatIp(network);
        if (sourceNatIp == null) {
            throw new InvalidParameterValueException(String.format("Network ID: %s does not have a source NAT IP associated with it. To provision a Kubernetes Cluster, source NAT IP is required", network.getUuid()));
        }
        validateIsolatedNetworkIpRules(sourceNatIp.getId(), FirewallRule.Purpose.Firewall, network, clusterTotalNodeCount);
        validateIsolatedNetworkIpRules(sourceNatIp.getId(), FirewallRule.Purpose.PortForwarding, network, clusterTotalNodeCount);
    }

    @Override
    public void validateVpcTier(Network network) {
        if (Network.State.Allocated.equals(network.getState())) {
            return;
        }
        if (network.getNetworkACLId() == NetworkACL.DEFAULT_DENY) {
            throw new InvalidParameterValueException(String.format("Network ID: %s can not be used for Kubernetes cluster as it uses default deny ACL", network.getUuid()));
        }
    }

    @Override
    public void validateNetwork(Network network, int clusterTotalNodeCount) {
        NetworkOffering networkOffering = networkOfferingDao.findById(network.getNetworkOfferingId());
        if (networkOffering.isSystemOnly()) {
            throw new InvalidParameterValueException(String.format("Network ID: %s is for system use only", network.getUuid()));
        }
        if (!networkModel.areServicesSupportedInNetwork(network.getId(), Service.UserData)) {
            throw new InvalidParameterValueException(String.format("Network ID: %s does not support userdata that is required for Kubernetes cluster", network.getUuid()));
        }
        if (!networkModel.areServicesSupportedInNetwork(network.getId(), Service.Dhcp)) {
            throw new InvalidParameterValueException(String.format("Network ID: %s does not support DHCP that is required for Kubernetes cluster", network.getUuid()));
        }
        if (routedIpv4Manager.isRoutedNetwork(network)) {
            logger.debug("No need to add firewall and port forwarding rules for ROUTED network. Assume the VMs are reachable.");
            return;
        }
        Long vpcId = network.getVpcId();
        if (vpcId == null && !networkModel.areServicesSupportedInNetwork(network.getId(), Service.Firewall)) {
            throw new InvalidParameterValueException(String.format("Network ID: %s does not support firewall that is required for Kubernetes cluster", network.getUuid()));
        }
        if (!networkModel.areServicesSupportedInNetwork(network.getId(), Service.PortForwarding)) {
            throw new InvalidParameterValueException(String.format("Network ID: %s does not support port forwarding that is required for Kubernetes cluster", network.getUuid()));
        }
        if (network.getVpcId() != null) {
            validateVpcTier(network);
            return;
        }
        validateIsolatedNetwork(network, clusterTotalNodeCount);
    }

    @Override
    public void validateDockerRegistryParams(final String dockerRegistryUserName,
                                             final String dockerRegistryPassword,
                                             final String dockerRegistryUrl) {
        if ((dockerRegistryUserName == null || dockerRegistryUserName.isEmpty()) &&
                (dockerRegistryPassword == null || dockerRegistryPassword.isEmpty()) &&
                (dockerRegistryUrl == null || dockerRegistryUrl.isEmpty())) {
            return;
        }

        if (!((dockerRegistryUserName != null && !dockerRegistryUserName.isEmpty()) &&
                (dockerRegistryPassword != null && !dockerRegistryPassword.isEmpty()) &&
                (dockerRegistryUrl != null && !dockerRegistryUrl.isEmpty()))) {

            throw new InvalidParameterValueException("All the docker private registry parameters (username, password, url) required are specified");
        }

        try {
            new URL(dockerRegistryUrl);
        } catch (MalformedURLException e) {
            throw new InvalidParameterValueException("Invalid docker registry url specified");
        }
    }
}
