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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.api.command.user.vpc.ListPrivateGatewaysCmd;
import org.apache.cloudstack.context.CallContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.Network;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.Provider;
import com.cloud.network.NetworkModel;
import com.cloud.network.NetworkService;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.element.VpcProvider;
import com.cloud.network.vpc.dao.NetworkACLDao;
import com.cloud.network.vpc.dao.PrivateIpDao;
import com.cloud.network.vpc.dao.StaticRouteDao;
import com.cloud.network.vpc.dao.VpcGatewayDao;
import com.cloud.network.vpc.dao.VpcServiceMapDao;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.utils.Pair;
import com.cloud.utils.db.EntityManager;

@RunWith(MockitoJUnitRunner.class)
public class PrivateGatewayServiceImplTest {

    @Mock private AccountManager accountMgr;
    @Mock private DataCenterDao dcDao;
    @Mock private EntityManager entityMgr;
    @Mock private NetworkACLDao networkAclDao;
    @Mock private NetworkOfferingDao networkOfferingDao;
    @Mock private NetworkDao networkDao;
    @Mock private NetworkModel networkModel;
    @Mock private NetworkService networkService;
    @Mock private PrivateIpDao privateIpDao;
    @Mock private StaticRouteDao staticRouteDao;
    @Mock private VpcGatewayDao vpcGatewayDao;
    @Mock private VpcServiceMapDao vpcSrvcMapDao;
    @Mock private VpcManager vpcManager;

    @InjectMocks
    private PrivateGatewayServiceImpl service;

    @Before
    public void setUp() {
        CallContext.register(mock(User.class), mock(Account.class));
    }

    @After
    public void tearDown() {
        CallContext.unregister();
    }

    // ---- getVpcPrivateGateway ----

    @Test
    public void getVpcPrivateGatewayReturnsNullWhenGatewayMissing() {
        when(vpcGatewayDao.findById(42L)).thenReturn(null);
        assertNull(service.getVpcPrivateGateway(42L));
    }

    @Test
    public void getVpcPrivateGatewayReturnsNullForNonPrivateType() {
        VpcGatewayVO gw = mock(VpcGatewayVO.class);
        when(gw.getType()).thenReturn(VpcGateway.Type.Public);
        when(vpcGatewayDao.findById(42L)).thenReturn(gw);
        assertNull(service.getVpcPrivateGateway(42L));
    }

    @Test
    public void getVpcPrivateGatewayWrapsAsProfileWhenPresent() {
        VpcGatewayVO gw = mock(VpcGatewayVO.class);
        when(gw.getType()).thenReturn(VpcGateway.Type.Private);
        when(gw.getNetworkId()).thenReturn(11L);
        when(vpcGatewayDao.findById(42L)).thenReturn(gw);

        NetworkVO network = mock(NetworkVO.class);
        when(network.getPhysicalNetworkId()).thenReturn(99L);
        when(networkModel.getNetwork(11L)).thenReturn(network);

        PrivateGateway result = service.getVpcPrivateGateway(42L);

        assertNotNull(result);
        assertEquals(99L, result.getPhysicalNetworkId());
    }

    // ---- getVpcPrivateGateways (plural) ----

    @Test
    public void getVpcPrivateGatewaysReturnsNullWhenDaoReturnsNull() {
        when(vpcGatewayDao.listByVpcIdAndType(7L, VpcGateway.Type.Private)).thenReturn(null);
        assertNull(service.getVpcPrivateGateways(7L));
    }

    @Test
    public void getVpcPrivateGatewaysReturnsProfilesForEachGateway() {
        VpcGatewayVO gw1 = mock(VpcGatewayVO.class);
        VpcGatewayVO gw2 = mock(VpcGatewayVO.class);
        when(gw1.getNetworkId()).thenReturn(11L);
        when(gw2.getNetworkId()).thenReturn(12L);
        when(vpcGatewayDao.listByVpcIdAndType(7L, VpcGateway.Type.Private)).thenReturn(Arrays.asList(gw1, gw2));
        NetworkVO n1 = mock(NetworkVO.class);
        NetworkVO n2 = mock(NetworkVO.class);
        when(networkModel.getNetwork(11L)).thenReturn(n1);
        when(networkModel.getNetwork(12L)).thenReturn(n2);

        List<PrivateGateway> result = service.getVpcPrivateGateways(7L);

        assertNotNull(result);
        assertEquals(2, result.size());
    }

    // ---- validateVpcPrivateGatewayAclId ----

    @Test
    public void validateVpcPrivateGatewayAclIdAcceptsNullAclId() {
        service.validateVpcPrivateGatewayAclId(1L, null);
        verify(networkAclDao, never()).findById(anyLong());
    }

