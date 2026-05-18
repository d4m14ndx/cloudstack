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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.apache.cloudstack.context.CallContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.dc.DataCenter;
import com.cloud.dc.Vlan.VlanType;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.AccountVlanMapDao;
import com.cloud.dc.dao.DomainVlanMapDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.dc.dao.VlanDetailsDao;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.network.IpAddress.State;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.rules.RulesManager;
import com.cloud.network.vpc.VpcManager;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.offerings.dao.NetworkOfferingServiceMapDao;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.ResourceLimitService;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.UserVmDao;

@RunWith(MockitoJUnitRunner.class)
public class IpAddressLifecycleServiceImplTest {

    @Mock
    IPAddressDao ipAddressDao;
    @Mock
    VlanDao vlanDao;
    @Mock
    VlanDetailsDao vlanDetailsDao;
    @Mock
    AccountVlanMapDao accountVlanMapDao;
    @Mock
    DomainVlanMapDao domainVlanMapDao;
    @Mock
    NetworkDao networksDao;
    @Mock
    VpcDao vpcDao;
    @Mock
    NetworkOfferingDao networkOfferingDao;
    @Mock
    NetworkOfferingServiceMapDao networkOfferingServiceMapDao;
    @Mock
    ResourceTagDao resourceTagDao;
    @Mock
    UserVmDao userVmDao;
    @Mock
    AccountManager accountMgr;
    @Mock
    ResourceLimitService resourceLimitMgr;
    @Mock
    IpAddressManager ipAddrMgr;
    @Mock
    VpcManager vpcMgr;
    @Mock
    RulesManager rulesMgr;
    @Mock
    EntityManager entityMgr;

    @InjectMocks
    IpAddressLifecycleServiceImpl svc;

    private MockedStatic<CallContext> callContextMocked;
    private CallContext callContext;
    private Account caller;
    private AutoCloseable closeable;

    private static final long IP_ID = 42L;
    private static final long VLAN_ID = 10L;
    private static final long ACCOUNT_ID = 5L;
    private static final long DOMAIN_ID = 2L;

    @Before
    public void setUp() {
        closeable = MockitoAnnotations.openMocks(this);
        callContextMocked = Mockito.mockStatic(CallContext.class);
        callContext = mock(CallContext.class);
        callContextMocked.when(CallContext::current).thenReturn(callContext);
        caller = mock(Account.class);
        when(callContext.getCallingAccount()).thenReturn(caller);
        when(callContext.getCallingUserId()).thenReturn(1L);
    }

    @After
    public void tearDown() throws Exception {
        callContextMocked.close();
        closeable.close();
    }

    // -----------------------------------------------------------------------
    // getIp(long)
    // -----------------------------------------------------------------------

    @Test
    public void testGetIpById_returnsFromDao() {
        IPAddressVO vo = mock(IPAddressVO.class);
        when(ipAddressDao.findById(IP_ID)).thenReturn(vo);
        IpAddress result = svc.getIp(IP_ID);
        assertSame(vo, result);
    }

    @Test
    public void testGetIpById_returnsNullWhenNotFound() {
        when(ipAddressDao.findById(IP_ID)).thenReturn(null);
        IpAddress result = svc.getIp(IP_ID);
        assertEquals(null, result);
    }

    // -----------------------------------------------------------------------
    // getIp(String)
    // -----------------------------------------------------------------------

    @Test
    public void testGetIpByString_returnsFromDao() {
        IPAddressVO vo = mock(IPAddressVO.class);
        when(ipAddressDao.findByIp("10.0.0.5")).thenReturn(vo);
        IpAddress result = svc.getIp("10.0.0.5");
        assertSame(vo, result);
    }

    // -----------------------------------------------------------------------
    // updateIP
    // -----------------------------------------------------------------------

