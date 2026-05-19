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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.network.RoutedIpv4Manager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.VlanVO;
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
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import com.cloud.network.vpc.NetworkACLManager;
import com.cloud.network.vpc.VpcManager;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.user.Account;
import com.cloud.utils.exception.CloudRuntimeException;

public class NetworkResourceCleanupServiceImplTest {
    private static final long NETWORK_ID = 101L;
    private static final long CALLER_USER_ID = 202L;
    private static final long OFFERING_ID = 303L;
    private static final long ZONE_ID = 404L;
    private static final long VLAN_ID = 505L;
    private static final String NETWORK_UUID = "network-uuid";

    private NetworkResourceCleanupServiceImpl service;
    private NetworkDao networkDao;
    private NetworkOfferingDao networkOfferingDao;
    private RoutedIpv4Manager routedIpv4Manager;
    private RulesManager rulesManager;
    private LoadBalancingRulesManager loadBalancingRulesManager;
    private FirewallManager firewallManager;
    private NetworkACLManager networkACLManager;
    private IPAddressDao ipAddressDao;
    private IpAddressManager ipAddressManager;
    private VpcManager vpcManager;
    private AnnotationDao annotationDao;
    private PortForwardingRulesDao portForwardingRulesDao;
    private FirewallRulesDao firewallRulesDao;
    private DataCenterDao dataCenterDao;
    private NetworkModel networkModel;
    private VlanDao vlanDao;
    private NetworkVO network;
    private Account caller;

    @Before
    public void setUp() throws ResourceUnavailableException {
        service = new NetworkResourceCleanupServiceImpl();
        networkDao = mock(NetworkDao.class);
        networkOfferingDao = mock(NetworkOfferingDao.class);
        routedIpv4Manager = mock(RoutedIpv4Manager.class);
        rulesManager = mock(RulesManager.class);
        loadBalancingRulesManager = mock(LoadBalancingRulesManager.class);
        firewallManager = mock(FirewallManager.class);
        networkACLManager = mock(NetworkACLManager.class);
        ipAddressDao = mock(IPAddressDao.class);
        ipAddressManager = mock(IpAddressManager.class);
        vpcManager = mock(VpcManager.class);
        annotationDao = mock(AnnotationDao.class);
        portForwardingRulesDao = mock(PortForwardingRulesDao.class);
        firewallRulesDao = mock(FirewallRulesDao.class);
        dataCenterDao = mock(DataCenterDao.class);
        networkModel = mock(NetworkModel.class);
        vlanDao = mock(VlanDao.class);
        network = mock(NetworkVO.class);
        caller = mock(Account.class);

        service.networkDao = networkDao;
        service.networkOfferingDao = networkOfferingDao;
        service.routedIpv4Manager = routedIpv4Manager;
        service.rulesManager = rulesManager;
        service.loadBalancingRulesManager = loadBalancingRulesManager;
        service.firewallManager = firewallManager;
        service.networkACLManager = networkACLManager;
        service.ipAddressDao = ipAddressDao;
        service.ipAddressManager = ipAddressManager;
        service.vpcManager = vpcManager;
        service.annotationDao = annotationDao;
        service.portForwardingRulesDao = portForwardingRulesDao;
        service.firewallRulesDao = firewallRulesDao;
        service.dataCenterDao = dataCenterDao;
        service.networkModel = networkModel;
        service.vlanDao = vlanDao;

        when(network.getId()).thenReturn(NETWORK_ID);
        when(network.getNetworkOfferingId()).thenReturn(OFFERING_ID);
        when(network.getUuid()).thenReturn(NETWORK_UUID);
        when(network.getDataCenterId()).thenReturn(ZONE_ID);
        when(network.getGuestType()).thenReturn(Network.GuestType.Isolated);
        when(networkDao.findById(NETWORK_ID)).thenReturn(network);
        when(networkOfferingDao.findById(OFFERING_ID)).thenReturn(mock(NetworkOfferingVO.class));
        when(routedIpv4Manager.removeBgpPeersFromNetwork(network)).thenReturn(network);
        when(rulesManager.revokeAllPFStaticNatRulesForNetwork(NETWORK_ID, CALLER_USER_ID, caller)).thenReturn(true);
        when(rulesManager.applyStaticNatForNetwork(network, false, caller, true)).thenReturn(true);
        when(loadBalancingRulesManager.removeAllLoadBalanacersForNetwork(NETWORK_ID, caller, CALLER_USER_ID)).thenReturn(true);
        when(loadBalancingRulesManager.revokeLoadBalancersForNetwork(network, Scheme.Public)).thenReturn(true);
        when(loadBalancingRulesManager.revokeLoadBalancersForNetwork(network, Scheme.Internal)).thenReturn(true);
        when(firewallManager.revokeAllFirewallRulesForNetwork(network, CALLER_USER_ID, caller)).thenReturn(true);
        when(firewallManager.applyRules(anyList(), eq(true), eq(false))).thenReturn(true);
        when(networkACLManager.revokeACLItemsForNetwork(NETWORK_ID)).thenReturn(true);
        when(ipAddressDao.listByAssociatedNetwork(NETWORK_ID, null)).thenReturn(Collections.emptyList());
        when(ipAddressManager.applyIpAssociations(network, true)).thenReturn(true);
        when(ipAddressManager.applyIpAssociations(eq(network), eq(true), eq(true), anyList())).thenReturn(true);
        when(portForwardingRulesDao.listByNetwork(NETWORK_ID)).thenReturn(Collections.emptyList());
        when(firewallRulesDao.listByNetworkAndPurpose(NETWORK_ID, Purpose.StaticNat)).thenReturn(Collections.emptyList());
        when(firewallRulesDao.listByNetworkPurposeTrafficType(NETWORK_ID, Purpose.Firewall, FirewallRule.TrafficType.Ingress)).thenReturn(Collections.emptyList());
        when(firewallRulesDao.listByNetworkPurposeTrafficType(NETWORK_ID, Purpose.Firewall, FirewallRule.TrafficType.Egress)).thenReturn(Collections.emptyList());
    }