    @Test
    public void validateVpcPrivateGatewayAclIdRejectsMissingAcl() {
        when(networkAclDao.findById(5L)).thenReturn(null);
        assertThrows(InvalidParameterValueException.class,
                () -> service.validateVpcPrivateGatewayAclId(1L, 5L));
    }

    @Test
    public void validateVpcPrivateGatewayAclIdRejectsAclFromAnotherVpc() {
        NetworkACLVO acl = mock(NetworkACLVO.class);
        when(acl.getVpcId()).thenReturn(2L);
        when(networkAclDao.findById(5L)).thenReturn(acl);

        assertThrows(InvalidParameterValueException.class,
                () -> service.validateVpcPrivateGatewayAclId(1L, 5L));
    }

    @Test
    public void validateVpcPrivateGatewayAclIdAcceptsAclInSameVpc() {
        NetworkACLVO acl = mock(NetworkACLVO.class);
        when(acl.getVpcId()).thenReturn(1L);
        when(networkAclDao.findById(5L)).thenReturn(acl);

        service.validateVpcPrivateGatewayAclId(1L, 5L);
    }

    @Test
    public void validateVpcPrivateGatewayAclIdAcceptsDefaultDenyAclEvenWhenVpcDiffers() {
        NetworkACLVO acl = mock(NetworkACLVO.class);
        when(acl.getVpcId()).thenReturn(999L);
        when(networkAclDao.findById(NetworkACL.DEFAULT_DENY)).thenReturn(acl);

        service.validateVpcPrivateGatewayAclId(1L, NetworkACL.DEFAULT_DENY);
    }

    // ---- getVpcPrivateGatewayNetworkOffering ----

    @Test
    public void getVpcPrivateGatewayNetworkOfferingRejectsMissingOffering() {
        when(networkOfferingDao.findById(123L)).thenReturn(null);
        assertThrows(InvalidParameterValueException.class,
                () -> service.getVpcPrivateGatewayNetworkOffering(123L, null));
    }

    @Test
    public void getVpcPrivateGatewayNetworkOfferingRejectsNonGuestTrafficType() {
        NetworkOfferingVO off = mock(NetworkOfferingVO.class);
        when(off.getTrafficType()).thenReturn(TrafficType.Management);
        when(networkOfferingDao.findById(123L)).thenReturn(off);
        assertThrows(InvalidParameterValueException.class,
                () -> service.getVpcPrivateGatewayNetworkOffering(123L, null));
    }

    @Test
    public void getVpcPrivateGatewayNetworkOfferingRejectsNonIsolatedGuestType() {
        NetworkOfferingVO off = mock(NetworkOfferingVO.class);
        when(off.getTrafficType()).thenReturn(TrafficType.Guest);
        when(off.getGuestType()).thenReturn(GuestType.Shared);
        when(networkOfferingDao.findById(123L)).thenReturn(off);
        assertThrows(InvalidParameterValueException.class,
                () -> service.getVpcPrivateGatewayNetworkOffering(123L, null));
    }

    @Test
    public void getVpcPrivateGatewayNetworkOfferingAcceptsGuestIsolatedOffering() {
        NetworkOfferingVO off = mock(NetworkOfferingVO.class);
        when(off.getTrafficType()).thenReturn(TrafficType.Guest);
        when(off.getGuestType()).thenReturn(GuestType.Isolated);
        when(networkOfferingDao.findById(123L)).thenReturn(off);

        NetworkOfferingVO result = service.getVpcPrivateGatewayNetworkOffering(123L, null);

        assertSame(off, result);
    }

    @Test
    public void getVpcPrivateGatewayNetworkOfferingDefaultsToSystemOfferingWithVlanWhenBroadcastUriProvided() {
        NetworkOfferingVO off = mock(NetworkOfferingVO.class);
        when(networkOfferingDao.findByUniqueName(com.cloud.offering.NetworkOffering.SystemPrivateGatewayNetworkOffering)).thenReturn(off);

        NetworkOfferingVO result = service.getVpcPrivateGatewayNetworkOffering(null, "vlan://100");

        assertSame(off, result);
    }

    @Test
    public void getVpcPrivateGatewayNetworkOfferingDefaultsToSystemOfferingWithoutVlanWhenBroadcastUriAbsent() {
        NetworkOfferingVO off = mock(NetworkOfferingVO.class);
        when(networkOfferingDao.findByUniqueName(com.cloud.offering.NetworkOffering.SystemPrivateGatewayNetworkOfferingWithoutVlan)).thenReturn(off);

        NetworkOfferingVO result = service.getVpcPrivateGatewayNetworkOffering(null, null);

        assertSame(off, result);
    }

    // ---- validateVpcPrivateGatewayPhysicalNetworkId ----

