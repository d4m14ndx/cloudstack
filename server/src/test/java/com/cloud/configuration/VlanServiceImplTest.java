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
package com.cloud.configuration;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import com.cloud.dc.Vlan;
import com.cloud.dc.VlanVO;
import com.cloud.dc.AccountVlanMapVO;
import com.cloud.dc.DomainVlanMapVO;
import com.cloud.dc.dao.AccountVlanMapDao;
import com.cloud.dc.dao.DomainVlanMapDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.domain.Domain;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.utils.Pair;
import com.cloud.utils.net.NetUtils;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link VlanServiceImpl} — the slice-7 extraction from
 * {@code ConfigurationManagerImpl}.
 */
public class VlanServiceImplTest {

    @Spy
    @InjectMocks
    VlanServiceImpl vlanService = new VlanServiceImpl();

    @Mock VlanDao _vlanDao;
    @Mock AccountVlanMapDao _accountVlanMapDao;
    @Mock DomainVlanMapDao _domainVlanMapDao;
    @Mock DomainDao _domainDao;
    @Mock AccountManager _accountMgr;
    @Mock NetworkModel _networkModel;
    @Mock Network network;
    @Mock VlanVO vlan;

    private AutoCloseable closeable;

    @Before
    public void setUp() {
        closeable = MockitoAnnotations.openMocks(this);
    }

    @After
    public void tearDown() throws Exception {
        closeable.close();
    }

    // ---------- checkIfSubsetOrSuperset ----------

    @Test
    public void checkIfSubsetOrSupersetSameSubnetReturnsSameSubnet() {
        NetUtils.SupersetOrSubset result = vlanService.checkIfSubsetOrSuperset(
                "10.0.0.1", "255.255.255.0", null, null, "10.0.0.2", "10.0.0.10");
        Assert.assertEquals(NetUtils.SupersetOrSubset.sameSubnet, result);
    }

