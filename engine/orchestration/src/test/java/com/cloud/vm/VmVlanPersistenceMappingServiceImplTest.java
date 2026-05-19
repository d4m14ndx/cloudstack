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

package com.cloud.vm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.api.query.dao.DomainRouterJoinDao;
import com.cloud.api.query.dao.UserVmJoinDao;
import com.cloud.api.query.vo.DomainRouterJoinVO;
import com.cloud.api.query.vo.UserVmJoinVO;
import com.cloud.network.Network;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.class)
public class VmVlanPersistenceMappingServiceImplTest {

    private static final long VM_ID = 42L;
    private static final long NETWORK_ID = 5L;
    private static final long OFFERING_ID = 7L;
    private static final String VLAN_ID = "123";
    private static final String VLAN_URI = "vlan://" + VLAN_ID;

    @InjectMocks
    private VmVlanPersistenceMappingServiceImpl service;

    @Mock
    private UserVmJoinDao userVmJoinDao;
    @Mock
    private DomainRouterJoinDao domainRouterJoinDao;
    @Mock
    private NetworkDao networkDao;
    @Mock
    private NetworkOfferingDao networkOfferingDao;
    @Mock
    private VMInstanceDao vmDao;

    @Test
    public void userVmJoinPersistentVlanL2ReturnsFalse() {
        stubUserVmNetwork(network(NETWORK_ID, OFFERING_ID, Network.GuestType.L2, VLAN_URI), offering(true));

        Map<String, Boolean> result = service.getVlanToPersistenceMapForVM(VM_ID);

        assertEquals(1, result.size());
        assertFalse(result.get(VLAN_ID));
    }

    @Test
    public void userVmJoinNonPersistentVlanReturnsTrue() {
        stubUserVmNetwork(network(NETWORK_ID, OFFERING_ID, Network.GuestType.L2, VLAN_URI), offering(false));

        Map<String, Boolean> result = service.getVlanToPersistenceMapForVM(VM_ID);

        assertEquals(1, result.size());
        assertTrue(result.get(VLAN_ID));
    }

    @Test
    public void otherPersistentNetworkPreventsDelete() {
        NetworkVO network = network(NETWORK_ID, OFFERING_ID, Network.GuestType.Shared, VLAN_URI);
        stubUserVmNetwork(network, offering(false));
        when(networkDao.getOtherPersistentNetworksCount(NETWORK_ID, VLAN_URI, true)).thenReturn(1);

        Map<String, Boolean> result = service.getVlanToPersistenceMapForVM(VM_ID);

        assertEquals(1, result.size());
        assertFalse(result.get(VLAN_ID));
    }

    @Test
    public void nullNetworkOrOfferingSkipped() {
        UserVmJoinVO firstJoin = userVmJoin(NETWORK_ID);
        UserVmJoinVO secondJoin = userVmJoin(NETWORK_ID + 1);
        NetworkVO networkWithoutOffering = network(NETWORK_ID + 1, OFFERING_ID + 1, Network.GuestType.L2, VLAN_URI);
        when(userVmJoinDao.searchByIds(VM_ID)).thenReturn(List.of(firstJoin, secondJoin));
        when(networkDao.findById(NETWORK_ID)).thenReturn(null);
        when(networkDao.findById(NETWORK_ID + 1)).thenReturn(networkWithoutOffering);
        when(networkOfferingDao.findById(OFFERING_ID + 1)).thenReturn(null);

        Map<String, Boolean> result = service.getVlanToPersistenceMapForVM(VM_ID);

        assertTrue(result.isEmpty());
    }

    @Test
    public void domainRouterFallbackUsesRouterJoin() {
        VMInstanceVO router = mock(VMInstanceVO.class);
        DomainRouterJoinVO routerJoin = routerJoin(NETWORK_ID);
        NetworkVO network = network(NETWORK_ID, OFFERING_ID, Network.GuestType.Isolated, VLAN_URI);
        NetworkOfferingVO persistentOffering = offering(true);
        when(userVmJoinDao.searchByIds(VM_ID)).thenReturn(List.of());
        when(vmDao.findById(VM_ID)).thenReturn(router);
        when(router.getType()).thenReturn(VirtualMachine.Type.DomainRouter);
        when(domainRouterJoinDao.findById(VM_ID)).thenReturn(routerJoin);
        when(networkDao.findById(NETWORK_ID)).thenReturn(network);
        when(networkOfferingDao.findById(OFFERING_ID)).thenReturn(persistentOffering);

        Map<String, Boolean> result = service.getVlanToPersistenceMapForVM(VM_ID);

        assertEquals(1, result.size());
        assertFalse(result.get(VLAN_ID));
    }

