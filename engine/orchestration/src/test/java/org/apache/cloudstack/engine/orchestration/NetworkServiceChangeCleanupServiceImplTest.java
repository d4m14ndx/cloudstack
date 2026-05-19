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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.Network;
import com.cloud.network.Network.Service;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkServiceMapDao;
import com.cloud.network.dao.NetworkServiceMapVO;
import com.cloud.network.dao.RemoteAccessVpnDao;
import com.cloud.network.dao.RemoteAccessVpnVO;
import com.cloud.network.lb.LoadBalancingRulesManager;
import com.cloud.network.rules.FirewallManager;
import com.cloud.network.rules.RulesManager;
import com.cloud.network.vpn.RemoteAccessVpnService;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.offerings.dao.NetworkOfferingServiceMapDao;
import com.cloud.user.Account;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.dao.AccountDao;

public class NetworkServiceChangeCleanupServiceImplTest {
    private static final long NETWORK_ID = 101L;
    private static final long ACCOUNT_ID = 202L;
    private static final long OFFERING_ID = 303L;
    private static final long VPN_SERVER_IP_ID = 404L;

    private NetworkServiceChangeCleanupServiceImpl service;
    private NetworkOfferingDao networkOfferingDao;
    private NetworkOfferingServiceMapDao networkOfferingServiceMapDao;
    private NetworkServiceMapDao networkServiceMapDao;
    private AccountDao accountDao;
    private IPAddressDao ipAddressDao;
    private RulesManager rulesManager;
    private LoadBalancingRulesManager lbManager;
    private FirewallManager firewallManager;
    private RemoteAccessVpnDao remoteAccessVpnDao;
    private RemoteAccessVpnService vpnManager;
    private Network network;
    private AccountVO systemAccount;

    @Before
    public void setUp() {
        service = new NetworkServiceChangeCleanupServiceImpl();
        networkOfferingDao = mock(NetworkOfferingDao.class);
        networkOfferingServiceMapDao = mock(NetworkOfferingServiceMapDao.class);
        networkServiceMapDao = mock(NetworkServiceMapDao.class);
        accountDao = mock(AccountDao.class);
        ipAddressDao = mock(IPAddressDao.class);
        rulesManager = mock(RulesManager.class);
        lbManager = mock(LoadBalancingRulesManager.class);
        firewallManager = mock(FirewallManager.class);
        remoteAccessVpnDao = mock(RemoteAccessVpnDao.class);
        vpnManager = mock(RemoteAccessVpnService.class);
        network = mock(Network.class);
        systemAccount = mock(AccountVO.class);

        service.networkOfferingDao = networkOfferingDao;
        service.networkOfferingServiceMapDao = networkOfferingServiceMapDao;
        service.networkServiceMapDao = networkServiceMapDao;
        service.accountDao = accountDao;
        service.ipAddressDao = ipAddressDao;
        service.rulesManager = rulesManager;
        service.lbManager = lbManager;
        service.firewallManager = firewallManager;
        service.remoteAccessVpnDao = remoteAccessVpnDao;
        service.vpnManager = vpnManager;

        when(network.getId()).thenReturn(NETWORK_ID);
        when(network.getAccountId()).thenReturn(ACCOUNT_ID);
        when(network.getNetworkOfferingId()).thenReturn(OFFERING_ID);
        when(accountDao.findById(Account.ACCOUNT_ID_SYSTEM)).thenReturn(systemAccount);
        when(networkOfferingDao.findById(OFFERING_ID)).thenReturn(mock(NetworkOfferingVO.class));
    }

    @Test
    public void getServicesNotSupportedInNewOfferingReturnsNetworkServicesMissingFromOffering() {
        NetworkOfferingVO offering = mock(NetworkOfferingVO.class);
        List<NetworkServiceMapVO> networkServices = Arrays.asList(serviceMap(Service.Dhcp), serviceMap(Service.StaticNat), serviceMap(Service.Lb));
        when(offering.getId()).thenReturn(OFFERING_ID);
        when(networkOfferingDao.findById(OFFERING_ID)).thenReturn(offering);
        when(networkOfferingServiceMapDao.listServicesForNetworkOffering(OFFERING_ID))
                .thenReturn(Arrays.asList(Service.Dhcp.getName(), Service.Dns.getName()));
        when(networkServiceMapDao.getServicesInNetwork(NETWORK_ID)).thenReturn(networkServices);

        List<String> result = service.getServicesNotSupportedInNewOffering(network, OFFERING_ID);

        assertEquals(Arrays.asList(Service.StaticNat.getName(), Service.Lb.getName()), result);
    }

    @Test
    public void getServicesNotSupportedInNewOfferingIgnoresGatewayService() {
        NetworkOfferingVO offering = mock(NetworkOfferingVO.class);
        List<NetworkServiceMapVO> networkServices = Arrays.asList(serviceMap(Service.Gateway), serviceMap(Service.Firewall));
        when(offering.getId()).thenReturn(OFFERING_ID);
        when(networkOfferingDao.findById(OFFERING_ID)).thenReturn(offering);
        when(networkOfferingServiceMapDao.listServicesForNetworkOffering(OFFERING_ID)).thenReturn(Collections.emptyList());
        when(networkServiceMapDao.getServicesInNetwork(NETWORK_ID)).thenReturn(networkServices);

        List<String> result = service.getServicesNotSupportedInNewOffering(network, OFFERING_ID);

        assertEquals(Collections.singletonList(Service.Firewall.getName()), result);
    }

