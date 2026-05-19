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

import java.util.List;

import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.bgp.BGPService;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.IpAddressManager;
import com.cloud.network.Network;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkModel;
import com.cloud.network.RemoteAccessVpn;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.lb.LoadBalancingRulesManager;
import com.cloud.network.rules.FirewallManager;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.FirewallRule.Purpose;
import com.cloud.network.rules.FirewallRuleVO;
import com.cloud.network.rules.LoadBalancerContainer.Scheme;
import com.cloud.network.rules.RulesManager;
import com.cloud.network.vpc.NetworkACLManager;
import com.cloud.network.vpn.RemoteAccessVpnService;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.user.Account;

@Component
public class NetworkRuleReprogrammingServiceImpl implements NetworkRuleReprogrammingService {
    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    protected NetworkModel networkModel;
    @Inject
    protected NetworkOfferingDao networkOfferingDao;
    @Inject
    protected DataCenterDao dataCenterDao;
    @Inject
    protected FirewallRulesDao firewallRulesDao;
    @Inject
    protected FirewallManager firewallManager;
    @Inject
    protected IpAddressManager ipAddressManager;
    @Inject
    protected BGPService bgpService;
    @Inject
    protected RulesManager rulesManager;
    @Inject
    protected LoadBalancingRulesManager lbManager;
    @Inject
    protected RemoteAccessVpnService vpnManager;
    @Inject
    protected NetworkACLManager networkACLManager;

    @Override
    public boolean reprogramNetworkRules(final long networkId, final Account caller, final Network network) throws ResourceUnavailableException {
        boolean success = true;

        //Apply egress rules first to effect the egress policy early on the guest traffic
        final List<FirewallRuleVO> firewallEgressRulesToApply = firewallRulesDao.listByNetworkPurposeTrafficType(networkId, Purpose.Firewall, FirewallRule.TrafficType.Egress);
        final NetworkOfferingVO offering = networkOfferingDao.findById(network.getNetworkOfferingId());
        final DataCenter zone = dataCenterDao.findById(network.getDataCenterId());
        if (networkModel.areServicesSupportedInNetwork(network.getId(), Service.Firewall) && networkModel.areServicesSupportedInNetwork(network.getId(), Service.Firewall)
                && (network.getGuestType() == Network.GuestType.Isolated || network.getGuestType() == Network.GuestType.Shared && zone.getNetworkType() == NetworkType.Advanced)) {
            // add default egress rule to accept the traffic
            firewallManager.applyDefaultEgressFirewallRule(network.getId(), offering.isEgressDefaultPolicy(), true);
        }
        if (!firewallManager.applyFirewallRules(firewallEgressRulesToApply, false, caller)) {
            logger.warn("Failed to reapply firewall Egress rule(s) as a part of Network {} restart", network);
            success = false;
        }

        // associate all ip addresses
        if (!ipAddressManager.applyIpAssociations(network, false)) {
            logger.warn("Failed to apply IP addresses as a part of Network {} restart", network);
            success = false;
        }

        // apply BGP settings
        if (!bgpService.applyBgpPeers(network, false)) {
            logger.warn("Failed to apply bpg peers as a part of network {} restart", network);
            success = false;
        }


        // apply static nat
        if (!rulesManager.applyStaticNatsForNetwork(network, false, caller)) {
            logger.warn("Failed to apply static nats a part of network {} restart", network);
            success = false;
        }

        // apply firewall rules
        final List<FirewallRuleVO> firewallIngressRulesToApply = firewallRulesDao.listByNetworkPurposeTrafficType(networkId, Purpose.Firewall, FirewallRule.TrafficType.Ingress);
        if (!firewallManager.applyFirewallRules(firewallIngressRulesToApply, false, caller)) {
            logger.warn("Failed to reapply Ingress firewall rule(s) as a part of network {} restart", network);
            success = false;
        }

        // apply port forwarding rules
        if (!rulesManager.applyPortForwardingRulesForNetwork(networkId, false, caller)) {
            logger.warn("Failed to reapply port forwarding rule(s) as a part of network {} restart", network);
            success = false;
        }

        // apply static nat rules
        if (!rulesManager.applyStaticNatRulesForNetwork(networkId, false, caller)) {
            logger.warn("Failed to reapply static nat rule(s) as a part of network {} restart", network);
            success = false;
        }

        // apply public load balancer rules
        if (!lbManager.applyLoadBalancersForNetwork(network, Scheme.Public)) {
            logger.warn("Failed to reapply Public load balancer rules as a part of network {} restart", network);
            success = false;
        }

        // apply internal load balancer rules
        if (!lbManager.applyLoadBalancersForNetwork(network, Scheme.Internal)) {
            logger.warn("Failed to reapply internal load balancer rules as a part of network {} restart", network);
            success = false;
        }

        // apply vpn rules
        final List<? extends RemoteAccessVpn> vpnsToReapply = vpnManager.listRemoteAccessVpns(networkId);
        if (vpnsToReapply != null) {
            for (final RemoteAccessVpn vpn : vpnsToReapply) {
                // Start remote access vpn per ip
                if (vpnManager.startRemoteAccessVpn(vpn.getServerAddressId(), false) == null) {
                    logger.warn("Failed to reapply vpn rules as a part of network {} restart", network);
                    success = false;
                }
            }
        }

        //apply network ACLs
        if (!networkACLManager.applyACLToNetwork(networkId)) {
            logger.warn("Failed to reapply network ACLs as a part of  of network {}", network);
            success = false;
        }

        return success;
    }
}
