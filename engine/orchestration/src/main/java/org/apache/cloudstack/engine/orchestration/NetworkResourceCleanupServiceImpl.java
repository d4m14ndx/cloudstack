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
import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.network.RoutedIpv4Manager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.IpAddress;
import com.cloud.network.IpAddressManager;
import com.cloud.network.Network;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkModel;
import com.cloud.network.addr.PublicIp;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.lb.LoadBalancingRulesManager;
import com.cloud.network.rules.FirewallManager;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.FirewallRule.Purpose;
import com.cloud.network.rules.FirewallRuleVO;
import com.cloud.network.rules.LoadBalancerContainer.Scheme;
import com.cloud.network.rules.PortForwardingRuleVO;
import com.cloud.network.rules.RulesManager;
import com.cloud.network.rules.StaticNatRule;
import com.cloud.network.rules.StaticNatRuleImpl;
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import com.cloud.network.vpc.NetworkACLManager;
import com.cloud.network.vpc.VpcManager;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.user.Account;
import com.cloud.utils.exception.CloudRuntimeException;

@Component
public class NetworkResourceCleanupServiceImpl implements NetworkResourceCleanupService {
    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    protected NetworkDao networkDao;
    @Inject
    protected NetworkOfferingDao networkOfferingDao;
    @Inject
    protected RoutedIpv4Manager routedIpv4Manager;
    @Inject
    protected RulesManager rulesManager;
    @Inject
    protected LoadBalancingRulesManager loadBalancingRulesManager;
    @Inject
    protected FirewallManager firewallManager;
    @Inject
    protected NetworkACLManager networkACLManager;
    @Inject
    protected IPAddressDao ipAddressDao;
    @Inject
    protected IpAddressManager ipAddressManager;
    @Inject
    protected VpcManager vpcManager;
    @Inject
    protected AnnotationDao annotationDao;
    @Inject
    protected PortForwardingRulesDao portForwardingRulesDao;
    @Inject
    protected FirewallRulesDao firewallRulesDao;
    @Inject
    protected DataCenterDao dataCenterDao;
    @Inject
    protected NetworkModel networkModel;
    @Inject
    protected VlanDao vlanDao;

    @Override
    public boolean cleanupNetworkResources(final long networkId, final Account caller, final long callerUserId) {
        boolean success = true;
        final NetworkVO network = networkDao.findById(networkId);
        final NetworkOfferingVO networkOffering = networkOfferingDao.findById(network.getNetworkOfferingId());

        //remove BGP peers from the network
        if (routedIpv4Manager.removeBgpPeersFromNetwork(network) != null) {
            logger.debug("Successfully removed BGP peers from network id={}", networkId);
        } else {
            success = false;
            logger.warn("Failed to remove BGP peers from network as a part of network id={} cleanup", networkId);
        }

        //remove all PF/Static Nat rules for the network
        try {
            if (rulesManager.revokeAllPFStaticNatRulesForNetwork(networkId, callerUserId, caller)) {
                logger.debug("Successfully cleaned up portForwarding/staticNat rules for network {}", network);
            } else {
                success = false;
                logger.warn("Failed to release portForwarding/StaticNat rules as a part of network {} cleanup", network);
            }
        } catch (final ResourceUnavailableException ex) {
            success = false;
            // shouldn't even come here as network is being cleaned up after all network elements are shutdown
            logger.warn("Failed to release portForwarding/StaticNat rules as a part of network {} cleanup due to resourceUnavailable", network, ex);
        }

        //remove all LB rules for the network
        if (loadBalancingRulesManager.removeAllLoadBalanacersForNetwork(networkId, caller, callerUserId)) {
            logger.debug("Successfully cleaned up load balancing rules for network {}", network);
        } else {
            // shouldn't even come here as network is being cleaned up after all network elements are shutdown
            success = false;
            logger.warn("Failed to cleanup LB rules as a part of network {} cleanup", network);
        }

        //revoke all firewall rules for the network
        try {
            if (firewallManager.revokeAllFirewallRulesForNetwork(network, callerUserId, caller)) {
                logger.debug("Successfully cleaned up firewallRules rules for network {}", network);
            } else {
                success = false;
                logger.warn("Failed to cleanup Firewall rules as a part of network {} cleanup", network);
            }
        } catch (final ResourceUnavailableException ex) {
            success = false;
            // shouldn't even come here as network is being cleaned up after all network elements are shutdown
            logger.warn("Failed to cleanup Firewall rules as a part of network {} cleanup due to resourceUnavailable", network, ex);
        }

        //revoke all network ACLs for network
        try {
            if (networkACLManager.revokeACLItemsForNetwork(networkId)) {
                logger.debug("Successfully cleaned up NetworkACLs for network {}", network);
            } else {
                success = false;
                logger.warn("Failed to cleanup NetworkACLs as a part of network {} cleanup", network);
            }
        } catch (final ResourceUnavailableException ex) {
            success = false;
            logger.warn("Failed to cleanup Network ACLs as a part of network {} cleanup due to resourceUnavailable ", network, ex);
        }

        //release all ip addresses
        final List<IPAddressVO> ipsToRelease = ipAddressDao.listByAssociatedNetwork(networkId, null);
        for (final IPAddressVO ipToRelease : ipsToRelease) {
            if (ipToRelease.getVpcId() == null) {
                if (!ipToRelease.isPortable()) {
                    final IPAddressVO ip = ipAddressManager.markIpAsUnavailable(ipToRelease.getId());
                    assert ip != null : "Unable to mark the ip address id=" + ipToRelease.getId() + " as unavailable.";
                } else {
                    // portable IP address are associated with owner, until explicitly requested to be disassociated
                    // so as part of network clean up just break IP association with guest network
                    ipToRelease.setAssociatedWithNetworkId(null);
                    ipAddressDao.update(ipToRelease.getId(), ipToRelease);
                    logger.debug("Portable IP address {} is no longer associated with any network", ipToRelease);
                }
            } else {
                vpcManager.unassignIPFromVpcNetwork(ipToRelease, network);
            }
        }

        try {
            if (!ipAddressManager.applyIpAssociations(network, true)) {
                logger.warn("Unable to apply ip address associations for {}", network);
                success = false;
            }
        } catch (final ResourceUnavailableException e) {
            throw new CloudRuntimeException("We should never get to here because we used true when applyIpAssociations", e);
        }

        annotationDao.removeByEntityType(AnnotationService.EntityType.NETWORK.name(), network.getUuid());

        return success;
    }

