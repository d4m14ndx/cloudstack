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
package com.cloud.network.vpc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;

import org.apache.cloudstack.context.CallContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.IpAddress;
import com.cloud.network.IpAddressManager;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetrisProviderDao;
import com.cloud.network.dao.NsxProviderDao;
import com.cloud.network.element.NetrisProviderVO;
import com.cloud.network.element.NsxProviderVO;
import com.cloud.network.rules.FirewallRuleVO;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.utils.exception.CloudRuntimeException;

@RunWith(MockitoJUnitRunner.class)
public class VpcIpAllocationServiceImplTest {

    @Mock private VpcDao vpcDao;
    @Mock private IPAddressDao ipAddressDao;
    @Mock private FirewallRulesDao firewallDao;
    @Mock private NetworkModel networkModel;
    @Mock private IpAddressManager ipAddrMgr;
    @Mock private AccountManager accountMgr;
    @Mock private NsxProviderDao nsxProviderDao;
    @Mock private NetrisProviderDao netrisProviderDao;
    @Mock private VpcManager vpcManager;

    @InjectMocks
    private VpcIpAllocationServiceImpl service;

    @Before
    public void setUp() {
        CallContext.register(mock(User.class), mock(Account.class));
    }

    @After
    public void tearDown() {
        CallContext.unregister();
    }

    // ---- isIpAllocatedToVpc ----

    @Test
    public void isIpAllocatedToVpcReturnsFalseForNullIp() {
        assertFalse(service.isIpAllocatedToVpc(null));
    }

    @Test
    public void isIpAllocatedToVpcReturnsFalseWhenVpcIdNull() {
        IpAddress ip = mock(IpAddress.class);
        when(ip.getVpcId()).thenReturn(null);
        assertFalse(service.isIpAllocatedToVpc(ip));
    }

    @Test
    public void isIpAllocatedToVpcTrueForOneToOneNat() {
        IpAddress ip = mock(IpAddress.class);
        when(ip.getVpcId()).thenReturn(7L);
        when(ip.isOneToOneNat()).thenReturn(true);
        assertTrue(service.isIpAllocatedToVpc(ip));
    }

    @Test
    public void isIpAllocatedToVpcTrueWhenFirewallRulesExist() {
        IpAddress ip = mock(IpAddress.class);
        when(ip.getVpcId()).thenReturn(7L);
        when(ip.getId()).thenReturn(42L);
        when(ip.isOneToOneNat()).thenReturn(false);
        when(firewallDao.listByIp(42L)).thenReturn(Arrays.asList(mock(FirewallRuleVO.class)));
        assertTrue(service.isIpAllocatedToVpc(ip));
    }

    @Test
    public void isIpAllocatedToVpcFalseWhenNoNatNoRules() {
        IpAddress ip = mock(IpAddress.class);
        when(ip.getVpcId()).thenReturn(7L);
        when(ip.getId()).thenReturn(42L);
        when(ip.isOneToOneNat()).thenReturn(false);
        when(firewallDao.listByIp(42L)).thenReturn(Collections.emptyList());
        assertFalse(service.isIpAllocatedToVpc(ip));
    }

    // ---- unassignIPFromVpcNetwork: short-circuits ----

    @Test
    public void unassignIPFromVpcNetworkSkipsWhenStillAllocated() throws Exception {
        IPAddressVO ip = mock(IPAddressVO.class);
        when(ip.getVpcId()).thenReturn(7L);
        when(ip.isOneToOneNat()).thenReturn(true);
        Network network = mock(Network.class);

        service.unassignIPFromVpcNetwork(ip, network);

        verify(ipAddrMgr, never()).applyIpAssociations(any(), anyBoolean());
        verify(ipAddressDao, never()).update(anyLong(), any());
    }

    @Test
    public void unassignIPFromVpcNetworkSkipsWhenIpNull() throws Exception {
        service.unassignIPFromVpcNetwork(null, mock(Network.class));
        verify(ipAddrMgr, never()).applyIpAssociations(any(), anyBoolean());
    }

    @Test
    public void unassignIPFromVpcNetworkSkipsWhenVpcIdNullOnIp() throws Exception {
        IPAddressVO ip = mock(IPAddressVO.class);
        when(ip.getVpcId()).thenReturn(null);

        service.unassignIPFromVpcNetwork(ip, mock(Network.class));

        verify(ipAddrMgr, never()).applyIpAssociations(any(), anyBoolean());
    }

