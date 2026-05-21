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
package com.cloud.network;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.api.ApiDBUtils;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.IpAddress.State;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.addr.PublicIp;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.rules.FirewallRule.Purpose;
import com.cloud.network.rules.FirewallRuleVO;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.Ip;

@RunWith(MockitoJUnitRunner.class)
public class NetworkOfferingIpCompatibilityServiceImplTest {

    private static final long OFFERING_ID = 200L;

    @Mock
    NetworkModel networkModel;
    @Mock
    NetworkOfferingDao networkOfferingDao;
    @Mock
    FirewallRulesDao firewallRulesDao;
    @Mock
    NetworkOfferingVO offering;

    @InjectMocks
    NetworkOfferingIpCompatibilityServiceImpl service;

    @Before
    public void setUp() {
        when(networkOfferingDao.findById(OFFERING_ID)).thenReturn(offering);
        when(networkModel.getNetworkOfferingServiceProvidersMap(OFFERING_ID)).thenReturn(new HashMap<>());
    }

    @Test
    public void canIpsUseOffering_allowsLbAndFirewallOnInlineOfferingByUsingFirewallProviderForLb() {
        PublicIp ip = mockPublicIp(1L, "10.0.0.1");
        List<FirewallRuleVO> rules = List.of(mockRule(Purpose.LoadBalancing), mockRule(Purpose.Firewall));
        when(firewallRulesDao.listByIpAndNotRevoked(1L)).thenReturn(rules);
        when(offering.isInline()).thenReturn(true);
        when(networkModel.getNetworkOfferingServiceProvidersMap(OFFERING_ID)).thenReturn(providerMap(
                Service.Lb, Provider.Netscaler,
                Service.Firewall, Provider.VirtualRouter));

        assertTrue(service.canIpsUseOffering(List.of(ip), OFFERING_ID));
    }

    @Test
    public void canIpsUseOffering_throwsWhenRulesNeedDifferentProviders() {
        PublicIp ip = mockPublicIp(1L, "10.0.0.1");
        List<FirewallRuleVO> rules = List.of(mockRule(Purpose.PortForwarding), mockRule(Purpose.StaticNat));
        when(firewallRulesDao.listByIpAndNotRevoked(1L)).thenReturn(rules);
        when(networkModel.getNetworkOfferingServiceProvidersMap(OFFERING_ID)).thenReturn(providerMap(
                Service.PortForwarding, Provider.VirtualRouter,
                Service.StaticNat, Provider.Netscaler));

        assertThrows(InvalidParameterValueException.class,
                () -> service.canIpsUseOffering(List.of(ip), OFFERING_ID));
    }

    @Test
    public void canIpUsedForNonConserveService_throwsWhenIpHasMultipleServices() {
        PublicIp ip = mockPublicIp(1L, "10.0.0.1");
        List<FirewallRuleVO> rules = List.of(mockRule(Purpose.LoadBalancing), mockRule(Purpose.PortForwarding));
        when(firewallRulesDao.listByIpAndNotRevoked(1L)).thenReturn(rules);

        assertThrows(InvalidParameterValueException.class,
                () -> service.canIpUsedForNonConserveService(ip, null));
    }

    @Test
    public void canIpsUseOffering_preservesDuplicateSourceNatException() {
        PublicIp firstIp = mockPublicIp(1L, "10.0.0.1");
        PublicIp secondIp = mockPublicIp(2L, "10.0.0.2");
        when(firstIp.isSourceNat()).thenReturn(true);
        when(secondIp.isSourceNat()).thenReturn(true);
        when(firstIp.getAssociatedWithNetworkId()).thenReturn(10L);
        when(secondIp.getAssociatedWithNetworkId()).thenReturn(10L);

        try (MockedStatic<ApiDBUtils> apiDBUtils = mockStatic(ApiDBUtils.class)) {
            apiDBUtils.when(() -> ApiDBUtils.findIpAddressById(10L)).thenReturn(null);

            assertThrows(CloudRuntimeException.class,
                    () -> service.canIpsUseOffering(List.of(firstIp, secondIp), OFFERING_ID));
        }
    }

    @Test
    public void canIpsUseOffering_keepsNullIpListBehavior() {
        assertTrue(service.canIpsUseOffering(null, OFFERING_ID));

        verify(networkModel).getNetworkOfferingServiceProvidersMap(OFFERING_ID);
        verify(networkOfferingDao).findById(OFFERING_ID);
    }

    private PublicIp mockPublicIp(long id, String address) {
        PublicIp ip = mock(PublicIp.class);
        when(ip.getId()).thenReturn(id);
        when(ip.getAddress()).thenReturn(new Ip(address));
        when(ip.getState()).thenReturn(State.Allocated);
        return ip;
    }

    private FirewallRuleVO mockRule(Purpose purpose) {
        FirewallRuleVO rule = mock(FirewallRuleVO.class);
        when(rule.getPurpose()).thenReturn(purpose);
        return rule;
    }

    private Map<Service, Set<Provider>> providerMap(Service firstService, Provider firstProvider,
            Service secondService, Provider secondProvider) {
        Map<Service, Set<Provider>> providerMap = new HashMap<>();
        providerMap.put(firstService, Collections.singleton(firstProvider));
        providerMap.put(secondService, Collections.singleton(secondProvider));
        return providerMap;
    }
}
