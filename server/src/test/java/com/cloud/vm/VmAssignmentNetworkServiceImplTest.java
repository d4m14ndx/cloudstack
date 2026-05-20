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

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import static org.mockito.Mockito.when;

import org.apache.cloudstack.acl.ControlledEntity.ACLType;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.dc.DataCenterVO;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.offering.NetworkOffering;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.utils.db.EntityManager;
import com.cloud.vm.dao.NicDao;

@RunWith(MockitoJUnitRunner.class)
public class VmAssignmentNetworkServiceImplTest {

    private static final long VM_ID = 1L;
    private static final long NETWORK_ID = 2L;
    private static final long NETWORK_OFFERING_ID = 3L;

    @Mock private NetworkModel networkModel;
    @Mock private NetworkDao networkDao;
    @Mock private NicDao nicDao;
    @Mock private EntityManager entityManager;
    @Mock private Account newAccount;
    @Mock private Network network;
    @Mock private NetworkVO networkVo;
    @Mock private NicVO nic;
    @Mock private UserVmVO vm;
    @Mock private NetworkOffering networkOffering;
    @Mock private VirtualMachineTemplate template;
    @Mock private VirtualMachineProfileImpl vmOldProfile;
    @Mock private DataCenterVO zone;

    private VmAssignmentNetworkServiceImpl service;

    @Before
    public void setUp() {
        service = new VmAssignmentNetworkServiceImpl();
        ReflectionTestUtils.setField(service, "networkModel", networkModel);
        ReflectionTestUtils.setField(service, "networkDao", networkDao);
        ReflectionTestUtils.setField(service, "nicDao", nicDao);
        ReflectionTestUtils.setField(service, "entityMgr", entityManager);
    }

    @Test
    public void canAccountUseNetworkReturnsTrueForDomainScopedL2NetworkWithPermission() {
        when(network.getAclType()).thenReturn(ACLType.Domain);
        when(network.getGuestType()).thenReturn(Network.GuestType.L2);
        doNothing().when(networkModel).checkNetworkPermissions(newAccount, network);

        assertTrue(service.canAccountUseNetwork(newAccount, network));
    }

    @Test
    public void updateBasicTypeNetworkForVmRejectsNetworkIdsInBasicZone() {
        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class, () ->
                service.updateBasicTypeNetworkForVm(vm, newAccount, template, vmOldProfile, zone, List.of(NETWORK_ID), List.of()));

        assertEquals("Cannot move VM with Network IDs; this is a basic zone VM.", exception.getMessage());
    }

    @Test
    public void keepOldSharedNetworkForVmPreservesOldIpsWhenNewAccountCanUseDomainNetwork() {
        HashMap<Long, String> requestedIPv4ForNics = new HashMap<>();
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<>();
        LinkedHashSet<NetworkVO> applicableNetworks = new LinkedHashSet<>();

        when(vm.getId()).thenReturn(VM_ID);
        when(nicDao.findDefaultNicForVM(VM_ID)).thenReturn(nic);
        when(nic.getNetworkId()).thenReturn(NETWORK_ID);
        when(nic.getIPv4Address()).thenReturn("10.1.1.8");
        when(nic.getIPv6Address()).thenReturn("fd00::8");
        when(networkDao.findById(NETWORK_ID)).thenReturn(networkVo);
        when(networkVo.getAclType()).thenReturn(ACLType.Domain);
        when(networkVo.getGuestType()).thenReturn(Network.GuestType.Shared);
        when(networkVo.getId()).thenReturn(NETWORK_ID);
        doNothing().when(networkModel).checkNetworkPermissions(newAccount, networkVo);

        service.keepOldSharedNetworkForVm(vm, newAccount, null, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);

        assertTrue(applicableNetworks.contains(networkVo));
        assertEquals("10.1.1.8", requestedIPv4ForNics.get(NETWORK_ID));
        assertEquals("fd00::8", requestedIPv6ForNics.get(NETWORK_ID));
    }

    @Test
    public void addAdditionalNetworksToVmRejectsSystemOnlyNetworkOffering() {
        when(networkDao.findById(NETWORK_ID)).thenReturn(networkVo);
        when(networkVo.getNetworkOfferingId()).thenReturn(NETWORK_OFFERING_ID);
        when(entityManager.findById(NetworkOffering.class, NETWORK_OFFERING_ID)).thenReturn(networkOffering);
        when(networkOffering.isSystemOnly()).thenReturn(true);

        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class, () ->
                service.addAdditionalNetworksToVm(vm, newAccount, List.of(NETWORK_ID), new LinkedHashSet<>(), new HashMap<>(), new HashMap<>()));

        assertEquals(String.format("Specified network [%s] is system only and cannot be used for VM deployment.", networkVo), exception.getMessage());
    }

    @Test
    public void addNicsToApplicableNetworksReturnsFirstNetworkAndAddsProfilesForAllNetworks() {
        NetworkVO secondNetwork = org.mockito.Mockito.mock(NetworkVO.class);
        LinkedHashSet<NetworkVO> applicableNetworks = new LinkedHashSet<>();
        LinkedHashMap<Network, List<? extends NicProfile>> networks = new LinkedHashMap<>();
        applicableNetworks.add(networkVo);
        applicableNetworks.add(secondNetwork);

        NetworkVO defaultNetwork = service.addNicsToApplicableNetworksAndReturnDefaultNetwork(applicableNetworks, new HashMap<>(), new HashMap<>(), networks);

        assertEquals(networkVo, defaultNetwork);
        assertEquals(2, networks.size());
    }
}
