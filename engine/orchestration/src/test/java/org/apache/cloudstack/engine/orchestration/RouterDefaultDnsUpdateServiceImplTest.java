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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.RouterNetworkDao;
import com.cloud.network.vpc.VpcManager;
import com.cloud.network.vpc.VpcVO;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.NicProfile;
import com.cloud.vm.VirtualMachine.Type;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.dao.DomainRouterDao;

@RunWith(MockitoJUnitRunner.class)
public class RouterDefaultDnsUpdateServiceImplTest {

    private static final long ROUTER_ID = 101L;
    private static final long VPC_ID = 202L;
    private static final long ROUTER_NETWORK_ID = 303L;

    private static final String EXISTING_IPV4_DNS1 = "1.1.1.1";
    private static final String EXISTING_IPV4_DNS2 = "1.0.0.1";
    private static final String EXISTING_IPV6_DNS1 = "2606:4700:4700::1111";
    private static final String EXISTING_IPV6_DNS2 = "2606:4700:4700::1001";
    private static final String CUSTOM_IPV4_DNS1 = "5.5.5.5";
    private static final String CUSTOM_IPV4_DNS2 = "6.6.6.6";
    private static final String CUSTOM_IPV6_DNS1 = "2001:4860:4860::5555";
    private static final String CUSTOM_IPV6_DNS2 = "2001:4860:4860::6666";

    @Mock
    private DomainRouterDao routerDao;
    @Mock
    private RouterNetworkDao routerNetworkDao;
    @Mock
    private VpcManager vpcManager;
    @Mock
    private NetworkDao networksDao;

    private RouterDefaultDnsUpdateServiceImpl service;

    @Before
    public void setUp() {
        service = new RouterDefaultDnsUpdateServiceImpl();
        service.routerDao = routerDao;
        service.routerNetworkDao = routerNetworkDao;
        service.vpcManager = vpcManager;
        service.networksDao = networksDao;
    }

    @Test
    public void updateRouterDefaultDnsReturnsForNonRouterVm() {
        VirtualMachineProfile vmProfile = vmProfile(Type.User);
        NicProfile nicProfile = defaultProfile();

        service.updateRouterDefaultDns(vmProfile, nicProfile);

        assertExistingDns(nicProfile);
        verifyNoInteractions(routerDao, routerNetworkDao, vpcManager, networksDao);
    }

    @Test
    public void updateRouterDefaultDnsReturnsForNonDefaultRouterNic() {
        VirtualMachineProfile vmProfile = vmProfile(Type.DomainRouter);
        NicProfile nicProfile = profile(false);

        service.updateRouterDefaultDns(vmProfile, nicProfile);

        assertExistingDns(nicProfile);
        verifyNoInteractions(routerDao, routerNetworkDao, vpcManager, networksDao);
    }

    @Test
    public void updateRouterDefaultDnsUsesVpcCustomDns() {
        DomainRouterVO router = router(VPC_ID);
        when(routerDao.findById(ROUTER_ID)).thenReturn(router);
        VpcVO vpc = mock(VpcVO.class);
        when(vpc.getIp4Dns1()).thenReturn(CUSTOM_IPV4_DNS1);
        when(vpc.getIp4Dns2()).thenReturn(CUSTOM_IPV4_DNS2);
        when(vpc.getIp6Dns1()).thenReturn(CUSTOM_IPV6_DNS1);
        when(vpc.getIp6Dns2()).thenReturn(CUSTOM_IPV6_DNS2);
        when(vpcManager.getActiveVpc(VPC_ID)).thenReturn(vpc);
        NicProfile nicProfile = defaultProfile();

        service.updateRouterDefaultDns(vmProfile(Type.DomainRouter), nicProfile);

        assertCustomDns(nicProfile);
        verifyNoInteractions(routerNetworkDao, networksDao);
    }

    @Test
    public void updateRouterDefaultDnsLeavesExistingDnsWhenVpcCustomDnsIsBlank() {
        DomainRouterVO router = router(VPC_ID);
        when(routerDao.findById(ROUTER_ID)).thenReturn(router);
        VpcVO vpc = mock(VpcVO.class);
        when(vpc.getIp4Dns1()).thenReturn("");
        when(vpc.getIp6Dns1()).thenReturn(null);
        when(vpcManager.getActiveVpc(VPC_ID)).thenReturn(vpc);
        NicProfile nicProfile = defaultProfile();

        service.updateRouterDefaultDns(vmProfile(Type.DomainRouter), nicProfile);

        assertExistingDns(nicProfile);
        verifyNoInteractions(routerNetworkDao, networksDao);
    }