    @Test
    public void cleanupConfigForStaticNatRevokesRulesAndClearsStaticNatIpState() throws ResourceUnavailableException {
        IPAddressVO ip = mock(IPAddressVO.class);
        when(ip.getId()).thenReturn(77L);
        when(rulesManager.revokeAllPFStaticNatRulesForNetwork(NETWORK_ID, User.UID_SYSTEM, systemAccount)).thenReturn(true);
        when(ipAddressDao.listStaticNatPublicIps(NETWORK_ID)).thenReturn(Collections.singletonList(ip));

        service.cleanupConfigForServicesInNetwork(Collections.singletonList(Service.StaticNat.getName()), network);

        verify(rulesManager).revokeAllPFStaticNatRulesForNetwork(NETWORK_ID, User.UID_SYSTEM, systemAccount);
        verify(ip).setOneToOneNat(false);
        verify(ip).setAssociatedWithVmId(null);
        verify(ip).setVmIp(null);
        verify(ip).setForRouter(false);
        verify(ipAddressDao).update(77L, ip);
    }

    @Test
    public void cleanupConfigForPortForwardingOnlyRevokesPfStaticNatRules() throws ResourceUnavailableException {
        when(rulesManager.revokeAllPFStaticNatRulesForNetwork(NETWORK_ID, User.UID_SYSTEM, systemAccount)).thenReturn(true);

        service.cleanupConfigForServicesInNetwork(Collections.singletonList(Service.PortForwarding.getName()), network);

        verify(rulesManager).revokeAllPFStaticNatRulesForNetwork(NETWORK_ID, User.UID_SYSTEM, systemAccount);
        verify(ipAddressDao, never()).listStaticNatPublicIps(NETWORK_ID);
    }

    @Test
    public void cleanupConfigForSourceNatClearsSourceNatFlagOnAssociatedIps() {
        IPAddressVO ip = mock(IPAddressVO.class);
        when(ip.getId()).thenReturn(88L);
        when(ipAddressDao.listByAssociatedNetwork(NETWORK_ID, true)).thenReturn(Collections.singletonList(ip));

        service.cleanupConfigForServicesInNetwork(Collections.singletonList(Service.SourceNat.getName()), network);

        verify(ip).setSourceNat(false);
        verify(ipAddressDao).update(88L, ip);
    }

    @Test
    public void cleanupConfigForLbRemovesAllLoadBalancersForNetwork() {
        when(lbManager.removeAllLoadBalanacersForNetwork(NETWORK_ID, systemAccount, User.UID_SYSTEM)).thenReturn(true);

        service.cleanupConfigForServicesInNetwork(Collections.singletonList(Service.Lb.getName()), network);

        verify(lbManager).removeAllLoadBalanacersForNetwork(NETWORK_ID, systemAccount, User.UID_SYSTEM);
    }

    @Test
    public void cleanupConfigForFirewallRevokesFirewallRules() throws ResourceUnavailableException {
        when(firewallManager.revokeAllFirewallRulesForNetwork(network, User.UID_SYSTEM, systemAccount)).thenReturn(true);

        service.cleanupConfigForServicesInNetwork(Collections.singletonList(Service.Firewall.getName()), network);

        verify(firewallManager).revokeAllFirewallRulesForNetwork(network, User.UID_SYSTEM, systemAccount);
    }

    @Test
    public void cleanupConfigForFirewallToleratesResourceUnavailableException() throws ResourceUnavailableException {
        when(firewallManager.revokeAllFirewallRulesForNetwork(network, User.UID_SYSTEM, systemAccount))
                .thenThrow(new ResourceUnavailableException("firewall unavailable", Network.class, NETWORK_ID));

        service.cleanupConfigForServicesInNetwork(Collections.singletonList(Service.Firewall.getName()), network);

        verify(firewallManager).revokeAllFirewallRulesForNetwork(network, User.UID_SYSTEM, systemAccount);
    }

    @Test
    public void cleanupConfigForVpnDestroysRemoteAccessVpnForNonVpcNetwork() throws ResourceUnavailableException {
        RemoteAccessVpnVO vpn = mock(RemoteAccessVpnVO.class);
        when(network.getVpcId()).thenReturn(null);
        when(remoteAccessVpnDao.findByAccountAndNetwork(ACCOUNT_ID, NETWORK_ID)).thenReturn(vpn);
        when(vpn.getServerAddressId()).thenReturn(VPN_SERVER_IP_ID);

        service.cleanupConfigForServicesInNetwork(Collections.singletonList(Service.Vpn.getName()), network);

        verify(vpnManager).destroyRemoteAccessVpnForIp(VPN_SERVER_IP_ID, systemAccount, true);
    }

    @Test
    public void cleanupConfigForVpnSkipsVpcNetworks() throws ResourceUnavailableException {
        when(network.getVpcId()).thenReturn(55L);

        service.cleanupConfigForServicesInNetwork(Collections.singletonList(Service.Vpn.getName()), network);

        verify(remoteAccessVpnDao, never()).findByAccountAndNetwork(ACCOUNT_ID, NETWORK_ID);
        verify(vpnManager, never()).destroyRemoteAccessVpnForIp(anyLong(), eq(systemAccount), eq(true));
    }

    private NetworkServiceMapVO serviceMap(Service serviceName) {
        NetworkServiceMapVO serviceMap = mock(NetworkServiceMapVO.class);
        when(serviceMap.getService()).thenReturn(serviceName.getName());
        return serviceMap;
    }
}