    @Test
    public void cleanupNetworkResourcesReturnsFalseWhenBgpCleanupFails() {
        when(routedIpv4Manager.removeBgpPeersFromNetwork(network)).thenReturn(null);

        boolean result = service.cleanupNetworkResources(NETWORK_ID, caller, CALLER_USER_ID);

        assertFalse(result);
        verify(networkOfferingDao).findById(OFFERING_ID);
    }

    @Test
    public void cleanupNetworkResourcesReturnsFalseWhenRuleCleanupThrows() throws ResourceUnavailableException {
        when(rulesManager.revokeAllPFStaticNatRulesForNetwork(NETWORK_ID, CALLER_USER_ID, caller)).thenThrow(resourceUnavailable());

        assertFalse(service.cleanupNetworkResources(NETWORK_ID, caller, CALLER_USER_ID));
    }

    @Test
    public void cleanupNetworkResourcesReleasesPortableAndVpcIps() {
        IPAddressVO portableIp = mock(IPAddressVO.class);
        IPAddressVO vpcIp = mock(IPAddressVO.class);
        when(portableIp.getVpcId()).thenReturn(null);
        when(portableIp.isPortable()).thenReturn(true);
        when(portableIp.getId()).thenReturn(11L);
        when(vpcIp.getVpcId()).thenReturn(22L);
        when(ipAddressDao.listByAssociatedNetwork(NETWORK_ID, null)).thenReturn(Arrays.asList(portableIp, vpcIp));

        assertTrue(service.cleanupNetworkResources(NETWORK_ID, caller, CALLER_USER_ID));

        verify(portableIp).setAssociatedWithNetworkId(null);
        verify(ipAddressDao).update(11L, portableIp);
        verify(vpcManager).unassignIPFromVpcNetwork(vpcIp, network);
        verify(annotationDao).removeByEntityType(AnnotationService.EntityType.NETWORK.name(), NETWORK_UUID);
    }