    @Test
    public void updateRouterDefaultDnsUsesSingleRouterNetworkCustomDns() {
        DomainRouterVO router = router(null);
        when(routerDao.findById(ROUTER_ID)).thenReturn(router);
        when(routerNetworkDao.getRouterNetworks(ROUTER_ID)).thenReturn(List.of(ROUTER_NETWORK_ID));
        NetworkVO routerNetwork = mock(NetworkVO.class);
        when(routerNetwork.getDns1()).thenReturn(CUSTOM_IPV4_DNS1);
        when(routerNetwork.getDns2()).thenReturn(CUSTOM_IPV4_DNS2);
        when(routerNetwork.getIp6Dns1()).thenReturn(CUSTOM_IPV6_DNS1);
        when(routerNetwork.getIp6Dns2()).thenReturn(CUSTOM_IPV6_DNS2);
        when(networksDao.findById(ROUTER_NETWORK_ID)).thenReturn(routerNetwork);
        NicProfile nicProfile = defaultProfile();

        service.updateRouterDefaultDns(vmProfile(Type.DomainRouter), nicProfile);

        assertCustomDns(nicProfile);
    }

    @Test
    public void updateRouterDefaultDnsLeavesExistingDnsWhenRouterHasNoNetwork() {
        DomainRouterVO router = router(null);
        when(routerDao.findById(ROUTER_ID)).thenReturn(router);
        when(routerNetworkDao.getRouterNetworks(ROUTER_ID)).thenReturn(Collections.emptyList());
        NicProfile nicProfile = defaultProfile();

        service.updateRouterDefaultDns(vmProfile(Type.DomainRouter), nicProfile);

        assertExistingDns(nicProfile);
        verifyNoInteractions(networksDao);
    }

    @Test
    public void updateRouterDefaultDnsLeavesExistingDnsWhenRouterHasMultipleNetworks() {
        DomainRouterVO router = router(null);
        when(routerDao.findById(ROUTER_ID)).thenReturn(router);
        when(routerNetworkDao.getRouterNetworks(ROUTER_ID)).thenReturn(Arrays.asList(ROUTER_NETWORK_ID, ROUTER_NETWORK_ID + 1));
        NicProfile nicProfile = defaultProfile();

        service.updateRouterDefaultDns(vmProfile(Type.DomainRouter), nicProfile);

        assertExistingDns(nicProfile);
        verifyNoInteractions(networksDao);
    }

    private VirtualMachineProfile vmProfile(Type type) {
        VirtualMachineProfile vmProfile = mock(VirtualMachineProfile.class);
        when(vmProfile.getType()).thenReturn(type);
        when(vmProfile.getId()).thenReturn(ROUTER_ID);
        return vmProfile;
    }

    private DomainRouterVO router(Long vpcId) {
        DomainRouterVO router = mock(DomainRouterVO.class);
        when(router.getVpcId()).thenReturn(vpcId);
        return router;
    }

    private NicProfile defaultProfile() {
        return profile(true);
    }

    private NicProfile profile(boolean defaultNic) {
        NicProfile nicProfile = new NicProfile();
        nicProfile.setDefaultNic(defaultNic);
        nicProfile.setIPv4Dns1(EXISTING_IPV4_DNS1);
        nicProfile.setIPv4Dns2(EXISTING_IPV4_DNS2);
        nicProfile.setIPv6Dns1(EXISTING_IPV6_DNS1);
        nicProfile.setIPv6Dns2(EXISTING_IPV6_DNS2);
        return nicProfile;
    }

    private void assertExistingDns(NicProfile nicProfile) {
        assertEquals(EXISTING_IPV4_DNS1, nicProfile.getIPv4Dns1());
        assertEquals(EXISTING_IPV4_DNS2, nicProfile.getIPv4Dns2());
        assertEquals(EXISTING_IPV6_DNS1, nicProfile.getIPv6Dns1());
        assertEquals(EXISTING_IPV6_DNS2, nicProfile.getIPv6Dns2());
    }

    private void assertCustomDns(NicProfile nicProfile) {
        assertEquals(CUSTOM_IPV4_DNS1, nicProfile.getIPv4Dns1());
        assertEquals(CUSTOM_IPV4_DNS2, nicProfile.getIPv4Dns2());
        assertEquals(CUSTOM_IPV6_DNS1, nicProfile.getIPv6Dns1());
        assertEquals(CUSTOM_IPV6_DNS2, nicProfile.getIPv6Dns2());
    }
}