    @Test
    public void domainRouterFallbackSkipsNullRouterJoin() {
        VMInstanceVO router = mock(VMInstanceVO.class);
        when(userVmJoinDao.searchByIds(VM_ID)).thenReturn(List.of());
        when(vmDao.findById(VM_ID)).thenReturn(router);
        when(router.getType()).thenReturn(VirtualMachine.Type.DomainRouter);
        when(domainRouterJoinDao.findById(VM_ID)).thenReturn(null);

        Map<String, Boolean> result = service.getVlanToPersistenceMapForVM(VM_ID);

        assertTrue(result.isEmpty());
        verifyNoInteractions(networkOfferingDao);
    }

    @Test
    public void existingFalseIsNotOverwrittenByLaterTrue() {
        UserVmJoinVO persistentJoin = userVmJoin(NETWORK_ID);
        UserVmJoinVO nonPersistentJoin = userVmJoin(NETWORK_ID + 1);
        NetworkVO persistentNetwork = network(NETWORK_ID, OFFERING_ID, Network.GuestType.L2, VLAN_URI);
        NetworkVO nonPersistentNetwork = network(NETWORK_ID + 1, OFFERING_ID + 1, Network.GuestType.L2, VLAN_URI);
        NetworkOfferingVO persistentOffering = offering(true);
        NetworkOfferingVO nonPersistentOffering = offering(false);
        when(userVmJoinDao.searchByIds(VM_ID)).thenReturn(List.of(persistentJoin, nonPersistentJoin));
        when(networkDao.findById(NETWORK_ID)).thenReturn(persistentNetwork);
        when(networkDao.findById(NETWORK_ID + 1)).thenReturn(nonPersistentNetwork);
        when(networkOfferingDao.findById(OFFERING_ID)).thenReturn(persistentOffering);
        when(networkOfferingDao.findById(OFFERING_ID + 1)).thenReturn(nonPersistentOffering);

        Map<String, Boolean> result = service.getVlanToPersistenceMapForVM(VM_ID);

        assertEquals(1, result.size());
        assertFalse(result.get(VLAN_ID));
        verify(networkOfferingDao).findById(OFFERING_ID + 1);
    }

    private void stubUserVmNetwork(NetworkVO network, NetworkOfferingVO offering) {
        UserVmJoinVO userVmJoin = userVmJoin(NETWORK_ID);
        when(userVmJoinDao.searchByIds(VM_ID)).thenReturn(List.of(userVmJoin));
        when(networkDao.findById(NETWORK_ID)).thenReturn(network);
        when(networkOfferingDao.findById(OFFERING_ID)).thenReturn(offering);
    }

    private UserVmJoinVO userVmJoin(long networkId) {
        UserVmJoinVO userVmJoin = mock(UserVmJoinVO.class);
        doReturn(networkId).when(userVmJoin).getNetworkId();
        return userVmJoin;
    }

    private DomainRouterJoinVO routerJoin(long networkId) {
        DomainRouterJoinVO routerJoin = mock(DomainRouterJoinVO.class);
        doReturn(networkId).when(routerJoin).getNetworkId();
        return routerJoin;
    }

    private NetworkVO network(long id, long offeringId, Network.GuestType guestType, String broadcastUri) {
        NetworkVO network = mock(NetworkVO.class);
        doReturn(id).when(network).getId();
        doReturn(offeringId).when(network).getNetworkOfferingId();
        doReturn(guestType).when(network).getGuestType();
        doReturn(URI.create(broadcastUri)).when(network).getBroadcastUri();
        return network;
    }

    private NetworkOfferingVO offering(boolean persistent) {
        NetworkOfferingVO offering = mock(NetworkOfferingVO.class);
        doReturn(persistent).when(offering).isPersistent();
        return offering;
    }
}