    @Test
    public void cleanupNetworkResourcesMarksNonPortableIpUnavailable() throws ResourceUnavailableException {
        IPAddressVO ipToRelease = mock(IPAddressVO.class);
        IPAddressVO unavailableIp = mock(IPAddressVO.class);
        when(ipToRelease.getVpcId()).thenReturn(null);
        when(ipToRelease.isPortable()).thenReturn(false);
        when(ipToRelease.getId()).thenReturn(33L);
        when(ipAddressDao.listByAssociatedNetwork(NETWORK_ID, null)).thenReturn(Collections.singletonList(ipToRelease));
        when(ipAddressManager.markIpAsUnavailable(33L)).thenReturn(unavailableIp);

        assertTrue(service.cleanupNetworkResources(NETWORK_ID, caller, CALLER_USER_ID));

        verify(ipAddressManager).markIpAsUnavailable(33L);
        verify(ipAddressManager).applyIpAssociations(network, true);
    }

    @Test(expected = CloudRuntimeException.class)
    public void cleanupNetworkResourcesThrowsCloudRuntimeExceptionWhenIpAssociationUnexpectedlyFails() throws ResourceUnavailableException {
        when(ipAddressManager.applyIpAssociations(network, true)).thenThrow(resourceUnavailable());

        service.cleanupNetworkResources(NETWORK_ID, caller, CALLER_USER_ID);
    }

    @Test
    public void shutdownNetworkResourcesMarksPortForwardingRulesRevokedAndAppliesThem() throws ResourceUnavailableException {
        PortForwardingRuleVO rule = mock(PortForwardingRuleVO.class);
        List<PortForwardingRuleVO> rules = Collections.singletonList(rule);
        when(portForwardingRulesDao.listByNetwork(NETWORK_ID)).thenReturn(rules);

        assertTrue(service.shutdownNetworkResources(network, caller, CALLER_USER_ID));

        verify(rule).setState(FirewallRule.State.Revoke);
        verify(firewallManager).applyRules(rules, true, false);
    }