    @Test
    public void testUpdateIP_setsCustomIdAndDisplayFlag() {
        IPAddressVO ipVO = mock(IPAddressVO.class);
        when(ipAddressDao.findById(IP_ID)).thenReturn(ipVO);
        when(ipVO.getAllocatedToAccountId()).thenReturn(ACCOUNT_ID);
        doNothing().when(accountMgr).checkAccess(caller, null, true, ipVO);
        when(ipAddressDao.findById(IP_ID)).thenReturn(ipVO); // second call after update
        when(ipAddressDao.update(anyLong(), any())).thenReturn(true);

        IpAddress result = svc.updateIP(IP_ID, "custom-id", Boolean.TRUE);
        verify(ipVO).setUuid("custom-id");
        verify(ipVO).setDisplay(true);
        verify(ipAddressDao).update(eq(IP_ID), any());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testUpdateIP_throwsWhenNotFound() {
        when(ipAddressDao.findById(IP_ID)).thenReturn(null);
        svc.updateIP(IP_ID, null, null);
    }

    @Test
    public void testUpdateIP_nonAllocatedByAdmin_succeeds() {
        IPAddressVO ipVO = mock(IPAddressVO.class);
        when(ipAddressDao.findById(IP_ID)).thenReturn(ipVO);
        when(ipVO.getAllocatedToAccountId()).thenReturn(null);
        when(caller.getType()).thenReturn(Account.Type.ADMIN);
        when(ipAddressDao.update(anyLong(), any())).thenReturn(true);

        svc.updateIP(IP_ID, null, Boolean.FALSE);
        verify(ipVO).setDisplay(false);
    }

    // -----------------------------------------------------------------------
    // reserveIpAddress
    // -----------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void testReserveIpAddress_throwsWhenNotFound() throws ResourceAllocationException {
        when(ipAddressDao.findById(IP_ID)).thenReturn(null);
        Account account = mock(Account.class);
        svc.reserveIpAddress(account, true, IP_ID);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testReserveIpAddress_throwsForNonVirtualNetworkVlan() throws ResourceAllocationException {
        IPAddressVO ipVO = mock(IPAddressVO.class);
        when(ipVO.getVlanId()).thenReturn(VLAN_ID);
        when(ipAddressDao.findById(IP_ID)).thenReturn(ipVO);
        Account account = mock(Account.class);
        doNothing().when(accountMgr).checkAccess(caller, null, true, account);

        VlanVO vlan = mock(VlanVO.class);
        when(vlanDao.findById(VLAN_ID)).thenReturn(vlan);
        when(vlan.getVlanType()).thenReturn(VlanType.DirectAttached);

        svc.reserveIpAddress(account, true, IP_ID);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testReserveIpAddress_throwsForPortableIp() throws ResourceAllocationException {
        IPAddressVO ipVO = mock(IPAddressVO.class);
        when(ipVO.getVlanId()).thenReturn(VLAN_ID);
        when(ipAddressDao.findById(IP_ID)).thenReturn(ipVO);
        Account account = mock(Account.class);
        doNothing().when(accountMgr).checkAccess(caller, null, true, account);

        VlanVO vlan = mock(VlanVO.class);
        when(vlanDao.findById(VLAN_ID)).thenReturn(vlan);
        when(vlan.getVlanType()).thenReturn(VlanType.VirtualNetwork);
        when(ipVO.isPortable()).thenReturn(true);

        svc.reserveIpAddress(account, true, IP_ID);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testReserveIpAddress_throwsForNonFreeState() throws ResourceAllocationException {
        IPAddressVO ipVO = mock(IPAddressVO.class);
        when(ipVO.getVlanId()).thenReturn(VLAN_ID);
        when(ipVO.getState()).thenReturn(State.Allocated);
        when(ipAddressDao.findById(IP_ID)).thenReturn(ipVO);
        Account account = mock(Account.class);
        doNothing().when(accountMgr).checkAccess(caller, null, true, account);

        VlanVO vlan = mock(VlanVO.class);
        when(vlanDao.findById(VLAN_ID)).thenReturn(vlan);
        when(vlan.getVlanType()).thenReturn(VlanType.VirtualNetwork);
        when(ipVO.isPortable()).thenReturn(false);

        svc.reserveIpAddress(account, true, IP_ID);
    }

    // -----------------------------------------------------------------------
    // releaseReservedIpAddress
    // -----------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void testReleaseReservedIpAddress_throwsWhenNotFound() throws InsufficientAddressCapacityException {
        when(ipAddressDao.findById(IP_ID)).thenReturn(null);
        svc.releaseReservedIpAddress(IP_ID);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testReleaseReservedIpAddress_throwsForPortable() throws InsufficientAddressCapacityException {
        IPAddressVO ipVO = mock(IPAddressVO.class);
        when(ipVO.isPortable()).thenReturn(true);
        when(ipAddressDao.findById(IP_ID)).thenReturn(ipVO);
        svc.releaseReservedIpAddress(IP_ID);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testReleaseReservedIpAddress_throwsForAllocatedState() throws InsufficientAddressCapacityException {
        IPAddressVO ipVO = mock(IPAddressVO.class);
        when(ipVO.isPortable()).thenReturn(false);
        when(ipVO.getState()).thenReturn(State.Allocated);
        when(ipAddressDao.findById(IP_ID)).thenReturn(ipVO);
        svc.releaseReservedIpAddress(IP_ID);
    }

    // -----------------------------------------------------------------------
    // releaseIpAddress (disassociate)
    // -----------------------------------------------------------------------

    @Test
    public void testReleaseIpAddress_notAllocatedReturnsTrue() throws InsufficientAddressCapacityException {
        IPAddressVO ipVO = mock(IPAddressVO.class);
        when(ipAddressDao.findById(IP_ID)).thenReturn(ipVO);
        when(ipVO.getAllocatedTime()).thenReturn(null);

        boolean result = svc.releaseIpAddress(IP_ID);
        assertTrue(result);
        verify(ipAddrMgr, never()).disassociatePublicIpAddress(any(), anyLong(), any());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testReleaseIpAddress_throwsWhenNotFound() throws InsufficientAddressCapacityException {
        when(ipAddressDao.findById(IP_ID)).thenReturn(null);
        svc.releaseIpAddress(IP_ID);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testReleaseIpAddress_throwsForSourceNatWithActiveNetwork() throws InsufficientAddressCapacityException {
        IPAddressVO ipVO = mock(IPAddressVO.class);
        when(ipAddressDao.findById(IP_ID)).thenReturn(ipVO);
        when(ipVO.getAllocatedTime()).thenReturn(new java.util.Date());
        when(ipVO.getAllocatedToAccountId()).thenReturn(null);
        when(ipVO.getAssociatedWithNetworkId()).thenReturn(99L);
        when(ipVO.getVpcId()).thenReturn(null);
        when(ipVO.isSourceNat()).thenReturn(true);

        NetworkVO guestNetwork = mock(NetworkVO.class);
        when(networksDao.findById(99L)).thenReturn(guestNetwork);
        when(guestNetwork.getState()).thenReturn(Network.State.Implemented);

        svc.releaseIpAddress(IP_ID);
    }

    // -----------------------------------------------------------------------
    // releasePortableIpAddress
    // -----------------------------------------------------------------------

    @Test
    public void testReleasePortableIpAddress_returnsFalseOnException() throws InsufficientAddressCapacityException {
        // If the internal release throws, the portable wrapper catches and returns false
        when(ipAddressDao.findById(IP_ID)).thenReturn(null);
        boolean result = svc.releasePortableIpAddress(IP_ID);
        // InvalidParameterValueException is RuntimeException; wrapper returns false
        assertTrue(!result);
    }

    // -----------------------------------------------------------------------
    // reserveIpAddressWithVlanDetail
    // -----------------------------------------------------------------------

    @Test(expected = CloudRuntimeException.class)
    public void testReserveIpAddressWithVlanDetail_throwsWhenNoVlanFound() throws ResourceAllocationException {
        Account account = mock(Account.class);
        doNothing().when(accountMgr).checkAccess(caller, null, true, account);
        DataCenter zone = mock(DataCenter.class);
        when(zone.getId()).thenReturn(1L);
        when(zone.getName()).thenReturn("testZone");
        when(vlanDao.listByZone(1L)).thenReturn(Collections.emptyList());

        svc.reserveIpAddressWithVlanDetail(account, zone, true, "NSX");
    }

    // -----------------------------------------------------------------------
    // allocateIP - delegation via IpAddressManager
    // -----------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void testAllocateIP_throwsForInvalidNetworkId() throws Exception {
        Account ipOwner = mock(Account.class);
        when(entityMgr.findById(DataCenter.class, 1L)).thenReturn(mock(DataCenter.class));
        when(networksDao.findById(99L)).thenReturn(null);

        svc.allocateIP(ipOwner, 1L, 99L, true, null);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testAllocatePortableIP_throwsWhenBothNetworkAndVpcNull() throws Exception {
        Account ipOwner = mock(Account.class);
        when(entityMgr.findById(DataCenter.class, 1L)).thenReturn(mock(DataCenter.class));

        svc.allocatePortableIP(ipOwner, 1, 1L, null, null);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testAllocatePortableIP_throwsWhenBothNetworkAndVpcProvided() throws Exception {
        Account ipOwner = mock(Account.class);
        when(entityMgr.findById(DataCenter.class, 1L)).thenReturn(mock(DataCenter.class));

        svc.allocatePortableIP(ipOwner, 1, 1L, 10L, 20L);
    }

    // -----------------------------------------------------------------------
    // associateIPToNetwork
    // -----------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void testAssociateIPToNetwork_throwsForInvalidNetwork() throws Exception {
        when(networksDao.findById(99L)).thenReturn(null);
        // releaseIpAddress will be called; need to stub ipAddressDao.findById for it
        IPAddressVO ipVO = mock(IPAddressVO.class);
        when(ipAddressDao.findById(IP_ID)).thenReturn(ipVO);
        when(ipVO.getAllocatedTime()).thenReturn(null);

        svc.associateIPToNetwork(IP_ID, 99L);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testAssociateIPToNetwork_throwsWhenNetworkBelongsToVpc() throws Exception {
        NetworkVO network = mock(NetworkVO.class);
        when(networksDao.findById(99L)).thenReturn(network);
        when(network.getVpcId()).thenReturn(7L);
        // releaseIpAddress will be called
        IPAddressVO ipVO = mock(IPAddressVO.class);
        when(ipAddressDao.findById(IP_ID)).thenReturn(ipVO);
        when(ipVO.getAllocatedTime()).thenReturn(null);

        svc.associateIPToNetwork(IP_ID, 99L);
    }
}