    @Override
    public boolean shutdownNetworkResources(final Network network, final Account caller, final long callerUserId) {
        // This method cleans up network rules on the backend w/o touching them in the DB
        boolean success = true;

        // Mark all PF rules as revoked and apply them on the backend (not in the DB)
        final List<PortForwardingRuleVO> pfRules = portForwardingRulesDao.listByNetwork(network.getId());
        logger.debug("Releasing {} port forwarding rules for network id={} as a part of shutdownNetworkRules.", pfRules.size(), network);

        for (final PortForwardingRuleVO pfRule : pfRules) {
            logger.trace("Marking pf rule {} with Revoke state", pfRule);
            pfRule.setState(FirewallRule.State.Revoke);
        }

        try {
            if (!firewallManager.applyRules(pfRules, true, false)) {
                logger.warn("Failed to cleanup pf rules as a part of shutdownNetworkRules");
                success = false;
            }
        } catch (final ResourceUnavailableException ex) {
            logger.warn("Failed to cleanup pf rules as a part of shutdownNetworkRules due to ", ex);
            success = false;
        }

        // Mark all static rules as revoked and apply them on the backend (not in the DB)
        final List<FirewallRuleVO> firewallStaticNatRules = firewallRulesDao.listByNetworkAndPurpose(network.getId(), Purpose.StaticNat);
        final List<StaticNatRule> staticNatRules = new ArrayList<>();
        logger.debug("Releasing {} static nat rules for network {} as a part of shutdownNetworkRules", firewallStaticNatRules.size(), network);

        for (final FirewallRuleVO firewallStaticNatRule : firewallStaticNatRules) {
            logger.trace("Marking static nat rule {} with Revoke state", firewallStaticNatRule);
            final IpAddress ip = ipAddressDao.findById(firewallStaticNatRule.getSourceIpAddressId());
            final FirewallRuleVO ruleVO = firewallRulesDao.findById(firewallStaticNatRule.getId());

            if (ip == null || !ip.isOneToOneNat() || ip.getAssociatedWithVmId() == null) {
                throw new InvalidParameterValueException(String.format("Source ip address of the rule %s is not static nat enabled", firewallStaticNatRule));
            }

            //String dstIp = _networkModel.getIpInNetwork(ip.getAssociatedWithVmId(), firewallStaticNatRule.getNetworkId());
            ruleVO.setState(FirewallRule.State.Revoke);
            staticNatRules.add(new StaticNatRuleImpl(ruleVO, ip.getVmIp()));
        }

        try {
            if (!firewallManager.applyRules(staticNatRules, true, false)) {
                logger.warn("Failed to cleanup static nat rules as a part of shutdownNetworkRules");
                success = false;
            }
        } catch (final ResourceUnavailableException ex) {
            logger.warn("Failed to cleanup static nat rules as a part of shutdownNetworkRules due to ", ex);
            success = false;
        }

        try {
            if (!loadBalancingRulesManager.revokeLoadBalancersForNetwork(network, Scheme.Public)) {
                logger.warn("Failed to cleanup public lb rules as a part of shutdownNetworkRules");
                success = false;
            }
        } catch (final ResourceUnavailableException ex) {
            logger.warn("Failed to cleanup public lb rules as a part of shutdownNetworkRules due to ", ex);
            success = false;
        }

        try {
            if (!loadBalancingRulesManager.revokeLoadBalancersForNetwork(network, Scheme.Internal)) {
                logger.warn("Failed to cleanup internal lb rules as a part of shutdownNetworkRules");
                success = false;
            }
        } catch (final ResourceUnavailableException ex) {
            logger.warn("Failed to cleanup public lb rules as a part of shutdownNetworkRules due to ", ex);
            success = false;
        }

        // revoke all firewall rules for the network w/o applying them on the DB
        final List<FirewallRuleVO> firewallRules = firewallRulesDao.listByNetworkPurposeTrafficType(network.getId(), Purpose.Firewall, FirewallRule.TrafficType.Ingress);
        logger.debug("Releasing firewall ingress rules for network {} as a part of shutdownNetworkRules", firewallRules.size(), network);

        for (final FirewallRuleVO firewallRule : firewallRules) {
            logger.trace("Marking firewall ingress rule {} with Revoke state", firewallRule);
            firewallRule.setState(FirewallRule.State.Revoke);
        }

        try {
            if (!firewallManager.applyRules(firewallRules, true, false)) {
                logger.warn("Failed to cleanup firewall ingress rules as a part of shutdownNetworkRules");
                success = false;
            }
        } catch (final ResourceUnavailableException ex) {
            logger.warn("Failed to cleanup firewall ingress rules as a part of shutdownNetworkRules due to ", ex);
            success = false;
        }

        final List<FirewallRuleVO> firewallEgressRules = firewallRulesDao.listByNetworkPurposeTrafficType(network.getId(), Purpose.Firewall, FirewallRule.TrafficType.Egress);
        logger.debug("Releasing {} firewall egress rules for network {} as a part of shutdownNetworkRules", firewallEgressRules.size(), network);

        try {
            // delete default egress rule
            final DataCenter zone = dataCenterDao.findById(network.getDataCenterId());
            if (networkModel.areServicesSupportedInNetwork(network.getId(), Service.Firewall)
                    && (network.getGuestType() == Network.GuestType.Isolated || network.getGuestType() == Network.GuestType.Shared && zone.getNetworkType() == NetworkType.Advanced)) {
                // add default egress rule to accept the traffic
                firewallManager.applyDefaultEgressFirewallRule(network.getId(), networkModel.getNetworkEgressDefaultPolicy(network.getId()), false);
            }

        } catch (final ResourceUnavailableException ex) {
            logger.warn("Failed to cleanup firewall default egress rule as a part of shutdownNetworkRules due to ", ex);
            success = false;
        }

        for (final FirewallRuleVO firewallRule : firewallEgressRules) {
            logger.trace("Marking firewall egress rule {} with Revoke state", firewallRule);
            firewallRule.setState(FirewallRule.State.Revoke);
        }

        try {
            if (!firewallManager.applyRules(firewallEgressRules, true, false)) {
                logger.warn("Failed to cleanup firewall egress rules as a part of shutdownNetworkRules");
                success = false;
            }
        } catch (final ResourceUnavailableException ex) {
            logger.warn("Failed to cleanup firewall egress rules as a part of shutdownNetworkRules due to ", ex);
            success = false;
        }

        if (network.getVpcId() != null) {
            logger.debug("Releasing Network ACL Items for network {} as a part of shutdownNetworkRules", network);

            try {
                //revoke all Network ACLs for the network w/o applying them in the DB
                if (!networkACLManager.revokeACLItemsForNetwork(network.getId())) {
                    logger.warn("Failed to cleanup network ACLs as a part of shutdownNetworkRules");
                    success = false;
                }
            } catch (final ResourceUnavailableException ex) {
                logger.warn("Failed to cleanup network ACLs as a part of shutdownNetworkRules due to ", ex);
                success = false;
            }

        }

        //release all static nats for the network
        if (!rulesManager.applyStaticNatForNetwork(network, false, caller, true)) {
            logger.warn("Failed to disable static nats as part of shutdownNetworkRules for network {}", network);
            success = false;
        }

        // Get all ip addresses, mark as releasing and release them on the backend
        final List<IPAddressVO> userIps = ipAddressDao.listByAssociatedNetwork(network.getId(), null);
        final List<PublicIp> publicIpsToRelease = new ArrayList<>();
        if (userIps != null && !userIps.isEmpty()) {
            for (final IPAddressVO userIp : userIps) {
                userIp.setState(IpAddress.State.Releasing);
                final PublicIp publicIp = PublicIp.createFromAddrAndVlan(userIp, vlanDao.findById(userIp.getVlanId()));
                publicIpsToRelease.add(publicIp);
            }
        }

        try {
            if (!ipAddressManager.applyIpAssociations(network, true, true, publicIpsToRelease)) {
                logger.warn("Unable to apply ip address associations for {} as a part of shutdownNetworkRules", network);
                success = false;
            }
        } catch (final ResourceUnavailableException e) {
            throw new CloudRuntimeException("We should never get to here because we used true when applyIpAssociations", e);
        }

        return success;
    }
}