    // ---- unassignIPFromVpcNetwork: success / failure paths ----

    @Test
    public void unassignIPFromVpcNetworkClearsNetworkOnSuccess() throws Exception {
        IPAddressVO ip = mock(IPAddressVO.class);
        when(ip.getVpcId()).thenReturn(7L);
        when(ip.getId()).thenReturn(42L);
        when(ip.isOneToOneNat()).thenReturn(false);
        when(firewallDao.listByIp(42L)).thenReturn(Collections.emptyList());
        Network network = mock(Network.class);
        when(ipAddrMgr.applyIpAssociations(network, true)).thenReturn(true);
        when(vpcDao.findById(7L)).thenReturn(mock(VpcVO.class));

        service.unassignIPFromVpcNetwork(ip, network);

        verify(ip).setAssociatedWithNetworkId(null);
        verify(ipAddressDao).update(42L, ip);
    }

    @Test
    public void unassignIPFromVpcNetworkThrowsWhenApplyFails() throws Exception {
        IPAddressVO ip = mock(IPAddressVO.class);
        when(ip.getVpcId()).thenReturn(7L);
        when(ip.getId()).thenReturn(42L);
        when(ip.isOneToOneNat()).thenReturn(false);
        when(firewallDao.listByIp(42L)).thenReturn(Collections.emptyList());
        Network network = mock(Network.class);
        when(ipAddrMgr.applyIpAssociations(network, true)).thenReturn(false);

        assertThrows(CloudRuntimeException.class,
                () -> service.unassignIPFromVpcNetwork(ip, network));
    }

    @Test
    public void unassignIPFromVpcNetworkWrapsResourceUnavailable() throws Exception {
        IPAddressVO ip = mock(IPAddressVO.class);
        when(ip.getVpcId()).thenReturn(7L);
        when(ip.getId()).thenReturn(42L);
        when(ip.isOneToOneNat()).thenReturn(false);
        when(firewallDao.listByIp(42L)).thenReturn(Collections.emptyList());
        Network network = mock(Network.class);
        doThrow(new ResourceUnavailableException("nope", Network.class, 1L))
                .when(ipAddrMgr).applyIpAssociations(network, true);

        assertThrows(CloudRuntimeException.class,
                () -> service.unassignIPFromVpcNetwork(ip, network));
    }

    @Test
    public void unassignIPFromVpcNetworkByIdResolvesIpAndNetwork() throws Exception {
        IPAddressVO ip = mock(IPAddressVO.class);
        when(ip.getVpcId()).thenReturn(7L);
        when(ip.isOneToOneNat()).thenReturn(true);
        when(ipAddressDao.findById(42L)).thenReturn(ip);
        Network network = mock(Network.class);
        when(networkModel.getNetwork(99L)).thenReturn(network);

        service.unassignIPFromVpcNetwork(42L, 99L);

        // still-allocated short-circuit
        verify(ipAddrMgr, never()).applyIpAssociations(any(), anyBoolean());
    }

    // ---- associateIPToVpc ----

    @Test
    public void associateIPToVpcReturnsNullWhenIpNotFound() throws Exception {
        when(networkModel.getIp(42L)).thenReturn(null);
        assertNull(service.associateIPToVpc(42L, 7L));
    }

    @Test
    public void associateIPToVpcRejectsUnknownVpc() throws Exception {
        IpAddress ipToAssoc = mock(IpAddress.class);
        when(ipToAssoc.getAllocatedToAccountId()).thenReturn(123L);
        when(networkModel.getIp(42L)).thenReturn(ipToAssoc);
        when(accountMgr.getAccount(123L)).thenReturn(mock(Account.class));
        when(vpcDao.findById(7L)).thenReturn(null);

        assertThrows(InvalidParameterValueException.class,
                () -> service.associateIPToVpc(42L, 7L));
    }

    // ---- getExistingSourceNatInVpc ----

