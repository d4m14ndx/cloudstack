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
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import com.cloud.api.query.dao.DomainRouterJoinDao;
import com.cloud.api.query.vo.DomainRouterJoinVO;
import com.cloud.network.Network;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.vpc.VpcVO;
import com.cloud.utils.Pair;
import com.cloud.utils.db.EntityManager;
import com.cloud.vm.NicProfile;
import com.cloud.vm.NicVO;

@RunWith(JUnit4.class)
public class NicProfileMtuServiceTest {

    private NicProfileMtuServiceImpl service;

    private DomainRouterJoinDao routerJoinDao;
    private NetworkDao networksDao;
    private EntityManager entityManager;

    private static final long ROUTER_ID = 42L;
    private static final long NETWORK_ID = 100L;
    private static final long VPC_ID = 200L;

    private static final Integer PUBLIC_MTU_NETWORK = 1500;
    private static final Integer PUBLIC_MTU_VPC = 1450;
    private static final Integer PRIVATE_MTU = 1400;

    @Before
    public void setUp() {
        service = new NicProfileMtuServiceImpl();
        routerJoinDao = mock(DomainRouterJoinDao.class);
        networksDao = mock(NetworkDao.class);
        entityManager = mock(EntityManager.class);
        service.routerJoinDao = routerJoinDao;
        service.networksDao = networksDao;
        service.entityManager = entityManager;
    }

    private DomainRouterJoinVO mockRouterRow(long networkId, long vpcId) {
        DomainRouterJoinVO row = mock(DomainRouterJoinVO.class);
        when(row.getNetworkId()).thenReturn(networkId);
        when(row.getVpcId()).thenReturn(vpcId);
        return row;
    }

    // ---------------- getGuestNetworkRouterAndVpcDetails ----------------

    @Test
    public void testGetGuestNetworkRouterAndVpcDetailsReturnsNullWhenBothTrafficTypesEmpty() {
        when(routerJoinDao.getRouterByIdAndTrafficType(ROUTER_ID, TrafficType.Guest)).thenReturn(new ArrayList<>());
        when(routerJoinDao.getRouterByIdAndTrafficType(ROUTER_ID, TrafficType.Public)).thenReturn(new ArrayList<>());

        Pair<NetworkVO, VpcVO> result = service.getGuestNetworkRouterAndVpcDetails(ROUTER_ID);

        assertNull(result);
        verify(routerJoinDao).getRouterByIdAndTrafficType(ROUTER_ID, TrafficType.Guest);
        verify(routerJoinDao).getRouterByIdAndTrafficType(ROUTER_ID, TrafficType.Public);
    }

    @Test
    public void testGetGuestNetworkRouterAndVpcDetailsPrefersGuestTrafficType() {
        DomainRouterJoinVO guestRow = mockRouterRow(NETWORK_ID, 0L);
        when(routerJoinDao.getRouterByIdAndTrafficType(ROUTER_ID, TrafficType.Guest)).thenReturn(Arrays.asList(guestRow));
        NetworkVO networkVO = mock(NetworkVO.class);
        when(networksDao.findById(NETWORK_ID)).thenReturn(networkVO);

        Pair<NetworkVO, VpcVO> result = service.getGuestNetworkRouterAndVpcDetails(ROUTER_ID);

        assertEquals(networkVO, result.first());
        assertNull(result.second());
        verify(routerJoinDao, never()).getRouterByIdAndTrafficType(ROUTER_ID, TrafficType.Public);
    }

    @Test
    public void testGetGuestNetworkRouterAndVpcDetailsFallsBackToPublicTrafficType() {
        DomainRouterJoinVO publicRow = mockRouterRow(NETWORK_ID, 0L);
        when(routerJoinDao.getRouterByIdAndTrafficType(ROUTER_ID, TrafficType.Guest)).thenReturn(new ArrayList<>());
        when(routerJoinDao.getRouterByIdAndTrafficType(ROUTER_ID, TrafficType.Public)).thenReturn(Arrays.asList(publicRow));
        NetworkVO networkVO = mock(NetworkVO.class);
        when(networksDao.findById(NETWORK_ID)).thenReturn(networkVO);

        Pair<NetworkVO, VpcVO> result = service.getGuestNetworkRouterAndVpcDetails(ROUTER_ID);

        assertEquals(networkVO, result.first());
        assertNull(result.second());
    }