    @Test
    public void shutdownNetworkResourcesBuildsStaticNatRulesFromValidStaticNatIps() throws ResourceUnavailableException {
        FirewallRuleVO staticNatRule = mock(FirewallRuleVO.class);
        FirewallRuleVO ruleVO = mockStaticNatRuleVO();
        IPAddressVO ip = mock(IPAddressVO.class);
        when(staticNatRule.getId()).thenReturn(44L);
        when(staticNatRule.getSourceIpAddressId()).thenReturn(55L);
        when(firewallRulesDao.listByNetworkAndPurpose(NETWORK_ID, Purpose.StaticNat)).thenReturn(Collections.singletonList(staticNatRule));
        when(firewallRulesDao.findById(44L)).thenReturn(ruleVO);
        when(ipAddressDao.findById(55L)).thenReturn(ip);
        when(ip.isOneToOneNat()).thenReturn(true);
        when(ip.getAssociatedWithVmId()).thenReturn(66L);
        when(ip.getVmIp()).thenReturn("10.1.1.8");
        ArgumentCaptor<List> rulesCaptor = ArgumentCaptor.forClass(List.class);

        assertTrue(service.shutdownNetworkResources(network, caller, CALLER_USER_ID));

        verify(ruleVO).setState(FirewallRule.State.Revoke);
        verify(firewallManager, times(4)).applyRules(rulesCaptor.capture(), eq(true), eq(false));
        StaticNatRule appliedRule = (StaticNatRule) rulesCaptor.getAllValues().get(1).get(0);
        assertEquals("10.1.1.8", appliedRule.getDestIpAddress());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void shutdownNetworkResourcesThrowsWhenStaticNatIpIsInvalid() {
        FirewallRuleVO staticNatRule = mock(FirewallRuleVO.class);
        IPAddressVO ip = mock(IPAddressVO.class);
        when(staticNatRule.getId()).thenReturn(44L);
        when(staticNatRule.getSourceIpAddressId()).thenReturn(55L);
        when(firewallRulesDao.listByNetworkAndPurpose(NETWORK_ID, Purpose.StaticNat)).thenReturn(Collections.singletonList(staticNatRule));
        when(ipAddressDao.findById(55L)).thenReturn(ip);
        when(ip.isOneToOneNat()).thenReturn(false);

        service.shutdownNetworkResources(network, caller, CALLER_USER_ID);
    }

    @Test
    public void shutdownNetworkResourcesAppliesDefaultEgressRuleWhenFirewallServiceIsSupported() throws ResourceUnavailableException {
        DataCenterVO zone = mock(DataCenterVO.class);
        when(dataCenterDao.findById(ZONE_ID)).thenReturn(zone);
        when(zone.getNetworkType()).thenReturn(NetworkType.Advanced);
        when(networkModel.areServicesSupportedInNetwork(NETWORK_ID, Service.Firewall)).thenReturn(true);
        when(networkModel.getNetworkEgressDefaultPolicy(NETWORK_ID)).thenReturn(false);

        assertTrue(service.shutdownNetworkResources(network, caller, CALLER_USER_ID));

        verify(firewallManager).applyDefaultEgressFirewallRule(NETWORK_ID, false, false);
    }

    @Test
    public void shutdownNetworkResourcesReturnsFalseWhenBackendCleanupReportsFailures() throws ResourceUnavailableException {
        when(loadBalancingRulesManager.revokeLoadBalancersForNetwork(network, Scheme.Public)).thenReturn(false);
        when(networkACLManager.revokeACLItemsForNetwork(NETWORK_ID)).thenReturn(false);
        when(network.getVpcId()).thenReturn(77L);
        when(rulesManager.applyStaticNatForNetwork(network, false, caller, true)).thenReturn(false);
        when(ipAddressManager.applyIpAssociations(eq(network), eq(true), eq(true), anyList())).thenReturn(false);

        assertFalse(service.shutdownNetworkResources(network, caller, CALLER_USER_ID));
    }

    @Test
    public void shutdownNetworkResourcesBuildsPublicIpsForRelease() throws ResourceUnavailableException {
        IPAddressVO userIp = mock(IPAddressVO.class);
        VlanVO vlan = mock(VlanVO.class);
        when(userIp.getVlanId()).thenReturn(VLAN_ID);
        when(userIp.getDataCenterId()).thenReturn(ZONE_ID);
        when(userIp.getMacAddress()).thenReturn(1L);
        when(ipAddressDao.listByAssociatedNetwork(NETWORK_ID, null)).thenReturn(Collections.singletonList(userIp));
        when(vlanDao.findById(VLAN_ID)).thenReturn(vlan);
        ArgumentCaptor<List> publicIpsCaptor = ArgumentCaptor.forClass(List.class);

        assertTrue(service.shutdownNetworkResources(network, caller, CALLER_USER_ID));

        verify(userIp).setState(IpAddress.State.Releasing);
        verify(ipAddressManager).applyIpAssociations(eq(network), eq(true), eq(true), publicIpsCaptor.capture());
        assertEquals(1, publicIpsCaptor.getValue().size());
        assertSame(userIp, ((PublicIp) publicIpsCaptor.getValue().get(0)).ip());
    }

    @Test(expected = CloudRuntimeException.class)
    public void shutdownNetworkResourcesThrowsCloudRuntimeExceptionWhenIpAssociationUnexpectedlyFails() throws ResourceUnavailableException {
        when(ipAddressManager.applyIpAssociations(eq(network), eq(true), eq(true), anyList())).thenThrow(resourceUnavailable());

        service.shutdownNetworkResources(network, caller, CALLER_USER_ID);
    }

    private FirewallRuleVO mockStaticNatRuleVO() {
        FirewallRuleVO ruleVO = mock(FirewallRuleVO.class);
        when(ruleVO.getId()).thenReturn(44L);
        when(ruleVO.getXid()).thenReturn("xid");
        when(ruleVO.getUuid()).thenReturn("uuid");
        when(ruleVO.getProtocol()).thenReturn("tcp");
        when(ruleVO.getSourcePortStart()).thenReturn(1);
        when(ruleVO.getSourcePortEnd()).thenReturn(65535);
        when(ruleVO.getAccountId()).thenReturn(88L);
        when(ruleVO.getDomainId()).thenReturn(99L);
        when(ruleVO.getNetworkId()).thenReturn(NETWORK_ID);
        when(ruleVO.getSourceIpAddressId()).thenReturn(55L);
        when(ruleVO.isDisplay()).thenReturn(true);
        return ruleVO;
    }

    private ResourceUnavailableException resourceUnavailable() {
        return new ResourceUnavailableException("unavailable", Network.class, NETWORK_ID);
    }
}