    @Test
    public void validateVpcPrivateGatewayPhysicalNetworkIdRejectsMissingAssociatedNetwork() {
        when(entityMgr.findById(eq(Network.class), eq(55L))).thenReturn(null);
        NetworkOfferingVO off = mock(NetworkOfferingVO.class);

        assertThrows(InvalidParameterValueException.class,
                () -> service.validateVpcPrivateGatewayPhysicalNetworkId(1L, 9L, 55L, off));
    }

    @Test
    public void validateVpcPrivateGatewayPhysicalNetworkIdRejectsMismatchedPhysicalNetwork() {
        Network associated = mock(Network.class);
        when(associated.getPhysicalNetworkId()).thenReturn(7L);
        when(entityMgr.findById(eq(Network.class), eq(55L))).thenReturn(associated);
        NetworkOfferingVO off = mock(NetworkOfferingVO.class);

        assertThrows(InvalidParameterValueException.class,
                () -> service.validateVpcPrivateGatewayPhysicalNetworkId(1L, 9L, 55L, off));
    }

    @Test
    public void validateVpcPrivateGatewayPhysicalNetworkIdAdoptsAssociatedPhysicalIdWhenNotSet() {
        Network associated = mock(Network.class);
        when(associated.getPhysicalNetworkId()).thenReturn(7L);
        when(entityMgr.findById(eq(Network.class), eq(55L))).thenReturn(associated);
        NetworkOfferingVO off = mock(NetworkOfferingVO.class);

        Long result = service.validateVpcPrivateGatewayPhysicalNetworkId(1L, null, 55L, off);

        assertEquals(Long.valueOf(7L), result);
    }

    @Test
    public void validateVpcPrivateGatewayPhysicalNetworkIdDerivesFromOfferingTagsWhenNoAssociatedNetwork() {
        NetworkOfferingVO off = mock(NetworkOfferingVO.class);
        when(off.getTags()).thenReturn("tags");
        when(off.getTrafficType()).thenReturn(TrafficType.Guest);
        when(networkService.findPhysicalNetworkId(1L, "tags", TrafficType.Guest)).thenReturn(7L);

        Long result = service.validateVpcPrivateGatewayPhysicalNetworkId(1L, null, null, off);

        assertEquals(Long.valueOf(7L), result);
    }

    @Test
    public void validateVpcPrivateGatewayPhysicalNetworkIdKeepsExplicitPhysicalNetworkWhenMatchingAssociated() {
        Network associated = mock(Network.class);
        when(associated.getPhysicalNetworkId()).thenReturn(7L);
        when(entityMgr.findById(eq(Network.class), eq(55L))).thenReturn(associated);
        NetworkOfferingVO off = mock(NetworkOfferingVO.class);

        Long result = service.validateVpcPrivateGatewayPhysicalNetworkId(1L, 7L, 55L, off);

        assertEquals(Long.valueOf(7L), result);
    }

    // ---- applyVpcPrivateGateway ----

    @Test
    public void applyVpcPrivateGatewayMarksReadyWhenAllProvidersSucceed() throws ConcurrentOperationException, ResourceUnavailableException {
        VpcGatewayVO vo = mock(VpcGatewayVO.class);
        when(vo.getVpcId()).thenReturn(7L);
        when(vo.getState()).thenReturn(VpcGateway.State.Ready);
        when(vpcGatewayDao.findById(42L)).thenReturn(vo);

        // 2nd lookup performs the type check then the profile lookup
        VpcGatewayVO voGateway = mock(VpcGatewayVO.class);
        when(voGateway.getType()).thenReturn(VpcGateway.Type.Private);
        when(voGateway.getNetworkId()).thenReturn(11L);
        // re-mock findById to also support the getVpcPrivateGateway call
        // (kept via the same return for simplicity)
        when(vpcGatewayDao.findById(42L)).thenReturn(vo, voGateway, voGateway);
        NetworkVO network = mock(NetworkVO.class);
        when(network.getPhysicalNetworkId()).thenReturn(99L);
        when(networkModel.getNetwork(11L)).thenReturn(network);

        when(vpcSrvcMapDao.getDistinctProviders(7L)).thenReturn(Collections.singletonList("VPCVirtualRouter"));
        VpcProvider provider = mock(VpcProvider.class);
        when(provider.getProvider()).thenReturn(Provider.VPCVirtualRouter);
        when(provider.createPrivateGateway(any(PrivateGateway.class))).thenReturn(true);
        when(vpcManager.getVpcElements()).thenReturn(Collections.singletonList(provider));

        PrivateGateway result = service.applyVpcPrivateGateway(42L, false);

        assertNotNull(result);
        verify(provider).createPrivateGateway(any(PrivateGateway.class));
    }

