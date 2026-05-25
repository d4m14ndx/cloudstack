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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.junit.Before;
import org.junit.Test;

import com.cloud.bgp.BGPService;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.DataCenterVO;
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

public class NetworkRuleReprogrammingServiceImplTest {
    private static final long NETWORK_ID = 101L;
    private static final long OFFERING_ID = 202L;
    private static final long ZONE_ID = 303L;
    private static final long VPN_SERVER_ADDRESS_ID = 404L;

    private NetworkRuleReprogrammingServiceImpl service;
    private NetworkModel networkModel;
    private NetworkOfferingDao networkOfferingDao;
    private DataCenterDao dataCenterDao;
    private FirewallRulesDao firewallRulesDao;
    private FirewallManager firewallManager;
    private IpAddressManager ipAddressManager;
    private BGPService bgpService;
    private RulesManager rulesManager;
    private LoadBalancingRulesManager lbManager;
    private RemoteAccessVpnService vpnManager;
    private NetworkACLManager networkACLManager;
    private Network network;
    private Account caller;
    private NetworkOfferingVO offering;
    private DataCenterVO zone;
    private FirewallRuleVO egressRule;
    private FirewallRuleVO ingressRule;
    private RemoteAccessVpn vpn;

    @Before
    public void setUp() throws ResourceUnavailableException {
        service = new NetworkRuleReprogrammingServiceImpl();
        networkModel = mock(NetworkModel.class);
        networkOfferingDao = mock(NetworkOfferingDao.class);
        dataCenterDao = mock(DataCenterDao.class);
        firewallRulesDao = mock(FirewallRulesDao.class);
        firewallManager = mock(FirewallManager.class);
        ipAddressManager = mock(IpAddressManager.class);
        bgpService = mock(BGPService.class);
        rulesManager = mock(RulesManager.class);
        lbManager = mock(LoadBalancingRulesManager.class);
        vpnManager = mock(RemoteAccessVpnService.class);
        networkACLManager = mock(NetworkACLManager.class);
        network = mock(Network.class);
        caller = mock(Account.class);
        offering = mock(NetworkOfferingVO.class);
        zone = mock(DataCenterVO.class);
        egressRule = mock(FirewallRuleVO.class);
        ingressRule = mock(FirewallRuleVO.class);
        vpn = mock(RemoteAccessVpn.class);

        service.networkModel = networkModel;
        service.networkOfferingDao = networkOfferingDao;
        service.dataCenterDao = dataCenterDao;
        service.firewallRulesDao = firewallRulesDao;
        service.firewallManager = firewallManager;
        service.ipAddressManager = ipAddressManager;
        service.bgpService = bgpService;
        service.rulesManager = rulesManager;
        service.lbManager = lbManager;
        service.vpnManager = vpnManager;
        service.networkACLManager = networkACLManager;

        when(network.getId()).thenReturn(NETWORK_ID);
        when(network.getNetworkOfferingId()).thenReturn(OFFERING_ID);
        when(network.getDataCenterId()).thenReturn(ZONE_ID);
        when(network.getGuestType()).thenReturn(Network.GuestType.Isolated);
        when(networkOfferingDao.findById(OFFERING_ID)).thenReturn(offering);
        when(dataCenterDao.findById(ZONE_ID)).thenReturn(zone);
        when(zone.getNetworkType()).thenReturn(NetworkType.Advanced);
        when(offering.isEgressDefaultPolicy()).thenReturn(true);
        when(networkModel.areServicesSupportedInNetwork(NETWORK_ID, Service.Firewall)).thenReturn(true);
        when(firewallRulesDao.listByNetworkPurposeTrafficType(NETWORK_ID, Purpose.Firewall, FirewallRule.TrafficType.Egress))
                .thenReturn(Collections.singletonList(egressRule));
        when(firewallRulesDao.listByNetworkPurposeTrafficType(NETWORK_ID, Purpose.Firewall, FirewallRule.TrafficType.Ingress))
                .thenReturn(Collections.singletonList(ingressRule));
        when(firewallManager.applyFirewallRules(Collections.singletonList(egressRule), false, caller)).thenReturn(true);
        when(firewallManager.applyFirewallRules(Collections.singletonList(ingressRule), false, caller)).thenReturn(true);
        when(ipAddressManager.applyIpAssociations(network, false)).thenReturn(true);
        when(bgpService.applyBgpPeers(network, false)).thenReturn(true);
        when(rulesManager.applyStaticNatsForNetwork(network, false, caller)).thenReturn(true);
        when(rulesManager.applyPortForwardingRulesForNetwork(NETWORK_ID, false, caller)).thenReturn(true);
        when(rulesManager.applyStaticNatRulesForNetwork(NETWORK_ID, false, caller)).thenReturn(true);
        when(lbManager.applyLoadBalancersForNetwork(network, Scheme.Public)).thenReturn(true);
        when(lbManager.applyLoadBalancersForNetwork(network, Scheme.Internal)).thenReturn(true);
        doReturn(Collections.singletonList(vpn)).when(vpnManager).listRemoteAccessVpns(NETWORK_ID);
        when(vpn.getServerAddressId()).thenReturn(VPN_SERVER_ADDRESS_ID);
        when(vpnManager.startRemoteAccessVpn(VPN_SERVER_ADDRESS_ID, false)).thenReturn(vpn);
        when(networkACLManager.applyACLToNetwork(NETWORK_ID)).thenReturn(true);
    }