    @Test
    public void testGetGuestNetworkRouterAndVpcDetailsResolvesVpcWhenAssigned() {
        DomainRouterJoinVO guestRow = mockRouterRow(NETWORK_ID, VPC_ID);
        when(routerJoinDao.getRouterByIdAndTrafficType(ROUTER_ID, TrafficType.Guest)).thenReturn(Arrays.asList(guestRow));
        NetworkVO networkVO = mock(NetworkVO.class);
        when(networksDao.findById(NETWORK_ID)).thenReturn(networkVO);
        VpcVO vpcVO = mock(VpcVO.class);
        when(entityManager.findById(eq(VpcVO.class), eq(VPC_ID))).thenReturn(vpcVO);

        Pair<NetworkVO, VpcVO> result = service.getGuestNetworkRouterAndVpcDetails(ROUTER_ID);

        assertEquals(networkVO, result.first());
        assertEquals(vpcVO, result.second());
    }

    @Test
    public void testGetGuestNetworkRouterAndVpcDetailsUsesFirstRowWhenMultiple() {
        DomainRouterJoinVO first = mockRouterRow(NETWORK_ID, 0L);
        DomainRouterJoinVO second = mockRouterRow(999L, 0L);
        when(routerJoinDao.getRouterByIdAndTrafficType(ROUTER_ID, TrafficType.Guest)).thenReturn(Arrays.asList(first, second));
        NetworkVO networkVO = mock(NetworkVO.class);
        when(networksDao.findById(NETWORK_ID)).thenReturn(networkVO);

        Pair<NetworkVO, VpcVO> result = service.getGuestNetworkRouterAndVpcDetails(ROUTER_ID);

        assertEquals(networkVO, result.first());
        verify(networksDao).findById(NETWORK_ID);
        verify(networksDao, never()).findById(999L);
    }

    // ---------------- setMtuDetailsInVRNic ----------------

    @Test
    public void testSetMtuDetailsInVRNicPublicWithVpcUsesVpcMtu() {
        Network network = mock(Network.class);
        when(network.getTrafficType()).thenReturn(TrafficType.Public);
        NicVO vo = mock(NicVO.class);
        NetworkVO networkVO = mock(NetworkVO.class);
        VpcVO vpcVO = mock(VpcVO.class);
        when(vpcVO.getPublicMtu()).thenReturn(PUBLIC_MTU_VPC);
        Pair<NetworkVO, VpcVO> networks = new Pair<>(networkVO, vpcVO);

        service.setMtuDetailsInVRNic(networks, network, vo);

        verify(vo).setMtu(PUBLIC_MTU_VPC);
    }

    @Test
    public void testSetMtuDetailsInVRNicPublicWithoutVpcUsesNetworkMtu() {
        Network network = mock(Network.class);
        when(network.getTrafficType()).thenReturn(TrafficType.Public);
        NicVO vo = mock(NicVO.class);
        NetworkVO networkVO = mock(NetworkVO.class);
        when(networkVO.getPublicMtu()).thenReturn(PUBLIC_MTU_NETWORK);
        Pair<NetworkVO, VpcVO> networks = new Pair<>(networkVO, null);

        service.setMtuDetailsInVRNic(networks, network, vo);

        verify(vo).setMtu(PUBLIC_MTU_NETWORK);
    }