    @Test
    public void applyVpcPrivateGatewayReturnsNullWhenProviderFails() throws ConcurrentOperationException, ResourceUnavailableException {
        VpcGatewayVO vo = mock(VpcGatewayVO.class);
        when(vo.getVpcId()).thenReturn(7L);
        VpcGatewayVO voGateway = mock(VpcGatewayVO.class);
        when(voGateway.getType()).thenReturn(VpcGateway.Type.Private);
        when(voGateway.getNetworkId()).thenReturn(11L);
        when(vpcGatewayDao.findById(42L)).thenReturn(vo, voGateway);
        NetworkVO network = mock(NetworkVO.class);
        when(networkModel.getNetwork(11L)).thenReturn(network);

        when(vpcSrvcMapDao.getDistinctProviders(7L)).thenReturn(Collections.singletonList("VPCVirtualRouter"));
        VpcProvider provider = mock(VpcProvider.class);
        when(provider.getProvider()).thenReturn(Provider.VPCVirtualRouter);
        when(provider.createPrivateGateway(any(PrivateGateway.class))).thenReturn(false);
        when(vpcManager.getVpcElements()).thenReturn(Collections.singletonList(provider));

        // destroyOnFailure=false to avoid the destroy-from-DB path
        PrivateGateway result = service.applyVpcPrivateGateway(42L, false);

        assertNull(result);
    }

    // ---- deleteVpcPrivateGateway ----

    @Test
    public void deleteVpcPrivateGatewayReturnsTrueWhenAlreadyDeleted() throws ConcurrentOperationException, ResourceUnavailableException {
        when(vpcGatewayDao.findById(42L)).thenReturn(null);
        assertTrue(service.deleteVpcPrivateGateway(42L));
        verify(vpcGatewayDao, never()).acquireInLockTable(anyLong());
    }

    @Test
    public void deleteVpcPrivateGatewayThrowsWhenLockCannotBeAcquired() {
        VpcGatewayVO gw = mock(VpcGatewayVO.class);
        when(vpcGatewayDao.findById(42L)).thenReturn(gw);
        when(vpcGatewayDao.acquireInLockTable(42L)).thenReturn(null);
        assertThrows(ConcurrentOperationException.class, () -> service.deleteVpcPrivateGateway(42L));
    }

    @Test
    public void deleteVpcPrivateGatewayThrowsWhenLockedGatewayIsNotPrivate() {
        VpcGatewayVO gw = mock(VpcGatewayVO.class);
        when(vpcGatewayDao.findById(42L)).thenReturn(gw);
        VpcGatewayVO locked = mock(VpcGatewayVO.class);
        when(locked.getType()).thenReturn(VpcGateway.Type.Public);
        when(vpcGatewayDao.acquireInLockTable(42L)).thenReturn(locked);

        assertThrows(ConcurrentOperationException.class, () -> service.deleteVpcPrivateGateway(42L));
    }

    // ---- listPrivateGateway ----

    @Test
    public void listPrivateGatewayBuildsCriteriaAndWrapsResults() {
        ListPrivateGatewaysCmd cmd = mock(ListPrivateGatewaysCmd.class);
        when(cmd.getStartIndex()).thenReturn(0L);
        when(cmd.getPageSizeVal()).thenReturn(10L);
        when(cmd.isRecursive()).thenReturn(false);
        when(cmd.listAll()).thenReturn(false);

        @SuppressWarnings("unchecked")
        com.cloud.utils.db.SearchBuilder<VpcGatewayVO> sb = mock(com.cloud.utils.db.SearchBuilder.class);
        VpcGatewayVO entity = mock(VpcGatewayVO.class);
        lenient().when(sb.entity()).thenReturn(entity);
        @SuppressWarnings("unchecked")
        com.cloud.utils.db.SearchCriteria<VpcGatewayVO> sc = mock(com.cloud.utils.db.SearchCriteria.class);
        when(sb.create()).thenReturn(sc);
        when(vpcGatewayDao.createSearchBuilder()).thenReturn(sb);

        VpcGatewayVO gw = mock(VpcGatewayVO.class);
        when(gw.getNetworkId()).thenReturn(11L);
        Pair<List<VpcGatewayVO>, Integer> page =
                new Pair<List<VpcGatewayVO>, Integer>(Collections.singletonList(gw), 1);
        when(vpcGatewayDao.searchAndCount(any(), any())).thenReturn(page);
        NetworkVO network = mock(NetworkVO.class);
        when(networkModel.getNetwork(11L)).thenReturn(network);

        Pair<List<PrivateGateway>, Integer> result = service.listPrivateGateway(cmd);

        assertNotNull(result);
        assertEquals(Integer.valueOf(1), result.second());
        assertEquals(1, result.first().size());
    }
}