    @Test
    public void reprogramNetworkRulesAppliesDefaultEgressRuleForIsolatedFirewallNetworkAndReturnsTrue() throws ResourceUnavailableException {
        assertTrue(service.reprogramNetworkRules(NETWORK_ID, caller, network));

        verify(firewallManager).applyDefaultEgressFirewallRule(NETWORK_ID, true, true);
        verify(firewallManager).applyFirewallRules(Collections.singletonList(egressRule), false, caller);
        verify(ipAddressManager).applyIpAssociations(network, false);
        verify(bgpService).applyBgpPeers(network, false);
        verify(rulesManager).applyStaticNatsForNetwork(network, false, caller);
        verify(firewallManager).applyFirewallRules(Collections.singletonList(ingressRule), false, caller);
        verify(rulesManager).applyPortForwardingRulesForNetwork(NETWORK_ID, false, caller);
        verify(rulesManager).applyStaticNatRulesForNetwork(NETWORK_ID, false, caller);
        verify(lbManager).applyLoadBalancersForNetwork(network, Scheme.Public);
        verify(lbManager).applyLoadBalancersForNetwork(network, Scheme.Internal);
        verify(vpnManager).startRemoteAccessVpn(VPN_SERVER_ADDRESS_ID, false);
        verify(networkACLManager).applyACLToNetwork(NETWORK_ID);
    }

    @Test
    public void reprogramNetworkRulesAppliesDefaultEgressRuleForSharedAdvancedFirewallNetwork() throws ResourceUnavailableException {
        when(network.getGuestType()).thenReturn(Network.GuestType.Shared);
        when(zone.getNetworkType()).thenReturn(NetworkType.Advanced);

        assertTrue(service.reprogramNetworkRules(NETWORK_ID, caller, network));

        verify(firewallManager).applyDefaultEgressFirewallRule(NETWORK_ID, true, true);
    }

    @Test
    public void reprogramNetworkRulesDoesNotApplyDefaultEgressRuleForSharedBasicFirewallNetwork() throws ResourceUnavailableException {
        when(network.getGuestType()).thenReturn(Network.GuestType.Shared);
        when(zone.getNetworkType()).thenReturn(NetworkType.Basic);

        assertTrue(service.reprogramNetworkRules(NETWORK_ID, caller, network));

        verify(firewallManager, never()).applyDefaultEgressFirewallRule(NETWORK_ID, true, true);
    }

    @Test
    public void reprogramNetworkRulesReturnsFalseWhenEgressFirewallApplyFailsAndStillAppliesAcl() throws ResourceUnavailableException {
        when(firewallManager.applyFirewallRules(Collections.singletonList(egressRule), false, caller)).thenReturn(false);

        assertFalse(service.reprogramNetworkRules(NETWORK_ID, caller, network));

        verify(networkACLManager).applyACLToNetwork(NETWORK_ID);
    }

    @Test
    public void reprogramNetworkRulesReturnsFalseWhenIpAssociationFailsAndStillAppliesAcl() throws ResourceUnavailableException {
        when(ipAddressManager.applyIpAssociations(network, false)).thenReturn(false);

        assertFalse(service.reprogramNetworkRules(NETWORK_ID, caller, network));

        verify(networkACLManager).applyACLToNetwork(NETWORK_ID);
    }

    @Test
    public void reprogramNetworkRulesReturnsFalseWhenBgpApplyFailsAndStillAppliesAcl() throws ResourceUnavailableException {
        when(bgpService.applyBgpPeers(network, false)).thenReturn(false);

        assertFalse(service.reprogramNetworkRules(NETWORK_ID, caller, network));

        verify(networkACLManager).applyACLToNetwork(NETWORK_ID);
    }

    @Test
    public void reprogramNetworkRulesReturnsFalseWhenStaticNatApplyFailsAndStillAppliesAcl() throws ResourceUnavailableException {
        when(rulesManager.applyStaticNatsForNetwork(network, false, caller)).thenReturn(false);

        assertFalse(service.reprogramNetworkRules(NETWORK_ID, caller, network));

        verify(networkACLManager).applyACLToNetwork(NETWORK_ID);
    }

    @Test
    public void reprogramNetworkRulesReturnsFalseWhenIngressFirewallApplyFailsAndStillAppliesAcl() throws ResourceUnavailableException {
        when(firewallManager.applyFirewallRules(Collections.singletonList(ingressRule), false, caller)).thenReturn(false);

        assertFalse(service.reprogramNetworkRules(NETWORK_ID, caller, network));

        verify(networkACLManager).applyACLToNetwork(NETWORK_ID);
    }