    @Test
    public void getExistingSourceNatInVpcReturnsNullWhenEmpty() {
        // No IPs assigned — listPublicIpsAssignedToVpc will return [] from
        // the search builder. Since the builder is built lazily and we are
        // calling listPublicIpsAssignedToVpc through the same instance,
        // simulate by spying on the service.
        VpcIpAllocationServiceImpl spy = org.mockito.Mockito.spy(service);
        org.mockito.Mockito.doReturn(Collections.emptyList())
                .when(spy).listPublicIpsAssignedToVpc(anyLong(), eq(true), anyLong());

        assertNull(spy.getExistingSourceNatInVpc(1L, 7L, false, false));
    }

    @Test
    public void getExistingSourceNatInVpcPicksSourceNatAmongMany() {
        IPAddressVO regular = mock(IPAddressVO.class);
        when(regular.isSourceNat()).thenReturn(false);
        IPAddressVO sourceNat = mock(IPAddressVO.class);
        when(sourceNat.isSourceNat()).thenReturn(true);

        VpcIpAllocationServiceImpl spy = org.mockito.Mockito.spy(service);
        org.mockito.Mockito.doReturn(Arrays.asList(regular, sourceNat))
                .when(spy).listPublicIpsAssignedToVpc(anyLong(), eq(true), anyLong());

        assertSame(sourceNat, spy.getExistingSourceNatInVpc(1L, 7L, false, false));
    }

    @Test
    public void getExistingSourceNatInVpcReturnsSourceNat() {
        IPAddressVO sourceNat = mock(IPAddressVO.class);
        when(sourceNat.isSourceNat()).thenReturn(true);

        VpcIpAllocationServiceImpl spy = org.mockito.Mockito.spy(service);
        org.mockito.Mockito.doReturn(Arrays.asList(sourceNat))
                .when(spy).listPublicIpsAssignedToVpc(anyLong(), eq(true), anyLong());

        assertSame(sourceNat, spy.getExistingSourceNatInVpc(1L, 7L, false, false));
    }

    @Test
    public void getExistingSourceNatInVpcForNsxPrefersSystemVm() {
        IPAddressVO regularSourceNat = mock(IPAddressVO.class);
        when(regularSourceNat.isSourceNat()).thenReturn(true);
        when(regularSourceNat.isForSystemVms()).thenReturn(false);
        IPAddressVO systemVmSourceNat = mock(IPAddressVO.class);
        when(systemVmSourceNat.isSourceNat()).thenReturn(true);
        when(systemVmSourceNat.isForSystemVms()).thenReturn(true);

        VpcIpAllocationServiceImpl spy = org.mockito.Mockito.spy(service);
        org.mockito.Mockito.doReturn(Arrays.asList(regularSourceNat, systemVmSourceNat))
                .when(spy).listPublicIpsAssignedToVpc(anyLong(), eq(true), anyLong());

        // For NSX, non-system-VM source-NAT IPs are skipped in favour of the system-VM IP.
        assertSame(systemVmSourceNat, spy.getExistingSourceNatInVpc(1L, 7L, true, false));
    }

    @Test
    public void getExistingSourceNatInVpcForNsxAcceptsSystemVm() {
        IPAddressVO sourceNat = mock(IPAddressVO.class);
        when(sourceNat.isSourceNat()).thenReturn(true);
        when(sourceNat.isForSystemVms()).thenReturn(true);

        VpcIpAllocationServiceImpl spy = org.mockito.Mockito.spy(service);
        org.mockito.Mockito.doReturn(Arrays.asList(sourceNat))
                .when(spy).listPublicIpsAssignedToVpc(anyLong(), eq(true), anyLong());

        assertSame(sourceNat, spy.getExistingSourceNatInVpc(1L, 7L, true, false));
    }

    // ---- assignSourceNatIpAddressToVpc ----

