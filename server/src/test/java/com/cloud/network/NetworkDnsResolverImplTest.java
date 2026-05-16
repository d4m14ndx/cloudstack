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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.dc.DataCenter;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.vpc.VpcVO;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.utils.Pair;

/**
 * Focused unit tests for {@link NetworkDnsResolverImpl}. Covers the
 * IPv4/IPv6 fallback chain (network -> VPC -> zone) and the user-input
 * validation rules for DNS pairs.
 */
@RunWith(MockitoJUnitRunner.class)
public class NetworkDnsResolverImplTest {

    private static final String IP4_NETWORK_DNS1 = "5.5.5.5";
    private static final String IP4_NETWORK_DNS2 = "7.7.7.7";
    private static final String IP4_VPC_DNS1 = "9.9.9.9";
    private static final String IP4_VPC_DNS2 = "10.10.10.10";
    private static final String IP4_ZONE_DNS1 = "6.6.6.6";
    private static final String IP4_ZONE_DNS2 = "8.8.8.8";

    private static final String IP6_NETWORK_DNS1 = "2001:4860:4860::5555";
    private static final String IP6_NETWORK_DNS2 = "2001:4860:4860::7777";
    private static final String IP6_VPC_DNS1 = "2001:4860:4860::9999";
    private static final String IP6_VPC_DNS2 = "2001:4860:4860::AAAA";
    private static final String IP6_ZONE_DNS1 = "2001:4860:4860::6666";
    private static final String IP6_ZONE_DNS2 = "2001:4860:4860::8888";

    @Mock
    private VpcDao vpcDao;

    @InjectMocks
    private NetworkDnsResolverImpl resolver;

    private Network network;
    private DataCenter zone;
    private VpcVO vpc;

    @Before
    public void setUp() {
        network = mock(Network.class);
        zone = mock(DataCenter.class);
        vpc = mock(VpcVO.class);
    }

    // --- getNetworkIp4Dns ----------------------------------------------------

    @Test
    public void getNetworkIp4Dns_networkHasDns_returnsNetworkPair() {
        when(network.getDns1()).thenReturn(IP4_NETWORK_DNS1);
        when(network.getDns2()).thenReturn(IP4_NETWORK_DNS2);

        Pair<String, String> result = resolver.getNetworkIp4Dns(network, zone);

        assertEquals(IP4_NETWORK_DNS1, result.first());
        assertEquals(IP4_NETWORK_DNS2, result.second());
        // network short-circuits the chain — vpcDao must not be hit
        verify(vpcDao, never()).findById(anyLong());
    }

    @Test
    public void getNetworkIp4Dns_networkBlankVpcHasDns_returnsVpcPair() {
        when(network.getDns1()).thenReturn(null);
        when(network.getVpcId()).thenReturn(42L);
        when(vpcDao.findById(42L)).thenReturn(vpc);
        when(vpc.getIp4Dns1()).thenReturn(IP4_VPC_DNS1);
        when(vpc.getIp4Dns2()).thenReturn(IP4_VPC_DNS2);

        Pair<String, String> result = resolver.getNetworkIp4Dns(network, zone);

        assertEquals(IP4_VPC_DNS1, result.first());
        assertEquals(IP4_VPC_DNS2, result.second());
    }

    @Test
    public void getNetworkIp4Dns_noNetworkNoVpc_returnsZonePair() {
        when(network.getDns1()).thenReturn(null);
        when(network.getVpcId()).thenReturn(null);
        when(zone.getDns1()).thenReturn(IP4_ZONE_DNS1);
        when(zone.getDns2()).thenReturn(IP4_ZONE_DNS2);

        Pair<String, String> result = resolver.getNetworkIp4Dns(network, zone);

        assertEquals(IP4_ZONE_DNS1, result.first());
        assertEquals(IP4_ZONE_DNS2, result.second());
        verify(vpcDao, never()).findById(anyLong());
    }

    @Test
    public void getNetworkIp4Dns_vpcMissingFallsThroughToZone() {
        when(network.getDns1()).thenReturn(null);
        when(network.getVpcId()).thenReturn(42L);
        when(vpcDao.findById(42L)).thenReturn(null);
        when(zone.getDns1()).thenReturn(IP4_ZONE_DNS1);
        when(zone.getDns2()).thenReturn(IP4_ZONE_DNS2);

        Pair<String, String> result = resolver.getNetworkIp4Dns(network, zone);

        assertEquals(IP4_ZONE_DNS1, result.first());
        assertEquals(IP4_ZONE_DNS2, result.second());
    }

    @Test
    public void getNetworkIp4Dns_vpcBlankFallsThroughToZone() {
        when(network.getDns1()).thenReturn(null);
        when(network.getVpcId()).thenReturn(42L);
        when(vpcDao.findById(42L)).thenReturn(vpc);
        when(vpc.getIp4Dns1()).thenReturn(null);
        when(zone.getDns1()).thenReturn(IP4_ZONE_DNS1);
        when(zone.getDns2()).thenReturn(IP4_ZONE_DNS2);

        Pair<String, String> result = resolver.getNetworkIp4Dns(network, zone);

        assertEquals(IP4_ZONE_DNS1, result.first());
        assertEquals(IP4_ZONE_DNS2, result.second());
    }