    @Test
    public void checkIfSubsetOrSupersetNoSubnetMatchReturnsNeither() {
        NetUtils.SupersetOrSubset result = vlanService.checkIfSubsetOrSuperset(
                "10.0.0.1", "255.255.255.0", null, null, "11.0.0.2", "11.0.0.10");
        Assert.assertEquals(NetUtils.SupersetOrSubset.neitherSubetNorSuperset, result);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void checkIfSubsetOrSupersetOnlyGatewayProvidedThrows() {
        vlanService.checkIfSubsetOrSuperset(
                "10.0.0.1", "255.255.255.0", "10.0.0.2", null, "10.0.0.5", "10.0.0.10");
    }

    @Test(expected = InvalidParameterValueException.class)
    public void checkIfSubsetOrSupersetStartIpDifferentSubnetThrows() {
        vlanService.checkIfSubsetOrSuperset(
                "10.0.0.1", "255.255.255.0", "10.0.0.2", "255.255.255.0", "11.0.0.5", "10.0.0.10");
    }

    @Test
    public void checkIfSubsetOrSupersetExplicitSameRange() {
        NetUtils.SupersetOrSubset result = vlanService.checkIfSubsetOrSuperset(
                "10.0.0.1", "255.255.255.0", "10.0.0.1", "255.255.255.0", "10.0.0.5", "10.0.0.10");
        Assert.assertEquals(NetUtils.SupersetOrSubset.sameSubnet, result);
    }

    // ---------- hasSameSubnet ----------

    @Test
    public void hasSameSubnetNeitherIpv4NorIpv6ReturnsFalse() {
        boolean result = vlanService.hasSameSubnet(false, null, null, null, null,
                null, null, false, null, null, null, null, null);
        Assert.assertFalse(result);
    }

    @Test
    public void hasSameSubnetIpv4SameSubnetReturnsTrue() {
        boolean result = vlanService.hasSameSubnet(true, "10.0.0.1", "255.255.255.0",
                "10.0.0.1", "255.255.255.0", "10.0.0.2", "10.0.0.10",
                false, null, null, null, null, null);
        Assert.assertTrue(result);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void hasSameSubnetIpv4DifferentGatewayThrows() {
        vlanService.hasSameSubnet(true, "10.0.0.1", "255.255.255.0",
                "10.0.0.2", "255.255.255.0", "10.0.0.2", "10.0.0.10",
                false, null, null, null, null, null);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void hasSameSubnetIpv4SubsetThrows() {
        vlanService.hasSameSubnet(true, "10.0.0.1", "255.255.0.0",
                "10.0.0.2", "255.255.255.0", "10.0.0.2", "10.0.0.10",
                false, null, null, null, null, null);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void hasSameSubnetIpv4SupersetThrows() {
        vlanService.hasSameSubnet(true, "10.0.0.1", "255.255.255.0",
                "10.0.0.2", "255.255.0.0", "10.0.0.2", "10.0.0.10",
                false, null, null, null, null, null);
    }

    // ---------- validateIpRange ----------

    @Test(expected = InvalidParameterValueException.class)
    public void validateIpRangeNoVlansAndNoGatewayThrows() {
        List<VlanVO> emptyVlans = new ArrayList<>();
        when(network.getGateway()).thenReturn(null);
        vlanService.validateIpRange("10.0.0.5", "10.0.0.10", null, null, emptyVlans,
                true, false, null, null, null, null, network);
    }

    @Test
    public void validateIpRangeReturnsNewSubnetWhenNoOverlap() {
        List<VlanVO> emptyVlans = new ArrayList<>();
        when(network.getGateway()).thenReturn(null);
        Pair<Boolean, Pair<String, String>> result = vlanService.validateIpRange(
                "10.0.0.5", "10.0.0.10", "10.0.0.1", "255.255.255.0", emptyVlans,
                true, false, null, null, null, null, network);
        Assert.assertFalse(result.first());
        Assert.assertEquals("10.0.0.1", result.second().first());
    }

    // ---------- getVlanAccount ----------

    @Test
    public void getVlanAccountVirtualNetworkReturnsAccount() {
        when(vlan.getVlanType()).thenReturn(Vlan.VlanType.VirtualNetwork);
        when(_vlanDao.findById(1L)).thenReturn(vlan);
        AccountVlanMapVO map = new AccountVlanMapVO(42L, 1L);
        when(_accountVlanMapDao.listAccountVlanMapsByVlan(1L)).thenReturn(List.of(map));
        Account expected = new AccountVO("acct", 1, "domain", Account.Type.NORMAL, java.util.UUID.randomUUID().toString());
        when(_accountMgr.getAccount(42L)).thenReturn(expected);

        Account result = vlanService.getVlanAccount(1L);
        Assert.assertSame(expected, result);
    }

    @Test
    public void getVlanAccountDirectAttachedReturnsNull() {
        when(vlan.getVlanType()).thenReturn(Vlan.VlanType.DirectAttached);
        when(_vlanDao.findById(1L)).thenReturn(vlan);
        Account result = vlanService.getVlanAccount(1L);
        Assert.assertNull(result);
    }

    @Test
    public void getVlanAccountNoMapsReturnsNull() {
        when(vlan.getVlanType()).thenReturn(Vlan.VlanType.VirtualNetwork);
        when(_vlanDao.findById(1L)).thenReturn(vlan);
        when(_accountVlanMapDao.listAccountVlanMapsByVlan(1L)).thenReturn(new ArrayList<>());
        Account result = vlanService.getVlanAccount(1L);
        Assert.assertNull(result);
    }

    // ---------- getVlanDomain ----------

    @Test
    public void getVlanDomainVirtualNetworkReturnsDomain() {
        when(vlan.getVlanType()).thenReturn(Vlan.VlanType.VirtualNetwork);
        when(_vlanDao.findById(2L)).thenReturn(vlan);
        DomainVlanMapVO map = new DomainVlanMapVO(7L, 2L);
        when(_domainVlanMapDao.listDomainVlanMapsByVlan(2L)).thenReturn(List.of(map));
        DomainVO expected = new DomainVO();
        when(_domainDao.findById(7L)).thenReturn(expected);

        Domain result = vlanService.getVlanDomain(2L);
        Assert.assertSame(expected, result);
    }

    @Test
    public void getVlanDomainDirectAttachedReturnsNull() {
        when(vlan.getVlanType()).thenReturn(Vlan.VlanType.DirectAttached);
        when(_vlanDao.findById(3L)).thenReturn(vlan);
        Domain result = vlanService.getVlanDomain(3L);
        Assert.assertNull(result);
    }

    @Test
    public void getVlanDomainNoMapsReturnsNull() {
        when(vlan.getVlanType()).thenReturn(Vlan.VlanType.VirtualNetwork);
        when(_vlanDao.findById(4L)).thenReturn(vlan);
        when(_domainVlanMapDao.listDomainVlanMapsByVlan(4L)).thenReturn(new ArrayList<>());
        Domain result = vlanService.getVlanDomain(4L);
        Assert.assertNull(result);
    }

    // ---------- releaseDomainSpecificVirtualRanges / releaseAccountSpecificVirtualRanges ----------

    @Test
    public void releaseDomainSpecificVirtualRangesNoMapsReturnsTrue() {
        Domain domain = new DomainVO();
        when(_domainVlanMapDao.listDomainVlanMapsByDomain(anyLong())).thenReturn(new ArrayList<>());
        boolean result = vlanService.releaseDomainSpecificVirtualRanges(domain);
        Assert.assertTrue(result);
    }

    @Test
    public void releaseAccountSpecificVirtualRangesNoMapsReturnsTrue() {
        Account account = new AccountVO("acct", 1, "domain", Account.Type.NORMAL, java.util.UUID.randomUUID().toString());
        when(_accountVlanMapDao.listAccountVlanMapsByAccount(anyLong())).thenReturn(new ArrayList<>());
        boolean result = vlanService.releaseAccountSpecificVirtualRanges(account);
        Assert.assertTrue(result);
    }

    @Test
    public void releaseAccountSpecificVirtualRangesNullListReturnsTrue() {
        Account account = new AccountVO("acct", 1, "domain", Account.Type.NORMAL, java.util.UUID.randomUUID().toString());
        when(_accountVlanMapDao.listAccountVlanMapsByAccount(anyLong())).thenReturn(null);
        boolean result = vlanService.releaseAccountSpecificVirtualRanges(account);
        Assert.assertTrue(result);
    }

    // ---------- IPv6 hasSameSubnet ----------

    @Test
    public void hasSameSubnetIpv6MatchingNetworkReturnsTrue() {
        when(network.getIp6Gateway()).thenReturn("2001:db8:0:f101::1");
        when(network.getIp6Cidr()).thenReturn("2001:db8:0:f101::0/64");
        when(network.getGuestType()).thenReturn(Network.GuestType.Shared);
        boolean result = vlanService.hasSameSubnet(false, null, null, null, null, null, null,
                true, "2001:db8:0:f101::1", "2001:db8:0:f101::0/64",
                "2001:db8:0:f101::2", "2001:db8:0:f101::a", network);
        Assert.assertTrue(result);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void hasSameSubnetIpv6MismatchedGatewayThrows() {
        when(network.getIp6Gateway()).thenReturn("2001:db8:0:f101::1");
        when(network.getIp6Cidr()).thenReturn("2001:db8:0:f101::0/64");
        vlanService.hasSameSubnet(false, null, null, null, null, null, null,
                true, "2001:db8:0:f101::2", "2001:db8:0:f101::0/64",
                "2001:db8:0:f101::2", "2001:db8:0:f101::a", network);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void hasSameSubnetIpv6MismatchedCidrThrows() {
        when(network.getIp6Gateway()).thenReturn("2001:db8:0:f101::1");
        when(network.getIp6Cidr()).thenReturn("2001:db8:0:f101::0/64");
        vlanService.hasSameSubnet(false, null, null, null, null, null, null,
                true, "2001:db8:0:f101::1", "2001:db8:0:f101::0/63",
                "2001:db8:0:f101::2", "2001:db8:0:f101::a", network);
    }

    @Test
    public void hasSameSubnetIpv6NullGatewayAndCidrFallsBackToNetwork() {
        when(network.getIp6Gateway()).thenReturn("2001:db8:0:f101::1");
        when(network.getIp6Cidr()).thenReturn("2001:db8:0:f101::0/64");
        when(network.getGuestType()).thenReturn(Network.GuestType.Shared);
        boolean result = vlanService.hasSameSubnet(false, null, null, null, null, null, null,
                true, null, null, "2001:db8:0:f101::2", "2001:db8:0:f101::a", network);
        Assert.assertTrue(result);
    }

    // ---------- validateIpRange existing-subnet match ----------

    @Test
    public void validateIpRangeReturnsSameSubnetWhenMatch() {
        VlanVO existing = mock(VlanVO.class);
        when(existing.getVlanGateway()).thenReturn("10.147.33.1");
        when(existing.getVlanNetmask()).thenReturn("255.255.255.128");
        List<VlanVO> vlans = List.of(existing);
        Pair<Boolean, Pair<String, String>> result = vlanService.validateIpRange(
                "10.147.33.104", "10.147.33.105", "10.147.33.1", "255.255.255.128", vlans,
                true, false, null, null, null, null, network);
        Assert.assertTrue(result.first());
        Assert.assertEquals("10.147.33.1", result.second().first());
    }
}