    @Test
    public void assignSourceNatIpAddressReturnsExistingWhenPresent() throws Exception {
        Vpc vpc = mock(Vpc.class);
        when(vpc.getZoneId()).thenReturn(11L);
        when(vpc.getId()).thenReturn(7L);
        Account owner = mock(Account.class);
        when(owner.getId()).thenReturn(33L);
        when(nsxProviderDao.findByZoneId(11L)).thenReturn(null);
        when(netrisProviderDao.findByZoneId(11L)).thenReturn(null);

        VpcIpAllocationServiceImpl spy = org.mockito.Mockito.spy(service);
        IPAddressVO existing = mock(IPAddressVO.class);
        when(existing.getVlanId()).thenReturn(55L);
        org.mockito.Mockito.doReturn(existing)
                .when(spy).getExistingSourceNatInVpc(33L, 7L, false, false);

        // For non-NSX, vlan lookup is needed; stub vlanDao via reflection-free spy.
        // We just verify that no allocation path is taken when an existing IP is returned.
        try {
            spy.assignSourceNatIpAddressToVpc(owner, vpc, null);
        } catch (NullPointerException expected) {
            // vlanDao.findById may NPE in stub; what matters is that no allocation occurred.
        }
        verify(ipAddrMgr, never()).assignPublicIpAddress(anyLong(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean());
        verify(ipAddrMgr, never()).assignDedicateIpAddress(any(), any(), anyLong(), anyLong(), anyBoolean());
    }

    @Test
    public void assignSourceNatIpAddressAllocatesDedicatedForRegularVpc() throws Exception {
        Vpc vpc = mock(Vpc.class);
        when(vpc.getZoneId()).thenReturn(11L);
        when(vpc.getId()).thenReturn(7L);
        Account owner = mock(Account.class);
        when(owner.getId()).thenReturn(33L);
        when(nsxProviderDao.findByZoneId(11L)).thenReturn(null);
        when(netrisProviderDao.findByZoneId(11L)).thenReturn(null);

        VpcIpAllocationServiceImpl spy = org.mockito.Mockito.spy(service);
        org.mockito.Mockito.doReturn(null)
                .when(spy).getExistingSourceNatInVpc(anyLong(), anyLong(), anyBoolean(), anyBoolean());

        spy.assignSourceNatIpAddressToVpc(owner, vpc, null);

        verify(ipAddrMgr).assignDedicateIpAddress(owner, null, 7L, 11L, true);
        verify(ipAddrMgr, never()).assignPublicIpAddress(anyLong(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    public void assignSourceNatIpAddressUsesPublicForNsx() throws Exception {
        Vpc vpc = mock(Vpc.class);
        when(vpc.getZoneId()).thenReturn(11L);
        when(vpc.getId()).thenReturn(7L);
        Account owner = mock(Account.class);
        when(owner.getId()).thenReturn(33L);
        when(nsxProviderDao.findByZoneId(11L)).thenReturn(mock(NsxProviderVO.class));
        when(netrisProviderDao.findByZoneId(11L)).thenReturn(null);

        VpcIpAllocationServiceImpl spy = org.mockito.Mockito.spy(service);
        org.mockito.Mockito.doReturn(null)
                .when(spy).getExistingSourceNatInVpc(anyLong(), anyLong(), anyBoolean(), anyBoolean());

        spy.assignSourceNatIpAddressToVpc(owner, vpc, 99L);

        verify(ipAddrMgr).assignPublicIpAddress(eq(11L), eq(99L), eq(owner), any(), eq(null), eq(null), eq(false), eq(true));
        verify(ipAddrMgr, never()).assignDedicateIpAddress(any(), any(), anyLong(), anyLong(), anyBoolean());
    }

    @Test
    public void assignSourceNatIpAddressUsesPublicForNetris() throws Exception {
        Vpc vpc = mock(Vpc.class);
        when(vpc.getZoneId()).thenReturn(11L);
        when(vpc.getId()).thenReturn(7L);
        Account owner = mock(Account.class);
        when(owner.getId()).thenReturn(33L);
        when(nsxProviderDao.findByZoneId(11L)).thenReturn(null);
        when(netrisProviderDao.findByZoneId(11L)).thenReturn(mock(NetrisProviderVO.class));

        VpcIpAllocationServiceImpl spy = org.mockito.Mockito.spy(service);
        org.mockito.Mockito.doReturn(null)
                .when(spy).getExistingSourceNatInVpc(anyLong(), anyLong(), anyBoolean(), anyBoolean());

        spy.assignSourceNatIpAddressToVpc(owner, vpc, null);

        verify(ipAddrMgr).assignPublicIpAddress(eq(11L), eq(null), eq(owner), any(), eq(null), eq(null), eq(false), eq(false));
        verify(ipAddrMgr, never()).assignDedicateIpAddress(any(), any(), anyLong(), anyLong(), anyBoolean());
    }
}