    @Test
    public void reprogramNetworkRulesReturnsFalseWhenPortForwardingApplyFailsAndStillAppliesAcl() throws ResourceUnavailableException {
        when(rulesManager.applyPortForwardingRulesForNetwork(NETWORK_ID, false, caller)).thenReturn(false);

        assertFalse(service.reprogramNetworkRules(NETWORK_ID, caller, network));

        verify(networkACLManager).applyACLToNetwork(NETWORK_ID);
    }

    @Test
    public void reprogramNetworkRulesReturnsFalseWhenStaticNatRulesApplyFailsAndStillAppliesAcl() throws ResourceUnavailableException {
        when(rulesManager.applyStaticNatRulesForNetwork(NETWORK_ID, false, caller)).thenReturn(false);

        assertFalse(service.reprogramNetworkRules(NETWORK_ID, caller, network));

        verify(networkACLManager).applyACLToNetwork(NETWORK_ID);
    }

    @Test
    public void reprogramNetworkRulesReturnsFalseWhenPublicLoadBalancerApplyFailsAndStillAppliesAcl() throws ResourceUnavailableException {
        when(lbManager.applyLoadBalancersForNetwork(network, Scheme.Public)).thenReturn(false);

        assertFalse(service.reprogramNetworkRules(NETWORK_ID, caller, network));

        verify(networkACLManager).applyACLToNetwork(NETWORK_ID);
    }

    @Test
    public void reprogramNetworkRulesReturnsFalseWhenInternalLoadBalancerApplyFailsAndStillAppliesAcl() throws ResourceUnavailableException {
        when(lbManager.applyLoadBalancersForNetwork(network, Scheme.Internal)).thenReturn(false);

        assertFalse(service.reprogramNetworkRules(NETWORK_ID, caller, network));

        verify(networkACLManager).applyACLToNetwork(NETWORK_ID);
    }

    @Test
    public void reprogramNetworkRulesReturnsFalseWhenRemoteAccessVpnStartReturnsNullAndStillAppliesAcl() throws ResourceUnavailableException {
        when(vpnManager.startRemoteAccessVpn(VPN_SERVER_ADDRESS_ID, false)).thenReturn(null);

        assertFalse(service.reprogramNetworkRules(NETWORK_ID, caller, network));

        verify(networkACLManager).applyACLToNetwork(NETWORK_ID);
    }

    @Test
    public void reprogramNetworkRulesReturnsFalseWhenNetworkAclApplyFails() throws ResourceUnavailableException {
        when(networkACLManager.applyACLToNetwork(NETWORK_ID)).thenReturn(false);

        assertFalse(service.reprogramNetworkRules(NETWORK_ID, caller, network));
    }

    @Test
    public void reprogramNetworkRulesAggregatesMultipleFailuresAndStillRunsLaterCollaborators() throws ResourceUnavailableException {
        when(firewallManager.applyFirewallRules(Collections.singletonList(egressRule), false, caller)).thenReturn(false);
        when(ipAddressManager.applyIpAssociations(network, false)).thenReturn(false);
        when(rulesManager.applyPortForwardingRulesForNetwork(NETWORK_ID, false, caller)).thenReturn(false);

        assertFalse(service.reprogramNetworkRules(NETWORK_ID, caller, network));

        verify(networkACLManager).applyACLToNetwork(NETWORK_ID);
    }

    @Test
    public void reprogramNetworkRulesToleratesNullRemoteAccessVpnList() throws ResourceUnavailableException {
        when(vpnManager.listRemoteAccessVpns(NETWORK_ID)).thenReturn(null);

        assertTrue(service.reprogramNetworkRules(NETWORK_ID, caller, network));

        verify(vpnManager, never()).startRemoteAccessVpn(VPN_SERVER_ADDRESS_ID, false);
    }

    @Test(expected = ResourceUnavailableException.class)
    public void reprogramNetworkRulesPropagatesResourceUnavailableFromIpAssociations() throws ResourceUnavailableException {
        when(ipAddressManager.applyIpAssociations(network, false))
                .thenThrow(new ResourceUnavailableException("ip apply failed", Network.class, NETWORK_ID));

        service.reprogramNetworkRules(NETWORK_ID, caller, network);
    }

    @Test(expected = ResourceUnavailableException.class)
    public void reprogramNetworkRulesPropagatesResourceUnavailableFromRemoteAccessVpnStart() throws ResourceUnavailableException {
        when(vpnManager.startRemoteAccessVpn(VPN_SERVER_ADDRESS_ID, false))
                .thenThrow(new ResourceUnavailableException("vpn failed", Network.class, NETWORK_ID));

        service.reprogramNetworkRules(NETWORK_ID, caller, network);
    }
}