    @Test
    public void getNetworkIp4Dns_nothingSet_returnsNullPair() {
        when(network.getDns1()).thenReturn(null);
        when(network.getVpcId()).thenReturn(null);
        when(zone.getDns1()).thenReturn(null);
        when(zone.getDns2()).thenReturn(null);

        Pair<String, String> result = resolver.getNetworkIp4Dns(network, zone);

        assertNull(result.first());
        assertNull(result.second());
    }

    // --- getNetworkIp6Dns ----------------------------------------------------

    @Test
    public void getNetworkIp6Dns_networkHasDns_returnsNetworkPair() {
        when(network.getIp6Dns1()).thenReturn(IP6_NETWORK_DNS1);
        when(network.getIp6Dns2()).thenReturn(IP6_NETWORK_DNS2);

        Pair<String, String> result = resolver.getNetworkIp6Dns(network, zone);

        assertEquals(IP6_NETWORK_DNS1, result.first());
        assertEquals(IP6_NETWORK_DNS2, result.second());
        verify(vpcDao, never()).findById(anyLong());
    }

    @Test
    public void getNetworkIp6Dns_networkBlankVpcHasDns_returnsVpcPair() {
        when(network.getIp6Dns1()).thenReturn(null);
        when(network.getVpcId()).thenReturn(42L);
        when(vpcDao.findById(42L)).thenReturn(vpc);
        when(vpc.getIp6Dns1()).thenReturn(IP6_VPC_DNS1);
        when(vpc.getIp6Dns2()).thenReturn(IP6_VPC_DNS2);

        Pair<String, String> result = resolver.getNetworkIp6Dns(network, zone);

        assertEquals(IP6_VPC_DNS1, result.first());
        assertEquals(IP6_VPC_DNS2, result.second());
    }

    @Test
    public void getNetworkIp6Dns_noNetworkNoVpc_returnsZonePair() {
        when(network.getIp6Dns1()).thenReturn(null);
        when(network.getVpcId()).thenReturn(null);
        when(zone.getIp6Dns1()).thenReturn(IP6_ZONE_DNS1);
        when(zone.getIp6Dns2()).thenReturn(IP6_ZONE_DNS2);

        Pair<String, String> result = resolver.getNetworkIp6Dns(network, zone);

        assertEquals(IP6_ZONE_DNS1, result.first());
        assertEquals(IP6_ZONE_DNS2, result.second());
    }

    @Test
    public void getNetworkIp6Dns_vpcBlankFallsThroughToZone() {
        when(network.getIp6Dns1()).thenReturn(null);
        when(network.getVpcId()).thenReturn(42L);
        when(vpcDao.findById(42L)).thenReturn(vpc);
        when(vpc.getIp6Dns1()).thenReturn(null);
        when(zone.getIp6Dns1()).thenReturn(IP6_ZONE_DNS1);
        when(zone.getIp6Dns2()).thenReturn(IP6_ZONE_DNS2);

        Pair<String, String> result = resolver.getNetworkIp6Dns(network, zone);

        assertEquals(IP6_ZONE_DNS1, result.first());
        assertEquals(IP6_ZONE_DNS2, result.second());
    }

    // --- verifyIp4DnsPair ----------------------------------------------------

    @Test
    public void verifyIp4DnsPair_bothEmpty_ok() {
        resolver.verifyIp4DnsPair(null, null);
        resolver.verifyIp4DnsPair("", "");
    }

    @Test
    public void verifyIp4DnsPair_validPair_ok() {
        resolver.verifyIp4DnsPair(IP4_NETWORK_DNS1, IP4_NETWORK_DNS2);
    }

    @Test
    public void verifyIp4DnsPair_onlyDns1Set_ok() {
        resolver.verifyIp4DnsPair(IP4_NETWORK_DNS1, null);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void verifyIp4DnsPair_dns2WithoutDns1_throws() {
        resolver.verifyIp4DnsPair(null, IP4_NETWORK_DNS2);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void verifyIp4DnsPair_invalidDns1_throws() {
        resolver.verifyIp4DnsPair("not-an-ip", IP4_NETWORK_DNS2);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void verifyIp4DnsPair_invalidDns2_throws() {
        resolver.verifyIp4DnsPair(IP4_NETWORK_DNS1, "not-an-ip");
    }

    // --- verifyIp6DnsPair ----------------------------------------------------

    @Test
    public void verifyIp6DnsPair_bothEmpty_ok() {
        resolver.verifyIp6DnsPair(null, null);
    }

    @Test
    public void verifyIp6DnsPair_validPair_ok() {
        resolver.verifyIp6DnsPair(IP6_NETWORK_DNS1, IP6_NETWORK_DNS2);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void verifyIp6DnsPair_dns2WithoutDns1_throws() {
        resolver.verifyIp6DnsPair(null, IP6_NETWORK_DNS2);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void verifyIp6DnsPair_invalidDns1_throws() {
        resolver.verifyIp6DnsPair("not-an-ipv6", IP6_NETWORK_DNS2);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void verifyIp6DnsPair_invalidDns2_throws() {
        resolver.verifyIp6DnsPair(IP6_NETWORK_DNS1, "not-an-ipv6");
    }
}