    @Test
    public void testSetMtuDetailsInVRNicPublicWithNullPairIsNoOp() {
        Network network = mock(Network.class);
        when(network.getTrafficType()).thenReturn(TrafficType.Public);
        NicVO vo = mock(NicVO.class);

        service.setMtuDetailsInVRNic(null, network, vo);

        verify(vo, never()).setMtu(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    public void testSetMtuDetailsInVRNicGuestUsesNetworkPrivateMtu() {
        Network network = mock(Network.class);
        when(network.getTrafficType()).thenReturn(TrafficType.Guest);
        when(network.getPrivateMtu()).thenReturn(PRIVATE_MTU);
        NicVO vo = mock(NicVO.class);

        // null pair is fine for the Guest path -- it short-circuits on network only
        service.setMtuDetailsInVRNic(null, network, vo);

        verify(vo).setMtu(PRIVATE_MTU);
    }

    @Test
    public void testSetMtuDetailsInVRNicNonPublicNonGuestIsNoOp() {
        Network network = mock(Network.class);
        when(network.getTrafficType()).thenReturn(TrafficType.Management);
        NicVO vo = mock(NicVO.class);
        Pair<NetworkVO, VpcVO> networks = new Pair<>(mock(NetworkVO.class), null);

        service.setMtuDetailsInVRNic(networks, network, vo);

        verify(vo, never()).setMtu(org.mockito.ArgumentMatchers.anyInt());
    }

    // ---------------- setMtuInVRNicProfile ----------------

    @Test
    public void testSetMtuInVRNicProfileNullPairIsNoOp() {
        NicProfile profile = mock(NicProfile.class);

        service.setMtuInVRNicProfile(null, TrafficType.Guest, profile);

        verify(profile, never()).setMtu(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    public void testSetMtuInVRNicProfileNullFirstIsNoOp() {
        NicProfile profile = mock(NicProfile.class);
        Pair<NetworkVO, VpcVO> networks = new Pair<>(null, mock(VpcVO.class));

        service.setMtuInVRNicProfile(networks, TrafficType.Public, profile);

        verify(profile, never()).setMtu(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    public void testSetMtuInVRNicProfilePublicWithVpcUsesVpcMtu() {
        NicProfile profile = mock(NicProfile.class);
        NetworkVO networkVO = mock(NetworkVO.class);
        VpcVO vpcVO = mock(VpcVO.class);
        when(vpcVO.getPublicMtu()).thenReturn(PUBLIC_MTU_VPC);
        Pair<NetworkVO, VpcVO> networks = new Pair<>(networkVO, vpcVO);

        service.setMtuInVRNicProfile(networks, TrafficType.Public, profile);

        verify(profile).setMtu(PUBLIC_MTU_VPC);
    }

    @Test
    public void testSetMtuInVRNicProfilePublicWithoutVpcUsesNetworkPublicMtu() {
        NicProfile profile = mock(NicProfile.class);
        NetworkVO networkVO = mock(NetworkVO.class);
        when(networkVO.getPublicMtu()).thenReturn(PUBLIC_MTU_NETWORK);
        Pair<NetworkVO, VpcVO> networks = new Pair<>(networkVO, null);

        service.setMtuInVRNicProfile(networks, TrafficType.Public, profile);

        verify(profile).setMtu(PUBLIC_MTU_NETWORK);
    }

    @Test
    public void testSetMtuInVRNicProfileGuestUsesNetworkPrivateMtu() {
        NicProfile profile = mock(NicProfile.class);
        NetworkVO networkVO = mock(NetworkVO.class);
        when(networkVO.getPrivateMtu()).thenReturn(PRIVATE_MTU);
        Pair<NetworkVO, VpcVO> networks = new Pair<>(networkVO, null);

        service.setMtuInVRNicProfile(networks, TrafficType.Guest, profile);

        verify(profile).setMtu(PRIVATE_MTU);
    }

    @Test
    public void testSetMtuInVRNicProfileOtherTrafficTypeIsNoOp() {
        NicProfile profile = mock(NicProfile.class);
        NetworkVO networkVO = mock(NetworkVO.class);
        Pair<NetworkVO, VpcVO> networks = new Pair<>(networkVO, null);

        service.setMtuInVRNicProfile(networks, TrafficType.Storage, profile);

        verify(profile, never()).setMtu(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    public void testCompoundFlowMimicsAllocateNicCallSite() {
        // Mirror the sequence the orchestrator uses in allocateNic for a domain router.
        DomainRouterJoinVO guestRow = mockRouterRow(NETWORK_ID, VPC_ID);
        when(routerJoinDao.getRouterByIdAndTrafficType(ROUTER_ID, TrafficType.Guest)).thenReturn(Arrays.asList(guestRow));
        NetworkVO networkVO = mock(NetworkVO.class);
        when(networkVO.getPublicMtu()).thenReturn(PUBLIC_MTU_NETWORK);
        when(networksDao.findById(NETWORK_ID)).thenReturn(networkVO);
        VpcVO vpcVO = mock(VpcVO.class);
        when(vpcVO.getPublicMtu()).thenReturn(PUBLIC_MTU_VPC);
        when(entityManager.findById(eq(VpcVO.class), eq(VPC_ID))).thenReturn(vpcVO);

        Network network = mock(Network.class);
        when(network.getTrafficType()).thenReturn(TrafficType.Public);
        NicVO vo = mock(NicVO.class);
        NicProfile profile = mock(NicProfile.class);

        Pair<NetworkVO, VpcVO> networks = service.getGuestNetworkRouterAndVpcDetails(ROUTER_ID);
        service.setMtuDetailsInVRNic(networks, network, vo);
        service.setMtuInVRNicProfile(networks, network.getTrafficType(), profile);

        verify(vo).setMtu(PUBLIC_MTU_VPC);
        verify(profile).setMtu(PUBLIC_MTU_VPC);
    }

    @Test
    public void testGetGuestNetworkRouterAndVpcDetailsDoesNotLookupVpcWhenIdZero() {
        DomainRouterJoinVO row = mockRouterRow(NETWORK_ID, 0L);
        when(routerJoinDao.getRouterByIdAndTrafficType(ROUTER_ID, TrafficType.Guest)).thenReturn(Arrays.asList(row));
        when(networksDao.findById(NETWORK_ID)).thenReturn(mock(NetworkVO.class));

        Pair<NetworkVO, VpcVO> result = service.getGuestNetworkRouterAndVpcDetails(ROUTER_ID);

        assertNull(result.second());
        verify(entityManager, never()).findById(eq(VpcVO.class), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    public void testEmptyListThenMultiplePublicEntriesReturnsFirst() {
        // Empty guest, multi public; takes index 0.
        DomainRouterJoinVO first = mockRouterRow(NETWORK_ID, 0L);
        DomainRouterJoinVO second = mockRouterRow(888L, 0L);
        when(routerJoinDao.getRouterByIdAndTrafficType(ROUTER_ID, TrafficType.Guest)).thenReturn(new ArrayList<>());
        when(routerJoinDao.getRouterByIdAndTrafficType(ROUTER_ID, TrafficType.Public)).thenReturn(Arrays.asList(first, second));
        NetworkVO networkVO = mock(NetworkVO.class);
        when(networksDao.findById(NETWORK_ID)).thenReturn(networkVO);

        Pair<NetworkVO, VpcVO> result = service.getGuestNetworkRouterAndVpcDetails(ROUTER_ID);

        assertEquals(networkVO, result.first());
    }

    @Test
    public void testInterfaceContractIsImplementedByImpl() {
        // Defensive: ensure the impl is wired through the interface, matching the
        // injection pattern used by NetworkOrchestrator.
        NicProfileMtuService asInterface = service;
        List<DomainRouterJoinVO> empty = new ArrayList<>();
        when(routerJoinDao.getRouterByIdAndTrafficType(ROUTER_ID, TrafficType.Guest)).thenReturn(empty);
        when(routerJoinDao.getRouterByIdAndTrafficType(ROUTER_ID, TrafficType.Public)).thenReturn(empty);

        assertNull(asInterface.getGuestNetworkRouterAndVpcDetails(ROUTER_ID));
    }
}
